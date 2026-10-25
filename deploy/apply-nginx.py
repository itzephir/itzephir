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



def without_project_gateway(config):
    config = re.sub(r"    # Managed project gateway;.*?(?=    location / \{)", "", config, flags=re.S)
    config = re.sub(r"    # Managed project routing v2 start.*?    # Managed project routing v2 end\n\n?", "", config, flags=re.S)
    return re.sub(r"\n# Managed project subdomains v2 start.*?# Managed project subdomains v2 end\n", "", config, flags=re.S)


def without_staging_interceptor(config):
    # Registry auth must run before deciding whether to invoke the legacy stage interceptor.
    return re.sub(r"    # Legacy stage interceptor start\n.*?    # Legacy stage interceptor end\n\n?", "", config, flags=re.S)


def routing_locations(target, entry):
    port = PORTS[target]
    dns_port = 18553 if target == "prod" else 18554
    selector = ('~ "^/dev/projects?/(?<entry_slug>[a-z0-9][a-z0-9_-]{0,63})(?:/.*)?$"' if entry else "/")
    slug = "$entry_slug" if entry else "$app_slug"
    return f"""    # Managed project routing v2 start
    location {selector} {{
        set $locator_slug {slug};
        set $locator_entry {'true' if entry else 'false'};
        set $project_connection close;
        if ($http_upgrade != "") {{ set $project_connection upgrade; }}
        auth_request /_locator_resolve;
        auth_request_set $project_upstream $upstream_http_x_project_upstream;
        auth_request_set $project_uri $upstream_http_x_project_uri;
        auth_request_set $project_host $upstream_http_x_project_upstream_host;
        auth_request_set $project_tls_name $upstream_http_x_project_tls_name;
        auth_request_set $project_redirect $upstream_http_location;
        error_page 403 = @locator_unavailable;
        resolver 127.0.0.1:{dns_port} valid=5s ipv6=off;
        resolver_timeout 3s;
        proxy_pass $project_upstream$project_uri;
        proxy_http_version 1.1;
        proxy_redirect $project_upstream/ /;
        proxy_set_header Host $project_host;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $project_connection;
        proxy_ssl_server_name on;
        proxy_ssl_name $project_tls_name;
        proxy_ssl_verify on;
        proxy_ssl_trusted_certificate /etc/ssl/certs/ca-certificates.crt;
        proxy_connect_timeout 5s;
        proxy_read_timeout 60s;
        proxy_buffering off;
        client_max_body_size 32m;
    }}
    location = /_locator_resolve {{
        internal;
        proxy_pass http://127.0.0.1:{port}/api/project-locator/v1/gateway/$locator_slug;
        proxy_pass_request_body off;
        proxy_set_header Content-Length "";
        proxy_set_header X-Project-Host $host;
        proxy_set_header X-Project-Entry $locator_entry;
        proxy_set_header X-Project-Upgrade $http_upgrade;
        proxy_set_header X-Original-URI $request_uri;
        proxy_set_header Cookie "";
        proxy_set_header Authorization "";
    }}
    location @locator_unavailable {{
        add_header Cache-Control no-store always;
        if ($project_redirect = "") {{ return 404; }}
        return 307 $project_redirect;
    }}
    # Managed project routing v2 end

"""


def with_registry_gateway(config, target):
    """The registry selects a proxy or redirect on every request, without nginx writes."""
    config = without_staging_interceptor(without_project_gateway(config))
    host = "itzephir.com" if target == "prod" else "dev.itzephir.com"
    for block in server_blocks(config):
        if names(block) == {host} and re.search(r"\blisten\s+(?:\[::\]:)?443\s", block):
            anchor = "    location / {"
            if block.count(anchor) != 1:
                raise ValueError("Expected one canonical portfolio location")
            config = config.replace(block, block.replace(anchor, routing_locations(target, entry=True) + anchor, 1), 1)
            break
    else:
        raise ValueError("Missing canonical HTTPS server for project routing")
    # Do not use .domain wildcards: they also match nested names. Each slug is one DNS label.
    pattern = f'"~^(?<app_slug>[a-z0-9](?:[a-z0-9-]{{0,61}}[a-z0-9])?)\\.{host.replace(".", r"\.")}$"'
    certificate = f"/etc/letsencrypt/live/itzephir-projects-{target}"
    return config + f"""
# Managed project subdomains v2 start
server {{
    listen 80;
    listen [::]:80;
    server_name {pattern};
    location / {{ return 308 https://$host$request_uri; }}
}}
server {{
    listen 443 ssl;
    listen [::]:443 ssl;
    http2 on;
    server_name {pattern};
    ssl_certificate {certificate}/fullchain.pem;
    ssl_certificate_key {certificate}/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    server_tokens off;
    add_header X-Content-Type-Options nosniff always;
{routing_locations(target, entry=False)}}}
# Managed project subdomains v2 end
"""


def with_project_gateway(config, target):
    """Keep existing project deployments working until DNS/TLS and registry routing are enabled."""
    config = without_project_gateway(config)
    host = "itzephir.com" if target == "prod" else "dev.itzephir.com"
    port = 18100 if target == "prod" else 18101
    locations = f"""    # Managed project gateway; application deployments own their routes.
    location ^~ /dev/project/ {{
        proxy_pass http://127.0.0.1:{port};
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }}

"""
    for block in server_blocks(config):
        if names(block) == {host} and re.search(r"\blisten\s+(?:\[::\]:)?443\s", block):
            anchor = "    location / {"
            if block.count(anchor) != 1:
                raise ValueError("Expected one canonical portfolio location")
            return config.replace(block, block.replace(anchor, locations + anchor, 1), 1)
    raise ValueError("Missing canonical HTTPS server for project routing")


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

    def check_routing(self, target):
        # Validate the future wildcard servers without changing active files or reloading.
        with self.locked():
            config = with_registry_gateway((self.directory / f"{target}.conf").read_text(), target)
            with tempfile.NamedTemporaryFile(mode="w", suffix=".conf", prefix="itzephir-routing-") as candidate:
                candidate.write("events {}\nhttp {\n" + config + "\n}\n")
                candidate.flush()
                self.runner(["nginx", "-t", "-c", candidate.name])

    def publish(self, target, release, source):
        config = without_project_gateway(source.read_text())
        validate_environment(config, target)
        enabled = (source.parent / "enable-project-routing").is_file()
        config = (with_registry_gateway if enabled else with_project_gateway)(config, target)
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
    if args == ["--version"]:
        print("2")
        return
    if os.geteuid() != 0:
        raise PermissionError("Run through the configured sudo command")
    publisher = Publisher()
    if len(args) == 2 and args[0] == "--check-routing" and args[1] in DOMAINS:
        publisher.check_routing(args[1])
        return
    if args == ["--projects"]:
        with publisher.locked():
            publisher.switch({publisher.directory / f"{target}.conf": with_project_gateway((publisher.directory / f"{target}.conf").read_text(), target).encode() for target in DOMAINS})
        return
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
