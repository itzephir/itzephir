"""Real TLS proxy: every path requires authentication, including health/static files."""
import base64
import http.client
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SOURCE = Path(__file__).resolve().parents[1] / "opendesign"


def free_port():
    with socket.socket() as stream:
        stream.bind(("127.0.0.1", 0))
        return stream.getsockname()[1]


@unittest.skipUnless(os.getenv("OPENDESIGN_REQUIRE_NGINX_TESTS") == "true", "Run in the nginx CI container")
class OpenDesignNginxTests(unittest.TestCase):
    def test_tls_auth_uploads_forwarded_headers_and_websockets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            root.chmod(0o755)
            (root / "logs").mkdir()
            certificate, key = root / "cert.pem", root / "key.pem"
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                            "-subj", "/CN=opendesign.itzephir.com", "-keyout", str(key), "-out", str(certificate)],
                           check=True, capture_output=True)
            hashed = subprocess.run(["openssl", "passwd", "-6", "-stdin"], input="test-only-password\n",
                                    text=True, capture_output=True, check=True).stdout.strip()
            password = root / "htpasswd"
            password.write_text(f"open-design:{hashed}\n")
            password.chmod(0o644)

            class Backend(BaseHTTPRequestHandler):
                def log_message(self, *_): pass

                def do_GET(self):
                    if self.headers.get("Upgrade") == "websocket":
                        self.send_response(101)
                        self.send_header("Upgrade", "websocket")
                        self.send_header("Connection", "Upgrade")
                        self.end_headers()
                        return
                    self.do_POST()

                def do_POST(self):
                    body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                    payload = json.dumps({"body": body.decode(), "path": self.path,
                                          "host": self.headers.get("Host"), "proto": self.headers.get("X-Forwarded-Proto"),
                                          "authorization": self.headers.get("Authorization")}).encode()
                    self.send_response(200)
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)

            backend = ThreadingHTTPServer(("127.0.0.1", 0), Backend)
            threading.Thread(target=backend.serve_forever, daemon=True).start()
            self.addCleanup(backend.server_close)
            self.addCleanup(backend.shutdown)
            http_port, https_port = free_port(), free_port()
            site = (SOURCE / "nginx.conf").read_text()
            site = site.replace("listen 80;", f"listen 127.0.0.1:{http_port};").replace("listen [::]:80;", "")
            site = site.replace("listen 443 ssl;", f"listen 127.0.0.1:{https_port} ssl;").replace("listen [::]:443 ssl;", "")
            site = site.replace("/etc/letsencrypt/live/itzephir-projects-prod/fullchain.pem", str(certificate))
            site = site.replace("/etc/letsencrypt/live/itzephir-projects-prod/privkey.pem", str(key))
            site = site.replace("/etc/nginx/opendesign.htpasswd", str(password))
            site = site.replace("127.0.0.1:7456", f"127.0.0.1:{backend.server_port}")
            config = root / "nginx.conf"
            config.write_text(f"pid {root}/nginx.pid;\nerror_log {root}/error.log;\nevents {{}}\nhttp {{\n{site}\n}}")
            checked = subprocess.run(["nginx", "-t", "-p", str(root), "-c", str(config)], capture_output=True, text=True)
            self.assertEqual(checked.returncode, 0, checked.stderr)
            process = subprocess.Popen(["nginx", "-p", str(root), "-c", str(config), "-g", "daemon off;"],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                for _ in range(50):
                    try:
                        with socket.create_connection(("127.0.0.1", https_port), timeout=.2): break
                    except OSError: time.sleep(.05)
                authorization = "Basic " + base64.b64encode(b"open-design:test-only-password").decode()

                def request(path, method="GET", body=None, auth=None, extra=None):
                    connection = http.client.HTTPSConnection("127.0.0.1", https_port, context=ssl._create_unverified_context(), timeout=5)
                    headers = {"Host": "opendesign.itzephir.com", **(extra or {})}
                    if auth: headers["Authorization"] = auth
                    connection.request(method, path, body=body, headers=headers)
                    response = connection.getresponse()
                    result = response.status, dict(response.getheaders()), response.read()
                    connection.close()
                    return result

                for path in ("/", "/api/health", "/_next/static/file.js", "/artifacts/private.html"):
                    self.assertEqual(request(path)[0], 401)
                    self.assertEqual(request(path, auth="Basic d3Jvbmc6d3Jvbmc=")[0], 401)
                    self.assertEqual(request(path, auth=authorization)[0], 200)
                status, _, body = request("/api/upload?q=1", "POST", "saved content", authorization)
                self.assertEqual(status, 200)
                self.assertEqual(json.loads(body), {"body": "saved content", "path": "/api/upload?q=1",
                                                  "host": "opendesign.itzephir.com", "proto": "https", "authorization": authorization})
                self.assertEqual(request("/ws", auth=authorization, extra={"Upgrade": "websocket", "Connection": "Upgrade"})[0], 101)
                plain = http.client.HTTPConnection("127.0.0.1", http_port)
                plain.request("GET", "/project?a=1", headers={"Host": "opendesign.itzephir.com"})
                response = plain.getresponse()
                self.assertEqual(response.status, 308)
                self.assertEqual(response.getheader("Location"), "https://opendesign.itzephir.com/project?a=1")
                response.read()
                plain.close()
            finally:
                process.terminate()
                process.wait(timeout=5)


if __name__ == "__main__":
    unittest.main()
