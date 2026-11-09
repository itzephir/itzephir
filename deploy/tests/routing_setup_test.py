"""Run the real root bootstrap in an ephemeral container, with external services faked."""
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest

DEPLOY = Path(__file__).resolve().parents[1]

# Real files, permissions, shell execution and OpenSSL; no live ACME, Docker daemon or systemd.
TOOLS = r"""#!/usr/bin/env python3
import json
import os
from pathlib import Path
import subprocess
import sys

path = Path(os.environ["ROUTING_TEST_STATE"])
state = json.loads(path.read_text())
name, args = Path(sys.argv[0]).name, sys.argv[1:]
state["calls"].append([name, *args])
def save(): path.write_text(json.dumps(state))
def stop(code=0): save(); sys.exit(code)
def option(key): return args[args.index(key) + 1]
if name == "docker":
    if args[0] in ("info", "pull"): stop()
    if args[:2] == ["network", "inspect"]:
        label = state["networks"].get(args[-1])
        if label is None: stop(1)
        print(label if "--format" in args else "[]")
        stop()
    if args[:2] == ["network", "create"]:
        state["networks"][args[-1]] = option("--label").split("=", 1)[1]
        stop()
    if args[0] == "inspect":
        container = state["containers"].get(args[-1])
        if not container: stop(1)
        print(container["target"] if "--format" in args else "[]")
        stop()
    if args[0] == "run":
        if os.getenv("FAIL_DNS_CONTAINER"): stop(1)
        state["containers"][option("--name")] = {"target": option("--label").split("=", 1)[1], "running": True}
        stop()
    if args[0] == "rm":
        state["containers"].pop(args[-1], None)
        stop()
    if args[0] in ("stop", "start"):
        state["containers"][args[-1]]["running"] = args[0] == "start"
        stop()
    if args[0] == "rename":
        if os.getenv("FAIL_DNS_RENAME") and args[1].endswith("-next"): stop(1)
        state["containers"][args[2]] = state["containers"].pop(args[1])
        stop()
elif name == "certbot":
    if args[0] == "certonly":
        if os.getenv("FAIL_CERTIFICATE"): stop(1)
        assert "--manual" in args and "--non-interactive" in args
        assert option("--manual-auth-hook").endswith("timeweb-dns-hook auth")
        assert option("--manual-cleanup-hook").endswith("timeweb-dns-hook cleanup")
        assert "--account" in args or "--email" in args
        directory = Path("/etc/letsencrypt/live") / option("--cert-name")
        directory.mkdir(parents=True, exist_ok=True)
        if not (directory / "fullchain.pem").exists():
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
                "-subj", "/CN=" + option("--domain"), "-addext", "subjectAltName=DNS:" + option("--domain"),
                "-keyout", str(directory / "privkey.pem"), "-out", str(directory / "fullchain.pem")],
                check=True, capture_output=True)
        stop()
    if args[0] == "renew":
        assert "--dry-run" in args and "--cert-name" in args
        assert "--no-random-sleep-on-renew" in args
        stop(1 if os.getenv("FAIL_RENEWAL") else 0)
elif name == "nginx":
    if args == ["-V"]: print("--with-http_auth_request_module")
    if "-c" in args and os.getenv("FAIL_NGINX_PREFLIGHT"): stop(1)
    stop(1 if args == ["-t"] and os.getenv("FAIL_NGINX") else 0)
elif name == "dig":
    print("ns1.example.invalid." if args[-1] == "NS" else
          "192.0.2.10" if args[-2].endswith("itzephir.com") else "127.0.0.2")
    stop()
elif name in ("systemctl", "sleep"): stop()
save()
raise AssertionError((name, args))
"""

