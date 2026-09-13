import copy
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.dont_write_bytecode = True
from source_bootstrap_fixtures import ApplyFixture, encoded, load_verifier
from source_bootstrap_mock_tool import BOOTSTRAP, MARKER, TOKEN
from source_bootstrap_data import VerificationError


class BootstrapApplyTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.fixture = ApplyFixture(self.root)

    def no_secrets(self, result):
        for hidden in (MARKER, TOKEN, "synthetic-admin-password", "synthetic-store-password",
                       str(self.root), self.fixture.signed.private.with_suffix(".b64").read_text()):
            self.assertNotIn(hidden, result.stdout)
        self.assertNotIn("Traceback", result.stdout)

    def refused(self, result):
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertNotIn("Immutable bootstrap origin verified", result.stdout)
        self.assertNotIn("Apply completed", result.stdout)
        self.no_secrets(result)

    def requests(self):
        return [event["path"] for event in self.fixture.events() if event["tool"] == "curl"]

    def test_explicit_apply_confirmation_is_still_required(self):
        result = self.fixture.run(confirm=False)
        self.refused(result)
        self.assertEqual(64, result.returncode)
        self.assertEqual([], self.fixture.events())

    def test_stale_full_field_lifecycle_and_order_drift_fail_before_external_action(self):
        original = json.loads(self.fixture.original.read_bytes())
        edits = [
            lambda d: d["sources"][0]["endpoints"]["details"].update(url="{itemUrl}"),
            lambda d: d["sources"][1].update(displayName="non-Azora drift"),
            lambda d: d["sources"][2].update(lifecycle="disabled"),
            lambda d: d["sources"].reverse(),
        ]
        for index, edit in enumerate(edits):
            with self.subTest(index=index):
                value = copy.deepcopy(original)
                edit(value)
                self.fixture.original.write_bytes(encoded(value))
                self.fixture.scenario()
                self.refused(self.fixture.run())
                self.assertEqual([], self.fixture.events())
                self.assertFalse((self.root / "posted-input.json").exists())

    def test_original_path_replacement_cannot_change_exact_frozen_posted_bytes(self):
        original = self.fixture.original.read_bytes()
        (self.root / "replacement.json").write_bytes(b"changed after first external mutation")
        (self.root / "pins-file.txt").write_text(self.fixture.signed.pins)
        with self.fixture.config.open("a") as stream:
            stream.write("KIRA_SOURCE_CONFIG_PINNED_KEYS_FILE=" + str(self.root / "pins-file.txt") + "\n")
        self.fixture.scenario(replace_original=True)
        result = self.fixture.run()
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual(original, (self.root / "posted-input.json").read_bytes())
        self.assertNotEqual(original, self.fixture.original.read_bytes())
        self.assertEqual(self.fixture.signed.pins, (self.root / "applied-pins.txt").read_text())
        paths = self.requests()
        self.assertEqual(1, paths.count(BOOTSTRAP))
        self.assertEqual(12, sum("/sources/" in path for path in paths))
        self.assertEqual(16, len(paths))  # Login, bootstrap, manifest, twelve members, v1.
        self.assertIn("Immutable bootstrap origin verified", result.stdout)
        self.assertNotIn("/api/v1/admin/sources/import-bundled", paths)
        self.no_secrets(result)
        self.assertEqual([], list((self.root / "tmp").iterdir()))

    def test_config_only_does_not_query_or_gate_an_empty_current_catalog(self):
        self.fixture.signed.evolved_manifest(empty=True)
        result = self.fixture.run(publish=False)
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual([], self.requests())
        self.assertIn("Apply completed", result.stdout)
        self.no_secrets(result)

    def test_exact_receipt_replay_verifies_without_a_second_bootstrap_attempt_per_run(self):
        receipt = (self.fixture.signed.root / "receipt.json").read_bytes()
        for repeat in range(2):
            with self.subTest(repeat=repeat):
                self.fixture.scenario()
                result = self.fixture.run()
                self.assertEqual(0, result.returncode, result.stdout)
                self.assertEqual(1, self.requests().count(BOOTSTRAP))
                self.assertEqual(receipt, (self.fixture.signed.root / "receipt.json").read_bytes())
                self.assertNotIn("new publication", result.stdout)
                self.no_secrets(result)

    def test_evolved_thirteen_or_empty_latest_refuses_proof_without_reimport_or_roster_gate(self):
        receipt = (self.fixture.signed.root / "receipt.json").read_bytes()
        for empty in (False, True):
            with self.subTest(empty=empty):
                self.fixture.signed.publish()
                self.fixture.signed.evolved_manifest(empty=empty)
                self.fixture.scenario()
                result = self.fixture.run()
                self.refused(result)
                self.assertEqual(3, result.returncode)
                self.assertIn("delivery proof unavailable; no reimport performed", result.stdout)
                self.assertEqual(["/api/v1/auth/login", BOOTSTRAP, "/api/v2/source-config/manifest"], self.requests())
                self.assertEqual(receipt, (self.fixture.signed.root / "receipt.json").read_bytes())

    def test_receipt_payload_mismatch_prevents_member_delivery_verification(self):
        self.fixture.signed.receipt["payloadSha256"] = "0" * 64
        (self.fixture.signed.root / "receipt.json").write_bytes(encoded(self.fixture.signed.receipt))
        result = self.fixture.run()
        self.refused(result)
        self.assertIn("does not bind the frozen input bytes", result.stdout)
        self.assertEqual(1, self.requests().count(BOOTSTRAP))
        self.assertFalse(any("/sources/" in path for path in self.requests()))

    def test_final_member_tampering_cannot_claim_verified_success(self):
        path = self.fixture.signed.root / "member-11.json"
        path.write_bytes(path.read_bytes() + b" ")
        result = self.fixture.run()
        self.refused(result)
        self.assertIn("body checksum mismatch", result.stdout)
        self.assertEqual(12, sum("/sources/" in path for path in self.requests()))
        self.assertEqual(1, self.requests().count(BOOTSTRAP))

    def test_http_errors_and_redirects_are_never_followed_or_retried(self):
        for name, status in (("login-response", 302), ("receipt", 307), ("manifest", 301), ("member-0", 500)):
            with self.subTest(name=name, status=status):
                self.fixture.scenario(response_name=name, status=status)
                result = self.fixture.run()
                self.refused(result)
                self.assertIn("unexpected HTTP response status", result.stdout)
                self.assertLessEqual(self.requests().count(BOOTSTRAP), 1)
                self.assertEqual(len(self.requests()), len(set(self.requests())))

    def test_bearer_config_injection_and_invalid_tokens_stop_before_bootstrap(self):
        for token in ('bad"token', "bad\r\nurl = https://other.test", "bad\\token", "two words", "", "a=b"):
            with self.subTest(token=repr(token)):
                self.fixture.scenario(token=token)
                result = self.fixture.run()
                self.refused(result)
                self.assertIn("invalid bearer token", result.stdout)
                self.assertEqual(["/api/v1/auth/login"], self.requests())

    def test_provider_failure_output_is_suppressed(self):
        for tool in ("gh", "install-github-secret.sh", "curl"):
            with self.subTest(tool=tool):
                self.fixture.scenario(fail_tool=tool)
                self.refused(self.fixture.run())
                self.assertNotIn(BOOTSTRAP, self.requests())

    def test_curl_reader_caps_bodies_and_headers_before_writing_past_limit(self):
        wire = self.root / "wire"
        wire.mkdir()
        (wire / "login.json").write_text("{}")
        (wire / "document.input.json").write_bytes(self.fixture.original.read_bytes())
        (wire / "document.input.json").chmod(0o400)
        (wire / "curl.conf").write_text('header = "Authorization: Bearer ' + TOKEN + '"\n')
        (wire / "curl.conf").chmod(0o600)
        cases = [("login", "login-response", "/api/v1/auth/login", 64 * 1024),
                 ("bootstrap", "receipt", BOOTSTRAP, 16 * 1024),
                 ("public", "manifest", "/api/v2/source-config/manifest", 5 * 1024 * 1024)]
        for kind, name, path, limit in cases:
            for overflow in ("body", "headers"):
                with self.subTest(name=name, overflow=overflow):
                    self.fixture.scenario(response_name=name, overflow=overflow)
                    output = name + "-" + overflow
                    command = [sys.executable, "-B", str(self.fixture.release / "verify-source-bootstrap.py"),
                               "request", kind, str(wire), "https://backend.test" + path, output]
                    result = subprocess.run(command, env=self.fixture.environment, stdout=subprocess.PIPE,
                                            stderr=subprocess.STDOUT, text=True, timeout=15, check=False)
                    self.refused(result)
                    self.assertIn("response exceeded its byte limit", result.stdout)
                    self.assertLessEqual((wire / (output + ".json")).stat().st_size, limit)
                    self.assertLessEqual((wire / (output + ".headers")).stat().st_size, 64 * 1024)
                    self.assertEqual(1, len(self.requests()))

    def test_expired_reader_deadline_kills_settles_and_closes_child(self):
        verifier = load_verifier()
        wire = self.root / "deadline"
        wire.mkdir()
        processes = []
        original_popen = subprocess.Popen

        def start(*arguments, **keywords):
            child = original_popen(*arguments, **keywords)
            processes.append(child)
            return child

        with patch.dict(os.environ, self.fixture.environment, clear=True), patch.object(verifier, "REQUEST_TIMEOUT", 0), \
                patch.object(verifier.subprocess, "Popen", side_effect=start):
            with self.assertRaisesRegex(VerificationError, "request deadline exceeded"):
                verifier.request("public", wire, "https://backend.test/api/v2/source-config/manifest", "manifest")
        self.assertEqual(1, len(processes))
        self.assertIsNotNone(processes[0].returncode)
        self.assertTrue(processes[0].stdout.closed)
