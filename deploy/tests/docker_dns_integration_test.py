"""Verify the exact VM CoreDNS image/config/launch flags against a real Docker daemon."""
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import time
import unittest
import uuid

DEPLOY = Path(__file__).resolve().parents[1]


def read_exact(stream, size):
    data = b""
    while len(data) < size:
        part = stream.recv(size - len(data))
        if not part: raise ConnectionError("Incomplete TCP DNS response")
        data += part
    return data


def addresses(port, hostname, transport):
    question = b"".join(bytes([len(label)]) + label.encode() for label in hostname.split(".")) + b"\0"
    request = struct.pack("!6H", 1234, 0x0100, 1, 0, 0, 0) + question + struct.pack("!HH", 1, 1)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM if transport == "udp" else socket.SOCK_STREAM) as stream:
        stream.settimeout(2)
        stream.connect(("127.0.0.1", port))
        if transport == "udp":
            stream.send(request)
            response = stream.recv(65535)
        else:
            stream.sendall(struct.pack("!H", len(request)) + request)
            response = read_exact(stream, struct.unpack("!H", read_exact(stream, 2))[0])
    identifier, flags, questions, answers, _, _ = struct.unpack("!6H", response[:12])
    if identifier != 1234 or flags & 15: return set()
    def skip_name(offset):
        while response[offset]:
            if response[offset] & 0xc0 == 0xc0: return offset + 2
            offset += response[offset] + 1
        return offset + 1
    offset = 12
    for _ in range(questions): offset = skip_name(offset) + 4
    result = set()
    for _ in range(answers):
        offset = skip_name(offset)
        kind, cls, _, length = struct.unpack("!HHIH", response[offset:offset + 10])
        offset += 10
        if kind == 1 and cls == 1 and length == 4: result.add(socket.inet_ntoa(response[offset:offset + 4]))
        offset += length
    return result


@unittest.skipUnless(os.getenv("PROJECT_ROUTING_REQUIRE_DOCKER_TESTS") == "true", "Requires an isolated Docker test network")
class DockerDNSTest(unittest.TestCase):
    def test_real_nonroot_coredns_resolves_docker_names_over_udp_and_tcp(self):
        def docker(*args):
            result = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=120)
            self.assertEqual(result.returncode, 0, result.stderr)
            return result.stdout.strip()
        name = "itzephir-dns-test-" + uuid.uuid4().hex[:12]
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        root.chmod(0o755)
        config = root / "Corefile"
        config.write_bytes((DEPLOY / "coredns/Corefile").read_bytes())
        config.chmod(0o644)
        docker("network", "create", name)
        self.addCleanup(lambda: docker("network", "rm", name))
        self.addCleanup(lambda: subprocess.run(["docker", "rm", "-f", name], capture_output=True))
        command = ['bash', '-c', 'set -euo pipefail; source "$1"; docker pull "$PROJECT_DNS_IMAGE" >/dev/null; start_project_dns dev "$2" "$2" 0 "$3"',
                   'docker-dns-test', str(DEPLOY / "project-dns-container.sh"), name, str(root)]
        launched = subprocess.run(command, capture_output=True, text=True, timeout=180)
        self.assertEqual(launched.returncode, 0, launched.stderr)
        inspected = json.loads(docker("inspect", name))[0]
        if not inspected["State"]["Running"]:
            self.fail(docker("logs", name))
        self.assertEqual(inspected["Config"]["User"], "65532:65532")
        self.assertTrue(inspected["HostConfig"]["ReadonlyRootfs"])
        self.assertIn("no-new-privileges", inspected["HostConfig"]["SecurityOpt"])
        expected = inspected["NetworkSettings"]["Networks"][name]["IPAddress"]
        for transport in ("udp", "tcp"):
            port = int(docker("port", name, "1053/" + transport).rsplit(":", 1)[1])
            for attempt in range(30):
                try:
                    if expected in addresses(port, name, transport): break
                except (OSError, ConnectionError): pass
                time.sleep(.1)
            else: self.fail("Docker DNS lookup failed over " + transport + ": " + docker("logs", name))


if __name__ == "__main__": unittest.main()
