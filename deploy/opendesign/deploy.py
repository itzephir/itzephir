#!/usr/bin/env python3
"""Root entry point, invoked by committed CI; never print workspace credentials."""
import fcntl
import grp
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
from contextlib import contextmanager

HOST = "opendesign.itzephir.com"
VOLUME = "itzephir-opendesign-data"
LABEL = "io.itzephir.service=opendesign"


def command(args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, **kwargs).stdout.strip()


@contextmanager
def locked(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a") as handle:
        fcntl.flock(handle, fcntl.LOCK_EX)
        yield


def atomic_write(path, content, mode=0o600):
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as handle:
        pending = Path(handle.name)
        try:
            os.fchmod(handle.fileno(), mode)
            handle.write(content)
            handle.close()
            pending.replace(path)
        finally:
            pending.unlink(missing_ok=True)


class Deployment:
    def __init__(self, source, root=Path("/"), run=command):
        self.source, self.root, self.run = source, root, run
        self.directory = root / "srv/itzephir/opendesign"
        self.compose_file = self.directory / "compose.yaml"
        self.env = root / "etc/itzephir/opendesign.env"
        self.password_file = root / "etc/nginx/opendesign.htpasswd"
        self.site = root / "etc/nginx/sites-available/opendesign.itzephir.com"
        self.enabled = root / "etc/nginx/sites-enabled/opendesign.itzephir.com"
        self.marker = self.directory / "deployment.json"

    def compose(self, *args):
        return self.run(["docker", "compose", "--project-name", "itzephir-opendesign",
                         "--env-file", str(self.env), "-f", str(self.compose_file), *args])

    def prepare_credentials(self):
        self.env.parent.mkdir(parents=True, exist_ok=True)
        if not self.env.exists():
            atomic_write(self.env, f"OD_API_TOKEN={secrets.token_hex(32)}\n".encode())
        content = self.env.read_text()
        if not re.fullmatch(r"OD_API_TOKEN=[0-9a-f]{64}\n", content):
            raise ValueError("Refusing to replace an invalid existing OpenDesign credential file")
        self.env.chmod(0o600)
        token = content.strip().split("=", 1)[1]
        password_hash = self.run(["openssl", "passwd", "-6", "-stdin"], input=token + "\n")
        atomic_write(self.password_file, f"open-design:{password_hash}\n".encode(), 0o640)
        if self.root == Path("/"):
            os.chown(self.password_file, 0, grp.getgrnam("www-data").gr_gid)
        return token

    def check_volume(self):
        names = self.run(["docker", "volume", "ls", "--format", "{{.Name}}", "--filter", f"name=^{VOLUME}$"])
        if names:
            owner = self.run(["docker", "volume", "inspect", "--format",
                              '{{index .Labels "io.itzephir.service"}}', VOLUME])
            if owner != "opendesign":
                raise ValueError("Refusing to adopt a foreign Docker volume")
        else:
            self.run(["docker", "volume", "create", "--label", LABEL, VOLUME])

    def volume_tool(self, image, backup, script):
        self.run(["docker", "run", "--rm", "--network", "none", "--read-only", "--user", "0:0",
                  "--label", LABEL, "--entrypoint", "sh",
                  "--mount", f"type=volume,src={VOLUME},dst=/workspace",
                  "--mount", f"type=bind,src={backup},dst=/backup",
                  image, "-ec", script])

    def probe(self, token):
        with tempfile.NamedTemporaryFile(mode="w", dir=self.directory) as config:
            config.write(f'user = "open-design:{token}"\n')
            config.flush()
            base = ["curl", "--silent", "--show-error", "--max-time", "15",
                    "--resolve", f"{HOST}:443:127.0.0.1"]
            for path in ("/", "/api/health"):
                status = self.run([*base, "--output", "/dev/null", "--write-out", "%{http_code}", f"https://{HOST}{path}"])
                if status != "401":
                    raise RuntimeError("OpenDesign must reject unauthenticated requests")
                self.run([*base, "--config", config.name, "--fail", "--output", "/dev/null", f"https://{HOST}{path}"])

    def activate(self, revision):
        self.run(["nginx", "-t"])
        self.run(["docker", "info"])
        certificate = self.root / "etc/letsencrypt/live/itzephir-projects-prod/fullchain.pem"
        self.run(["openssl", "x509", "-in", str(certificate), "-noout", "-checkend", "86400", "-checkhost", HOST])
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        if self.compose_file.exists() and not self.marker.exists():
            raise ValueError("Refusing to replace an unmanaged OpenDesign installation")
        if (self.site.exists() or self.enabled.is_symlink()) and not self.marker.exists():
            raise ValueError("Refusing to replace an unmanaged nginx site")
        token = self.prepare_credentials()
        self.check_volume()
        old_compose = self.compose_file.read_bytes() if self.compose_file.exists() else None
        old_site = self.site.read_bytes() if self.site.exists() else None
        old_enabled = self.enabled.is_symlink()
        old_marker = self.marker.read_bytes() if self.marker.exists() else None
        backup = None
        snapshot_complete = False
        candidate_started = False
        nginx_changed = False
        try:
            # Pull before stopping the workspace. No build competes with the portfolio.
            atomic_write(self.compose_file, (self.source / "compose.yaml").read_bytes())
            self.compose("config", "--quiet")
            self.compose("pull", "--quiet")
            if old_compose:
                image = json.loads(old_marker)["image"]
                backup = self.directory / "backups" / str(time.time_ns())
                backup.mkdir(parents=True, mode=0o700)
                atomic_write(backup / "compose.yaml", old_compose)
                self.compose("stop")
                self.volume_tool(image, backup, "tar -czf /backup/workspace.tar.gz -C /workspace .")
                snapshot_complete = True
            candidate_started = True
            self.compose("up", "-d", "--no-build", "--wait", "--wait-timeout", "120")
            with locked(self.root / "run/lock/itzephir-nginx.lock"):
                try:
                    atomic_write(self.site, (self.source / "nginx.conf").read_bytes(), 0o644)
                    if not old_enabled:
                        self.enabled.symlink_to(self.site)
                    nginx_changed = True
                    self.run(["nginx", "-t"])
                    self.run(["systemctl", "reload", "nginx"])
                    self.probe(token)
                except Exception:
                    self.restore_nginx(old_site, old_enabled)
                    nginx_changed = False
                    raise
            image = json.loads(self.compose("config", "--format", "json"))["services"]["workspace"]["image"]
            atomic_write(self.marker, json.dumps({"revision": revision, "image": image}).encode())
        except Exception:
            if nginx_changed:
                with locked(self.root / "run/lock/itzephir-nginx.lock"):
                    self.restore_nginx(old_site, old_enabled)
            if candidate_started:
                self.compose("down")  # External data volume is deliberately retained.
            if old_compose:
                atomic_write(self.compose_file, old_compose)
                if candidate_started and snapshot_complete:
                    self.volume_tool(json.loads(old_marker)["image"], backup,
                                     "find /workspace -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +; "
                                     "tar -xzf /backup/workspace.tar.gz -C /workspace")
                self.compose("up", "-d", "--no-build", "--wait", "--wait-timeout", "120")
            else:
                self.compose_file.unlink(missing_ok=True)
            raise
        # Retain seven offline snapshots, only after successful activation.
        backups = self.directory / "backups"
        if backups.exists():
            for expired in sorted(backups.iterdir(), key=lambda path: path.name, reverse=True)[7:]:
                if expired.name.isdigit() and expired.is_dir() and not expired.is_symlink():
                    shutil.rmtree(expired)

    def restore_nginx(self, content, enabled):
        if content is None:
            self.site.unlink(missing_ok=True)
        else:
            atomic_write(self.site, content, 0o644)
        if not enabled:
            self.enabled.unlink(missing_ok=True)
        self.run(["nginx", "-t"])
        self.run(["systemctl", "reload", "nginx"])


def main():
    if os.geteuid() != 0:
        raise RuntimeError("Run OpenDesign provisioning as root through infrastructure CI")
    if len(sys.argv) != 2 or not re.fullmatch(r"[0-9a-f]{40}", sys.argv[1]):
        raise ValueError("Supply the committed deployment revision")
    with locked(Path("/run/lock/itzephir-opendesign.lock")):
        Deployment(Path(__file__).resolve().parent).activate(sys.argv[1])
    print("OpenDesign is healthy over authenticated HTTPS; credentials remain on the VM.")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        # Captured stdout can contain interpolated compose secrets; never print it.
        print(f"OpenDesign deployment failed while running {error.cmd[0]} (exit {error.returncode}).", file=sys.stderr)
        sys.exit(1)
