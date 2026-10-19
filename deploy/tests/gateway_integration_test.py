"""Exercise the generated nginx gateway with real HTTP/TLS, DNS, uploads and WebSockets."""
import http.client
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import socketserver
import ssl
import struct
import subprocess
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DEPLOY = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("gateway_nginx", DEPLOY / "apply-nginx.py")
nginx = importlib.util.module_from_spec(spec)
spec.loader.exec_module(nginx)


def free_port():
    with socket.socket() as stream:
        stream.bind(("127.0.0.1", 0))
        return stream.getsockname()[1]


class GatewayIntegrationTest(unittest.TestCase):
    def test_proxy_redirect_legacy_dns_tls_and_websocket(self):
        if not shutil.which("nginx"):
            if os.getenv("PROJECT_ROUTING_REQUIRE_NGINX_TESTS") == "true":
                self.fail("nginx is required for gateway integration tests")
            self.skipTest("nginx unavailable; CI runs this test in an isolated nginx container")
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root / "logs").mkdir()
        certificate, key = root / "cert.pem", root / "key.pem"
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                        "-subj", "/CN=tls-backend", "-addext", "subjectAltName=DNS:tls-backend,DNS:*.dev.itzephir.com",
                        "-keyout", str(key), "-out", str(certificate)], check=True, capture_output=True)
        gateway_port, backend_port, tls_port, http_port, https_port = [free_port() for _ in range(5)]

        class Backend(BaseHTTPRequestHandler):
            def log_message(self, *_): pass

            def do_GET(self):
                if self.headers.get("Upgrade") == "websocket":
                    self.send_response(101)
                    self.send_header("Upgrade", "websocket")
                    self.send_header("Connection", "Upgrade")
                    self.end_headers()
                    self.wfile.write(self.rfile.read(4))
                    self.wfile.flush()
                    self.close_connection = True
                    return
                if self.path.startswith("/api/project-locator/v1/gateway/"):
                    slug = self.path.rsplit("/", 1)[-1]
                    entry = self.headers.get("X-Project-Entry") == "true"
                    original = self.headers.get("X-Original-URI")
                    if slug in {"missing", "pending", "revoked"}:
                        self.send_response(403)
                    elif slug == "redirect" or (entry and slug != "legacy"):
                        self.send_response(403)
                        self.send_header("Location", "https://example.org/exact?q=1" if slug == "redirect" else
                                         f"https://{slug}.dev.itzephir.com" + original.split(slug, 1)[1])
                    else:
                        self.send_response(204)
                        target = f"http://app-container:{backend_port}"
                        if slug == "tls": target = f"https://tls-backend:{tls_port}"
                        if slug == "bad-tls": target = f"https://app-container:{tls_port}"
                        self.send_header("X-Project-Upstream", target)
                        self.send_header("X-Project-URI", original)
                        self.send_header("X-Project-Upstream-Host", target.split("//")[1])
                        self.send_header("X-Project-TLS-Name", target.split("//")[1].split(":")[0])
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                if self.path == "/upstream-redirect":
                    self.send_response(302)
                    self.send_header("Location", f"http://app-container:{backend_port}/login")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                self.echo()

            def do_POST(self): self.echo()

            def echo(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                payload = json.dumps({"path": self.path, "method": self.command, "body": body.decode(),
                                      "host": self.headers.get("Host"), "forwarded": self.headers.get("X-Forwarded-Host")}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

        def server(port, tls=False):
            instance = ThreadingHTTPServer(("127.0.0.1", port), Backend)
            if tls:
                context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
                context.load_cert_chain(certificate, key)
                instance.socket = context.wrap_socket(instance.socket, server_side=True)
            thread = threading.Thread(target=instance.serve_forever, daemon=True)
            thread.start()
            self.addCleanup(instance.server_close)
            self.addCleanup(instance.shutdown)
            return instance

        server(gateway_port)
        server(backend_port)
        server(tls_port, tls=True)

        class DNS(socketserver.BaseRequestHandler):
            def handle(self):
                packet, stream = self.request
                end = 12
                while packet[end]: end += packet[end] + 1
                question = packet[12:end + 5]
                answer = b"\xc0\x0c\x00\x01\x00\x01" + struct.pack("!IH", 1, 4) + socket.inet_aton("127.0.0.1")
                stream.sendto(packet[:2] + b"\x81\x80\x00\x01\x00\x01\x00\x00\x00\x00" + question + answer, self.client_address)

        dns = socketserver.UDPServer(("127.0.0.1", 0), DNS)
        threading.Thread(target=dns.serve_forever, daemon=True).start()
        self.addCleanup(dns.server_close)
        self.addCleanup(dns.shutdown)
        generated = nginx.with_registry_gateway((DEPLOY / "nginx/dev.conf").read_text(), "dev")
        generated = generated.replace("listen 80;", f"listen 127.0.0.1:{http_port};").replace("listen [::]:80;", "")
        generated = generated.replace("listen 443 ssl;", f"listen 127.0.0.1:{https_port} ssl;").replace("listen [::]:443 ssl;", "")
        generated = generated.replace("127.0.0.1:18081", f"127.0.0.1:{gateway_port}")
        generated = generated.replace("127.0.0.1:18554", f"127.0.0.1:{dns.server_address[1]}")
        for name in ("itzephir.com", "itzephir-projects-dev"):
            generated = generated.replace(f"/etc/letsencrypt/live/{name}/fullchain.pem", str(certificate))
            generated = generated.replace(f"/etc/letsencrypt/live/{name}/privkey.pem", str(key))
        generated = generated.replace("/etc/ssl/certs/ca-certificates.crt", str(certificate))
        generated = generated.replace("/var/log/nginx/", str(root) + "/")
        configuration = root / "nginx.conf"
        configuration.write_text(f"pid {root}/nginx.pid;\nerror_log {root}/error.log;\nevents {{}}\nhttp {{\n{generated}\n}}")
        checked = subprocess.run(["nginx", "-t", "-p", str(root), "-c", str(configuration)], capture_output=True, text=True)
        self.assertEqual(checked.returncode, 0, checked.stderr)
        process = subprocess.Popen(["nginx", "-p", str(root), "-c", str(configuration), "-g", "daemon off;"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.addCleanup(lambda: (process.terminate(), process.wait(timeout=5)))
        for _ in range(50):
            try:
                with socket.create_connection(("127.0.0.1", https_port), timeout=.2): break
            except OSError: time.sleep(.05)
        context = ssl._create_unverified_context()

        def request(host, path="/", method="GET", body=None):
            connection = http.client.HTTPSConnection("127.0.0.1", https_port, context=context, timeout=5)
            connection.request(method, path, body=body, headers={"Host": host})
            response = connection.getresponse()
            result = response.status, dict(response.getheaders()), response.read()
            connection.close()
            return result

        status, headers, _ = request("dev.itzephir.com", "/dev/project/app/docs?q=1")
        self.assertEqual(status, 307)
        self.assertEqual(headers["Location"], "https://app.dev.itzephir.com/docs?q=1")
        self.assertEqual(request("dev.itzephir.com", "/dev/projects/app/docs")[0], 307)
        self.assertEqual(request("dev.itzephir.com", "/__staging-project/app/")[0], 404)
        status, headers, _ = request("dev.itzephir.com", "/dev/project/redirect/?private=secret")
        self.assertEqual(status, 307)
        self.assertEqual(headers["Location"], "https://example.org/exact?q=1")
        for slug in ["missing", "pending", "revoked"]:
            self.assertEqual(request(f"{slug}.dev.itzephir.com")[0], 404)
            self.assertEqual(request("dev.itzephir.com", f"/dev/project/{slug}/")[0], 404)
        status, _, payload = request("app.dev.itzephir.com", "/api?q=a%2Fb", "POST", "request-body")
        self.assertEqual(status, 200, (root / "error.log").read_text())
        self.assertEqual(json.loads(payload), {"path": "/api?q=a%2Fb", "method": "POST", "body": "request-body",
                                              "host": f"app-container:{backend_port}", "forwarded": "app.dev.itzephir.com"})
        self.assertEqual(request("tls.dev.itzephir.com")[0], 200)
        self.assertEqual(request("bad-tls.dev.itzephir.com")[0], 502)
        self.assertEqual(request("dev.itzephir.com", "/dev/project/legacy/asset.css")[0], 200)
        status, headers, _ = request("app.dev.itzephir.com", "/upstream-redirect")
        self.assertEqual(status, 302)
        self.assertTrue(headers["Location"].endswith("/login"))
        self.assertNotIn("app-container", headers["Location"])
        connection = context.wrap_socket(socket.create_connection(("127.0.0.1", https_port)), server_hostname="app.dev.itzephir.com")
        with connection:
            connection.settimeout(5)
            connection.sendall(b"GET /socket HTTP/1.1\r\nHost: app.dev.itzephir.com\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n")
            received = b""
            while b"\r\n\r\n" not in received: received += connection.recv(1024)
            self.assertIn(b"101", received.split(b"\r\n", 1)[0])
            connection.sendall(b"ping")
            self.assertEqual(connection.recv(4), b"ping")


if __name__ == "__main__": unittest.main()
