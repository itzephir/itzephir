"""Keep operator inventory and credentials out of published source and PR text."""
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
PUBLIC_RESOLVERS = {"1.1.1.1", "8.8.8.8", "8.8.4.4", "9.9.9.9"}
IPV4 = re.compile(r"(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])")
HOME = re.compile(r"/(?:Users|home)/[A-Za-z0-9_.-]+/")
RESOURCE = re.compile(r'''\b(?:server|account|project|disk|network|vm)[_-]?id\b["'`]?\s*[:=]\s*["'`]?\d{5,}''', re.I)
KEY = re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----")


def findings(text):
    for number, line in enumerate(text.splitlines(), 1):
        for match in IPV4.finditer(line):
            try:
                address = ipaddress.ip_address(match.group())
            except ValueError:
                continue
            if address.is_global and str(address) not in PUBLIC_RESOLVERS:
                yield number, "public IP address; use private deployment configuration"
        if HOME.search(line):
            yield number, "personal home path; use a portable path"
        if RESOURCE.search(line):
            yield number, "provider resource ID; keep operator inventory private"
        if KEY.search(line):
            yield number, "private key material"


class PublicMetadataTests(unittest.TestCase):
    def test_tracked_source_contains_no_operator_inventory(self):
        paths = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
        violations = []
        for name in paths:
            if not name or name.startswith("third_party/"):
                continue
            try:
                content = (ROOT / name).read_text()
            except (UnicodeError, OSError):
                continue
            violations.extend(f"{name}:{line}: {reason}" for line, reason in findings(content))
        self.assertEqual(violations, [], "\n".join(violations))

    def test_pr_description_contains_no_operator_inventory(self):
        event_path = os.getenv("GITHUB_EVENT_PATH")
        if not event_path:
            return
        event = json.loads(Path(event_path).read_text())
        pr = event.get("pull_request", {})
        violations = [f"PR {field}:{line}: {reason}" for field in ("title", "body")
                      for line, reason in findings(pr.get(field) or "")]
        self.assertEqual(violations, [], "\n".join(violations))


if __name__ == "__main__":
    unittest.main()
