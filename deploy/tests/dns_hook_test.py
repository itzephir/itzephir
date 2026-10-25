import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from urllib.error import HTTPError

spec = importlib.util.spec_from_file_location("dns_hook", Path(__file__).resolve().parents[1] / "timeweb-dns-hook.py")
hook = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hook)
VALUE = "ACME_validation_" + "x" * 32


class FakeAPI:
    def __init__(self):
        self.saved = [
            {"id": 1, "type": "TXT", "data": {"subdomain": None, "value": "v=spf1 redirect=_spf.mail.ru"}},
            {"id": 2, "type": "TXT", "data": {"subdomain": "_acme-challenge", "value": "other-validation"}},
        ]
        self.calls = []

    def request(self, method, suffix="", payload=None):
        self.calls.append((method, suffix, payload))
        if method == "POST":
            record = {"id": 3, "type": payload["type"], "data": {"subdomain": payload["subdomain"], "value": payload["value"]}}
            self.saved.append(record)
            return {"dns_record": record}
        if method == "DELETE":
            self.saved = [record for record in self.saved if record["id"] != int(suffix[1:])]
            return {}
        raise AssertionError(method)

    def records(self):
        return iter(self.saved)


class DNSHookTest(unittest.TestCase):
    def test_auth_and_cleanup_preserve_mail_and_other_acme_validations(self):
        for domain, subdomain in [("*.itzephir.com", "_acme-challenge"), ("dev.itzephir.com", "_acme-challenge.dev")]:
            with self.subTest(domain=domain):
                api = FakeAPI()
                before = list(api.saved)
                self.assertEqual(hook.challenge(domain, VALUE), subdomain)
                waited = []
                record_id = hook.authenticate(api, subdomain, VALUE, lambda *args: waited.append(args))
                self.assertEqual(waited, [(subdomain, VALUE)])
                self.assertEqual(api.calls[0], ("POST", "", {"type": "TXT", "subdomain": subdomain, "value": VALUE, "ttl": 300}))
                hook.cleanup(api, subdomain, VALUE, str(record_id))
                self.assertEqual(api.saved, before)
                hook.cleanup(api, subdomain, VALUE, str(record_id))  # Already removed: safe retry.
                self.assertEqual(sum(call[0] == "DELETE" for call in api.calls), 1)

    def test_failed_propagation_removes_only_the_new_record(self):
        api = FakeAPI()
        before = list(api.saved)
        def failed(*_):
            raise RuntimeError("DNS timeout")
        with self.assertRaisesRegex(RuntimeError, "DNS timeout"):
            hook.authenticate(api, "_acme-challenge", VALUE, failed)
        self.assertEqual(api.saved, before)

    def test_cleanup_refuses_unrelated_record_ids_or_missing_auth_output(self):
        api = FakeAPI()
        with self.assertRaisesRegex(ValueError, "does not match"):
            hook.cleanup(api, "_acme-challenge", VALUE, "1")
        hook.cleanup(api, "_acme-challenge", VALUE, "")
        hook.cleanup(api, "_acme-challenge", VALUE, "unexpected output")
        self.assertEqual(api.calls, [])

    def test_unrelated_domains_and_invalid_validations_are_rejected(self):
        for domain in ("example.com", "foo.itzephir.com", "itzephir.com.example.org", "ITZEPHIR.com"):
            with self.assertRaises(ValueError):
                hook.challenge(domain, VALUE)
        with self.assertRaises(ValueError):
            hook.challenge("itzephir.com", "bad value")

    def test_token_permissions_and_contents(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "token"
            path.write_text("private-token-" + "x" * 32)
            path.chmod(0o600)
            self.assertEqual(hook.read_token(path), path.read_text())
            path.chmod(0o644)
            with self.assertRaisesRegex(ValueError, "owner-only"):
                hook.read_token(path)
            path.chmod(0o600)
            path.write_text("invalid\nheader injection")
            with self.assertRaises(ValueError):
                hook.read_token(path)

    def test_api_uses_v1_txt_payload_and_redacts_error_bodies(self):
        token = "secret-token-" + "x" * 32
        requests = []
        class Opener:
            def open(self, request, timeout):
                requests.append(request)
                raise HTTPError(request.full_url, 403, "forbidden", {}, io.BytesIO(token.encode()))
        api = hook.Timeweb(token, Opener())
        with self.assertRaisesRegex(RuntimeError, "HTTP 403") as error:
            api.request("POST", payload={"type": "TXT", "subdomain": "_acme-challenge", "value": VALUE})
        self.assertNotIn(token, str(error.exception))
        self.assertEqual(requests[0].full_url, "https://api.timeweb.cloud/api/v1/domains/itzephir.com/dns-records")
        self.assertEqual(requests[0].get_header("Authorization"), "Bearer " + token)
        self.assertEqual(json.loads(requests[0].data)["value"], VALUE)
        self.assertIsNone(hook.NoRedirect().redirect_request(None, None, None, None, None))

    def test_pagination_keeps_cleanup_correct_beyond_first_page(self):
        class API(hook.Timeweb):
            def request(self, method, suffix="", payload=None):
                self.pages.append(suffix)
                return {"dns_records": [{"id": 1 if "offset=0" in suffix else 2}], "meta": {"total": 2}}
        api = API("unused")
        api.pages = []
        self.assertEqual(list(api.records()), [{"id": 1}, {"id": 2}])
        self.assertEqual(api.pages, ["?limit=100&offset=0", "?limit=100&offset=1"])

    def test_slow_authority_does_not_prime_recursive_cache_with_previous_record(self):
        elapsed = [0]
        cached_until = [0]
        recursive_queries = []
        def sleep(seconds): elapsed[0] += seconds
        def query(server, host, kind):
            if kind == "NS": return {"ns1.timeweb.ru.", "ns3.timeweb.org."}
            if server == "1.1.1.1":
                recursive_queries.append(elapsed[0])
                if elapsed[0] < 120 and cached_until[0] <= elapsed[0]:
                    cached_until[0] = elapsed[0] + 600
                if elapsed[0] < cached_until[0]:
                    return {'"v=spf1 include:_spf.timeweb.ru ~all"'}
            if server == "ns3.timeweb.org." and elapsed[0] < 120: return set()
            return {'"' + VALUE + '"'}
        hook.wait_for_dns("_acme-challenge.dev", VALUE, query=query, sleep=sleep, clock=lambda: elapsed[0])
        self.assertEqual(elapsed[0], 120)
        self.assertEqual(recursive_queries, [120])

    def test_existing_recursive_cache_can_expire_after_the_original_ten_minute_limit(self):
        elapsed = [0]
        def sleep(seconds): elapsed[0] += seconds
        def query(server, host, kind):
            if kind == "NS": return {"ns1.timeweb.ru."}
            if elapsed[0] < (900 if server == "1.1.1.1" else 420): return set()
            return {'"' + VALUE + '"'}
        hook.wait_for_dns("_acme-challenge", VALUE, query=query, sleep=sleep, clock=lambda: elapsed[0])
        self.assertEqual(elapsed[0], 900)

    def test_propagation_waits_for_every_authoritative_server_and_recursive_cache(self):
        elapsed = [0]
        calls = []
        def sleep(seconds): elapsed[0] += seconds
        def query(server, host, kind):
            calls.append((server, host, kind))
            if kind == "NS": return {"ns1.timeweb.ru.", "ns2.timeweb.ru."}
            if server == "ns2.timeweb.ru." and elapsed[0] < 10: return set()
            return {'"' + VALUE + '"'}
        hook.wait_for_dns("_acme-challenge.dev", VALUE, query=query, sleep=sleep, clock=lambda: elapsed[0])
        self.assertEqual(elapsed[0], 10)
        self.assertIn(("1.1.1.1", "_acme-challenge.dev.itzephir.com", "TXT"), calls)
        with self.assertRaisesRegex(RuntimeError, "Timed out"):
            hook.wait_for_dns("_acme-challenge", VALUE, timeout=10,
                              query=lambda *args: {"ns1.timeweb.ru."} if args[2] == "NS" else set(),
                              sleep=sleep, clock=lambda: elapsed[0])


if __name__ == "__main__": unittest.main()
