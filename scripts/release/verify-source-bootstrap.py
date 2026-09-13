#!/usr/bin/env python3
"""Release-only bootstrap preflight and immutable-origin delivery proof.

No source signing/canonicalization, repair, historical fetch or import fallback.
The request adapter is only for this script's bounded curl exchanges; all policy
comparison and signature verification operate on retained local bytes.
"""

import os
import re
import selectors
import subprocess
import sys
import time
import uuid
from pathlib import Path
from urllib.parse import quote, urlsplit

sys.dont_write_bytecode = True
from source_bootstrap_data import (  # noqa: E402
    DeliveryProofUnavailable, GENERIC_APIS, MAX_BYTES, POLICY_ID, REFERENCE_SHA256,
    VerificationError, normalize, read_bytes, reference_sources, require, sha256,
    stage_input, strict_json, validated_input,
)
from source_bootstrap_signatures import (  # noqa: E402
    DOCUMENT_FORMAT, HEADER_LIMIT, MANIFEST_FORMAT, digest, instant, openssl_binary,
    positive_revision, read_headers, read_pins, verify_member, verify_snapshot,
)

CONFIRMATION = "X-Kira-Bootstrap-Confirmation: WITHHOLD_33_LEGACY_SOURCES"
REQUEST_TIMEOUT = 70
RECEIPT_LIMIT = 16 * 1024
AUTH_LIMIT = 64 * 1024


def receipt_for(root):
    receipt = normalize("receipt", strict_json(read_bytes(root / "receipt.json", RECEIPT_LIMIT)))
    require(receipt["policyId"] == POLICY_ID and receipt["referenceSha256"] == REFERENCE_SHA256,
            "bootstrap receipt policy/reference mismatch")
    require(receipt["payloadSha256"] == sha256(validated_input(root / "document.input.json")),
            "bootstrap receipt does not bind the frozen input bytes")
    for field in ("referenceSha256", "payloadSha256", "documentChecksum", "catalogChecksum"):
        digest(receipt[field])
    positive_revision(receipt["catalogRevision"])
    require(receipt["documentRevision"] == receipt["catalogRevision"] and receipt["catalogRevision"] > 6,
            "bootstrap receipt revision mismatch")
    require(receipt["documentChecksum"] != receipt["catalogChecksum"], "bootstrap receipt conflates v1 and v2 checksums")
    instant(receipt["completedAt"])
    require(str(uuid.UUID(receipt["actorId"])) == receipt["actorId"], "invalid bootstrap receipt actor")
    return receipt


def require_origin(metadata, receipt, kind):
    revision, checksum, created_at, previous = metadata
    if revision > receipt[kind + "Revision"]:
        # Deliberately BEFORE any baseline roster/content check. Evolved and empty
        # current catalogs are legal; they cannot prove delivery of a hidden origin.
        raise DeliveryProofUnavailable("signed latest advanced; bootstrap origin delivery proof unavailable; no reimport performed")
    require(revision == receipt[kind + "Revision"] and checksum == receipt[kind + "Checksum"],
            "signed artifact differs from the immutable bootstrap receipt")
    require(created_at == receipt["completedAt"] and previous is None, "bootstrap origin time/predecessor mismatch")


def origin_manifest(root):
    receipt = receipt_for(root)
    pins = read_pins(root / "pins.txt")
    openssl = openssl_binary()
    raw = read_bytes(root / "manifest.json")
    metadata = verify_snapshot(raw, read_headers(root / "manifest.headers"), pins, openssl, MANIFEST_FORMAT)
    require_origin(metadata, receipt, "catalog")
    manifest = normalize("manifest", strict_json(raw))
    require(manifest["schemaVersion"] == 1 and manifest["sourceSchemaVersion"] == 1,
            "unsupported bootstrap manifest schema")
    require(manifest["catalogRevision"] == metadata[0] and manifest["generatedAt"] == metadata[2],
            "manifest body/signature metadata mismatch")
    entries = manifest["sources"]
    require(tuple(entry["api"] for entry in entries) == GENERIC_APIS and not manifest["removedSources"],
            "bootstrap origin manifest inventory mismatch")
    for index, entry in enumerate(entries):
        require(entry["order"] == index and entry["lifecycle"] == "active" and entry["engine"] == "generic",
                "bootstrap origin manifest order/lifecycle/engine mismatch")
        positive_revision(entry["sourceRevision"])
        digest(entry["checksum"])
    return receipt, entries, pins, openssl


def write_plan(root):
    _, entries, _, _ = origin_manifest(root)
    for index, entry in enumerate(entries):
        path = "/api/v2/source-config/sources/" + quote(entry["api"], safe="")
        print(str(index) + "\t" + path + "/revisions/" + str(entry["sourceRevision"]))


def verify_delivery(root):
    receipt, entries, pins, openssl = origin_manifest(root)
    expected = reference_sources()
    for index, entry in enumerate(entries):
        raw = read_bytes(root / ("member-" + str(index) + ".json"))
        headers = read_headers(root / ("member-" + str(index) + ".headers"))
        verify_member(raw, headers, entry, pins, openssl)
        require(normalize("source", strict_json(raw)) == expected[index], "immutable source content differs from approved reference")
    # Bind the distinct v1 receipt checksum too, using served bytes, not an App encoder.
    raw = read_bytes(root / "document.json")
    metadata = verify_snapshot(raw, read_headers(root / "document.headers"), pins, openssl, DOCUMENT_FORMAT)
    require_origin(metadata, receipt, "document")
    document = normalize("document", strict_json(raw))
    require(document["schemaVersion"] == 1 and document["revision"] == receipt["documentRevision"] and
            document["generatedAt"] == receipt["completedAt"] and document["sources"] == expected,
            "v1 origin document/reference mismatch")


