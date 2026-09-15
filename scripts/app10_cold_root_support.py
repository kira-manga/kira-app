"""App10-only bounded pipes, source readback and private ADB endpoint checks. No entry point."""
import hashlib
import json
import os
from pathlib import Path
import selectors
import socket
import subprocess
import time

from app29_linux_owned_processes import OwnedChildren, OwnershipError, descendants_from, process_identity, same_identity

MIB = 1024 * 1024
JSON_CAPS = {"result.json": 65536, "inputs.json": 65536, "commands.json": 131072,
             "cleanup.json": MIB, "observations.json": 65536}
SHOTS = ["01-welcome.png", "02-theme-optional.png", "03-theme-denial.png", "04-start-reading.png",
         "05-first-library.png", "06-cold-backfill.png", "07-cold-library.png"]
APK_CAPS = {"app-debug.apk": 128*MIB, "platform-device-test.apk": 128*MIB}
PUBLIC_CAPS = {**JSON_CAPS, "build.log": 8*MIB, "adb-server.log": MIB, **{name: 8*MIB for name in SHOTS}}
PUBLIC_CAPS.update(APK_CAPS)
PUBLIC_CAPS.update({"build.log": 16*MIB, "device-build.log": 16*MIB,
    **{name + "-provenance.json": 8*MIB for name in ("host", "device")},
    **{name + "-tests.json": MIB for name in ("host", "device")},
    **{name + "-native.json": MIB for name in ("app", "device")},
    **{f"host-{i:02}.xml": MIB for i in range(1, 11)},
    **{f"device-{i:02}.xml": MIB for i in range(1, 33)}})
OPERATION_CODES = {"listener-tcp4", "listener-tcp6", "listener-census", "listener-fd-list",
                   "listener-fd-readlink", "listener-identity", "listener-unproved",
                   "command-log-create", "command-log-read"}
OPERATION_NUMBERS = ("port", "pid", "start_ticks", "fd", "errno", "targets", "proven")
OPERATION_NOTE_CAP = 32


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def regular(path, cap):
    require(path.is_file() and not path.is_symlink() and path.resolve() == path and path.stat().st_size <= cap,
            "Missing, aliased or oversized admitted file")
    return path.read_bytes()


def record(reports, name, value):
    data = (json.dumps(value, sort_keys=True, indent=2) + "\n").encode()
    require(len(data) <= JSON_CAPS[name], "Public JSON cap exceeded")
    with (reports / name).open("xb") as output:
        output.write(data)


def error_row(stage, error):
    row = dict(stage=stage, type=type(error).__name__)
    detail = getattr(error, "_app10_operation", None)
    if isinstance(detail, dict) and detail.get("operation") in OPERATION_CODES:
        row["operation"] = detail["operation"]
        row.update({key: detail[key] for key in OPERATION_NUMBERS
                    if type(detail.get(key)) is int and detail[key] >= 0})
    return row  # Never exception text, filenames, FD targets, command lines or environments.


def operation_error(lane, operation, error, *, port=None, identity=None, fd=None,
                    targets=None, proven=None, candidate_fd_denial=False):
    require(operation in OPERATION_CODES, "Unknown fixed App10 operation")
    identity = identity or {}
    values = dict(port=port, pid=identity.get("pid"), start_ticks=identity.get("start_ticks"), fd=fd,
                  errno=error.errno if isinstance(error, OSError) else None, targets=targets, proven=proven)
    detail = dict(operation=operation, **{key: value for key, value in values.items()
                                       if type(value) is int and value >= 0})
    error._app10_operation = detail  # Preserve the original exception type and failure.
    notes = lane.operation_notes
    if len(notes["events"]) < OPERATION_NOTE_CAP:
        notes["events"].append(dict(detail, type=type(error).__name__, candidate_fd_denial=candidate_fd_denial))
    else:
        notes["omitted"] += 1  # The final error still carries its own fixed operation metadata.


