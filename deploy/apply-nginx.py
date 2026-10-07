#!/usr/bin/env python3
"""Publish one environment's nginx configuration from a packaged CI release."""

import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

DOMAINS = {"dev": {"dev.itzephir.com"}, "prod": {"itzephir.com", "www.itzephir.com"}}
PORTS = {"dev": 18081, "prod": 18080}
TOKENS = re.compile(r'''"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\#[^\n]*|[{};]|[^\s{};"'\#]+''')


def server_blocks(config):
    tokens = [token for token in TOKENS.finditer(config) if not token.group().startswith("#")]
    blocks = []
    index = 0
    while index < len(tokens):
        if tokens[index].group() != "server" or index + 1 == len(tokens) or tokens[index + 1].group() != "{":
            raise ValueError("Expected only server blocks in the environment configuration")
        start = tokens[index].start()
        index += 1
        depth = 0
        while index < len(tokens):
            value = tokens[index].group()
            depth += (value == "{") - (value == "}")
            index += 1
            if depth == 0:
                blocks.append(config[start:tokens[index - 1].end()])
                break
        else:
            raise ValueError("Unclosed server block")
    if not blocks:
        raise ValueError("Missing server blocks")
    return blocks


def names(block):
    clean = "\n".join(line.split("#", 1)[0] for line in block.splitlines())
    declared = re.findall(r"\bserver_name\s+([^;]+);", clean)
    return {name for declaration in declared for name in declaration.split()}


def validate_environment(config, target):
    seen = set()
    for block in server_blocks(config):
        hosts = names(block)
        if not hosts or not hosts <= DOMAINS[target]:
            raise ValueError(f"Configuration must contain only {target} hostnames")
        seen.update(hosts)
    if seen != DOMAINS[target]:
        raise ValueError(f"Missing {target} hostnames")
    clean = "\n".join(line.split("#", 1)[0] for line in config.splitlines())
    if re.search(r"\binclude\s", clean):
        raise ValueError("Environment configurations must be self-contained")
    backends = re.findall(r"\bproxy_pass\s+([^;]+);", clean)
    if not backends or any(backend != f"http://127.0.0.1:{PORTS[target]}" for backend in backends):
        raise ValueError(f"Unexpected {target} backend")


def write_atomic(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    if content is None:
        path.unlink(missing_ok=True)
        return
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as temporary:
        temporary.write(content)
        temporary.flush()
        os.fsync(temporary.fileno())
        temporary_path = Path(temporary.name)
    try:
        temporary_path.chmod(0o644)
        temporary_path.replace(path)
    finally:
        temporary_path.unlink(missing_ok=True)


def read_optional(path):
    return path.read_bytes() if path.exists() else None


class Publisher:
    def __init__(self, directory=Path("/etc/nginx/itzephir"), site=Path("/etc/nginx/sites-available/itzephir.com"), lock=Path("/run/lock/itzephir-nginx.lock"), runner=subprocess.check_call):
        self.directory, self.site, self.lock, self.runner = directory, site, lock, runner

    def locked(self):
        self.lock.parent.mkdir(parents=True, exist_ok=True)
        handle = self.lock.open("a")
        fcntl.flock(handle, fcntl.LOCK_EX)
        return handle

    def reload(self):
        self.runner(["nginx", "-t"])
        self.runner(["systemctl", "reload", "nginx"])

    def switch(self, changes):
        previous = {path: read_optional(path) for path in changes}
        if previous == changes:
            return
        try:
            for path, content in changes.items():
                write_atomic(path, content)
            self.reload()
        except Exception:
            for path, content in previous.items():
                write_atomic(path, content)
            self.reload()
            raise

    def bootstrap(self, templates):
        with self.locked():
            wrapper = ("# Managed by itzephir CI; configurations are published per environment.\n"
                       f"include {self.directory}/prod.conf;\ninclude {self.directory}/dev.conf;\n").encode()
            if read_optional(self.site) == wrapper:
                if not all((self.directory / f"{target}.conf").is_file() for target in DOMAINS):
                    raise ValueError("Missing managed environment configuration")
                return
            # Preserve an existing production/static configuration during the initial split.
            if self.site.exists():
                configs = {target: [] for target in DOMAINS}
                for block in server_blocks(self.site.read_text()):
                    hosts = names(block)
                    target = next((target for target, domains in DOMAINS.items() if hosts and hosts <= domains), None)
                    if target is None:
                        raise ValueError("Unexpected hostnames in the existing site")
                    configs[target].append(block)
                if not all(configs.values()):
                    raise ValueError("Existing site must contain both environments")
                changes = {self.directory / f"{target}.conf": ("\n\n".join(blocks) + "\n").encode() for target, blocks in configs.items()}
            else:
                changes = {}
                for target in DOMAINS:
                    config = (templates / f"{target}.conf").read_text()
                    validate_environment(config, target)
                    changes[self.directory / f"{target}.conf"] = config.encode()
            changes[self.site] = wrapper
            self.switch(changes)

    def publish(self, target, release, source):
        config = source.read_text()
        validate_environment(config, target)
        destination = self.directory / f"{target}.conf"
        state = self.directory / f".{target}-rollback.json"
        with self.locked():
            previous = destination.read_text()
            old_state = read_optional(state)
            write_atomic(state, json.dumps({"release": release, "previous": previous}).encode())
            try:
                self.switch({destination: config.encode()})
            except Exception:
                write_atomic(state, old_state)
                raise

    def rollback(self, target, release):
        state = self.directory / f".{target}-rollback.json"
        with self.locked():
            previous = json.loads(state.read_text())
            if previous["release"] != release:
                raise ValueError("Refusing to roll back another deployment's configuration")
            self.switch({self.directory / f"{target}.conf": previous["previous"].encode()})
            state.unlink()


def main(args):
    if os.geteuid() != 0:
        raise PermissionError("Run through the configured sudo command")
    publisher = Publisher()
    if len(args) == 2 and args[0] == "--bootstrap":
        publisher.bootstrap(Path(args[1]))
        return
    if len(args) not in (2, 3) or args[0] not in DOMAINS or not re.fullmatch(r"[a-f0-9]{12}-[0-9]+-[0-9]+", args[1]):
        raise ValueError("Usage: itzephir-apply-nginx dev|prod RELEASE [--rollback]")
    target, release = args[:2]
    if len(args) == 3:
        if args[2] != "--rollback":
            raise ValueError("Unknown option")
        publisher.rollback(target, release)
    else:
        source = Path("/srv/itzephir") / target / "releases" / release / "nginx" / f"{target}.conf"
        if source.is_symlink():
            raise ValueError("Configuration must be a regular packaged file")
        publisher.publish(target, release, source)


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except Exception as error:
        print(f"nginx configuration failed: {error}", file=sys.stderr)
        sys.exit(1)
