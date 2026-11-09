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
from urllib.parse import urlsplit

DEPLOY = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("gateway_nginx", DEPLOY / "apply-nginx.py")
nginx = importlib.util.module_from_spec(spec)
spec.loader.exec_module(nginx)


def free_port():
    with socket.socket() as stream:
        stream.bind(("127.0.0.1", 0))
        return stream.getsockname()[1]


class GatewayIntegrationTest(unittest.TestCase):
    def test_namespaces_proxy_redirect_retired_paths_dns_tls_and_websocket(self):
        if os.getenv("PROJECT_ROUTING_REQUIRE_NGINX_TESTS") != "true":
            self.skipTest("run in the dedicated nginx container; runner nginx may predate http2 on")
        if not shutil.which("nginx"):
            self.fail("nginx is required for gateway integration tests")
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root / "logs").mkdir()
        certificate, key = root / "cert.pem", root / "key.pem"
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                        "-subj", "/CN=tls-backend", "-addext", "subjectAltName=DNS:tls-backend,DNS:*.dev.itzephir.com,DNS:*.itzephir.com",
                        "-keyout", str(key), "-out", str(certificate)], check=True, capture_output=True)
        namespace_certificate, namespace_key = root / "namespace-cert.pem", root / "namespace-key.pem"
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                        "-subj", "/CN=project-namespaces", "-addext",
                        "subjectAltName=" + ",".join(f"DNS:*.{slug}.{domain}" for slug in ("app", "other", "redirect", "proxy")
                                                     for domain in ("itzephir.com", "dev.itzephir.com")),
                        "-keyout", str(namespace_key), "-out", str(namespace_certificate)], check=True, capture_output=True)
        gateway_port, prod_gateway_port, backend_port, tls_port, http_port, https_port = [free_port() for _ in range(6)]
        resolutions = []

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
                    original = self.headers.get("X-Original-URI")
                    host = self.headers.get("X-Project-Host")
                    resolutions.append(("dev" if self.server.server_port == gateway_port else "prod", slug, host))
                    if slug in {"missing", "pending", "revoked"}:
                        self.send_response(403)
                    elif slug == "redirect":
                        self.send_response(403)
                        self.send_header("Location", "https://example.org/exact?q=1")
                    else:
                        self.send_response(204)
                        target = f"http://app-container:{backend_port}"
                        if slug == "tls": target = f"https://tls-backend:{tls_port}"
                        if slug == "proxy": target = f"https://tls-backend:{tls_port}/base"
                        if slug == "bad-tls": target = f"https://app-container:{tls_port}"
                        self.send_header("X-Project-Upstream", target)
                        self.send_header("X-Project-URI", original)
                        self.send_header("X-Project-Upstream-Host", host if slug in {"app", "other"} else urlsplit(target).netloc)
                        self.send_header("X-Project-TLS-Name", urlsplit(target).hostname)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                if self.path == "/upstream-redirect":
                    self.send_response(302)
                    self.send_header("Location", f"http://app-container:{backend_port}/login")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                if self.path == "/base/upstream-redirect":
                    self.send_response(302)
                    self.send_header("Location", f"https://tls-backend:{tls_port}/base/login")
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
        server(prod_gateway_port)
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
        generated = "\n".join(nginx.with_registry_gateway((DEPLOY / f"nginx/{target}.conf").read_text(), target,
                                                        ["app", "other", "redirect", "proxy"]) for target in ("prod", "dev"))
        generated = generated.replace("listen 80;", f"listen 127.0.0.1:{http_port};").replace("listen [::]:80;", "")
        generated = generated.replace("listen 443 ssl;", f"listen 127.0.0.1:{https_port} ssl;").replace("listen [::]:443 ssl;", "")
        generated = generated.replace("127.0.0.1:18081", f"127.0.0.1:{gateway_port}")
        generated = generated.replace("127.0.0.1:18080", f"127.0.0.1:{prod_gateway_port}")
        generated = generated.replace("127.0.0.1:18554", f"127.0.0.1:{dns.server_address[1]}")
        generated = generated.replace("127.0.0.1:18553", f"127.0.0.1:{dns.server_address[1]}")
        for target in ("prod", "dev"):
            for slug in ("app", "other", "redirect", "proxy"):
                generated = generated.replace(f"/etc/letsencrypt/live/itzephir-project-{target}-{slug}/fullchain.pem", str(namespace_certificate))
                generated = generated.replace(f"/etc/letsencrypt/live/itzephir-project-{target}-{slug}/privkey.pem", str(namespace_key))
        for name in ("itzephir.com", "itzephir-projects-dev", "itzephir-projects-prod"):
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
            connection.request(method, path, body=body, headers={"Host": host, "X-Forwarded-Host": "spoofed.example"})
            response = connection.getresponse()
            result = response.status, dict(response.getheaders()), response.read()
            connection.close()
            return result

        for host in ("itzephir.com", "dev.itzephir.com"):
            for path in ("/dev/project/app/docs?q=1", "/dev/projects/app/docs", "/dev/project", "/dev/projects",
                         "/__staging-project/app/", "/dev/project/redirect/?private=secret"):
                status, headers, _ = request(host, path)
                self.assertEqual(status, 404, (host, path))
                self.assertNotIn("Location", headers)
        self.assertFalse(resolutions)
        for slug in ["missing", "pending", "revoked"]:
            self.assertEqual(request(f"{slug}.dev.itzephir.com")[0], 404)
            self.assertEqual(request("dev.itzephir.com", f"/dev/project/{slug}/")[0], 404)
        status, _, payload = request("app.dev.itzephir.com", "/api?q=a%2Fb", "POST", "request-body")
        self.assertEqual(status, 200, (root / "error.log").read_text())
        self.assertEqual(json.loads(payload), {"path": "/api?q=a%2Fb", "method": "POST", "body": "request-body",
                                              "host": "app.dev.itzephir.com", "forwarded": "app.dev.itzephir.com"})
        # Both environments coexist; the prefix cannot select another slug or locator stand.
        for stand, domain in (("prod", "itzephir.com"), ("dev", "dev.itzephir.com")):
            for prefix, slug in (("dev", "app"), ("pr-42", "app"), ("other", "app"), ("app", "other")):
                host = f"{prefix}.{slug}.{domain}"
                status, _, payload = request(host, "/nested/a%20b?x=a%2Fb&x=2", "POST", "namespace-body")
                self.assertEqual(status, 200, (root / "error.log").read_text())
                self.assertEqual(json.loads(payload), {"path": "/nested/a%20b?x=a%2Fb&x=2", "method": "POST",
                                                      "body": "namespace-body", "host": host, "forwarded": host})
                self.assertEqual(resolutions[-1], (stand, slug, host))
                plain = http.client.HTTPConnection("127.0.0.1", http_port, timeout=5)
                plain.request("GET", "/docs?q=a%2Fb", headers={"Host": host})
                response = plain.getresponse()
                self.assertEqual(response.status, 308)
                self.assertEqual(response.getheader("Location"), f"https://{host}/docs?q=a%2Fb")
                response.read()
                plain.close()
        for host in ("a.b.app.itzephir.com", "a.b.app.dev.itzephir.com", "dev.app.itzephir.com.evil.org", "-bad.app.itzephir.com",
                     "a_b.app.dev.itzephir.com", "dev.unenrolled.itzephir.com"):
            before = len(resolutions)
            request(host)
            self.assertEqual(len(resolutions), before, host)
        # Real SNI and hostname verification: a common wildcard cannot serve the deeper name.
        verified = ssl.create_default_context(cafile=str(certificate))
        verified.load_verify_locations(cafile=str(namespace_certificate))
        for host in ("app.itzephir.com", "app.dev.itzephir.com", "dev.app.itzephir.com", "pr-42.app.dev.itzephir.com"):
            with verified.wrap_socket(socket.create_connection(("127.0.0.1", https_port)), server_hostname=host) as stream:
                stream.sendall(f"GET / HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\n".encode())
                self.assertIn(b"200", stream.recv(1024).split(b"\r\n", 1)[0])
        with self.assertRaises(ssl.SSLCertVerificationError):
            with verified.wrap_socket(socket.create_connection(("127.0.0.1", https_port)), server_hostname="a.b.app.itzephir.com"):
                pass
        status, headers, _ = request("dev.redirect.itzephir.com", "/private?q=secret")
        self.assertEqual(status, 307)
        self.assertEqual(headers["Location"], "https://example.org/exact?q=1")
        self.assertEqual(request("tls.dev.itzephir.com")[0], 200)
        self.assertEqual(request("bad-tls.dev.itzephir.com")[0], 502)
        status, _, payload = request("pr-42.proxy.dev.itzephir.com", "/a%20b?q=a%2Fb")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(payload), {"path": "/base/a%20b?q=a%2Fb", "method": "GET", "body": "",
                                              "host": f"tls-backend:{tls_port}", "forwarded": "pr-42.proxy.dev.itzephir.com"})
        status, headers, _ = request("pr-42.proxy.dev.itzephir.com", "/upstream-redirect")
        self.assertEqual(status, 302)
        self.assertTrue(headers["Location"].endswith("/login"))
        self.assertNotIn("tls-backend", headers["Location"])
        status, headers, _ = request("pr-42.app.dev.itzephir.com", "/upstream-redirect")
        self.assertEqual(status, 302)
        self.assertTrue(headers["Location"].endswith("/login"))
        self.assertNotIn("app-container", headers["Location"])
        connection = verified.wrap_socket(socket.create_connection(("127.0.0.1", https_port)), server_hostname="pr-42.app.dev.itzephir.com")
        with connection:
            connection.settimeout(5)
            connection.sendall(b"GET /socket HTTP/1.1\r\nHost: pr-42.app.dev.itzephir.com\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n")
            received = b""
            while b"\r\n\r\n" not in received: received += connection.recv(1024)
            self.assertIn(b"101", received.split(b"\r\n", 1)[0])
            connection.sendall(b"ping")
            self.assertEqual(connection.recv(4), b"ping")


if __name__ == "__main__": unittest.main()
