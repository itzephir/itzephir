"""Credential preservation and rollback at the Docker/nginx/data boundaries."""
import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / "opendesign"
spec = importlib.util.spec_from_file_location("opendesign_deploy", SOURCE / "deploy.py")
deployment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deployment)


class Runner:
    def __init__(self):
        self.calls = []
        self.volume = False
        self.owner = "opendesign"
        self.fail = None
        self.image = "official-image@sha256:candidate"
        self.curl_statuses = []

    def __call__(self, args, **kwargs):
        self.calls.append(args)
        if self.fail and self.fail(args):
            self.fail = None
            raise subprocess.CalledProcessError(1, args)
        if args[:3] == ["openssl", "passwd", "-6"]:
            return deployment.command(args, **kwargs)
        if args[:3] == ["docker", "volume", "ls"]:
            return deployment.VOLUME if self.volume else ""
        if args[:3] == ["docker", "volume", "inspect"]:
            return self.owner
        if args[:3] == ["docker", "volume", "create"]:
            self.volume = True
        if args[:2] == ["docker", "compose"] and args[-3:] == ["config", "--format", "json"]:
            return json.dumps({"services": {"workspace": {"image": self.image}}})
        if args[:2] == ["docker", "run"] and "tar -czf" in args[-1]:
            mount = next(arg for arg in args if arg.startswith("type=bind,src="))
            Path(mount.split("src=", 1)[1].split(",dst=", 1)[0], "workspace.tar.gz").write_bytes(b"snapshot")
        if args[0] == "curl":
            if "--config" in args:
                config = Path(args[args.index("--config") + 1])
                assert stat.S_IMODE(config.stat().st_mode) == 0o600
                assert config.read_text().startswith('user = "open-design:')
            if self.curl_statuses:
                return self.curl_statuses.pop(0)
            return "200" if "--config" in args else "401"
        return ""


class OpenDesignDeploymentTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for path in ("etc/nginx/sites-available", "etc/nginx/sites-enabled"):
            (self.root / path).mkdir(parents=True)
        self.runner = Runner()
        self.deploy = deployment.Deployment(SOURCE, self.root, self.runner)

    def seed_previous(self):
        self.deploy.activate("a" * 40)
        self.deploy.compose_file.write_bytes(b"previous compose\n")
        self.deploy.site.write_bytes(b"previous nginx\n")
        self.runner.calls.clear()

    def test_retries_preserve_password_data_volume_and_back_up_before_replacing(self):
        self.deploy.activate("a" * 40)
        token = self.deploy.env.read_bytes()
        self.deploy.activate("b" * 40)
        self.assertEqual(self.deploy.env.read_bytes(), token)
        self.assertEqual(stat.S_IMODE(self.deploy.env.stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(self.deploy.password_file.stat().st_mode), 0o640)
        self.assertEqual(json.loads(self.deploy.marker.read_text())["revision"], "b" * 40)
        calls = self.runner.calls
        stop = next(i for i, args in enumerate(calls) if args[-1:] == ["stop"])
        snapshot = next(i for i, args in enumerate(calls) if "tar -czf" in args[-1])
        start = next(i for i, args in enumerate(calls) if i > stop and "--wait" in args)
        self.assertLess(stop, snapshot)
        self.assertLess(snapshot, start)
        self.assertTrue(self.deploy.enabled.is_symlink())
        self.assertFalse(any("--volumes" in args or "-v" in args for args in calls))

    def test_failed_candidate_restores_previous_compose_and_offline_snapshot(self):
        self.seed_previous()
        self.runner.fail = lambda args: args[:2] == ["docker", "compose"] and "--wait" in args
        with self.assertRaises(subprocess.CalledProcessError):
            self.deploy.activate("b" * 40)
        self.assertEqual(self.deploy.compose_file.read_bytes(), b"previous compose\n")
        self.assertEqual(self.deploy.site.read_bytes(), b"previous nginx\n")
        self.assertEqual(json.loads(self.deploy.marker.read_text())["revision"], "a" * 40)
        self.assertTrue(any("tar -xzf" in args[-1] for args in self.runner.calls))

    def test_failed_backup_restarts_previous_service_without_restoring_partial_data(self):
        self.seed_previous()
        self.runner.fail = lambda args: "tar -czf" in args[-1]
        with self.assertRaises(subprocess.CalledProcessError):
            self.deploy.activate("b" * 40)
        self.assertFalse(any("tar -xzf" in args[-1] for args in self.runner.calls))
        self.assertIn("--wait", self.runner.calls[-1])
        self.assertEqual(self.deploy.compose_file.read_bytes(), b"previous compose\n")

    def test_failed_public_probe_rolls_back_nginx_and_new_container(self):
        self.runner.fail = lambda args: args[0] == "curl" and "--config" in args
        with self.assertRaises(subprocess.CalledProcessError):
            self.deploy.activate("a" * 40)
        self.assertFalse(self.deploy.site.exists())
        self.assertFalse(self.deploy.enabled.is_symlink())
        self.assertFalse(self.deploy.compose_file.exists())
        self.assertFalse(self.deploy.marker.exists())
        self.assertTrue(any(args[-1:] == ["down"] for args in self.runner.calls))

    def test_foreign_volume_and_invalid_credentials_are_never_adopted(self):
        self.runner.volume, self.runner.owner = True, "someone-else"
        with self.assertRaisesRegex(ValueError, "foreign Docker volume"):
            self.deploy.activate("a" * 40)
        self.assertFalse(self.deploy.compose_file.exists())
        self.deploy.env.write_text("EXTERNAL_CONFIG=preserve\n")
        with self.assertRaisesRegex(ValueError, "invalid existing"):
            self.deploy.activate("a" * 40)
        self.assertEqual(self.deploy.env.read_text(), "EXTERNAL_CONFIG=preserve\n")

    def test_reload_can_briefly_serve_the_previous_404_before_auth_is_ready(self):
        self.runner.curl_statuses = ["404", "401", "200", "401", "200"]
        self.deploy.activate("a" * 40)
        self.assertTrue(self.deploy.marker.exists())
        self.assertEqual(self.runner.curl_statuses, [])
        self.assertFalse(any(args[-1:] == ["down"] for args in self.runner.calls))

    def test_unprotected_success_is_rejected_immediately_and_rolled_back(self):
        self.runner.curl_statuses = ["200"]
        with self.assertRaisesRegex(RuntimeError, "anonymous GET /: expected HTTP 401, received 200"):
            self.deploy.activate("a" * 40)
        self.assertEqual(sum(args[0] == "curl" for args in self.runner.calls), 1)
        self.assertFalse(self.deploy.enabled.is_symlink())
        self.assertFalse(self.deploy.marker.exists())

    def test_readiness_timeout_reports_the_path_and_http_status_without_credentials(self):
        self.deploy.directory.mkdir(parents=True)
        token = self.deploy.prepare_credentials()
        self.runner.curl_statuses = ["404", "404", "404"]
        with self.assertRaisesRegex(RuntimeError, "not ready after 3 checks.*received 404") as error:
            self.deploy.probe(token, attempts=3, delay=0)
        self.assertNotIn(token, str(error.exception))
        self.assertEqual(sum(args[0] == "curl" for args in self.runner.calls), 3)

    def test_bad_authenticated_credentials_are_not_treated_as_a_reload_delay(self):
        self.deploy.directory.mkdir(parents=True)
        token = self.deploy.prepare_credentials()
        self.runner.curl_statuses = ["401", "401"]
        with self.assertRaisesRegex(RuntimeError, "authenticated GET /: expected HTTP 200, received 401"):
            self.deploy.probe(token, attempts=3, delay=0)
        self.assertEqual(sum(args[0] == "curl" for args in self.runner.calls), 2)


if __name__ == "__main__":
    unittest.main()