def write_auth_config(response, destination):
    value = strict_json(read_bytes(response, AUTH_LIMIT))
    require(type(value) is dict and type(value.get("accessToken")) is str, "invalid authentication response")
    token = value["accessToken"]
    require(0 < len(token) <= RECEIPT_LIMIT and re.fullmatch(r"[A-Za-z0-9._~+/-]+=*", token) is not None,
            "invalid bearer token; curl configuration was not written")
    with os.fdopen(os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
        stream.write('header = "Authorization: Bearer ' + token + '"\n')


def copy_responses(process, header_fd, body_file, header_file, body_limit):
    deadline = time.monotonic() + REQUEST_TIMEOUT
    with selectors.DefaultSelector() as selector:
        selector.register(process.stdout, selectors.EVENT_READ, [body_file, body_limit])
        selector.register(header_fd, selectors.EVENT_READ, [header_file, HEADER_LIMIT])
        while selector.get_map():
            remaining = deadline - time.monotonic()
            require(remaining > 0, "backend request deadline exceeded")
            for key, _ in selector.select(min(remaining, 1)):
                chunk = os.read(key.fd, min(65536, key.data[1] + 1))
                if not chunk:
                    selector.unregister(key.fileobj)
                    continue
                require(len(chunk) <= key.data[1], "backend response exceeded its byte limit")
                key.data[0].write(chunk)
                key.data[1] -= len(chunk)
        require(process.wait(timeout=max(0.1, deadline - time.monotonic())) == 0, "backend transport failed")


def capture_curl(arguments, body_path, header_path, body_limit):
    reader, writer = os.pipe()
    process = None
    try:
        with open(body_path, "xb") as body_file, open(header_path, "xb") as header_file:
            process = subprocess.Popen(
                ["curl", "--disable", "--silent", "--max-time", "60", "--proto", "=https", "--tlsv1.2",
                 "--suppress-connect-headers", "--dump-header", "/dev/fd/" + str(writer), "--output", "-"] + arguments,
                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, pass_fds=(writer,),
            )
            os.close(writer)
            writer = None
            copy_responses(process, reader, body_file, header_file, body_limit)
    finally:
        if writer is not None:
            os.close(writer)
        os.close(reader)
        if process is not None:
            try:
                if process.poll() is None:
                    process.kill()
                process.wait(timeout=5)
            finally:
                process.stdout.close()
    read_headers(header_path)  # Includes HTTP200; 3xx is refusal, never followed.


def request(kind, root, url, name):
    parsed = urlsplit(url)
    require(parsed.scheme == "https" and parsed.hostname and parsed.username is None and
            parsed.password is None and not parsed.query and not parsed.fragment, "invalid backend request URL")
    require(re.fullmatch(r"[a-z0-9-]+", name) is not None, "invalid local response name")
    arguments = [url]
    limit = MAX_BYTES
    if kind in ("login", "bootstrap"):
        source = "login.json" if kind == "login" else "document.input.json"
        arguments += ["--request", "POST", "--header", "Content-Type: application/json", "--header", "Expect:",
                      "--data-binary", "@" + str(root / source)]
        limit = AUTH_LIMIT if kind == "login" else RECEIPT_LIMIT
    else:
        require(kind == "public", "unsupported backend request")
    if kind == "bootstrap":
        arguments += ["--config", str(root / "curl.conf"), "--header", CONFIRMATION]
    capture_curl(arguments, root / (name + ".json"), root / (name + ".headers"), limit)


def main(arguments):
    if len(arguments) == 2 and arguments[0] == "input":
        validated_input(arguments[1])
    elif len(arguments) == 3 and arguments[0] == "stage":
        stage_input(arguments[1], arguments[2])
    elif len(arguments) == 2 and arguments[0] == "pins":
        read_pins(arguments[1])
    elif len(arguments) == 3 and arguments[0] == "auth-config":
        write_auth_config(arguments[1], arguments[2])
    elif len(arguments) == 2 and arguments[0] == "plan":
        write_plan(Path(arguments[1]))
    elif len(arguments) == 2 and arguments[0] == "verify":
        verify_delivery(Path(arguments[1]))
    elif len(arguments) == 5 and arguments[0] == "request":
        request(arguments[1], Path(arguments[2]), arguments[3], arguments[4])
    else:
        raise VerificationError("invalid local bootstrap verifier arguments")


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except DeliveryProofUnavailable as error:
        print("FAIL " + str(error), file=sys.stderr)
        sys.exit(3)
    except VerificationError as error:
        print("FAIL " + str(error), file=sys.stderr)
        sys.exit(1)
    except (OSError, ValueError, UnicodeError, RecursionError, subprocess.SubprocessError):
        print("FAIL local bootstrap verification or bounded transport could not complete", file=sys.stderr)
        sys.exit(1)
