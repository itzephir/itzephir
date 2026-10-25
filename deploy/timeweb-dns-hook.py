#!/usr/bin/env python3
"""Certbot DNS-01 hooks, restricted to the two itzephir wildcard challenges.

Uses Timeweb API v1, like twc's TXT-record command. No third-party Python packages.
Only the record ID returned by this auth invocation may be removed by cleanup.
"""

import argparse
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener

ZONE = "itzephir.com"
API = "https://api.timeweb.cloud/api/v1"
TOKEN = Path("/etc/itzephir/timeweb-dns.token")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        return None


def read_token(path=TOKEN):
    metadata = path.stat()
    if path.is_symlink() or not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.geteuid() or metadata.st_mode & 0o077:
        raise ValueError("Timeweb token must be an owner-only regular file")
    token = path.read_text().strip()
    if not re.fullmatch(r"[A-Za-z0-9_.-]{20,4096}", token):
        raise ValueError("Missing or invalid Timeweb token")
    return token


class Timeweb:
    def __init__(self, token, opener=None):
        self.token = token
        self.opener = opener or build_opener(NoRedirect())

    def request(self, method, suffix="", payload=None):
        request = Request(API + "/domains/" + ZONE + "/dns-records" + suffix,
                          data=json.dumps(payload).encode() if payload is not None else None,
                          headers={"Authorization": "Bearer " + self.token, "Content-Type": "application/json",
                                   "Accept": "application/json", "User-Agent": "itzephir-certbot-dns"}, method=method)
        try:
            with self.opener.open(request, timeout=30) as response:
                content = response.read()
                return json.loads(content) if content else {}
        except HTTPError as error:
            if method == "DELETE" and error.code == 404:
                return {}
            # Never include response bodies, authorization headers or credentials in logs.
            raise RuntimeError(f"Timeweb DNS API returned HTTP {error.code}") from None
        except (URLError, OSError, ValueError):
            raise RuntimeError("Timeweb DNS API request failed") from None

    def records(self):
        offset = 0
        while True:
            page = self.request("GET", f"?limit=100&offset={offset}")
            records = page["dns_records"]
            yield from records
            offset += len(records)
            if offset >= page["meta"]["total"]:
                return
            if not records:
                raise RuntimeError("Timeweb DNS API returned incomplete records")


def challenge(domain, validation):
    domain = domain.removeprefix("*.")
    if domain not in {ZONE, "dev." + ZONE} or not re.fullmatch(r"[A-Za-z0-9_-]{20,256}", validation):
        raise ValueError("Only itzephir.com and dev.itzephir.com DNS challenges are allowed")
    return "_acme-challenge" + (".dev" if domain.startswith("dev.") else "")


def dig(server, host, kind):
    result = subprocess.run(["dig", "+short", "+time=2", "+tries=1", "@" + server, host, kind],
                            capture_output=True, text=True, timeout=10)
    if result.returncode:
        return set()
    return set(result.stdout.strip().splitlines())


def wait_for_dns(subdomain, validation, timeout=600, query=dig, sleep=time.sleep, clock=time.monotonic):
    deadline = clock() + timeout
    hostname = subdomain + "." + ZONE
    # Check every authoritative server and a recursive resolver, including negative-cache expiry.
    servers = query("1.1.1.1", ZONE, "NS")
    if not servers or any(not re.fullmatch(r"[A-Za-z0-9.-]+\.?", name) for name in servers):
        raise RuntimeError("Cannot discover authoritative DNS servers")
    while clock() < deadline:
        if all('"' + validation + '"' in query(server, hostname, "TXT") for server in sorted(servers | {"1.1.1.1"})):
            return
        sleep(5)
    raise RuntimeError("Timed out waiting for the ACME TXT record on authoritative and recursive DNS")


def matches(record, record_id, subdomain, validation):
    return (record["id"] == record_id and record["type"] == "TXT" and
            record["data"].get("subdomain") == subdomain and record["data"].get("value") == validation)


def cleanup(api, subdomain, validation, auth_output):
    if not re.fullmatch(r"[1-9][0-9]*", auth_output.strip()):
        return  # A failed auth hook may have produced no record ID.
    record_id = int(auth_output.strip())
    record = next((record for record in api.records() if record["id"] == record_id), None)
    if record is None:
        return
    if not matches(record, record_id, subdomain, validation):
        raise ValueError("Refusing to remove a DNS record that does not match this challenge")
    api.request("DELETE", "/" + str(record_id))


def authenticate(api, subdomain, validation, wait=wait_for_dns):
    response = api.request("POST", payload={"type": "TXT", "subdomain": subdomain, "value": validation, "ttl": 300})
    record_id = response["dns_record"]["id"]
    if not isinstance(record_id, int) or isinstance(record_id, bool) or record_id <= 0:
        raise ValueError("Timeweb DNS API returned an invalid record ID")
    try:
        wait(subdomain, validation)
    except Exception:
        cleanup(api, subdomain, validation, str(record_id))
        raise
    # Certbot supplies stdout verbatim as CERTBOT_AUTH_OUTPUT to the cleanup hook.
    return record_id


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["auth", "cleanup"])
    action = parser.parse_args().action
    if os.geteuid() != 0:
        raise PermissionError("Run through root-owned Certbot")
    domain = os.environ.get("CERTBOT_IDENTIFIER") or os.environ.get("CERTBOT_DOMAIN", "")
    validation = os.environ.get("CERTBOT_VALIDATION", "")
    subdomain = challenge(domain, validation)
    api = Timeweb(read_token())
    if action == "auth":
        print(authenticate(api, subdomain, validation))
    else:
        cleanup(api, subdomain, validation, os.environ.get("CERTBOT_AUTH_OUTPUT", ""))


if __name__ == "__main__":
    try:
        main()
    except (KeyError, ValueError, OSError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"Timeweb ACME hook failed: {error}", file=sys.stderr)
        sys.exit(1)