def directory_identity(path):
    require(path.is_dir() and not path.is_symlink() and path.resolve() == path, "Aliased owned directory")
    value = path.stat()
    return value.st_dev, value.st_ino


def environment(work, app, engine, java, sdk):
    for name in ("home", "gradle", "konan", "tmp", "project-cache", "avd", "android", "logs"):
        (work / name).mkdir(mode=0o700)
    # Deliberately no inherited credentials, Gradle options or conflicting ADB routing variables.
    return dict(PATH=f"{java / 'bin'}:/usr/bin:/bin", HOME=str(work / "home"), JAVA_HOME=str(java),
        JAVA_OPTS="-Xmx512m -XX:ActiveProcessorCount=2", JAVA_TOOL_OPTIONS=f"-Djava.io.tmpdir={work / 'tmp'}",
        ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk), ANDROID_USER_HOME=str(work / "android"),
        ANDROID_EMULATOR_HOME=str(work / "android"), ANDROID_AVD_HOME=str(work / "avd"), ANDROID_ADB_SERVER_PORT="5038",
        ANDROID_SERIAL="emulator-5580",
        GRADLE_USER_HOME=str(work / "gradle"), KONAN_DATA_DIR=str(work / "konan"),
        TMPDIR=str(work / "tmp"), TMP=str(work / "tmp"), TEMP=str(work / "tmp"),
        APP10_PRIMARY_ADMITTED="1", APP10_APP_ROOT=str(app), APP10_ENGINE_ROOT=str(engine),
        KIRA_SOURCE_CONFIG_BASE_URL="", KIRA_SOURCE_CONFIG_PINNED_KEYS="", KIRA_APP_VERSION="1.0.5",
        PYTHONDONTWRITEBYTECODE="1", GIT_OPTIONAL_LOCKS="0", LANG="C.UTF-8", LC_ALL="C.UTF-8", TZ="UTC")


