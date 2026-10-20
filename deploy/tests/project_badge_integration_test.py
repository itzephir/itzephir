"""Real public nginx -> stage app interceptor -> project gateway -> project app."""
import gzip
import http.client
import importlib.util
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DEPLOY = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("badge_nginx", DEPLOY / "apply-nginx.py")
nginx = importlib.util.module_from_spec(spec)
spec.loader.exec_module(nginx)


def free_port():
    with socket.socket() as stream:
        stream.bind(("127.0.0.1", 0))
        return stream.getsockname()[1]


class ProjectBadgeIntegrationTest(unittest.TestCase):
    def test_project_gateway_html_and_production_isolation(self):
        if os.getenv("PROJECT_BADGE_REQUIRE_NGINX_TESTS") != "true":
            self.skipTest("run in the dedicated nginx/JRE container")
        for command in ("nginx", "java"):
            self.assertTrue(shutil.which(command), f"{command} is required")
        root_context = tempfile.TemporaryDirectory()
        self.addCleanup(root_context.cleanup)
        root = Path(root_context.name)
        requests = []
        html = b"<!doctype html><html><head><title>Project</title></head><body><h1>Project app</h1>" + b"content " * 8000 + b"</body></html>"
        binary = bytes(range(256)) * 16

        class Project(BaseHTTPRequestHandler):
            def log_message(self, *_): pass

            def do_GET(self):
                requests.append((self.path, self.headers.get("Accept-Encoding"), self.headers.get("If-None-Match")))
                if self.path == "/redirect":
                    self.send_response(302)
                    self.send_header("Location", "/next?q=a%2Fb")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                if self.headers.get("If-None-Match") == '"project-v1"':
                    self.send_response(304)
                    self.send_header("ETag", '"project-v1"')
                    self.end_headers()
                    return
                payload, content_type = html, "text/html; charset=utf-8"
                if self.path == "/api": payload, content_type = b'{"ok":true}', "application/json"
                if self.path == "/asset": payload, content_type = binary, "application/octet-stream"
                if self.path == "/fragment": payload = b"<main>fragment</main>"
                encoded = "gzip" in self.headers.get("Accept-Encoding", "")
                if encoded: payload = gzip.compress(payload)
                self.send_response(200)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(payload)))
                self.send_header("Cache-Control", "public, max-age=3600")
                self.send_header("ETag", '"project-v1"')
                self.send_header("Set-Cookie", "project=a; Path=/")
                if encoded: self.send_header("Content-Encoding", "gzip")
                if self.path == "/download": self.send_header("Content-Disposition", "attachment; filename=page.html")
                self.end_headers()
                self.wfile.write(payload)

        project = ThreadingHTTPServer(("127.0.0.1", 0), Project)
        thread = threading.Thread(target=project.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(project.server_close)
        self.addCleanup(project.shutdown)
        app_port, stage_port, prod_port = [free_port() for _ in range(3)]
        distribution = DEPLOY.parent / "website/build/install/website"
        self.assertTrue((distribution / "lib/website.jar").is_file(), "run :website:installDist first")
        app_log = (root / "application.log").open("w+")
        self.addCleanup(app_log.close)
        app = subprocess.Popen(["java", "-cp", str(distribution / "lib/*"), "com.itzephir.website.ApplicationKt"],
                               cwd=root, env={**os.environ, "PORT": str(app_port), "BIND_HOST": "127.0.0.1", "DEPLOYMENT_ENV": "dev"},
                               stdout=app_log, stderr=subprocess.STDOUT)
        self.addCleanup(lambda: app.wait(timeout=10))
        self.addCleanup(app.terminate)
        for _ in range(100):
            if app.poll() is not None:
                self.fail((root / "application.log").read_text())
            try:
                connection = http.client.HTTPConnection("127.0.0.1", app_port, timeout=1)
                connection.request("GET", "/healthz")
                if connection.getresponse().read() == b"ok": break
            except OSError: pass
            finally: connection.close()
            time.sleep(0.1)
        else: self.fail("application did not start")

        routes = root / "projects"
        routes.mkdir()
        for environment in ("dev", "prod"):
            (routes / f"example-{environment}.conf").write_text(f"""
location = /dev/project/example {{ absolute_redirect off; return 308 /dev/project/example/$is_args$args; }}
location ^~ /dev/project/example/ {{
    proxy_pass http://127.0.0.1:{project.server_port}/;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_redirect / /dev/project/example/;
    proxy_cookie_path / /dev/project/example/;
}}
""")

        def public_server(target, port):
            source = (DEPLOY / f"nginx/{target}.conf").read_text()
            # Exercise the same restrictions and gateway generation as the installed publisher.
            nginx.validate_environment(source, target)
            config = nginx.with_project_gateway(source, target)
            block = next(block for block in nginx.server_blocks(config) if f"proxy_pass http://127.0.0.1:{nginx.PORTS[target]}" in block)
            block = re.sub(r"^\s*listen .*?;", "", block, flags=re.M)
            block = re.sub(r"^\s*(?:ssl_\w+|http2|access_log|error_log) .*?;", "", block, flags=re.M)
            block = block.replace("server {", f"server {{\n    listen 127.0.0.1:{port};", 1)
            return block.replace(f":{nginx.PORTS[target]};", f":{app_port};")

        gateways = (DEPLOY / "nginx/project-gateway.conf").read_text().replace("/etc/nginx/projects/", str(routes) + "/")
        config = root / "nginx.conf"
        config.write_text(f"""pid {root}/nginx.pid;
error_log {root}/error.log;
events {{ worker_connections 128; }}
http {{
    access_log off;
    client_body_temp_path {root}/client;
    proxy_temp_path {root}/proxy;
    {gateways}
    {public_server('dev', stage_port)}
    {public_server('prod', prod_port)}
}}
""")
        subprocess.run(["nginx", "-t", "-c", str(config), "-p", str(root)], check=True, capture_output=True)
        process = subprocess.Popen(["nginx", "-c", str(config), "-p", str(root), "-g", "daemon off;"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.addCleanup(lambda: process.wait(timeout=10))
        self.addCleanup(process.terminate)
        for _ in range(100):
            if process.poll() is not None: self.fail((root / "error.log").read_text())
            try:
                with socket.create_connection(("127.0.0.1", stage_port), timeout=0.1): break
            except OSError: time.sleep(0.05)
        else: self.fail("public nginx did not start")

        def request(path, *, prod=False, headers=None):
            connection = http.client.HTTPConnection("127.0.0.1", prod_port if prod else stage_port, timeout=10)
            connection.request("GET", path, headers={"Host": "itzephir.com" if prod else "dev.itzephir.com", "Accept-Encoding": "gzip", **(headers or {})})
            response = connection.getresponse()
            body = response.read()
            result_headers = {name.lower(): value for name, value in response.getheaders()}
            status = response.status
            connection.close()
            if result_headers.get("content-encoding") == "gzip": body = gzip.decompress(body)
            return status, result_headers, body

        prefix = "/dev/project/example/"
        for query in ("", "?show_badge=1", "?show_badge=0"):
            status, headers, body = request(prefix + query)
            self.assertEqual(status, 200, body.decode(errors="replace") + "\n" + (root / "application.log").read_text() + "\n" + (root / "error.log").read_text())
            self.assertEqual(body.count(b'id="staging-badge"'), int(query != "?show_badge=0"))
            self.assertEqual(requests[-1][1], "identity")
            if query != "?show_badge=0":
                self.assertEqual(headers["cache-control"], "no-store")
                self.assertNotIn("etag", headers)
                self.assertIn(prefix.encode() + b"?show_badge=0", body)
            else: self.assertEqual(body, html)
            status, _, body = request(prefix + query, prod=True)
            self.assertEqual(status, 200)
            self.assertEqual(body, html)
            self.assertEqual(requests[-1][1], "gzip")

        status, _, body = request(prefix + "?q=a%2Fb&tag=a&tag=b&show_badge=1")
        self.assertEqual(status, 200)
        self.assertEqual(requests[-1][0], "/?q=a%2Fb&tag=a&tag=b&show_badge=1")
        self.assertIn(b"tag=a&amp;tag=b", body)
        for path, expected in (("api", b'{"ok":true}'), ("asset", binary), ("download", html), ("fragment", b"<main>fragment</main>")):
            status, _, body = request(prefix + path)
            self.assertEqual(status, 200)
            self.assertEqual(body, expected, path)
        status, headers, _ = request(prefix + "redirect")
        self.assertEqual(status, 302)
        self.assertEqual(headers["location"], prefix + "next?q=a%2Fb")
        self.assertIn("Path=/dev/project/example/", request(prefix)[1]["set-cookie"])
        self.assertEqual(request("/__staging-project/example/")[0], 404)
        self.assertEqual(request(prefix.rstrip("/"))[0], 308)
        self.assertEqual(request("/dev/project/missing/")[0], 404)
        self.assertEqual(request("/")[2].count(b'id="staging-badge"'), 1)
        self.assertEqual(request(prefix, headers={"HX-Request": "true"})[2], html)
        self.assertEqual(request(prefix, headers={"HX-Request": "true", "HX-History-Restore-Request": "true"})[2].count(b'id="staging-badge"'), 1)
        self.assertEqual(request(prefix, headers={"If-None-Match": '"project-v1"'})[0], 304)
        self.assertEqual(request(prefix + "?show_badge=0", headers={"If-None-Match": '"project-v1"'})[0], 304)
        self.assertEqual(request(prefix + "?show_badge=1", prod=True, headers={"If-None-Match": '"project-v1"'})[0], 304)
        self.assertEqual(request(prefix + "api", headers={"If-None-Match": '"project-v1"'})[0], 304)
        self.assertEqual(request(prefix, headers={"Upgrade": "websocket"})[2], html)
        self.assertEqual(requests[-1][1], "gzip")


if __name__ == "__main__":
    unittest.main()