# Only the isolated copy imports this transport; production code keeps the fixed HTTPS API.
DNS_API = r'''
class Timeweb:
    def __init__(self, token): pass

    def request(self, method, suffix="", payload=None, *, fqdn=ZONE, version="v1", missing_ok=False):
        state_path = Path(os.environ["ROUTING_TEST_STATE"])
        state = json.loads(state_path.read_text())
        if os.getenv("FAIL_NAMESPACE_DNS"): raise RuntimeError("DNS provider unavailable")
        state["calls"].append(["timeweb", method, fqdn])
        records = state.setdefault("dns", {
            "*.itzephir.com": [{"type": "A", "data": {"value": "192.0.2.10"}}],
            "*.dev.itzephir.com": [{"type": "A", "data": {"value": "192.0.2.10"}}],
        })
        if method == "GET":
            rows = records.get(fqdn)
            state_path.write_text(json.dumps(state))
            return None if rows is None else {"dns_records": rows, "meta": {"total": len(rows)}}
        assert method == "POST" and version == "v2"
        records[fqdn].append({"type": payload["type"], "data": {"value": payload["value"]}})
        state_path.write_text(json.dumps(state))
        return {}

    def add_subdomain(self, relative):
        state_path = Path(os.environ["ROUTING_TEST_STATE"])
        state = json.loads(state_path.read_text())
        state["calls"].append(["timeweb", "subdomain", relative])
        state["dns"][relative + "." + ZONE] = []
        state_path.write_text(json.dumps(state))
'''


@unittest.skipUnless(os.getenv("PROJECT_ROUTING_REQUIRE_PROVISION_TESTS") == "true",
                     "Requires the dedicated disposable root container")
