import importlib.util
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest
from urllib.parse import urlparse

spec = importlib.util.spec_from_file_location("locator_setup", Path(__file__).resolve().parents[1] / "setup-locator.py")
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class ConfigurationTests(unittest.TestCase):
    def test_secrets_survive_retry_and_environments_are_separate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dev, pending, active = setup.prepare_config("dev", root)
            self.assertFalse(active.exists(), "Incomplete setup must not enable the app")
            self.assertEqual(stat.S_IMODE(pending.stat().st_mode), 0o600)
            self.assertEqual(setup.prepare_config("dev", root)[0], dev)
            pending.replace(active)
            self.assertEqual(setup.prepare_config("dev", root)[0], dev)
            prod, _, _ = setup.prepare_config("prod", root)
            for key in ("LOCATOR_DATABASE_PASSWORD", "LOCATOR_ADMIN_PASSWORD", "LOCATOR_ADMIN_TOKEN", "LOCATOR_JWT_SECRET"):
                self.assertNotEqual(dev[key], prod[key])
                self.assertGreaterEqual(len(dev[key]), 32)

    def test_existing_external_configuration_and_invalid_target_are_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "locator-dev.env"
            content = "LOCATOR_DATABASE_URL=jdbc:postgresql://other/db\n"
            config.write_text(content)
            with self.assertRaises(ValueError):
                setup.prepare_config("dev", root)
            with self.assertRaises(ValueError):
                setup.prepare_config("../prod", root)
            self.assertEqual(config.read_text(), content)

    def test_existing_database_ownership_is_never_taken_over(self):
        with tempfile.TemporaryDirectory() as directory:
            values, _, _ = setup.prepare_config("dev", Path(directory))
            statements = []

            def sql(statement, **_):
                statements.append(statement)
                return "someone_else\n"

            with self.assertRaises(ValueError):
                setup.provision_database(values, sql)
            self.assertEqual(len(statements), 1)


@unittest.skipUnless(os.environ.get("LOCATOR_TEST_DATABASE_URL"), "Requires an isolated PostgreSQL instance")
class PostgreSQLSetupTests(unittest.TestCase):
    def test_real_provisioning_preserves_data_password_and_environment_isolation(self):
        psql = shutil.which("psql")
        self.assertIsNotNone(psql, "PostgreSQL setup checks require psql")
        url = urlparse(os.environ["LOCATOR_TEST_DATABASE_URL"].removeprefix("jdbc:"))
        owner = os.environ.get("LOCATOR_TEST_DATABASE_USER", "locator")
        admin_env = {**os.environ, "PGPASSWORD": os.environ.get("LOCATOR_TEST_DATABASE_PASSWORD", "")}
        address = [psql, "-X", "-qAt", "-w", "-h", url.hostname, "-p", str(url.port or 5432)]

        def execute(statement, user=owner, database="postgres", env=admin_env):
            return subprocess.run([*address, "-U", user, "-d", database, "-v", "ON_ERROR_STOP=1"],
                                  input=statement, text=True, capture_output=True, env=env)

        def sql(statement, sensitive=False):
            result = execute(statement)
            if result.returncode:
                self.fail("Provisioning failed" if sensitive else result.stderr)
            return result.stdout

        # Never test provisioning against a deployed locator DB, even when misconfigured.
        self.assertEqual(url.path, "/locator_test")
        try:
            with tempfile.TemporaryDirectory() as directory:
                values, _, _ = setup.prepare_config("dev", Path(directory))
                other, _, _ = setup.prepare_config("prod", Path(directory))
                setup.provision_database(values, sql)
                setup.provision_database(other, sql)
                name = values["LOCATOR_DATABASE_USER"]
                login_env = {**os.environ, "PGPASSWORD": values["LOCATOR_DATABASE_PASSWORD"]}
                created = execute("CREATE TABLE preserved (value text); INSERT INTO preserved VALUES ('kept');", name, name, login_env)
                self.assertEqual(created.returncode, 0, created.stderr)
                setup.provision_database(values, sql)
                read = execute("SELECT value FROM preserved;", name, name, login_env)
                self.assertEqual(read.stdout.strip(), "kept")
                self.assertEqual(read.returncode, 0)
                flags = sql(f"SELECT rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls FROM pg_roles WHERE rolname='{name}';")
                self.assertEqual(flags.strip(), "f|f|f|f|f")
                cross = execute("SELECT 1;", name, other["LOCATOR_DATABASE_USER"], login_env)
                self.assertNotEqual(cross.returncode, 0, "Dev role must not connect to production")
        finally:
            for target in ("dev", "prod"):
                name = setup.target_name(target)
                sql(f"DROP DATABASE IF EXISTS {name} WITH (FORCE);")
                sql(f"DROP ROLE IF EXISTS {name};")


if __name__ == "__main__":
    unittest.main()
