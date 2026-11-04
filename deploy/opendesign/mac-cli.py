#!/usr/bin/python3
"""Local entry point for the official CLI in the remote OpenDesign workspace."""
import json
import os
from pathlib import Path
import re
import shlex
import sys

DEFAULT_CONFIG = Path.home() / ".config/opendesign/remote.json"
DEFAULTS = {
    "ssh_target": "root@deployment-host.example.invalid",
    "container": "itzephir-opendesign",
    "daemon_url": "http://127.0.0.1:7456",
}
HELP = """OpenDesign remote CLI for itzephir

Usage:
  opendesign status --json
  opendesign version
  opendesign project list --json
  opendesign design-systems --help
  opendesign files --help
  opendesign mcp
  opendesign help                  Official CLI help (requires deployment)

Commands execute inside the VM's OpenDesign container over SSH. File paths
refer to that container, not this Mac. The agent/model stays on your Mac;
this entry point starts neither a local daemon nor a model on the VM.

Configuration: ~/.config/opendesign/remote.json
SSH uses your existing key and requires a previously verified host key.
System /usr/bin/od is unchanged.
"""


def configuration(path=DEFAULT_CONFIG):
    values = {**DEFAULTS, **(json.loads(path.read_text()) if path.exists() else {})}
    if not isinstance(values["ssh_target"], str) or not re.fullmatch(
            r"[A-Za-z0-9_][A-Za-z0-9_.-]*@[A-Za-z0-9][A-Za-z0-9.-]*", values["ssh_target"]):
        raise ValueError("ssh_target must be user@hostname")
    if not isinstance(values["container"], str) or not re.fullmatch(
            r"[A-Za-z0-9][A-Za-z0-9_.-]*", values["container"]):
        raise ValueError("Invalid container name")
    if values["daemon_url"] != DEFAULTS["daemon_url"]:
        raise ValueError("The container CLI must use its loopback daemon URL")
    return values


def ssh_command(args, values):
    inspect = shlex.join(["docker", "inspect", "--format", "{{.State.Running}}", values["container"]])
    cli = shlex.join([
        "docker", "exec", "-i", "--user", "1001:1001", "--workdir", "/app",
        "--env", "OD_DATA_DIR=/app/.od", "--env", "OD_DAEMON_URL=" + values["daemon_url"],
        values["container"], "node", "apps/daemon/dist/cli.js", *args,
    ])
    message = shlex.quote("OpenDesign is not running on the VM. Deploy PR #16 before using the remote CLI.\n")
    remote = (f'if [ "$({inspect} 2>/dev/null)" != true ]; then '
              f"printf %s {message} >&2; exit 69; fi; exec {cli}")
    return ["/usr/bin/ssh", "-T", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
            "-o", "ForwardAgent=no", "-o", "ConnectTimeout=15", values["ssh_target"], remote]


def main():
    args = sys.argv[1:]
    if not args or args in (["--help"], ["-h"]):
        print(HELP)
        return
    if args == ["help"]:
        args = ["--help"]
    command = ssh_command(args, configuration())
    os.execv(command[0], command)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError) as error:
        print(f"OpenDesign CLI: {error}", file=sys.stderr)
        sys.exit(2)