class RoutingSetupTest(unittest.TestCase):
    def setUp(self):
        self.assertEqual(os.geteuid(), 0)
        self.assertTrue(Path("/.dockerenv").exists(), "Never run root provisioning tests on a real host")
        self.paths = [Path("/etc/itzephir"), Path("/etc/letsencrypt"), Path("/usr/local/libexec/itzephir"), Path("/etc/nginx/itzephir")]
        for path in self.paths:
            self.assertFalse(path.exists(), f"Refusing to overwrite pre-existing {path}")
            self.addCleanup(shutil.rmtree, path, True)
        self.addCleanup(lambda: Path("/usr/local/sbin/itzephir-apply-nginx").unlink(missing_ok=True))
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.package = self.root / "deploy"
        self.package.mkdir()
        for name in ("prepare-project-routing.sh", "setup-project-certificates.sh", "setup-project-routing.sh",
                     "setup-project-namespace-dns.py", "timeweb-dns-hook.py", "renew-project-certificate.sh",
                     "apply-nginx.py", "project-dns-container.sh"):
            shutil.copyfile(DEPLOY / name, self.package / name)
        shutil.copytree(DEPLOY / "coredns", self.package / "coredns")
        with (self.package / "timeweb-dns-hook.py").open("a") as transport:
            transport.write(DNS_API)
        tools = self.root / "bin"
        tools.mkdir()
        for name in ("docker", "certbot", "nginx", "dig", "systemctl", "sleep"):
            path = tools / name
            path.write_text(TOOLS)
            path.chmod(0o755)
        self.state = self.root / "state.json"
        self.state.write_text(json.dumps({"networks": {}, "containers": {}, "calls": []}))
        self.token = self.root / "token"
        self.token.write_text("private-test-token-" + "x" * 32)
        self.token.chmod(0o600)
        account = Path("/etc/letsencrypt/renewal/itzephir.com.conf")
        account.parent.mkdir(parents=True)
        self.root_renewal = "authenticator = webroot\naccount = " + "a" * 32 + "\n"
        account.write_text(self.root_renewal)
        managed_nginx = Path("/etc/nginx/itzephir")
        managed_nginx.mkdir(parents=True)
        for target in ("dev", "prod"):
            (managed_nginx / f"{target}.conf").write_bytes((DEPLOY / "nginx" / f"{target}.conf").read_bytes())
        Path("/run/lock").mkdir(parents=True, exist_ok=True)
        self.env = {**os.environ, "PATH": str(tools) + ":" + os.environ["PATH"], "ROUTING_TEST_STATE": str(self.state)}
        self.env.pop("ACME_EMAIL", None)

    def run_setup(self, target="dev", namespace="", **extra):
        return subprocess.run(["bash", str(self.package / "prepare-project-routing.sh"), target, str(self.token), namespace],
                              env={**self.env, **extra}, capture_output=True, text=True)

    def saved(self): return json.loads(self.state.read_text())

    def enable_routing(self, target):
        import importlib.util
        spec = importlib.util.spec_from_file_location("publisher", DEPLOY / "apply-nginx.py")
        publisher = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(publisher)
        path = Path(f"/etc/nginx/itzephir/{target}.conf")
        path.write_text(publisher.with_registry_gateway(path.read_text(), target))

    def test_real_scripts_preserve_root_tls_and_separate_stands_on_retry(self):
        for target in ("dev", "prod", "dev"):
            result = self.run_setup(target)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        state = self.saved()
        self.assertEqual(state["networks"], {"itzephir-projects-dev": "dev", "itzephir-projects-prod": "prod"})
        self.assertEqual(set(state["containers"]), {"itzephir-project-dns-dev", "itzephir-project-dns-prod"})
        self.assertTrue(all(container["running"] for container in state["containers"].values()))
        credential = Path("/etc/itzephir/timeweb-dns.token")
        self.assertEqual(stat.S_IMODE(credential.stat().st_mode), 0o600)
        self.assertEqual(credential.read_text(), self.token.read_text())
        self.assertEqual(Path("/etc/letsencrypt/renewal/itzephir.com.conf").read_text(), self.root_renewal)
        self.assertEqual(subprocess.check_output(["/usr/local/sbin/itzephir-apply-nginx", "--version"], text=True).strip(), "2")
        self.assertTrue(any(call[0] == "dig" and "+tcp" in call for call in state["calls"]))
        docker_runs = [call for call in state["calls"] if call[:2] == ["docker", "run"]]
        self.assertTrue(all("--read-only" in call and "--cap-drop" in call for call in docker_runs))
        self.assertTrue(all(call[call.index("--cap-add") + 1] == "NET_BIND_SERVICE" for call in docker_runs))
        self.assertTrue(all(any(value.startswith("127.0.0.1:1855") for value in call) for call in docker_runs))
        self.assertIn(["systemctl", "enable", "--now", "certbot.timer"], state["calls"])
        self.assertTrue(any(call[:3] == ["nginx", "-t", "-c"] for call in state["calls"]))
        self.assertNotIn("auth_request /_locator_resolve;", Path("/etc/nginx/itzephir/dev.conf").read_text())
        self.assertFalse(any(call[:2] == ["systemctl", "restart"] for call in state["calls"]))
        self.assertNotIn(self.token.read_text(), result.stdout + result.stderr)

    def test_certificate_or_renewal_failure_prevents_docker_and_publisher_changes(self):
        for failure in ("FAIL_CERTIFICATE", "FAIL_RENEWAL"):
            with self.subTest(failure=failure):
                result = self.run_setup(**{failure: "true"})
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(self.saved()["containers"])
                self.assertFalse(Path("/usr/local/sbin/itzephir-apply-nginx").exists())

    def test_namespace_acquisition_creates_dns_tls_and_routes_without_changing_project_containers(self):
        for target in ("dev", "prod"):
            result = self.run_setup(target)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.enable_routing(target)
        before = self.saved()
        for target in ("dev", "prod", "dev"):
            other = "prod" if target == "dev" else "dev"
            other_config = Path(f"/etc/nginx/itzephir/{other}.conf").read_bytes()
            result = self.run_setup(target, "anyq")
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            lineage = f"itzephir-project-{target}-anyq"
            domain = "anyq." + ("dev." if target == "dev" else "") + "itzephir.com"
            certificate = f"/etc/letsencrypt/live/{lineage}/fullchain.pem"
            subprocess.run(["openssl", "x509", "-in", certificate, "-noout", "-checkhost", f"pr-42.{domain}"], check=True, capture_output=True)
            self.assertEqual(json.loads(Path(f"/etc/itzephir/project-namespaces/{target}.json").read_text()), ["anyq"])
            active = Path(f"/etc/nginx/itzephir/{target}.conf").read_text()
            self.assertIn(certificate, active)
            self.assertIn("set $locator_slug anyq;", active)
            self.assertEqual(Path(f"/etc/nginx/itzephir/{other}.conf").read_bytes(), other_config)
            for name in (domain, "*." + domain):
                self.assertEqual(self.saved()["dns"][name], [{"type": "A", "data": {"value": "192.0.2.10"}}])
            self.assertTrue(any(call[:2] == ["certbot", "renew"] and lineage in call for call in self.saved()["calls"]))
        after = self.saved()
        self.assertEqual(before["containers"], after["containers"])
        self.assertEqual(before["networks"], after["networks"])
        later_calls = after["calls"][len(before["calls"]):]
        self.assertFalse(any(call[0] == "docker" and call[1] != "info" for call in later_calls))
        for failure in ("FAIL_NAMESPACE_DNS", "FAIL_CERTIFICATE", "FAIL_RENEWAL", "FAIL_NGINX_PREFLIGHT"):
            before_config = Path("/etc/nginx/itzephir/dev.conf").read_bytes()
            result = self.run_setup("dev", "failed", **{failure: "true"})
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(json.loads(Path("/etc/itzephir/project-namespaces/dev.json").read_text()), ["anyq"])
            self.assertEqual(Path("/etc/nginx/itzephir/dev.conf").read_bytes(), before_config)
        self.assertNotIn(self.token.read_text(), result.stdout + result.stderr)

    def test_namespace_requires_base_preparation_and_rejects_unsafe_names(self):
        result = self.run_setup("dev", "anyq")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any(call[0] == "certbot" for call in self.saved()["calls"]))
        result = self.run_setup()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        result = self.run_setup("dev", "anyq")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Enable base project routing", result.stderr)
        self.assertFalse(any(call[0] == "timeweb" for call in self.saved()["calls"]))
        self.enable_routing("dev")
        before = len(self.saved()["calls"])
        for slug in ("dev", "opendesign", "www", "../prod", "anyq.dev", "a_b", "a" * 64):
            result = self.run_setup("dev", slug)
            self.assertNotEqual(result.returncode, 0, slug)
        self.assertFalse(any(call[0] == "certbot" for call in self.saved()["calls"][before:]))

    def test_failed_dns_container_restores_previous_container_and_corefile(self):
        result = self.run_setup()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        corefile = Path("/etc/itzephir/project-dns-dev/Corefile")
        corefile.write_text("previous DNS configuration\n")
        result = self.run_setup(FAIL_DNS_CONTAINER="true")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(corefile.read_text(), "previous DNS configuration\n")
        self.assertEqual(self.saved()["containers"], {"itzephir-project-dns-dev": {"target": "dev", "running": True}})

    def test_failed_dns_promotion_restores_previous_container_and_credentials(self):
        result = self.run_setup()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        corefile = Path("/etc/itzephir/project-dns-dev/Corefile")
        corefile.write_text("previous DNS configuration\n")
        result = self.run_setup(FAIL_DNS_RENAME="true")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(corefile.read_text(), "previous DNS configuration\n")
        self.assertEqual(self.saved()["containers"], {"itzephir-project-dns-dev": {"target": "dev", "running": True}})
        credential = Path("/etc/itzephir/timeweb-dns.token")
        previous = credential.read_text()
        self.token.write_text("rotated-test-token-" + "z" * 32)
        result = self.run_setup(FAIL_RENEWAL="true")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(credential.read_text(), previous)

    def test_unmanaged_network_is_not_adopted(self):
        state = self.saved()
        state["networks"]["itzephir-projects-dev"] = "someone-else"
        self.state.write_text(json.dumps(state))
        result = self.run_setup()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unmanaged project network", result.stderr)
        self.assertFalse(self.saved()["containers"])

    def test_invalid_target_and_bad_nginx_fail_before_issuing_certificates(self):
        for target, extra in [("../prod", {}), ("dev", {"FAIL_NGINX": "true"})]:
            result = self.run_setup(target, **extra)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(any(call[0] == "certbot" for call in self.saved()["calls"]))

    def test_renewal_reload_requires_valid_nginx_and_only_handles_project_lineages(self):
        command = ["bash", str(DEPLOY / "renew-project-certificate.sh")]
        for lineage, extra, expected in [
            ("/etc/letsencrypt/live/itzephir-projects-dev", {}, 0),
            ("/etc/letsencrypt/live/itzephir-project-dev-anyq", {}, 0),
            ("/etc/letsencrypt/live/itzephir-project-prod-anyq", {"FAIL_NGINX": "true"}, 1),
            ("/etc/letsencrypt/live/itzephir-projects-prod", {"FAIL_NGINX": "true"}, 1),
            ("/etc/letsencrypt/live/itzephir.com", {}, 0),
        ]:
            state = self.saved()
            state["calls"] = []
            self.state.write_text(json.dumps(state))
            result = subprocess.run(command, env={**self.env, **extra, "RENEWED_LINEAGE": lineage}, capture_output=True)
            self.assertEqual(result.returncode, expected)
            calls = self.saved()["calls"]
            if lineage.endswith("-dev") or lineage.endswith("-dev-anyq"):
                self.assertEqual(calls, [["nginx", "-t"], ["systemctl", "reload", "nginx"]])
            elif expected:
                self.assertEqual(calls, [["nginx", "-t"]])
            else:
                self.assertEqual(calls, [])


if __name__ == "__main__": unittest.main()
