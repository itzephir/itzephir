import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("namespace_dns", Path(__file__).resolve().parents[1] / "setup-project-namespace-dns.py")
dns = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dns)
ADDRESS = "192.0.2.10"


class FakeAPI:
    def __init__(self):
        self.saved = {"*.itzephir.com": [{"id": 1, "type": "A", "data": {"value": ADDRESS}}],
                      "*.dev.itzephir.com": [{"id": 2, "type": "A", "data": {"value": ADDRESS}}]}
        self.writes = []

    def request(self, method, suffix="", payload=None, *, fqdn, version="v1", missing_ok=False):
        if method == "GET":
            rows = self.saved.get(fqdn)
            if rows is None:
                assert missing_ok
                return None
            return {"dns_records": copy.deepcopy(rows), "meta": {"total": len(rows)}}
        assert method == "POST" and version == "v2" and fqdn in self.saved
        self.writes.append((method, fqdn, payload))
        self.saved[fqdn].append({"id": 100 + len(self.writes), "type": payload["type"], "fqdn": fqdn,
                                 "data": {"value": payload["value"]}})

    def add_subdomain(self, relative):
        self.writes.append(("subdomain", relative))
        assert relative + ".itzephir.com" not in self.saved
        self.saved[relative + ".itzephir.com"] = []


class NamespaceDNSTest(unittest.TestCase):
    def test_acquisition_is_scoped_to_fqdn_idempotent_and_preserves_other_records(self):
        for target, domain in [("prod", "anyq.itzephir.com"), ("dev", "anyq.dev.itzephir.com")]:
            with self.subTest(target=target):
                api = FakeAPI()
                api.saved[domain] = [{"id": 40, "type": "TXT", "data": {"value": "keep-this-record"}}]
                before = copy.deepcopy(api.saved)
                waited = []
                for _ in range(2):
                    self.assertEqual(dns.provision(api, target, "anyq", wait=lambda *args: waited.append(args)), domain)
                self.assertEqual(waited, [(domain, ADDRESS)] * 2)
                self.assertEqual(len(api.writes), 3)  # One resource and exactly two A records, even after retry.
                for name, rows in before.items():
                    self.assertEqual(api.saved[name][:len(rows)], rows)
                for name in (domain, "*." + domain):
                    self.assertEqual([r["data"]["value"] for r in api.saved[name] if r["type"] == "A"], [ADDRESS])
                self.assertTrue(all("subdomain" not in call[2] for call in api.writes if call[0] == "POST"))

    def test_both_names_are_checked_before_any_write_on_a_cname_aaaa_or_address_conflict(self):
        for kind, value in [("CNAME", "elsewhere.example"), ("AAAA", "2001:db8::1"), ("A", "192.0.2.99")]:
            for name in ("anyq.itzephir.com", "*.anyq.itzephir.com"):
                with self.subTest(kind=kind, name=name):
                    api = FakeAPI()
                    api.saved[name] = [{"id": 50, "type": kind, "data": {"value": value}}]
                    before = copy.deepcopy(api.saved)
                    with self.assertRaisesRegex(ValueError, "Conflicting"):
                        dns.provision(api, "prod", "anyq", wait=lambda *_: None)
                    self.assertEqual(api.saved, before)
                    self.assertEqual(api.writes, [])

    def test_invalid_scope_and_missing_or_ambiguous_base_cannot_create_dns(self):
        for target, slug in [("prod", "../dev"), ("prod", "www"), ("dev", "dev"), ("prod", "a.b"), ("bad", "anyq")]:
            api = FakeAPI()
            with self.assertRaises(ValueError):
                dns.provision(api, target, slug)
            self.assertEqual(api.writes, [])
        for source in [None, [], [{"type": "A", "data": {"value": "invalid"}}],
                       [{"type": "A", "data": {"value": ADDRESS}}, {"type": "A", "data": {"value": "192.0.2.11"}}]]:
            api = FakeAPI()
            api.saved["*.itzephir.com"] = source
            with self.assertRaises(ValueError):
                dns.provision(api, "prod", "anyq")
            self.assertEqual(api.writes, [])

    def test_propagation_failure_can_be_retried_without_duplicate_or_deleted_records(self):
        api = FakeAPI()
        with self.assertRaisesRegex(RuntimeError, "not ready"):
            dns.provision(api, "prod", "anyq", wait=lambda *_: (_ for _ in ()).throw(RuntimeError("not ready")))
        before = copy.deepcopy(api.saved)
        writes = list(api.writes)
        dns.provision(api, "prod", "anyq", wait=lambda *_: None)
        self.assertEqual(api.saved, before)
        self.assertEqual(api.writes, writes)

    def test_partial_api_failure_is_recoverable_on_retry(self):
        api = FakeAPI()
        original = api.request
        def fail(method, *args, **kwargs):
            if method == "POST" and kwargs["fqdn"].startswith("*."):
                raise RuntimeError("provider unavailable")
            return original(method, *args, **kwargs)
        api.request = fail
        with self.assertRaisesRegex(RuntimeError, "unavailable"):
            dns.provision(api, "prod", "anyq", wait=lambda *_: None)
        api.request = original
        dns.provision(api, "prod", "anyq", wait=lambda *_: None)
        self.assertEqual(len([call for call in api.writes if call[0] == "POST"]), 2)

    def test_propagation_checks_exact_and_wildcard_before_using_recursive_dns(self):
        elapsed, recursive = [0], []
        def sleep(seconds): elapsed[0] += seconds
        def query(server, host, kind):
            if kind == "NS": return {"ns1.example.", "ns2.example."}
            if server == "ns2.example." and host.startswith("routing-probe.") and elapsed[0] < 10:
                return set()
            if server == "1.1.1.1": recursive.append((elapsed[0], host))
            return {ADDRESS}
        dns.wait_for_addresses("anyq.itzephir.com", ADDRESS, query=query, sleep=sleep, clock=lambda: elapsed[0])
        self.assertEqual(recursive, [(10, "anyq.itzephir.com"), (10, "routing-probe.anyq.itzephir.com")])
        with self.assertRaisesRegex(RuntimeError, "Timed out"):
            dns.wait_for_addresses("anyq.itzephir.com", ADDRESS, timeout=5,
                                   query=lambda *args: {"ns1.example."} if args[2] == "NS" else set(),
                                   sleep=sleep, clock=lambda: elapsed[0])


if __name__ == "__main__":
    unittest.main()
