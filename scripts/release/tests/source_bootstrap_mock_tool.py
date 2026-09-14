#!/usr/bin/env python3
"""Test-only gh/curl/keytool/signing stand-ins: no networking or real credentials."""

import json
import os
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit

MARKER = "SYNTHETIC_PROVIDER_SECRET_MUST_NOT_APPEAR"
TOKEN = "synthetic.access-token-123"
CONFIRMATION = "X-Kira-Bootstrap-Confirmation: WITHHOLD_33_LEGACY_SOURCES"
BOOTSTRAP = "/api/v1/admin/source-catalog-v2/cutover/import-bundled"


def main():
    state = Path(os.environ["BOOTSTRAP_TEST_STATE"])
    scenario = json.loads((state / "scenario.json").read_bytes())
    tool = Path(sys.argv[0]).name
    arguments = sys.argv[1:]
    print(MARKER, file=sys.stderr)  # Also emitted on success: provider diagnostics must stay hidden.
    if tool == "keytool":
        return 0  # Apply tests use a local keystore stub; the existing validator suite uses real keytool.

    def event(**fields):
        with (state / "events.jsonl").open("a") as stream:
            stream.write(json.dumps({"tool": tool, **fields}) + "\n")

    if tool in ("gh", "install-github-secret.sh"):
        event()
        if tool == "gh":
            if arguments[:2] == ["secret", "set"]:
                sys.stdin.buffer.read()
            if arguments[:3] == ["variable", "set", "KIRA_SOURCE_CONFIG_PINNED_KEYS"]:
                (state / "applied-pins.txt").write_text(arguments[arguments.index("--body") + 1])
            if scenario.get("replace_original"):
                (state / "original-input.json").write_bytes((state / "replacement.json").read_bytes())
                if (state / "pins-file.txt").exists():
                    (state / "pins-file.txt").write_text("changed after frozen pins")
        return 73 if scenario.get("fail_tool") == tool else 0

    assert tool == "curl" and arguments[0] == "--disable"
    assert not set(arguments) & {"--location", "--location-trusted", "-L", "--retry", "--insecure", "-k"}

    def option(name):
        return arguments[arguments.index(name) + 1]

    url = next(argument for argument in arguments if argument.startswith("https://"))
    parsed = urlsplit(url)
    assert parsed.netloc == "backend.test" and not parsed.query and not parsed.fragment
    path = parsed.path
    method = option("--request") if "--request" in arguments else "GET"
    event(path=path, method=method)
    if scenario.get("fail_tool") == "curl":
        return 73
    assert option("--proto") == "=https" and option("--max-time") == "60"
    headers = [arguments[index + 1] for index, item in enumerate(arguments) if item == "--header"]
    fixtures = state / "signed"
    if path == "/api/v1/auth/login":
        name = "login-response"
        assert method == "POST" and "--config" not in arguments
        body = json.dumps({"accessToken": scenario.get("token", TOKEN)}).encode()
        head = b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n"
    elif path == BOOTSTRAP:
        name = "receipt"
        assert method == "POST" and headers.count(CONFIRMATION) == 1
        auth = Path(option("--config"))
        assert auth.stat().st_mode & 0o777 == 0o600
        assert auth.read_text() == 'header = "Authorization: Bearer ' + TOKEN + '"\n'
        payload = option("--data-binary")
        assert payload.startswith("@")
        frozen = Path(payload[1:])
        assert frozen.name == "document.input.json" and frozen != state / "original-input.json"
        assert frozen.stat().st_mode & 0o777 == 0o400
        (state / "posted-input.json").write_bytes(frozen.read_bytes())
        body = (fixtures / "receipt.json").read_bytes()
        head = b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n"
    else:
        assert method == "GET" and "--config" not in arguments and not any("Authorization" in h for h in headers)
        if path == "/api/v2/source-config/manifest":
            name = "manifest"
        elif path == "/api/v1/source-config/document":
            name = "document"
        else:
            parts = path.split("/")
            assert len(parts) == 8 and parts[1:5] == ["api", "v2", "source-config", "sources"]
            assert parts[6:] == ["revisions", "1"]
            entries = json.loads((fixtures / "manifest.json").read_bytes())["sources"]
            index = next(index for index, entry in enumerate(entries) if entry["api"] == unquote(parts[5]))
            name = "member-" + str(index)
        body = (fixtures / (name + ".json")).read_bytes()
        head = (fixtures / (name + ".headers")).read_bytes()
    if method == "POST":
        assert headers.count("Content-Type: application/json") == 1 and "Expect:" in headers
    if scenario.get("response_name") == name:
        if "status" in scenario:
            head = ("HTTP/1.1 " + str(scenario["status"]) + " Mock\r\nLocation: https://other.test\r\n\r\n").encode()
            body = json.dumps({"error": MARKER}).encode()
        if scenario.get("overflow") == "body":
            limit = {"login-response": 64 * 1024, "receipt": 16 * 1024}.get(name, 5 * 1024 * 1024)
            body = b"x" * (limit + 1)
        elif scenario.get("overflow") == "headers":
            head = b"HTTP/1.1 200 OK\r\nX-Filler: " + b"x" * (64 * 1024) + b"\r\n\r\n"
    with open(option("--dump-header"), "wb") as stream:
        stream.write(head)
    sys.stdout.buffer.write(body)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
