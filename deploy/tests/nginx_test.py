import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

DEPLOY = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("apply_nginx", DEPLOY / "apply-nginx.py")
nginx = importlib.util.module_from_spec(spec)
spec.loader.exec_module(nginx)


class NginxPublishingTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.commands = []
        self.fail_command = None
        self.publisher = nginx.Publisher(self.root / "managed", self.root / "site.conf", self.root / "lock", self.run_command)

    def run_command(self, command):
        self.commands.append(command)
        if command == self.fail_command:
            self.fail_command = None
            raise subprocess.CalledProcessError(1, command)

    def bootstrap(self):
        self.publisher.bootstrap(DEPLOY / "nginx")
        self.commands.clear()

    def test_bootstrap_preserves_existing_static_production(self):
        prod = "server { server_name itzephir.com www.itzephir.com; root /srv/prod/current; location / { try_files $uri /index.html; } }"
        dev = (DEPLOY / "nginx/dev.conf").read_text()
        self.publisher.site.write_text(prod + "\n" + dev)
        self.bootstrap()
        self.assertEqual((self.publisher.directory / "prod.conf").read_text(), prod + "\n")
        self.assertEqual(len(nginx.server_blocks((self.publisher.directory / "dev.conf").read_text())), 2)
        self.assertIn(f"include {self.publisher.directory}/prod.conf;", self.publisher.site.read_text())
        self.publisher.bootstrap(DEPLOY / "nginx")
        self.assertEqual(self.commands, [])

    def test_publish_changes_only_its_environment_and_rollback_restores_it(self):
        self.bootstrap()
        dev_path = self.publisher.directory / "dev.conf"
        prod_before = (self.publisher.directory / "prod.conf").read_bytes()
        before = dev_path.read_bytes()
        source = self.root / "release.conf"
        source.write_bytes(before.replace(b"gzip_comp_level 6;", b"gzip_comp_level 5;"))
        self.publisher.publish("dev", "release-1", source)
        self.assertEqual(dev_path.read_bytes(), nginx.with_project_gateway(source.read_text(), "dev").encode())
        self.assertEqual((self.publisher.directory / "prod.conf").read_bytes(), prod_before)
        self.assertEqual(self.commands, [["nginx", "-t"], ["systemctl", "reload", "nginx"]])
        with self.assertRaises(ValueError):
            self.publisher.rollback("dev", "another-release")
        self.assertEqual(dev_path.read_bytes(), nginx.with_project_gateway(source.read_text(), "dev").encode())
        self.publisher.rollback("dev", "release-1")
        self.assertEqual(dev_path.read_bytes(), before)

    def test_unchanged_configuration_skips_reload(self):
        self.bootstrap()
        self.publisher.publish("dev", "release-1", DEPLOY / "nginx/dev.conf")
        self.assertEqual(self.commands, [["nginx", "-t"], ["systemctl", "reload", "nginx"]])
        self.commands.clear()
        self.publisher.publish("dev", "release-2", DEPLOY / "nginx/dev.conf")
        self.assertEqual(self.commands, [])
        self.publisher.rollback("dev", "release-2")
        self.assertEqual(self.commands, [])

    def test_validation_or_reload_failure_restores_config_and_previous_rollback(self):
        for failure in (["nginx", "-t"], ["systemctl", "reload", "nginx"]):
            with self.subTest(failure=failure):
                self.bootstrap()
                dev_path = self.publisher.directory / "dev.conf"
                before = dev_path.read_bytes()
                self.publisher.publish("dev", "older-release", dev_path)
                before = dev_path.read_bytes()
                state_path = self.publisher.directory / ".dev-rollback.json"
                state_before = state_path.read_bytes()
                source = self.root / "bad.conf"
                source.write_bytes(before.replace(b"gzip_comp_level 6;", b"gzip_comp_level 5;"))
                self.fail_command = failure
                with self.assertRaises(subprocess.CalledProcessError):
                    self.publisher.publish("dev", "release-1", source)
                self.assertEqual(dev_path.read_bytes(), before)
                self.assertEqual(state_path.read_bytes(), state_before)
                self.assertEqual(self.commands[-2:], [["nginx", "-t"], ["systemctl", "reload", "nginx"]])

    def test_bootstrap_failure_restores_combined_site(self):
        site = "server { server_name itzephir.com www.itzephir.com; }\nserver { server_name dev.itzephir.com; }"
        self.publisher.site.write_text(site)
        self.fail_command = ["nginx", "-t"]
        with self.assertRaises(subprocess.CalledProcessError):
            self.bootstrap()
        self.assertEqual(self.publisher.site.read_text(), site)
        self.assertFalse((self.publisher.directory / "prod.conf").exists())
        self.assertFalse((self.publisher.directory / "dev.conf").exists())

    def test_production_publish_preserves_project_routes_for_old_release_templates(self):
        self.bootstrap()
        source = DEPLOY / "nginx/prod.conf"
        self.publisher.publish("prod", "release-1", source)
        actual = (self.publisher.directory / "prod.conf").read_text()
        self.assertIn("location ^~ /dev/project/", actual)
        self.assertNotIn("location ^~ /project/", actual)
        self.assertEqual(actual.count("proxy_pass http://127.0.0.1:18100;"), 1)
        self.assertIn("proxy_pass http://127.0.0.1:18080;", actual)
        self.assertEqual(nginx.with_project_gateway(actual, "prod"), actual)
        dev = nginx.with_project_gateway((DEPLOY / "nginx/dev.conf").read_text(), "dev")
        self.assertIn("proxy_pass http://127.0.0.1:18101;", dev)
        self.assertIn("location ^~ /dev/project/", dev)

    def test_routing_preflight_checks_generated_tls_without_publishing_or_reloading(self):
        self.bootstrap()
        before = {path.name: path.read_bytes() for path in self.publisher.directory.iterdir()}
        checked = []
        def runner(command):
            checked.append(command)
            candidate = Path(command[-1])
            config = candidate.read_text()
            self.assertIn("itzephir-projects-dev/fullchain.pem", config)
            self.assertIn("auth_request /_locator_resolve;", config)
            self.assertIn("events {}", config)
            raise subprocess.CalledProcessError(1, command)
        self.publisher.runner = runner
        with self.assertRaises(subprocess.CalledProcessError):
            self.publisher.check_routing("dev")
        self.assertEqual({path.name: path.read_bytes() for path in self.publisher.directory.iterdir()}, before)
        self.assertEqual(len(checked), 1)
        self.assertEqual(checked[0][:3], ["nginx", "-t", "-c"])
        self.assertFalse(Path(checked[0][-1]).exists())

    def test_cross_environment_backends_includes_and_top_level_directives_are_rejected(self):
        dev = (DEPLOY / "nginx/dev.conf").read_text()
        configs = [
            dev.replace("dev.itzephir.com", "itzephir.com"),
            dev.replace("18081", "18080"),
            dev.replace("gzip on;", "include /etc/nginx/prod.conf;"),
            dev + "\ninclude /etc/nginx/prod.conf;",
            dev.replace("server_name dev.itzephir.com;", "server_name dev.itzephir.com other.com;"),
        ]
        for config in configs:
            with self.subTest(config=config), self.assertRaises(ValueError):
                nginx.validate_environment(config, "dev")

    def test_registry_routing_is_opt_in_and_rolls_back_to_the_legacy_gateway(self):
        self.bootstrap()
        source = self.root / "dev.conf"
        source.write_text((DEPLOY / "nginx/dev.conf").read_text())
        before = (self.publisher.directory / "dev.conf").read_bytes()
        (self.root / "enable-project-routing").touch()
        self.publisher.publish("dev", "registry-release", source)
        actual = (self.publisher.directory / "dev.conf").read_text()
        self.assertIn("auth_request /_locator_resolve;", actual)
        self.assertIn("proxy_pass $project_upstream$project_uri;", actual)
        self.assertIn("proxy_ssl_verify on;", actual)
        self.assertIn("18554", actual)
        self.assertIn("itzephir-projects-dev/fullchain.pem", actual)
        self.assertNotIn("itzephir-projects-prod", actual)
        self.assertNotIn("location ^~ /dev/project/", actual)
        self.assertEqual(nginx.with_registry_gateway(actual, "dev"), actual)
        self.assertNotIn("rewrite ^/dev/project/", actual)
        self.assertIn("location ^~ /__staging-project/", actual)
        self.assertEqual(nginx.without_project_gateway(actual).strip(), nginx.without_staging_interceptor(source.read_text()).strip())
        self.publisher.rollback("dev", "registry-release")
        self.assertEqual((self.publisher.directory / "dev.conf").read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
