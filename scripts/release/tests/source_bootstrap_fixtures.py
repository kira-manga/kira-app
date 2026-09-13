"""Synthetic local Ed25519 fixtures; NOT backend kcj-1/assembly parity evidence.

Only test-temporary keys are generated. Tests serialize their own JSON responses
and sign the existing wire envelopes; production code never serializes them.
"""

import base64
import copy
import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

sys.dont_write_bytecode = True
RELEASE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(RELEASE))
from source_bootstrap_data import LEGACY_APIS, POLICY_ID, REFERENCE, REFERENCE_SHA256  # noqa: E402
from source_bootstrap_signatures import openssl_binary  # noqa: E402

TIME = "2026-09-12T00:00:00Z"
KEY_ID = "bootstrap-test"
MANIFEST_FORMAT = "kira-source-catalog-manifest-v1"
DOCUMENT_FORMAT = "kira-source-signature-v1"


def encoded(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def load_verifier():
    spec = importlib.util.spec_from_file_location("bootstrap_verifier", RELEASE / "verify-source-bootstrap.py")
    verifier = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(verifier)
    return verifier


def checksum(raw):
    return hashlib.sha256(raw).hexdigest()


def initial_document():
    value = json.loads(REFERENCE.read_bytes())
    value["revision"] = 4  # Input provenance is NOT the reference/server revision.
    value["sources"] += [{"api": api, "language": "(EN)", "baseUrl": "https://legacy.test"} for api in LEGACY_APIS]
    return value


def run_openssl(binary, *arguments):
    subprocess.run([binary, *map(str, arguments)], stdin=subprocess.DEVNULL,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True, timeout=15)


def signing_keys(directory, key_id):
    directory.mkdir(parents=True, exist_ok=True)
    binary = openssl_binary()
    private = directory / (key_id + ".private.der")
    public = directory / (key_id + ".public.der")
    run_openssl(binary, "genpkey", "-algorithm", "Ed25519", "-outform", "DER", "-out", private)
    run_openssl(binary, "pkey", "-inform", "DER", "-in", private, "-pubout", "-outform", "DER", "-out", public)
    for path in (private, public):
        path.chmod(0o600)
        path.with_suffix(".b64").write_bytes(base64.b64encode(path.read_bytes()))
    return binary, private, public


class SignedFixture:
    def __init__(self, root):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.openssl, self.private, self.public = signing_keys(self.root, KEY_ID)
        self.pins = KEY_ID + "=" + base64.b64encode(self.public.read_bytes()).decode("ascii")
        (self.root / "pins.txt").write_text(self.pins)
        (self.root / "document.input.json").write_bytes(encoded(initial_document()) + b"\n")
        self.sources = copy.deepcopy(json.loads(REFERENCE.read_bytes())["sources"])
        self.publish()

    def sign(self, payload):
        (self.root / "signing-input.bin").write_bytes(payload)
        output = self.root / "signature.bin"
        run_openssl(self.openssl, "pkeyutl", "-sign", "-rawin", "-keyform", "DER", "-inkey", self.private,
                    "-in", self.root / "signing-input.bin", "-out", output)
        return base64.b64encode(output.read_bytes()).decode("ascii")

    def response(self, name, raw, headers):
        (self.root / (name + ".json")).write_bytes(raw)
        fields = {"Content-Type": "application/json; charset=UTF-8", "Content-Length": str(len(raw)), **headers}
        text = "HTTP/1.1 200 OK\r\n" + "".join(key + ": " + value + "\r\n" for key, value in fields.items()) + "\r\n"
        (self.root / (name + ".headers")).write_bytes(text.encode("ascii"))

    def snapshot(self, name, value, signature_format, previous=None):
        raw = encoded(value)
        sha = checksum(raw)
        revision = value.get("catalogRevision", value.get("revision"))
        predecessor = previous or (0, "-")
        envelope = "\n".join((signature_format, str(revision), str(predecessor[0]), predecessor[1], sha, TIME)) + "\n"
        headers = {
            "ETag": '"' + sha + '"', "X-Config-Revision": str(revision), "X-Config-Checksum": sha,
            "X-Config-Signature-Format": signature_format, "X-Config-Signature-Algorithm": "Ed25519",
            "X-Config-Signing-Key-Id": KEY_ID, "X-Config-Signature": self.sign(envelope.encode() + raw),
            "X-Config-Created-At": TIME,
        }
        if previous is not None:
            headers.update({"X-Config-Previous-Revision": str(previous[0]), "X-Config-Previous-Checksum": previous[1]})
        self.response(name, raw, headers)
        return sha

    def member(self, index, source):
        raw = encoded(source)
        sha = checksum(raw)
        prefix = "kira-source-revision-v1\n" + source["api"] + "\n1\n" + sha + "\n"
        entry = {
            "api": source["api"], "sourceRevision": 1, "checksum": sha, "order": index,
            "lifecycle": "active", "engine": "generic", "sourceSigningKeyId": KEY_ID,
            "sourceSignature": self.sign(prefix.encode("utf-8") + raw),
        }
        self.response("member-" + str(index), raw, {
            "ETag": '"' + sha + '"', "X-Source-Api": source["api"], "X-Source-Revision": "1",
            "X-Source-Checksum": sha, "X-Source-Canon-Version": "kcj-1",
        })
        return entry

    def publish(self, manifest_change=None):
        entries = [self.member(index, source) for index, source in enumerate(self.sources)]
        manifest = {"schemaVersion": 1, "sourceSchemaVersion": 1, "catalogRevision": 100,
                    "generatedAt": TIME, "sources": entries, "removedSources": []}
        if manifest_change:
            manifest_change(manifest)
        self.catalog_checksum = self.snapshot("manifest", manifest, MANIFEST_FORMAT)
        document = {"schemaVersion": 1, "revision": 100, "generatedAt": TIME, "sources": self.sources}
        document_checksum = self.snapshot("document", document, DOCUMENT_FORMAT)
        self.receipt = {
            "policyId": POLICY_ID, "referenceSha256": REFERENCE_SHA256,
            "payloadSha256": checksum((self.root / "document.input.json").read_bytes()),
            "documentRevision": 100, "documentChecksum": document_checksum,
            "catalogRevision": 100, "catalogChecksum": self.catalog_checksum, "completedAt": TIME,
            "actorId": "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
        }
        (self.root / "receipt.json").write_bytes(encoded(self.receipt))

    def evolved_manifest(self, empty=False):
        manifest = json.loads((self.root / "manifest.json").read_bytes())
        manifest["catalogRevision"] = 101
        if empty:
            manifest["sources"] = []
        else:
            extra = copy.deepcopy(self.sources[0])
            extra["api"] = "Legitimate Later Source"
            manifest["sources"].append(self.member(12, extra))
        self.snapshot("manifest", manifest, MANIFEST_FORMAT, (100, self.catalog_checksum))


class ApplyFixture:
    """Copies only the release consumer into a test app with a fake sibling signing helper."""

    def __init__(self, root):
        self.root = Path(root)
        self.signed = SignedFixture(self.root / "signed")
        self.release = self.root / "app/scripts/release"
        self.release.mkdir(parents=True)
        for name in ("apply-android-release-config.sh", "validate-android-release-config.sh",
                     "verify-source-bootstrap.py", "source_bootstrap_data.py", "source_bootstrap_signatures.py"):
            shutil.copy2(RELEASE / name, self.release / name)
        shutil.copytree(RELEASE / "reference", self.release / "reference")
        binary = self.root / "bin"
        binary.mkdir()
        mock = RELEASE / "tests/source_bootstrap_mock_tool.py"
        signing = self.root / "kira-backend/scripts/signing/install-github-secret.sh"
        signing.parent.mkdir(parents=True)
        for destination in [binary / name for name in ("gh", "curl", "keytool")] + [signing]:
            shutil.copyfile(mock, destination)
            destination.chmod(0o700)
        self.original = self.root / "original-input.json"
        self.original.write_bytes((self.signed.root / "document.input.json").read_bytes())
        (self.root / "scenario.json").write_text("{}")
        (self.root / "tmp").mkdir()
        (self.root / "home").mkdir()
        self.environment = {key: value for key, value in os.environ.items() if key in ("LANG", "LC_ALL", "OPENSSL_BIN")}
        self.environment.update({"PATH": str(binary) + os.pathsep + os.environ["PATH"],
                                 "BOOTSTRAP_TEST_STATE": str(self.root), "TMPDIR": str(self.root / "tmp"),
                                 "HOME": str(self.root / "home"), "PYTHONDONTWRITEBYTECODE": "1"})
        self.config = self.root / "release.env"
        self.write_config()

    def write_config(self):
        (self.root / "upload.jks").write_bytes(b"local mock keystore")
        (self.root / "firebase.json").write_text('{"project_info":{"project_id":"synthetic-release"}}')
        (self.root / "play.json").write_text('{"type":"service_account","project_id":"synthetic-release"}')
        settings = {
            "ANDROID_KEYSTORE_FILE": self.root / "upload.jks", "ANDROID_KEYSTORE_PASSWORD": "synthetic-store-password",
            "ANDROID_KEY_ALIAS": "synthetic-upload", "ANDROID_KEY_PASSWORD": "synthetic-store-password",
            "GOOGLE_SERVICES_JSON_FILE": self.root / "firebase.json", "GOOGLE_PLAY_SERVICE_ACCOUNT_JSON_FILE": self.root / "play.json",
            "KIRA_SOURCE_CONFIG_BASE_URL": "https://backend.test", "KIRA_SOURCE_CONFIG_PINNED_KEYS": self.signed.pins,
            "SOURCE_CONFIG_KEY_ID": KEY_ID, "SOURCE_CONFIG_PRIVATE_KEY_FILE": self.signed.private.with_suffix(".b64"),
            "SOURCE_CONFIG_PUBLIC_KEY_FILE": self.signed.public.with_suffix(".b64"),
            "KIRA_SIGNING_ACTIVE_KEY_ID": KEY_ID, "KIRA_SIGNING_VERIFICATION_KEYS_0_KEY_ID": KEY_ID,
            "SOURCE_CONFIG_DOCUMENT_FILE": self.original, "SOURCE_CONFIG_ADMIN_EMAIL": "admin@backend.test",
            "SOURCE_CONFIG_ADMIN_PASSWORD": "synthetic-admin-password", "KIRA_APP_REPOSITORY": "test/app",
            "KIRA_BACKEND_REPOSITORY": "test/backend",
        }
        self.config.write_text("".join(key + "=" + str(value) + "\n" for key, value in settings.items()))
        self.config.chmod(0o600)

    def scenario(self, **value):
        (self.root / "scenario.json").write_bytes(encoded(value))
        (self.root / "events.jsonl").write_bytes(b"")

    def events(self):
        path = self.root / "events.jsonl"
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def run(self, publish=True, confirm=True):
        command = ["bash", str(self.release / "apply-android-release-config.sh"), str(self.config)]
        if confirm:
            command.append("--confirm-apply")
        if publish:
            command.append("--publish-source-config")
        return subprocess.run(command, cwd=self.root / "app", env=self.environment, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True, timeout=120, check=False)


if __name__ == "__main__":
    os.umask(0o077)
    if len(sys.argv) == 3 and sys.argv[1] == "input":
        Path(sys.argv[2]).write_bytes(encoded(initial_document()) + b"\n")
    elif len(sys.argv) == 4 and sys.argv[1] == "keys":
        signing_keys(Path(sys.argv[3]), sys.argv[2])
    else:
        raise SystemExit("test fixture arguments are invalid")
