import importlib.util
import json
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
        self.publisher = nginx.Publisher(self.root / "managed", self.root / "site.conf", self.root / "lock", self.run_command, self.root / "namespaces")

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
        self.assertEqual(dev_path.read_bytes(), nginx.with_retired_paths(source.read_text(), "dev").encode())
        self.assertEqual((self.publisher.directory / "prod.conf").read_bytes(), prod_before)
        self.assertEqual(self.commands, [["nginx", "-t"], ["systemctl", "reload", "nginx"]])
        with self.assertRaises(ValueError):
            self.publisher.rollback("dev", "another-release")
        self.assertEqual(dev_path.read_bytes(), nginx.with_retired_paths(source.read_text(), "dev").encode())
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

    def test_publication_removes_legacy_routes_even_without_subdomain_activation(self):
        self.bootstrap()
        source = DEPLOY / "nginx/prod.conf"
        self.publisher.publish("prod", "release-1", source)
        actual = (self.publisher.directory / "prod.conf").read_text()
        self.assertIn('location ~ "^/dev/projects?(?:/|$)" { return 404; }', actual)
        self.assertNotIn("location ^~ /project/", actual)
        self.assertNotIn("18100", actual)
        self.assertIn("proxy_pass http://127.0.0.1:18080;", actual)
        self.assertEqual(nginx.with_retired_paths(actual, "prod"), actual)
        dev = nginx.with_retired_paths((DEPLOY / "nginx/dev.conf").read_text(), "dev")
        self.assertNotIn("18101", dev)
        self.assertNotIn("rewrite ^/dev/project/", dev)

    def test_old_published_path_proxy_and_stage_interceptor_are_removed_on_upgrade(self):
        for target, port in (("prod", 18100), ("dev", 18101)):
            config = (DEPLOY / f"nginx/{target}.conf").read_text()
            old = f"""    # Legacy stage interceptor start
    rewrite ^/dev/project/(.*)$ /__staging-project/$1 last;
    # Legacy stage interceptor end

    location ^~ /__staging-project/ {{
        internal;
        proxy_pass http://127.0.0.1:{nginx.PORTS[target]};
    }}

    # Managed project gateway; application deployments own their routes.
    location ^~ /dev/project/ {{
        proxy_pass http://127.0.0.1:{port};
    }}

"""
            config = config.replace("    location / {\n", old + "    location / {\n")
            for render in (nginx.with_registry_gateway, nginx.with_retired_paths):
                actual = render(config, target)
                self.assertNotIn(str(port), actual)
                self.assertNotIn("rewrite ^/dev/project/", actual)
                self.assertNotIn("location ^~ /__staging-project/", actual)
                self.assertIn('location ~ "^/dev/projects?(?:/|$)" { return 404; }', actual)
                self.assertEqual(render(actual, target), actual)

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

    def test_registry_routing_is_opt_in_and_rollback_restores_previous_configuration(self):
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
        self.assertNotIn("location ^~ /__staging-project/", actual)
        self.assertEqual(nginx.without_project_gateway(actual).strip(), nginx.without_staging_interceptor(source.read_text()).strip())
        self.publisher.rollback("dev", "registry-release")
        self.assertEqual((self.publisher.directory / "dev.conf").read_bytes(), before)

    def test_namespace_preparation_is_additive_isolated_and_requires_successful_preflight(self):
        self.bootstrap()
        before = (self.publisher.directory / "dev.conf").read_bytes()
        self.publisher.prepare_namespace("dev", "anyq")
        self.publisher.prepare_namespace("dev", "other")
        self.publisher.prepare_namespace("dev", "anyq")
        manifest = self.publisher.namespaces / "dev.json"
        self.assertEqual(json.loads(manifest.read_text()), ["anyq", "other"])
        self.assertEqual((self.publisher.directory / "dev.conf").read_bytes(), before)
        self.assertFalse((self.publisher.namespaces / "prod.json").exists())
        self.assertTrue(all(command[:3] == ["nginx", "-t", "-c"] for command in self.commands))
        self.publisher.runner = lambda command: (_ for _ in ()).throw(subprocess.CalledProcessError(1, command))
        with self.assertRaises(subprocess.CalledProcessError):
            self.publisher.prepare_namespace("dev", "failed")
        self.assertEqual(json.loads(manifest.read_text()), ["anyq", "other"])

    def test_namespace_servers_persist_across_releases_and_disable_with_routing(self):
        self.bootstrap()
        self.publisher.prepare_namespace("dev", "anyq")
        source = self.root / "dev.conf"
        source.write_text((DEPLOY / "nginx/dev.conf").read_text())
        (self.root / "enable-project-routing").touch()
        for release in ("first", "second"):
            self.publisher.publish("dev", release, source)
            actual = (self.publisher.directory / "dev.conf").read_text()
            self.assertIn("itzephir-project-dev-anyq/fullchain.pem", actual)
            self.assertIn("set $locator_slug anyq;", actual)
            self.assertNotIn("itzephir-project-prod-anyq", actual)
            self.assertEqual(nginx.with_registry_gateway(actual, "dev", ["anyq"]), actual)
        (self.root / "enable-project-routing").unlink()
        self.publisher.publish("dev", "disabled", source)
        self.assertNotIn("itzephir-project-dev-anyq", (self.publisher.directory / "dev.conf").read_text())
        self.publisher.rollback("dev", "disabled")
        self.assertEqual((self.publisher.directory / "dev.conf").read_text(), actual)

    def test_namespace_input_cannot_inject_nginx_or_escape_its_environment(self):
        for slug in ("dev", "www", "opendesign", "anyq.dev", "../prod", "a;return 200", "a_b", "-a", "a" * 64, 1):
            with self.subTest(slug=slug), self.assertRaises(ValueError):
                nginx.with_registry_gateway((DEPLOY / "nginx/dev.conf").read_text(), "dev", [slug])

    def enable_routing(self, target):
        path = self.publisher.directory / f"{target}.conf"
        path.write_text(nginx.with_registry_gateway(path.read_text(), target))

    def test_namespace_acquisition_activates_once_and_preserves_other_stand_and_release_rollback(self):
        self.bootstrap()
        self.enable_routing("prod")
        dev = (self.publisher.directory / "dev.conf").read_bytes()
        state = self.publisher.directory / ".prod-rollback.json"
        state.write_text('{"release":"existing"}')
        self.publisher.activate_namespace("prod", "anyq")
        self.publisher.activate_namespace("prod", "other")
        self.commands.clear()
        self.publisher.activate_namespace("prod", "anyq")
        actual = (self.publisher.directory / "prod.conf").read_text()
        self.assertIn("itzephir-project-prod-anyq/fullchain.pem", actual)
        self.assertIn("set $locator_slug anyq;", actual)
        self.assertIn("itzephir-project-prod-other/fullchain.pem", actual)
        self.assertEqual(json.loads((self.publisher.namespaces / "prod.json").read_text()), ["anyq", "other"])
        self.assertEqual((self.publisher.directory / "dev.conf").read_bytes(), dev)
        self.assertEqual(state.read_text(), '{"release":"existing"}')
        self.assertFalse(any(command[:2] == ["systemctl", "reload"] for command in self.commands))

    def test_failed_namespace_activation_restores_both_manifest_and_active_routes(self):
        for existing in (False, True):
            with self.subTest(existing=existing):
                self.bootstrap()
                self.enable_routing("prod")
                if existing:
                    self.publisher.activate_namespace("prod", "anyq")
                manifest = self.publisher.namespaces / "prod.json"
                before_manifest = manifest.read_bytes() if manifest.exists() else None
                config = self.publisher.directory / "prod.conf"
                before = config.read_bytes()
                failed = []
                def runner(command):
                    if command == ["systemctl", "reload", "nginx"] and not failed:
                        failed.append(True)
                        raise subprocess.CalledProcessError(1, command)
                original = self.publisher.runner
                self.publisher.runner = runner
                with self.assertRaises(subprocess.CalledProcessError):
                    self.publisher.activate_namespace("prod", "failed")
                self.publisher.runner = original
                self.assertEqual(config.read_bytes(), before)
                self.assertEqual(manifest.read_bytes() if manifest.exists() else None, before_manifest)

    def test_namespace_activation_requires_enabled_base_and_a_valid_tls_preflight(self):
        self.bootstrap()
        with self.assertRaisesRegex(ValueError, "Enable base"):
            self.publisher.activate_namespace("dev", "anyq")
        self.enable_routing("dev")
        before = (self.publisher.directory / "dev.conf").read_bytes()
        self.publisher.runner = lambda command: (_ for _ in ()).throw(subprocess.CalledProcessError(1, command))
        with self.assertRaises(subprocess.CalledProcessError):
            self.publisher.activate_namespace("dev", "anyq")
        self.assertFalse((self.publisher.namespaces / "dev.json").exists())
        self.assertEqual((self.publisher.directory / "dev.conf").read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
