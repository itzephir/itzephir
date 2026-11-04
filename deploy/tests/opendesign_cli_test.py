"""Exercise remote argument quoting and clean stdio without a live deployment."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("opendesign_cli", Path(__file__).resolve().parents[1] / "opendesign/mac-cli.py")
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)


class OpenDesignCliTests(unittest.TestCase):
    def test_remote_shell_preserves_arguments_and_stdin_without_interpreting_input(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            marker = root / "must-not-exist"
            docker = root / "docker"
            docker.write_text(f"#!{sys.executable}\n" + """import json, os, sys
if sys.argv[1] == 'inspect':
    print('true')
else:
    print(json.dumps({'args': sys.argv[1:], 'stdin': sys.stdin.read()}))
""")
            docker.chmod(0o755)
            args = ["files", "write", "project", "a file.html", "--content", f"$(touch {marker}); 'quoted'\nnext line"]
            command = cli.ssh_command(args, cli.DEFAULTS)
            result = subprocess.run(["/bin/sh", "-c", command[-1]], input='{"jsonrpc":"2.0"}\n',
                                    text=True, capture_output=True, env={**os.environ, "PATH": str(root)}, check=True)
            payload = json.loads(result.stdout)
            self.assertEqual(payload["args"][-len(args):], args)
            self.assertEqual(payload["stdin"], '{"jsonrpc":"2.0"}\n')
            self.assertEqual(result.stderr, "")
            self.assertFalse(marker.exists())
            self.assertIn("ForwardAgent=no", command)
            self.assertIn("StrictHostKeyChecking=yes", command)

    def test_missing_container_fails_cleanly_before_starting_any_cli(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            docker = root / "docker"
            docker.write_text("#!/bin/sh\nexit 1\n")
            docker.chmod(0o755)
            remote = cli.ssh_command(["mcp"], cli.DEFAULTS)[-1]
            result = subprocess.run(["/bin/sh", "-c", remote], text=True, capture_output=True,
                                    env={**os.environ, "PATH": str(root)})
            self.assertEqual(result.returncode, 69)
            self.assertEqual(result.stdout, "")
            self.assertIn("OpenDesign is not running", result.stderr)

    def test_configuration_rejects_ssh_options_as_a_destination(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "remote.json"
            config.write_text(json.dumps({"ssh_target": "-oProxyCommand=bad"}))
            with self.assertRaises(ValueError):
                cli.configuration(config)


if __name__ == "__main__":
    unittest.main()
