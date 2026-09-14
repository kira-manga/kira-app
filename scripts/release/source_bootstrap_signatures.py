"""Verify existing backend detached signatures over exact received bytes.

No private keys, signing, network or JSON serialization here. OpenSSL verifies
Ed25519 using the same X.509 pins as the App. Signature envelopes mirror backend
DocumentSignatureCodec / SourceCatalogSignatureCodec, not a new wire protocol.
"""

import base64
import datetime
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

from source_bootstrap_data import VerificationError, read_bytes, require, sha256

MANIFEST_FORMAT = "kira-source-catalog-manifest-v1"
DOCUMENT_FORMAT = "kira-source-signature-v1"
SOURCE_FORMAT = "kira-source-revision-v1"
SHA256 = re.compile(r"[0-9a-f]{64}")
KEY_ID = re.compile(r"[A-Za-z0-9._-]{1,64}")
ED25519_SPKI_PREFIX = bytes.fromhex("302a300506032b6570032100")
HEADER_LIMIT = 64 * 1024
PIN_LIMIT = 16 * 1024


def digest(value):
    require(type(value) is str and SHA256.fullmatch(value) is not None, "invalid SHA-256 metadata")
    return value


def positive_revision(value):
    require(type(value) is int and 0 < value < 2 ** 63, "invalid revision metadata")
    return value


def header_revision(value):
    require(re.fullmatch(r"[1-9][0-9]{0,18}", value) is not None, "invalid revision header")
    return positive_revision(int(value))


def instant(value):
    require(re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z", value) is not None,
            "invalid UTC signing instant")
    try:
        datetime.datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as error:
        raise VerificationError("invalid UTC signing instant") from error
    return value


def decoded_base64(value):
    try:
        raw = base64.b64decode(value, validate=True)
    except (ValueError, UnicodeError) as error:
        raise VerificationError("invalid signature or public-key encoding") from error
    require(base64.b64encode(raw).decode("ascii") == value, "noncanonical Base64 metadata")
    return raw


def read_pins(path):
    text = read_bytes(path, PIN_LIMIT).decode("ascii", errors="strict")
    require(text and not any(character.isspace() for character in text), "invalid pinned-key serialization")
    pins = {}
    for entry in text.split(","):
        key_id, separator, encoded = entry.partition("=")
        require(separator and KEY_ID.fullmatch(key_id) is not None and key_id not in pins,
                "invalid or duplicate pinned key ID")
        raw = decoded_base64(encoded)
        require(len(raw) == 44 and raw.startswith(ED25519_SPKI_PREFIX), "pinned key is not X.509 Ed25519")
        pins[key_id] = raw
    return pins


def read_headers(path):
    raw = read_bytes(path, HEADER_LIMIT)
    blocks = raw.decode("latin-1").split("\r\n\r\n")
    if blocks[-1] == "":
        blocks.pop()
    require(0 < len(blocks) <= 5, "invalid HTTP header blocks")
    for index, block in enumerate(blocks):
        lines = block.split("\r\n")
        status = re.fullmatch(r"HTTP/(?:1\.[01]|2|3) ([0-9]{3})(?: [^\r\n]*)?", lines[0])
        require(status is not None, "invalid HTTP status line")
        code = int(status.group(1))
        require(code == 200 if index == len(blocks) - 1 else 100 <= code < 200,
                "unexpected HTTP response status")
    return header_fields(lines[1:])


def header_fields(lines):
    headers = {}
    for line in lines:
        name, separator, value = line.partition(":")
        require(separator and re.fullmatch(r"[!#$%&'*+.^_`|~0-9A-Za-z-]+", name) is not None,
                "invalid HTTP header field")
        require(all(ord(character) >= 32 or character == "\t" for character in value) and "\x7f" not in value,
                "invalid HTTP header value")
        name = name.lower()
        critical = name.startswith(("x-config-", "x-source-")) or name in (
            "etag", "content-type", "content-length", "content-encoding",
        )
        require(not critical or name not in headers, "duplicate security-relevant HTTP header")
        headers[name] = value.strip(" \t")
    return headers


