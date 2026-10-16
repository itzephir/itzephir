#!/usr/bin/env python3
"""One-time, root-run PostgreSQL and web-login setup from a reviewed Git revision.

Does not restart the website or change nginx. CI activates the checked release.
Credentials are generated on the VM and never printed or passed in argv.
"""

import fcntl
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tempfile


def target_name(target):
    if target not in {"dev", "prod"}:
        raise ValueError("Environment must be dev or prod")
    return f"itzephir_locator_{target}"


def read_config(path):
    return dict(line.split("=", 1) for line in path.read_text().splitlines()
                if line and not line.startswith("#"))


def write_private(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as stream:
        temporary = Path(stream.name)
        try:
            os.fchmod(stream.fileno(), 0o600)
            stream.write(content.encode())
            stream.flush()
            os.fsync(stream.fileno())
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)


def prepare_config(target, directory):
    name = target_name(target)
    active = directory / f"locator-{target}.env"
    pending = directory / f".locator-{target}.env.pending"
    source = active if active.exists() else pending
    if not source.exists():
        values = {
            "LOCATOR_DATABASE_URL": f"jdbc:postgresql://127.0.0.1:5432/{name}",
            "LOCATOR_DATABASE_USER": name,
            "LOCATOR_DATABASE_PASSWORD": secrets.token_hex(32),
            "LOCATOR_ADMIN_TOKEN": secrets.token_hex(32),
            "LOCATOR_JWT_SECRET": secrets.token_hex(32),
            "LOCATOR_ADMIN_USERNAME": "itzephir",
            "LOCATOR_ADMIN_PASSWORD": secrets.token_hex(24),
        }
        write_private(pending, "".join(f"{key}={value}\n" for key, value in values.items()))
    values = read_config(source)
    if (values.get("LOCATOR_DATABASE_URL") != f"jdbc:postgresql://127.0.0.1:5432/{name}"
            or values.get("LOCATOR_DATABASE_USER") != name):
        raise ValueError("Existing database configuration differs; refusing to overwrite it")
    for key in ("LOCATOR_DATABASE_PASSWORD", "LOCATOR_ADMIN_TOKEN", "LOCATOR_JWT_SECRET", "LOCATOR_ADMIN_PASSWORD"):
        if not values.get(key) or not re.fullmatch(r"[A-Za-z0-9_-]{32,256}", values[key]):
            raise ValueError(f"Missing or unsupported {key}; existing credentials were not changed")
    return values, source, active


def provision_database(values, sql):
    name = values["LOCATOR_DATABASE_USER"]
    if not re.fullmatch(r"itzephir_locator_(dev|prod)", name):
        raise ValueError("Unexpected database owner")
    password = values["LOCATOR_DATABASE_PASSWORD"]
    if not re.fullmatch(r"[A-Za-z0-9_-]{32,256}", password):
        raise ValueError("Unsupported database password")
    owner = sql(f"SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = '{name}';").strip()
    if owner and owner != name:
        raise ValueError("Database already belongs to another role; refusing to change ownership")
    exists = sql(f"SELECT 1 FROM pg_roles WHERE rolname = '{name}';").strip() == "1"
    # stdin only; suppress statement logging and client SQL context for the password command.
    sql("SET log_statement = 'none'; SET log_min_error_statement = 'panic';\n"
        f"{'ALTER' if exists else 'CREATE'} ROLE {name} WITH LOGIN NOSUPERUSER NOCREATEDB "
        f"NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '{password}';", sensitive=True)
    if not owner:
        sql(f"CREATE DATABASE {name} OWNER {name};")
    # The other environment's role must not connect via PostgreSQL's PUBLIC defaults.
    sql(f"REVOKE ALL ON DATABASE {name} FROM PUBLIC;")


def peer_sql(statement, sensitive=False):
    result = subprocess.run(
        ["runuser", "-u", "postgres", "--", "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-d", "postgres"],
        input=statement, text=True, capture_output=True,
    )
    if result.returncode:
        raise RuntimeError("PostgreSQL provisioning command failed" if sensitive else result.stderr.strip())
    return result.stdout


def install_drop_in(target, configuration, unit_root=Path("/etc/systemd/system")):
    target_name(target)
    drop_in = unit_root / f"itzephir@{target}.service.d/locator.conf"
    write_private(drop_in, f"[Unit]\nAfter=postgresql.service\n\n[Service]\nEnvironmentFile={configuration}\n")
    # Bootstrap is normally invoked with umask 077; systemctl cat must still work as deploy.
    drop_in.parent.chmod(0o755)
    drop_in.chmod(0o644)


def main():
    if len(sys.argv) != 2:
        raise ValueError("Usage: setup-locator.py dev|prod")
    target = sys.argv[1]
    name = target_name(target)
    if os.geteuid() != 0:
        raise PermissionError("Run the committed setup script as root on the deployment VM")
    directory = Path("/etc/itzephir")
    directory.mkdir(mode=0o755, exist_ok=True)
    with open(directory / ".locator-setup.lock", "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if not shutil.which("psql"):
            subprocess.run(["apt-get", "update"], check=True)
            subprocess.run(["apt-get", "install", "-y", "postgresql"], check=True,
                           env={**os.environ, "DEBIAN_FRONTEND": "noninteractive"})
        subprocess.run(["systemctl", "enable", "--now", "postgresql"], check=True)
        values, source, active = prepare_config(target, directory)
        provision_database(values, peer_sql)
        # Exercise the same TCP/password route used by the JVM before enabling configuration.
        subprocess.run(["psql", "-X", "-w", "-qAt", "-h", "127.0.0.1", "-p", "5432", "-U", name,
                        "-d", name, "-c", "SELECT 1"], check=True, stdout=subprocess.DEVNULL,
                       env={**os.environ, "PGPASSWORD": values["LOCATOR_DATABASE_PASSWORD"]})
        if source != active:
            source.replace(active)
        active.chmod(0o600)
        # A required, per-environment drop-in also supports VMs bootstrapped before locator existed.
        install_drop_in(target, active)
        subprocess.run(["systemctl", "daemon-reload"], check=True)
    print(f"Locator {target} prepared. Credentials remain in {active}; activate the application through CI.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, PermissionError, RuntimeError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
