#!/usr/bin/env python3
"""Prepare exact and wildcard project DNS using the existing gateway wildcard."""

import argparse
import importlib.util
import ipaddress
import os
from pathlib import Path
import re
import subprocess
import sys
import time

spec = importlib.util.spec_from_file_location("timeweb_dns_hook", Path(__file__).with_name("timeweb-dns-hook.py"))
hook = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hook)


def namespace_domain(target, slug):
    if target not in {"dev", "prod"} or not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", slug) or slug in {"dev", "www", "opendesign"}:
        raise ValueError("Invalid project namespace or locator environment")
    return slug + "." + ("dev." if target == "dev" else "") + hook.ZONE


def inventory(api, fqdn):
    records, offset = [], 0
    while True:
        page = api.request("GET", f"?limit=100&offset={offset}", fqdn=fqdn, missing_ok=True)
        if page is None:
            if offset:
                raise RuntimeError("Timeweb DNS resource disappeared during inventory")
            return None
        batch = page["dns_records"]
        records.extend(batch)
        offset += len(batch)
        if offset >= page["meta"]["total"]:
            return records
        if not batch:
            raise RuntimeError("Timeweb DNS API returned incomplete records")


def check_records(records, address):
    if records is not None and any(record["type"] in {"AAAA", "CNAME"} or
                                   (record["type"] == "A" and record["data"]["value"] != address) for record in records):
        raise ValueError("Conflicting namespace DNS records; no existing records will be overwritten")


def wait_for_addresses(domain, address, *, timeout=1200, query=hook.dig, sleep=time.sleep, clock=time.monotonic):
    servers = query("1.1.1.1", hook.ZONE, "NS")
    if not servers or any(not re.fullmatch(r"[A-Za-z0-9.-]+\.?", name) for name in servers):
        raise RuntimeError("Cannot discover authoritative DNS servers")
    deadline = clock() + timeout
    names = [domain, "routing-probe." + domain]
    while clock() < deadline:
        # Avoid priming a negative recursive cache before all authorities agree.
        if all(query(server, name, "A") == {address} for server in sorted(servers) for name in names):
            if all(query("1.1.1.1", name, "A") == {address} for name in names):
                return
        sleep(5)
    raise RuntimeError("Timed out waiting for exact and wildcard namespace DNS")


def provision(api, target, slug, *, wait=wait_for_addresses):
    domain = namespace_domain(target, slug)
    base = ("dev." if target == "dev" else "") + hook.ZONE
    source = inventory(api, "*." + base)
    addresses = {record["data"]["value"] for record in source or [] if record["type"] == "A"}
    if len(addresses) != 1:
        raise ValueError("Base wildcard must have one existing gateway A address")
    address = next(iter(addresses))
    try:
        ipaddress.IPv4Address(address)
    except ipaddress.AddressValueError:
        raise ValueError("Base wildcard must have a valid gateway IPv4 address") from None
    names = [domain, "*." + domain]
    before = {name: inventory(api, name) for name in names}
    # Review both names before any write, including when just one already exists.
    for records in before.values():
        check_records(records, address)
    for name in names:
        records = before[name]
        if records is None:
            api.add_subdomain(name.removesuffix("." + hook.ZONE))
            records = inventory(api, name)
            if records is None:
                raise RuntimeError("New namespace DNS resource was not found")
            check_records(records, address)
        if not any(record["type"] == "A" and record["data"]["value"] == address for record in records):
            api.request("POST", payload={"type": "A", "value": address, "ttl": 300}, fqdn=name, version="v2")
        verified = inventory(api, name)
        check_records(verified, address)
        if not verified or not any(record["type"] == "A" and record["data"]["value"] == address for record in verified):
            raise RuntimeError("Namespace DNS verification failed")
    wait(domain, address)
    return domain


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", choices=["dev", "prod"])
    parser.add_argument("slug")
    parser.add_argument("token_file", type=Path)
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise PermissionError("Run through the main-only infrastructure workflow")
    namespace_domain(args.target, args.slug)
    domain = provision(hook.Timeweb(hook.read_token(args.token_file)), args.target, args.slug)
    print(f"Exact and wildcard DNS verified for {domain}")


if __name__ == "__main__":
    try:
        main()
    except (KeyError, ValueError, OSError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"Namespace DNS preparation failed: {error}", file=sys.stderr)
        sys.exit(1)