def body_headers(raw, headers, checksum):
    require(sha256(raw) == digest(checksum), "received body checksum mismatch")
    require(headers.get("etag") == '"' + checksum + '"', "body ETag mismatch")
    media_type = headers.get("content-type", "")
    require(re.fullmatch(r'application/json(?:;\s*charset=(?:utf-8|"utf-8"))?', media_type, re.IGNORECASE) is not None,
            "unexpected signed-body content type")
    require(headers.get("content-encoding", "identity").lower() == "identity", "encoded signed body is unsupported")
    if "content-length" in headers:
        require(headers["content-length"] == str(len(raw)), "body length metadata mismatch")


def openssl_binary():
    configured = os.environ.get("OPENSSL_BIN")
    candidates = [configured] if configured else [
        "/opt/homebrew/opt/openssl@3/bin/openssl", "/usr/local/opt/openssl@3/bin/openssl", shutil.which("openssl"),
    ]
    for candidate in candidates:
        if candidate and os.access(candidate, os.X_OK):
            result = subprocess.run([candidate, "list", "-public-key-algorithms"], stdin=subprocess.DEVNULL,
                                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=15, check=False)
            if result.returncode == 0 and b"ed25519" in result.stdout.lower():
                return candidate
    raise VerificationError("OpenSSL with Ed25519 verification is unavailable")


def verify_signature(payload, key_id, encoded_signature, pins, openssl):
    require(KEY_ID.fullmatch(key_id) is not None and key_id in pins, "signature key ID is not pinned")
    signature = decoded_base64(encoded_signature)
    require(len(signature) == 64, "invalid Ed25519 signature length")
    with tempfile.TemporaryDirectory(prefix="kira-bootstrap-signature-") as directory:
        root = Path(directory)
        (root / "public.der").write_bytes(pins[key_id])
        (root / "signature.bin").write_bytes(signature)
        (root / "payload.bin").write_bytes(payload)
        result = subprocess.run(
            [openssl, "pkeyutl", "-verify", "-pubin", "-keyform", "DER", "-inkey", str(root / "public.der"),
             "-rawin", "-in", str(root / "payload.bin"), "-sigfile", str(root / "signature.bin")],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15, check=False,
        )
    require(result.returncode == 0, "Ed25519 signature verification failed")


def verify_snapshot(raw, headers, pins, openssl, signature_format):
    checksum = headers.get("x-config-checksum", "")
    body_headers(raw, headers, checksum)
    require(headers.get("x-config-signature-format") == signature_format and
            headers.get("x-config-signature-algorithm") == "Ed25519", "unsupported snapshot signature metadata")
    revision = header_revision(headers.get("x-config-revision", ""))
    created_at = instant(headers.get("x-config-created-at", ""))
    previous_revision = headers.get("x-config-previous-revision")
    previous_checksum = headers.get("x-config-previous-checksum")
    require((previous_revision is None) == (previous_checksum is None), "incomplete predecessor metadata")
    if previous_revision is not None:
        require(header_revision(previous_revision) < revision, "invalid predecessor revision")
        digest(previous_checksum)
    # Concatenate the real domain-separated envelope with UNCHANGED received bytes.
    prefix = "\n".join((signature_format, str(revision), previous_revision or "0",
                        previous_checksum or "-", checksum, created_at)) + "\n"
    verify_signature(prefix.encode("utf-8") + raw, headers.get("x-config-signing-key-id", ""),
                     headers.get("x-config-signature", ""), pins, openssl)
    return revision, checksum, created_at, previous_revision


def verify_member(raw, headers, entry, pins, openssl):
    checksum = digest(entry["checksum"])
    body_headers(raw, headers, checksum)
    revision = positive_revision(entry["sourceRevision"])
    require(headers.get("x-source-api") == entry["api"] and
            headers.get("x-source-revision") == str(revision) and
            headers.get("x-source-checksum") == checksum and
            headers.get("x-source-canon-version") == "kcj-1", "immutable member identity metadata mismatch")
    prefix = "\n".join((SOURCE_FORMAT, entry["api"], str(revision), checksum)) + "\n"
    # Detached source signatures are manifest ENTRY fields, not source-response headers.
    verify_signature(prefix.encode("utf-8") + raw, entry["sourceSigningKeyId"], entry["sourceSignature"], pins, openssl)