class Lane:
    """One App10 parent; immutable helper owns every descendant, including adopted daemons."""
    def __init__(self, work, started, work_seconds=4500):
        require(work_seconds == 4500, "Only the primary-reviewed finite shared-Android budget")
        self.work, self.env = work, {}
        self.owner, self.poller = OwnedChildren(), selectors.DefaultSelector()
        self.rows, self.jobs, self.barriers = [], [], []
        self.operation_notes = dict(events=[], omitted=0)
        self.work_end, self.total_end, self.cleanup_end = started + work_seconds, started + work_seconds + 150, None
        self.phase_receipts, self.services = [], []
        self.connected_phase = False
        self.endpoint = dict(checks=0, private_connections=[])
        self.absent = False

    def end(self):
        return self.cleanup_end if self.cleanup_end is not None else self.work_end

    def start(self, argv, name, cwd, cap=MIB, background=False, stdin=None):
        self.absent = False
        require(self.owner.active and not self.owner.errors and time.monotonic() < self.end(), "No safe owned launch budget")
        require(len(self.jobs) < 512, "App10 command count cap exceeded")
        path = self.work / "logs" / (str(len(self.jobs)) + ".log")
        try:
            output = path.open("xb")
        except OSError as error:
            operation_error(self, "command-log-create", error)
            raise
        row = dict(name=name, cap=cap, bytes=0, overflow=False, exit=None)
        job = dict(row=row, output=output, path=path, background=background, eof=False, process=None)
        self.rows.append(row)
        self.jobs.append(job)
        try:
            process = subprocess.Popen([str(a) for a in argv], cwd=cwd, env=self.env, start_new_session=True,
                stdin=subprocess.PIPE if stdin is not None else subprocess.DEVNULL,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            job["process"] = process  # Retain before every fallible census/pipe operation.
            row["pid"] = process.pid
            self.poller.register(process.stdout, selectors.EVENT_READ, job)
            self.owner.track()
            if background:
                job["identity"] = process_identity(process.pid)
                require(job["identity"] is not None, "Background service identity unavailable")
            if stdin is not None:
                try:
                    process.stdin.write(stdin)  # Only fixed 3/64-byte SDK/AVD answers.
                finally:
                    process.stdin.close()
        except BaseException:
            if job["process"] is None:
                output.close()
                job["eof"] = True
            raise
        return job

    def pump(self, enforce=True):
        self.absent = False
        self.owner.track()
        for key, _ in self.poller.select(0.1):
            job, row = key.data, key.data["row"]
            chunk = os.read(key.fd, 65536)
            if not chunk:
                self.poller.unregister(key.fileobj)
                key.fileobj.close()
                job["output"].close()
                job["eof"] = True
                continue
            remaining = max(0, row["cap"] - row["bytes"])
            job["output"].write(chunk[:remaining])
            row["bytes"] += len(chunk)
            row["overflow"] |= row["bytes"] > row["cap"]
        if enforce:
            require(not any(row["overflow"] for row in self.rows), "Owned command output cap exceeded")
            require(all(not j["background"] or j["process"] is None or j["process"].poll() is None for j in self.jobs),
                    "Owned foreground service exited unexpectedly")
            if self.connected_phase:
                observe_gradle_endpoint(self)

    def wait(self, job, seconds, accepted=(0,), drain=True, deadline=float("inf")):
        end = min(self.end(), time.monotonic() + seconds, deadline)
        process = job["process"]
        while process.poll() is None or (drain and not job["eof"]):
            require(time.monotonic() < end, "Owned command deadline exceeded")
            self.pump(enforce=self.cleanup_end is None)
        job["row"]["exit"] = process.wait()
        require(process.returncode in accepted, "Owned command failed: " + job["row"]["name"])
        if drain:
            try:
                job["row"]["sha256"] = sha(job["path"])
                return regular(job["path"], job["row"]["cap"])
            except OSError as error:
                operation_error(self, "command-log-read", error, identity=job["row"])
                raise

    def run(self, argv, name, cwd, seconds=20, cap=MIB, accepted=(0,), stdin=None, deadline=float("inf")):
        end = min(self.end(), time.monotonic() + seconds, deadline)
        require(time.monotonic() < end, "No command phase budget")
        return self.wait(self.start(argv, name, cwd, cap, stdin=stdin), seconds, accepted, deadline=end)

    def barrier(self):
        self.absent = False
        try:
            # Join known handles naturally before the helper reaps remaining/adopted children.
            for job in self.jobs:
                if job["process"] is not None and job["row"]["exit"] is None and job["row"].get("pid") not in self.owner.reaped:
                    try:
                        job["row"]["exit"] = job["process"].wait(timeout=0)
                    except subprocess.TimeoutExpired:
                        pass
            remaining = max(0, self.end() - time.monotonic())
            receipt = self.owner.barrier(min(5, remaining / 3), min(10, remaining / 3), min(10, remaining / 3))
            while self.poller.get_map():
                require(time.monotonic() < self.end(), "Pipe drain deadline exceeded after absence")
                self.pump(enforce=False)
            # Draining performs fresh censuses, invalidating the first receipt; re-prove absence.
            receipt = self.owner.barrier(0, 0, 0)
            for job in self.jobs:
                if job["row"]["exit"] is None and job["row"].get("pid") in receipt["reaped"]:
                    job["row"]["reaped_by_helper"] = True  # Keep unknown exit status; never synthesize a zero.
                require(job["eof"], "Incomplete owned pipe registration/drain")
                job["row"]["sha256"] = sha(job["path"])
            self.absent = receipt["absent"]
            self.barriers.append(dict(receipt, pipes_drained=True))
            return receipt
        except BaseException:
            self.absent = False
            self.barriers.append(dict(self.owner.last_receipt, absent=False, pipes_drained=False))
            raise

    def foreground_drain(self, seconds=30):
        """Natural phase closure only, never total absence and never a signal/barrier exemption.

        Before device launch there are no retained services. After launch only the two
        already-owned service roots and their current descendants may live. Every other
        child must exit/reap and every foreground pipe must drain. Final barrier() still
        owns ALL descendants, with no exceptions, before ownership restoration.
        """
        end, empty = min(self.end(), time.monotonic() + seconds), 0
        while True:
            require(time.monotonic() < end and not self.owner.errors and not self.owner.signals,
                    "Natural foreground phase closure unavailable")
            self.pump()
            for job in self.jobs:
                if not job["background"] and job["process"] is not None:
                    code = job["process"].poll()
                    if code is not None:
                        job["row"]["exit"] = code
            children = self.owner.track()
            table = {r["pid"]: r for r in children}
            allowed = set()
            for job in self.services:
                ident = job["identity"]
                require(job["background"] and job["process"].poll() is None and same_identity(ident, table.get(ident["pid"])),
                        "Retained service root identity changed")
                allowed.add(ident["pid"])
                allowed.update(descendants_from(table, ident["pid"]))
            others = [r for r in children if r["pid"] not in allowed]
            for row in others:
                if row["state"] == "Z" and row["ppid"] == self.owner.parent["pid"]:
                    if self.owner.ops.reap(row["pid"]):
                        self.owner.reaped.append(row["pid"])
            drained = all(j["background"] or j["eof"] for j in self.jobs)
            empty = empty + 1 if not others and drained else 0
            if empty >= 2:
                for job in self.jobs:
                    if not job["background"]:
                        job["row"]["sha256"] = sha(job["path"])
                receipt = dict(foreground_absent=True, total_absence=False, forced=False,
                               services=[j["identity"] for j in self.services], pipes_drained=True)
                self.phase_receipts.append(receipt)
                return receipt


def observe_gradle_endpoint(lane):
    """Bounded passive TCP/FD readback during connected Gradle only; no new server/probe.

    Environment/--serial are intent, not proof. At least one current non-service owned
    descendant must hold an established loopback5038 connection. A5037 listener or
    owned connection is fatal; service FD denials are outside this non-service roster.
    """
    sockets, wrong = {}, set()
    for table in (Path("/proc/net/tcp"), Path("/proc/net/tcp6")):
        for line in table.read_text().splitlines()[1:]:
            f = line.split()
            local, remote = f[1].split(":"), f[2].split(":")
            local_port, remote_port = int(local[1], 16), int(remote[1], 16)
            require(not (local_port == 5037 and f[3] == "0A"), "Default ADB listener appeared; no fallback")
            if remote_port == 5037:
                wrong.add(f[9])
            if remote_port == 5038 and f[3] == "01":
                require(remote[0] in ("0100007F", "00000000000000000000000001000000", "0000000000000000FFFF00000100007F"),
                        "Private ADB connection is not loopback")
                sockets[f[9]] = remote_port
    children = lane.owner.track()
    table = {r["pid"]: r for r in children}
    excluded = set()
    for job in lane.services:
        ident = job["identity"]
        require(same_identity(ident, table.get(ident["pid"])), "Device service identity changed")
        excluded.add(ident["pid"])
        excluded.update(descendants_from(table, ident["pid"]))
    for ident in children:
        if ident["pid"] in excluded or ident["state"] == "Z":
            continue
        try:
            inodes = set()
            for fd in (Path("/proc") / str(ident["pid"]) / "fd").iterdir():
                try:
                    link = os.readlink(fd)
                except (FileNotFoundError, ProcessLookupError):
                    continue
                if link.startswith("socket:["):
                    inodes.add(link[8:-1])
            if not same_identity(ident, process_identity(ident["pid"])):
                continue
        except (FileNotFoundError, ProcessLookupError):
            continue
        require(not (inodes & wrong), "Owned connected-task descendant reached default5037")
        if inodes & sockets.keys():
            row = dict(pid=ident["pid"], start_ticks=ident["start_ticks"], port=5038, state="ESTABLISHED")
            if row not in lane.endpoint["private_connections"]:
                require(len(lane.endpoint["private_connections"]) < 32, "Private endpoint observation cap exceeded")
                lane.endpoint["private_connections"].append(row)
    lane.endpoint["checks"] += 1


def vacant(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", port))  # No connection, service start or default-ADB probe.


def owned_listener(lane, port):
    lane.absent = False
    inodes = set()
    for table, operation in ((Path("/proc/net/tcp"), "listener-tcp4"), (Path("/proc/net/tcp6"), "listener-tcp6")):
        try:
            for line in table.read_text().splitlines()[1:]:
                fields = line.split()
                if fields[3] == "0A" and int(fields[1].split(":")[1], 16) == port:
                    inodes.add(fields[9])
        except (OSError, UnicodeError, ValueError, IndexError) as error:
            operation_error(lane, operation, error, port=port)
            raise
    if not inodes:
        return False
    owned = set()
    try:
        candidates = lane.owner.track()
    except OwnershipError as error:
        operation_error(lane, "listener-census", error, port=port)
        raise
    for identity in candidates:
        directory = Path("/proc") / str(identity["pid"]) / "fd"
        try:
            descriptors = list(directory.iterdir())
        except PermissionError as error:
            operation_error(lane, "listener-fd-list", error, port=port, identity=identity, candidate_fd_denial=True)
            descriptors = []  # No ownership inferred; ALL target inodes still need positive proof below.
        except (FileNotFoundError, ProcessLookupError):
            continue
        except OSError as error:
            operation_error(lane, "listener-fd-list", error, port=port, identity=identity)
            raise
        links = []
        for fd in descriptors:
            try:
                links.append(os.readlink(fd))
            except PermissionError as error:
                operation_error(lane, "listener-fd-readlink", error, port=port, identity=identity,
                                fd=int(fd.name) if fd.name.isdecimal() else None, candidate_fd_denial=True)
            except (FileNotFoundError, ProcessLookupError):
                pass  # An unrelated short-lived FD must not hide the listener's owner.
            except OSError as error:
                operation_error(lane, "listener-fd-readlink", error, port=port, identity=identity,
                                fd=int(fd.name) if fd.name.isdecimal() else None)
                raise
        try:
            current = process_identity(identity["pid"])
        except (FileNotFoundError, ProcessLookupError):
            continue
        except (OSError, OwnershipError) as error:
            operation_error(lane, "listener-identity", error, port=port, identity=identity)
            raise
        if same_identity(identity, current):
            owned.update(link[8:-1] for link in links if link.startswith("socket:["))
    if not inodes <= owned:
        error = RuntimeError("Private service listener is not an owned descendant")
        operation_error(lane, "listener-unproved", error, port=port, targets=len(inodes), proven=len(inodes & owned))
        raise error
    return True


def snapshot(lane, root, expected=None, allowed=()):
    def git(*args):
        return lane.run(["git", "--no-optional-locks", "-C", root, *args], "git-read", root, cap=2*MIB)
    head = git("rev-parse", "HEAD").decode().strip()
    tree = git("rev-parse", "HEAD^{tree}").decode().strip()
    require(expected is None or (head, tree) == expected, "Wrong exact source commit/tree")
    status = git("status", "--porcelain=v1", "--untracked-files=all", "--ignored=matching", "-z")
    for entry in filter(None, status.split(b"\0")):
        path = root / entry[3:].decode().rstrip("/")
        require(entry[:3] == b"!! " and any(path == p or p in path.parents for p in allowed),
                "Changed source or unexpected untracked/ignored output; preserve")
    digest, count = hashlib.sha256(), 0
    for entry in git("ls-tree", "-rz", "HEAD").rstrip(b"\0").split(b"\0"):
        require(time.monotonic() < lane.end(), "Source readback deadline exceeded")
        meta, name = entry.decode().split("\t", 1)
        mode, kind, blob = meta.split()
        path = root / name
        require(kind == "blob" and path.is_file() and path.resolve() == path and not path.is_symlink(),
                "Nonregular tracked source")
        require(mode == ("100755" if path.stat().st_mode & 0o111 else "100644"), "Source mode changed")
        identity = hashlib.sha1(f"blob {path.stat().st_size}\0".encode())
        content = hashlib.sha256()
        with path.open("rb") as stream:
            while block := stream.read(65536):
                require(time.monotonic() < lane.end(), "Source hash deadline exceeded")
                identity.update(block)
                content.update(block)
        require(identity.hexdigest() == blob, "Tracked bytes differ from exact Git object")
        digest.update((name + "\0" + mode + "\0" + content.hexdigest() + "\n").encode())
        count += 1
    return dict(head=head, tree=tree, files=count, map_sha256=digest.hexdigest())


def properties(data):
    result = {}
    for line in data.decode().splitlines():
        line = line.strip()
        if line and not line.startswith(("#", "!")) and "=" in line:
            key, value = (part.strip() for part in line.split("=", 1))
            require(key not in result, "Duplicate SDK property")
            result[key] = value
    return result


def sdk_inputs(lane, sdk, runtime_only=False):
    packages = {"platforms;android-37.0": "platforms/android-37.0/android.jar", "build-tools;36.0.0": "build-tools/36.0.0/aapt2",
                "platform-tools": "platform-tools/adb", "emulator": "emulator/emulator",
                "system-images;android-35;default;x86_64": "system-images/android-35/default/x86_64/system.img"}
    if runtime_only:
        packages = {key: value for key, value in packages.items()
                    if key in ("platform-tools", "emulator", "system-images;android-35;default;x86_64")}
    manager = (sdk / "cmdline-tools/latest/bin/sdkmanager").resolve()
    avdmanager = (sdk / "cmdline-tools/latest/bin/avdmanager").resolve()
    require(manager.is_file() and avdmanager.is_file() and sdk in manager.parents and sdk in avdmanager.parents,
            "Installed SDK command-line tools required; no bootstrap/upgrade")
    missing = [key for key, value in packages.items() if not (sdk / value).is_file()]
    for key in missing:
        parent = (sdk / packages[key]).parent
        require(not parent.exists() and not parent.is_symlink() and parent.resolve() == parent, "Partial/aliased SDK package; preserve")
    installed = {}
    for key in packages.keys() - set(missing):
        path = sdk / packages[key]
        require(path.resolve() == path, "Aliased installed SDK input; preserve")
        metadata = regular(path.parent / "source.properties", 65536)
        installed[key] = (sha(path), hashlib.sha256(metadata).hexdigest())
    if missing:
        lane.run([manager, "--sdk_root=" + str(sdk), "--install", *missing], "install-missing-sdk-only", lane.work,
                 seconds=240, stdin=b"y\n" * 32)
        lane.foreground_drain()
    rows = {}
    for package, relative in packages.items():
        path = sdk / relative
        require(path.is_file() and path.resolve() == path, "Exact regular SDK/image input required")
        metadata = regular(path.parent / "source.properties", 65536)
        values = properties(metadata)
        pin = (sha(path), hashlib.sha256(metadata).hexdigest()) if missing or package not in installed else installed[package]
        require(package not in installed or installed[package] == pin, "SDK acquisition changed an existing package; preserve")
        rows[package] = dict(file_sha256=pin[0], metadata_sha256=pin[1], revision=values["Pkg.Revision"], acquired=package in missing)
        if package == "platforms;android-37.0":
            require(values.get("AndroidVersion.ApiLevel") == "37.0", "Only stable SDK37.0; no platform alias")
        elif package == "build-tools;36.0.0":
            require(values.get("Pkg.Revision") == "36.0.0", "Only build-tools36.0.0")
        elif package.startswith("system-images;"):
            require(all(values.get(key) == value for key, value in {"AndroidVersion.ApiLevel": "35",
                    "SystemImage.TagId": "default", "SystemImage.Abi": "x86_64"}.items()), "Only API35 default AOSP x86_64 image")
    return rows, avdmanager


def stop_gradle(lane, state, app, name, seconds=90):
    if not state.get("build_started") or state.get("gradle_stopped"):
        return
    installed = list((lane.work / "gradle/wrapper/dists/gradle-9.6.1-bin").glob("*/gradle-9.6.1/bin/gradle"))
    require(len(installed) <= 1, "Ambiguous owned Gradle distribution")
    if installed:
        require(installed[0].is_file() and installed[0].resolve() == installed[0], "Aliased owned Gradle distribution")
        lane.run([installed[0], "--gradle-user-home", lane.work / "gradle", "--stop"], name, app, seconds=seconds)
        state["gradle_stopped"] = True


def retain_ui(work, reports):
    folder = work / "ui"
    if not folder.exists():
        return None
    require(folder.is_dir() and folder.resolve() == folder, "Aliased UI output directory")
    require({f.name for f in folder.iterdir()} <= set(SHOTS + ["observations.json"]), "Unexpected UI output")
    value = json.loads(regular(folder / "observations.json", JSON_CAPS["observations.json"]))
    shots = value.get("screenshots", [])
    require(len(shots) <= 7 and len({s["file"] for s in shots}) == len(shots), "Invalid screenshot roster")
    require({f.name for f in folder.glob("*.png")} == {s["file"] for s in shots}, "Screenshot inventory differs")
    for shot in shots:
        require(shot["file"] in SHOTS, "Unexpected screenshot name")
        data = regular(folder / shot["file"], 8*MIB)
        require(data.startswith(b"\x89PNG\r\n\x1a\n") and hashlib.sha256(data).hexdigest() == shot["sha256"],
                "Screenshot hash/type mismatch")
        with (reports / shot["file"]).open("xb") as output:
            output.write(data)
    record(reports, "observations.json", value)
    return value


def publish_reports(reports, identity, payloads, result, passed, errors):
    """Only controller-owned bounded public files; a failed JSON cap cannot skip FAIL emission."""
    try:
        require(directory_identity(reports) == identity, "Public report root identity changed")
        for name, value in payloads.items():
            try:
                record(reports, name, value)
            except BaseException as error:
                errors.append(error_row("write-" + name, error))
                passed = False
        result.update(status="RESULT_REVIEW_REQUIRED" if passed else "FAIL", errors=errors)
        record(reports, "result.json", result)
        require({p.name for p in reports.iterdir()} <= PUBLIC_CAPS.keys(), "Unexpected public artifact")
        apk_rows = payloads.get("inputs.json", {}).get("verification_apks", [])
        require(isinstance(apk_rows, list) and len(apk_rows) <= 2 and
                all(set(row) == {"file", "bytes", "sha256"} for row in apk_rows), "Invalid APK retention roster")
        apks = {row["file"]: row for row in apk_rows}
        require(len(apks) == len(apk_rows) and set(apks) <= APK_CAPS.keys() and
                {p.name for p in reports.iterdir() if p.name in APK_CAPS} == set(apks), "Unbound retained APK")
        for path in reports.iterdir():
            if path.name in APK_CAPS:
                row = apks[path.name]
                require(path.is_file() and not path.is_symlink() and path.resolve() == path and
                        0 < path.stat().st_size == row["bytes"] <= APK_CAPS[path.name] and sha(path) == row["sha256"],
                        "Retained APK byte identity differs")
                continue  # Stream its hash; do not load a128MiB APK into the controller's heap.
            data = regular(path, PUBLIC_CAPS[path.name])
            if path.name.endswith(".json"):
                json.loads(data)
        # No upload on preexisting/aliased report roots or partial/oversized public files.
        with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
            output.write("public_reports=ready\n")
            if apks:
                output.write("verification_apks=ready\n")
    except BaseException:
        passed = False
    print("RESULT_REVIEW_REQUIRED" if passed else "FAIL", flush=True)
    return 0 if passed else 1
