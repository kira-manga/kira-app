#!/usr/bin/env python3
"""Owned HTTPS/platform fixture only; no production client, trust, or package changes."""
import hashlib
import http.server
import json
import os
from pathlib import Path
import platform
import re
import signal
import socket
import ssl
import subprocess
import sys
import threading
import time
SCRIPT = Path(__file__).resolve()
PASSWORD = "single-hop-test-only"
SECRET_FILES = ("ca.key", "ca.pem", "server.key", "server.pem", "server.csr", "tls.cnf", "truststore.p12")
def save(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, sort_keys=True) + "\n")
    temporary.replace(path)
def read(path):
    assert path.is_file() and not path.is_symlink() and path.stat().st_size <= 65536
    return json.loads(path.read_text())
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def run(args, root, timeout=30):
    env = {key: os.environ[key] for key in ("PATH", "HOME", "DEVELOPER_DIR") if key in os.environ}
    env["TMPDIR"] = str(root)
    try:
        return subprocess.check_output(args, env=env, stderr=subprocess.STDOUT, timeout=timeout, text=True).strip()
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        print("Fixture tool failure: " + " ".join(args[:3]) + " " + type(error).__name__, flush=True)
        raise

def owned(mode):
    assert mode in ("android", "ios") and os.environ.get("GITHUB_ACTIONS") == "true"
    owner = {key: os.environ[key] for key in ("GITHUB_REPOSITORY", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_SHA")}
    assert owner["GITHUB_REPOSITORY"] == "kira-manga/kira-source-engine"
    assert all(re.fullmatch(r"[1-9][0-9]*", owner[key]) for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"))
    assert re.fullmatch(r"[0-9a-f]{40}", owner["GITHUB_SHA"])
    owner.update(platform=mode, script_sha256=sha(SCRIPT))
    name = "kira-single-hop-" + owner["GITHUB_RUN_ID"] + "-" + owner["GITHUB_RUN_ATTEMPT"] + "-" + mode
    return Path(os.environ["RUNNER_TEMP"]).resolve(strict=True) / name, owner

def certificates(root, mode):
    (root / "tls.cnf").write_text("[req]\ndistinguished_name=dn\n[dn]\n[ca]\nbasicConstraints=critical,CA:true\n"
        "keyUsage=critical,keyCertSign,cRLSign\n[server]\nbasicConstraints=critical,CA:false\n"
        "subjectAltName=DNS:localhost,IP:127.0.0.1\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n")
    run(["/usr/bin/openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-days", "1",
         "-subj", "/CN=Kira-Single-Hop-Test-CA", "-config", str(root / "tls.cnf"), "-extensions", "ca",
         "-keyout", str(root / "ca.key"), "-out", str(root / "ca.pem")], root)
    run(["/usr/bin/openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes", "-sha256", "-subj", "/CN=localhost",
         "-config", str(root / "tls.cnf"), "-keyout", str(root / "server.key"), "-out", str(root / "server.csr")], root)
    run(["/usr/bin/openssl", "x509", "-req", "-in", str(root / "server.csr"), "-CA", str(root / "ca.pem"),
         "-CAkey", str(root / "ca.key"), "-set_serial", "1", "-days", "1", "-sha256", "-extfile", str(root / "tls.cnf"),
         "-extensions", "server", "-out", str(root / "server.pem")], root)
    if mode == "android":
        keytool = Path(os.environ["JAVA_HOME"]) / "bin/keytool"
        run([str(keytool), "-importcert", "-noprompt", "-alias", "fixture", "-file", str(root / "ca.pem"),
             "-keystore", str(root / "truststore.p12"), "-storetype", "PKCS12", "-storepass", PASSWORD], root)

def devices(root):
    return json.loads(run(["/usr/bin/xcrun", "simctl", "list", "devices", "--json"], root))["devices"]

def simulator(root):
    assert platform.system() == "Darwin" and platform.machine() == "arm64"
    current = devices(root)
    choices = [(runtime, device) for runtime, group in current.items()
               if re.fullmatch(r"com\.apple\.CoreSimulator\.SimRuntime\.iOS-[0-9-]+", runtime)
               for device in group if device.get("isAvailable") and
               device.get("deviceTypeIdentifier", "").startswith("com.apple.CoreSimulator.SimDeviceType.iPhone-")]
    assert choices and all(device["name"] != root.name for group in current.values() for device in group)
    runtime, device = max(choices, key=lambda pair: tuple(map(int, pair[0].split(".iOS-")[1].split("-"))))
    reservation = {"name": root.name, "runtime": runtime, "device_type": device["deviceTypeIdentifier"]}
    save(root / "simulator.json", reservation)  # Persist before creation, including partial-setup cleanup.
    udid = run(["/usr/bin/xcrun", "simctl", "create", root.name, reservation["device_type"], runtime], root)
    assert re.fullmatch(r"[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}", udid)
    save(root / "simulator.json", dict(reservation, udid=udid))
    run(["/usr/bin/xcrun", "simctl", "boot", udid], root)
    run(["/usr/bin/xcrun", "simctl", "bootstatus", udid, "-b"], root, 540)
    run(["/usr/bin/xcrun", "simctl", "keychain", udid, "add-root-cert", str(root / "ca.pem")], root)
    return udid

def prepare(mode):
    root, owner = owned(mode)
    root.mkdir(mode=0o700)
    save(root / "owner.json", owner)
    certificates(root, mode)
    udid = simulator(root) if mode == "ios" else None
    with (root / "server.log").open("xb") as log:
        process = subprocess.Popen([sys.executable, "-I", "-B", str(SCRIPT), "serve", str(root)],
            env={"PATH": os.defpath, "HOME": str(root), "TMPDIR": str(root)},
            stdout=log, stderr=log, start_new_session=True)
    deadline = time.monotonic() + 15
    while not (root / "server-ready.json").exists():
        assert process.poll() is None and time.monotonic() < deadline, "Owned fixture did not start"
        time.sleep(0.05)
    ready = read(root / "server-ready.json")
    ready.update(platform=mode, owner=owner, ca_cert=str(root / "ca.pem"), ca_sha256=sha(root / "ca.pem"))
    ready.update({"simulator_uuid": udid} if mode == "ios" else
                 {"truststore": str(root / "truststore.p12"), "truststore_sha256": sha(root / "truststore.p12")})
    save(root / "ready.json", ready)

class WireServer(http.server.ThreadingHTTPServer):
    daemon_threads = False
    def __init__(self, root):
        self.fixture_id = os.urandom(16).hex()
        self.lock, self.sockets = threading.Lock(), set()
        self.counts = dict(forbidden=0, bad_requests=0, redirect_get=0, redirect_post=0,
                           streams_started=0, streams_closed=0, streams_timed_out=0)
        self.tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.tls.minimum_version = ssl.TLSVersion.TLSv1_2
        self.tls.load_cert_chain(root / "server.pem", root / "server.key")
        super().__init__(("127.0.0.1", 0), WireHandler)

    def get_request(self):
        connection, address = super().get_request()
        connection.settimeout(10)
        try:
            connection = self.tls.wrap_socket(connection, server_side=True)
            with self.lock:
                assert len(self.sockets) < 8
                self.sockets.add(connection)
            return connection, address
        except Exception:
            connection.close()
            raise

    def shutdown_request(self, request):
        with self.lock:
            self.sockets.discard(request)
        super().shutdown_request(request)

class WireHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *args):
        pass  # Never log request headers or certificate/private-key material.

    def count(self, name):
        with self.server.lock:
            self.server.counts[name] += 1

    def reply(self, body, status=200, headers=(), stall=False):
        self.send_response(status)
        for key, value in headers:
            self.send_header(key, value)
        self.send_header("Content-Length", str(1048576 if stall else len(body)))
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.wfile.flush()
        if stall:
            self.count("streams_started")
            try:
                assert self.connection.recv(1) == b""
                self.count("streams_closed")
            except (TimeoutError, socket.timeout):
                self.count("streams_timed_out")
            except (ConnectionResetError, ssl.SSLError):
                self.count("streams_closed")

    def do_GET(self):
        if self.path.startswith("/forbidden"):
            self.count("forbidden")
        if self.headers.get("X-Kira-Fixture") != self.server.fixture_id:
            self.count("bad_requests")
            return self.reply(b"refused", 403)
        if self.path == "/ready":
            return self.reply(self.server.fixture_id.encode())
        if self.path == "/state":
            with self.server.lock:
                body = "".join(key + "=" + str(value) + "\n" for key, value in self.server.counts.items())
            return self.reply(body.encode())
        if self.path == "/ok":
            return self.reply(b"ok")
        if self.path == "/stall":
            return self.reply(b"x", stall=True)
        if self.path == "/redirect/get":
            self.count("redirect_get")
            target = "https://127.0.0.1:" + str(self.server.server_port) + "/forbidden/get"
            return self.reply(b"redirect", 302, [("Location", target)])
        return self.location()

    def location(self):
        cases = {"distinct": [("Location", "/forbidden/one"), ("Location", "/forbidden/two")],
                 "identical": [("Location", "/forbidden/one")] * 2,
                 "case": [("Location", "/forbidden/one"), ("lOcAtIoN", "/forbidden/two")],
                 "folded": [("Location", "/forbidden/one, /forbidden/two")],
                 "escaped": [("Location", "/forbidden/part%2Ctwo")]}
        if self.path.startswith("/location/") and self.path.removeprefix("/location/") in cases:
            return self.reply(b"redirect", 302, cases[self.path.removeprefix("/location/")])
        self.count("bad_requests")
        return self.reply(b"refused", 404)

    def do_POST(self):
        if self.path.startswith("/forbidden"):
            self.count("forbidden")
        if (self.path != "/redirect/post" or self.headers.get("X-Kira-Fixture") != self.server.fixture_id or
                self.headers.get("Content-Length") != "2" or self.rfile.read(2) != b"{}"):
            self.count("bad_requests")
            return self.reply(b"refused", 400)
        self.count("redirect_post")
        return self.reply(b"redirect", 307, [("Location", "/forbidden/post")])

def serve(root):
    assert root.is_absolute() and root == root.resolve(strict=True)
    assert read(root / "owner.json")["script_sha256"] == sha(SCRIPT)
    server = WireServer(root)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    save(root / "server-ready.json", {"origin": "https://localhost:" + str(server.server_port),
         "fixture_id": server.fixture_id, "server_pid": os.getpid()})
    try:
        server.serve_forever(poll_interval=0.1)
    except KeyboardInterrupt:
        pass
    finally:
        with server.lock:
            sockets = list(server.sockets)
        for connection in sockets:
            try:
                connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        server.server_close()
        save(root / "stats.json", server.counts)

def server_pids(root):
    # Exact command also recovers interruption before server-ready.json is persisted.
    output = run(["/bin/ps", "-axo", "pid=,stat=,command="], root, 10)
    assert len(output) <= 2 * 1024 * 1024
    expected = " ".join([sys.executable, "-I", "-B", str(SCRIPT), "serve", str(root)])
    rows = (row.split(None, 2) for row in output.splitlines())
    matches = [int(cells[0]) for cells in rows
               if len(cells) == 3 and cells[2] == expected and not cells[1].startswith("Z")]
    assert len(matches) <= 1 and all(pid > 1 for pid in matches)
    return matches

def stop_server(root):
    for sig in (signal.SIGTERM, signal.SIGKILL):
        for pid in server_pids(root):
            try:
                os.kill(pid, sig)
            except ProcessLookupError:
                pass
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if not server_pids(root):
                return
            time.sleep(0.1)
    raise RuntimeError("Owned fixture server remains")

def remove_simulator(root):
    if not (root / "simulator.json").exists():
        return
    reservation = read(root / "simulator.json")
    matches = [device for device in devices(root).get(reservation["runtime"], []) if device["name"] == root.name]
    assert reservation["name"] == root.name and len(matches) <= 1
    for device in matches:
        assert device["deviceTypeIdentifier"] == reservation["device_type"]
        assert "udid" not in reservation or reservation["udid"] == device["udid"]
        try:
            if device["state"] != "Shutdown":
                run(["/usr/bin/xcrun", "simctl", "shutdown", device["udid"]], root, 60)
        finally:
            run(["/usr/bin/xcrun", "simctl", "delete", device["udid"]], root, 60)
    assert not any(device["name"] == root.name for group in devices(root).values() for device in group)

def cleanup(mode):
    root, owner = owned(mode)
    if not root.exists():
        return
    assert root == root.resolve(strict=True) and read(root / "owner.json") == owner
    failures = []
    for name, action in (("server", stop_server), ("simulator", remove_simulator)):
        try:
            action(root)
        except Exception:
            failures.append(name)
    if "server" not in failures:
        for name in SECRET_FILES:
            (root / name).unlink(missing_ok=True)
    save(root / "cleanup.json", {"server_stopped": "server" not in failures,
         "simulator_deleted": "simulator" not in failures,
         "secret_files_removed": all(not (root / name).exists() for name in SECRET_FILES), "failures": failures})
    assert not failures, "Owned fixture cleanup failed"

if __name__ == "__main__":
    os.umask(0o077)
    try:
        assert len(sys.argv) == 3
        command, value = sys.argv[1:]
        {"serve": serve, "prepare": prepare, "cleanup": cleanup}[command](Path(value) if command == "serve" else value)
    except Exception:
        print("Owned single-hop fixture refused; no production networking or trust changes.", file=sys.stderr)
        sys.exit(1)
