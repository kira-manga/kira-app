import base64
import contextlib
import copy
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.dont_write_bytecode = True
from source_bootstrap_fixtures import DOCUMENT_FORMAT, KEY_ID, MANIFEST_FORMAT, SignedFixture, checksum, encoded, load_verifier
from source_bootstrap_data import DeliveryProofUnavailable, VerificationError, normalize

verifier = load_verifier()


class BootstrapDeliveryTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.fixture = SignedFixture(self.root)

    def receipt(self, **changes):
        self.fixture.receipt.update(changes)
        (self.root / "receipt.json").write_bytes(encoded(self.fixture.receipt))

    def manifest(self, change):
        value = json.loads((self.root / "manifest.json").read_bytes())
        change(value)
        self.receipt(catalogChecksum=self.fixture.snapshot("manifest", value, MANIFEST_FORMAT))

    def header(self, name, field, value):
        path = self.root / (name + ".headers")
        prefix = field.encode("ascii") + b":"
        lines = [line for line in path.read_bytes().split(b"\r\n") if not line.lower().startswith(prefix.lower())]
        lines.insert(1, prefix + b" " + value.encode("ascii"))
        path.write_bytes(b"\r\n".join(lines))

    def test_actual_ed25519_all_members_v1_v2_and_uri_plan(self):
        verifier.verify_delivery(self.root)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            verifier.write_plan(self.root)
        lines = output.getvalue().splitlines()
        self.assertEqual(12, len(lines))
        self.assertIn("2\t/api/v2/source-config/sources/Mangamello%20Plus/revisions/1", lines)
        self.assertIn("5\t/api/v2/source-config/sources/Team%20X/revisions/1", lines)
        self.assertNotEqual(self.fixture.receipt["documentChecksum"], self.fixture.receipt["catalogChecksum"])

    def test_omitted_and_explicit_defaults_have_equal_data_not_equal_bytes(self):
        before = (self.root / "member-0.json").read_bytes()
        self.fixture.sources = [normalize("source", source) for source in self.fixture.sources]
        self.fixture.publish()
        self.assertNotEqual(before, (self.root / "member-0.json").read_bytes())
        verifier.verify_delivery(self.root)

    def test_replay_verification_does_not_rewrite_origin(self):
        names = ["receipt.json", "manifest.json", "document.input.json", "document.json"]
        original = {name: (self.root / name).read_bytes() for name in names}
        verifier.verify_delivery(self.root)
        verifier.verify_delivery(self.root)
        self.assertEqual(original, {name: (self.root / name).read_bytes() for name in names})

    def test_higher_signed_evolved_and_empty_latest_are_unavailable_not_baseline_drift(self):
        receipt = (self.root / "receipt.json").read_bytes()
        for empty in (False, True):
            with self.subTest(empty=empty):
                self.fixture.publish()
                self.fixture.evolved_manifest(empty=empty)
                for path in self.root.glob("member-*.json"):
                    path.unlink()  # No origin-member fetch/check may be needed to identify this case.
                (self.root / "document.json").unlink()
                with self.assertRaisesRegex(DeliveryProofUnavailable, "delivery proof unavailable; no reimport"):
                    verifier.verify_delivery(self.root)
                self.assertEqual(receipt, (self.root / "receipt.json").read_bytes())

    def test_every_immutable_member_checksum_is_checked(self):
        for index in range(12):
            with self.subTest(index=index):
                path = self.root / ("member-" + str(index) + ".json")
                original = path.read_bytes()
                path.write_bytes(original + b" ")
                with self.assertRaisesRegex(VerificationError, "body checksum mismatch"):
                    verifier.verify_delivery(self.root)
                path.write_bytes(original)

    def test_changed_signed_bytes_fail_even_with_matching_hashes_and_pinned_key_id(self):
        for name, receipt_field in (("manifest", "catalogChecksum"), ("document", "documentChecksum")):
            with self.subTest(name=name):
                self.fixture.publish()
                path = self.root / (name + ".json")
                raw = path.read_bytes() + b" "
                path.write_bytes(raw)
                self.header(name, "X-Config-Checksum", checksum(raw))
                self.header(name, "ETag", '"' + checksum(raw) + '"')
                self.header(name, "Content-Length", str(len(raw)))
                self.receipt(**{receipt_field: checksum(raw)})
                self.assertIn(KEY_ID.encode(), (self.root / (name + ".headers")).read_bytes())
                with self.assertRaisesRegex(VerificationError, "Ed25519 signature verification failed"):
                    verifier.verify_delivery(self.root)

    def test_detached_source_signature_must_come_from_signed_manifest_entry(self):
        value = json.loads((self.root / "manifest.json").read_bytes())
        self.header("member-0", "X-Source-Signature", value["sources"][0]["sourceSignature"])
        self.manifest(lambda manifest: manifest["sources"][0].update(sourceSignature=base64.b64encode(b"\0" * 64).decode()))
        with self.assertRaisesRegex(VerificationError, "Ed25519 signature verification failed"):
            verifier.verify_delivery(self.root)

    def test_recomputed_member_checksum_cannot_bypass_detached_signature(self):
        path = self.root / "member-0.json"
        raw = path.read_bytes() + b" "
        path.write_bytes(raw)
        self.header("member-0", "X-Source-Checksum", checksum(raw))
        self.header("member-0", "ETag", '"' + checksum(raw) + '"')
        self.header("member-0", "Content-Length", str(len(raw)))
        self.manifest(lambda manifest: manifest["sources"][0].update(checksum=checksum(raw)))
        with self.assertRaisesRegex(VerificationError, "Ed25519 signature verification failed"):
            verifier.verify_delivery(self.root)

    def test_freshly_signed_stale_raw_content_cannot_hide_behind_manifest_projection(self):
        original = copy.deepcopy(self.fixture.sources)
        edits = [
            lambda sources: sources[0]["endpoints"]["details"].update(url="{itemUrl}"),
            lambda sources: sources[1].update(displayName="Non-Azora drift"),
            lambda sources: sources[2].update(priority=2),
            lambda sources: sources[2].update(lifecycle="disabled"),
        ]
        for index, edit in enumerate(edits):
            with self.subTest(index=index):
                self.fixture.sources = copy.deepcopy(original)
                edit(self.fixture.sources)
                self.fixture.publish()
                with self.assertRaisesRegex(VerificationError, "immutable source content differs"):
                    verifier.verify_delivery(self.root)

    def test_receipt_fields_are_required_bound_and_not_conflated(self):
        original = copy.deepcopy(self.fixture.receipt)
        edits = [
            {"policyId": "other"}, {"referenceSha256": "0" * 64}, {"payloadSha256": "0" * 64},
            {"documentRevision": 99}, {"catalogRevision": 99}, {"documentRevision": 6, "catalogRevision": 6},
            {"catalogChecksum": "0" * 64}, {"documentChecksum": "0" * 64},
            {"documentChecksum": original["catalogChecksum"]}, {"completedAt": "2026-09-13T00:00:00Z"},
            {"completedAt": "2026-09-12T00:00:00+00:00"}, {"actorId": "not-a-uuid"}, {"unknown": 1},
        ]
        for changes in edits:
            with self.subTest(fields=list(changes)):
                self.fixture.receipt = copy.deepcopy(original)
                self.receipt(**changes)
                with self.assertRaises((VerificationError, ValueError)):
                    verifier.verify_delivery(self.root)
        self.fixture.receipt = copy.deepcopy(original)
        del self.fixture.receipt["actorId"]
        self.receipt()
        with self.assertRaises(VerificationError):
            verifier.verify_delivery(self.root)

    def test_origin_manifest_shape_order_lifecycle_engine_and_identity(self):
        changes = [
            lambda m: m["sources"].pop(), lambda m: m["sources"].reverse(),
            lambda m: m["sources"][0].update(api="unexpected"),
            lambda m: m["sources"][0].update(order=1), lambda m: m["sources"][0].update(engine="legacy"),
            lambda m: m["sources"][0].update(lifecycle="disabled"), lambda m: m["sources"][0].update(sourceRevision=0),
            lambda m: m.update(schemaVersion=2), lambda m: m.update(sourceSchemaVersion=2),
            lambda m: m.update(generatedAt="2026-09-13T00:00:00Z"),
            lambda m: m.update(removedSources=[{"api": "removed"}]),
        ]
        for index, change in enumerate(changes):
            with self.subTest(index=index):
                self.fixture.publish(change)
                with self.assertRaises(VerificationError):
                    verifier.verify_delivery(self.root)

    def test_source_response_identity_and_canonicalization_metadata_are_bound(self):
        for field, value in (("X-Source-Api", "other"), ("X-Source-Revision", "2"),
                             ("X-Source-Checksum", "0" * 64), ("X-Source-Canon-Version", "kcj-2")):
            with self.subTest(field=field):
                self.fixture.publish()
                self.header("member-0", field, value)
                with self.assertRaisesRegex(VerificationError, "identity metadata mismatch"):
                    verifier.verify_delivery(self.root)

    def test_snapshot_metadata_is_strict_and_duplicates_fail_closed(self):
        original = (self.root / "manifest.headers").read_bytes()
        edits = [("X-Config-Revision", "0100"), ("X-Config-Signature-Format", DOCUMENT_FORMAT),
                 ("X-Config-Signature-Algorithm", "RSA"), ("X-Config-Signing-Key-Id", "untrusted"),
                 ("Content-Type", "text/plain"), ("Content-Encoding", "gzip"), ("Content-Length", "1"),
                 ("Content-Type", 'application/json; charset="utf-8'),
                 ("ETag", 'W/"' + self.fixture.catalog_checksum + '"'), ("X-Config-Previous-Revision", "99")]
        for field, value in edits:
            with self.subTest(field=field):
                (self.root / "manifest.headers").write_bytes(original)
                self.header("manifest", field, value)
                with self.assertRaises(VerificationError):
                    verifier.verify_delivery(self.root)
        for duplicate in ("X-Config-Checksum: " + self.fixture.catalog_checksum, "Content-Length: 1"):
            with self.subTest(duplicate=duplicate.split(":")[0]):
                (self.root / "manifest.headers").write_bytes(original.replace(b"\r\n\r\n", b"\r\n" + duplicate.encode() + b"\r\n\r\n"))
                with self.assertRaisesRegex(VerificationError, "duplicate security-relevant HTTP header"):
                    verifier.verify_delivery(self.root)

    def test_pins_reject_duplicate_ids_non_ed25519_and_wrong_actual_public_key(self):
        pins = self.fixture.pins
        for invalid in (pins + "," + pins, KEY_ID + "=" + base64.b64encode(b"not X.509").decode()):
            with self.subTest(kind=invalid[:20]):
                (self.root / "pins.txt").write_text(invalid)
                with self.assertRaises(VerificationError):
                    verifier.verify_delivery(self.root)
        public = bytearray(self.fixture.public.read_bytes())
        public[-1] ^= 1
        (self.root / "pins.txt").write_text(KEY_ID + "=" + base64.b64encode(public).decode())
        with self.assertRaisesRegex(VerificationError, "Ed25519 signature verification failed"):
            verifier.verify_delivery(self.root)
