#!/usr/bin/env python3
"""PRIVATE / UNBOUND / NOT_RUN. Existing App10 controller adapted for one shared Android job."""
import os
import sys

ADMISSION, BRANCH, SOURCE, SOURCE_TREE = "635ea460d34feb96c4c467b60440c525e26fde8da14cbf13b99b9d88d78874eb", "remediation/app-two-batch-shared-android-20260915-11", "826d7d2eecbcc8c2f834cc202d188898e41fd64f", "e0e9400ace05d8e608e6f7765cd81f64cc3719e4"
MODE = "shared-android"  # Fixed reviewed two-phase App10 adaptation, not a caller-selected controller.
if (ADMISSION == "UNBOUND" or os.environ.get("APP10_COLD_ROOT_ADMISSION") != ADMISSION or len(sys.argv) != 1 or
        os.environ.get("APP10_COLD_ROOT_MODE") != MODE):
    raise SystemExit("Separate primary admission is UNBOUND or caller arguments differ")
sys.dont_write_bytecode = True
import hashlib
import grp
import json
from pathlib import Path
import shutil
import signal
import stat
import time

SCRIPTS = Path(__file__).resolve().parent
CARRIED = {
    "app10_cold_root_support.py": "a7a9abf4c6ea59a1e2ffdc433552536f412b7c24d08d45db52935d81940aea49",
    "app10_cold_root_ui.py": "3ac6181120270aa5c9e261277fbee8dff4879a08220a7274efa05773b8b88e24",
    "app10-original-engine.init.gradle": "3322abb6e56f9c24d3cbbf4546ad32f5b81272997f2415b7aa3ee1a5f123104e",
    "app29_linux_owned_processes.py": "ac6fb4b61186c09ab06f9b4a543cb58e65dcbcdfe6ba405f6afbe1814b7fc455",
    "app10-android-observer.gradle": "eb4c3f83f52a2d9c9d23c7483d72fd9ecd3ea46d6eae30c9d6cd8a988615a806",
    "app10-android-portfolio.json": "68deead53938ee33e9050a0bff136a8913594b844e088ac97910dc188d334198",
    "app10_android_evidence.py": "fa2e23ea80d6536886d471e65c7c530630c1c6a6a9eee6ad2a9d2a33deb02cff",
}
for name, digest in CARRIED.items():
    path = SCRIPTS / name
    if not path.is_file() or path.resolve() != path or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
        raise SystemExit("Carried control identity differs; nothing may launch")
from app10_cold_root_support import (
    APK_CAPS, Lane, MIB, directory_identity, environment, error_row, owned_listener, properties,
    publish_reports, regular, require, retain_ui, sdk_inputs, sha, snapshot, stop_gradle, vacant,
)

APP_PIN = (SOURCE, SOURCE_TREE)
ENGINE_PIN = ("ed184165ebd3ee7f0d1db533cc40ca5a0868fdda", "14e46a1ead24b5757d612fd55031e440f5304661")
BASE = Path(os.environ["GITHUB_WORKSPACE"])
CONTROL, APP, ENGINE = [BASE / name for name in ("controls", "app", "engine")]
WORK = Path(os.environ["RUNNER_TEMP"]) / "app10-shared-android-work"
REPORTS = WORK.parent / "app10-shared-android-reports"
GOOGLE = APP / "app/google-services.json"
MODULES = ("", "app", "desktopApp", "core", "domain", "presentation", "ui", "platform", "data", "data/local",
           "data/remote", "data/download", "sources/legacy", "sources/contracts", "sources/engine", "sources/config", "composeApp")
OUTPUTS = [APP / name / "build" for name in MODULES] + [APP / ".gradle", APP / ".kotlin"]
OUTPUTS += [ENGINE / name for name in ("build", ".gradle", ".kotlin", "source-contract/build", "source-engine/build", "source-testkit/build")]
CONTROLS = sorted([".github/workflows/app10-cold-root.yml"] + ["scripts/" + n for n in
    (*CARRIED, "app10_cold_root.py")])
from app10_android_evidence import candidate_input, capture_phase, configure, native_apk
KVM = Path("/dev/kvm")
KVM_RULE = Path("/etc/udev/rules.d/99-kvm4all.rules")
KVM_POLICY = "github-hosted-ubuntu24-kvm-0666-owned-restore-v1"
KVM_RULE_BYTES = b'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"\n'
# Fixed privileged programs only: exclusive creation cannot clobber a preexisting rule. The first
# bounded stdout line proves the created inode even if a later write fails. No shell or caller path.
KVM_CREATE_RULE = f'''import json, os
fd = os.open("/etc/udev/rules.d/99-kvm4all.rules", os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o644)
try:
    value = os.fstat(fd)
    os.fchmod(fd, 0o644)
    print(json.dumps({{"created": [value.st_dev, value.st_ino]}}), flush=True)
    data = {KVM_RULE_BYTES!r}
    if os.write(fd, data) != len(data): raise RuntimeError("Short fixed rule write")
finally:
    os.close(fd)
'''
KVM_REMOVE_RULE = '''import hashlib, os, stat, sys
path = "/etc/udev/rules.d/99-kvm4all.rules"
value = os.lstat(path)
if not (stat.S_ISREG(value.st_mode) and value.st_uid == 0 and value.st_nlink == 1 and
        [value.st_dev, value.st_ino] == [int(sys.argv[1]), int(sys.argv[2])]): raise RuntimeError("Rule custody differs")
with open(path, "rb") as stream: data = stream.read(1025)
if len(data) > 1024 or hashlib.sha256(data).hexdigest() != sys.argv[3]: raise RuntimeError("Rule bytes differ")
os.unlink(path)
'''
KVM_RESTORE_DEVICE = '''import os, stat, sys
device, inode, rdev, group, mode, expected_group, expected_mode = map(int, sys.argv[1:])
path = "/dev/kvm"
value = os.lstat(path)
if not (stat.S_ISCHR(value.st_mode) and value.st_uid == 0 and
        (value.st_dev, value.st_ino, value.st_rdev) == (device, inode, rdev) and
        (value.st_gid, stat.S_IMODE(value.st_mode)) == (expected_group, expected_mode) and
        0 <= group < 2**32 and 0 <= mode <= 0o777): raise RuntimeError("Device custody differs")
if value.st_gid != group: os.chown(path, 0, group, follow_symlinks=False)
if stat.S_IMODE(value.st_mode) != mode: os.chmod(path, mode)
'''


def kvm_observation():
    result = dict(kind="unobserved", identity=None, owner_uid=None, group_gid=None, mode=None,
                  runner_in_group=None, readable=os.access(KVM, os.R_OK), writable=os.access(KVM, os.W_OK))
    try:
        value = KVM.lstat()
    except FileNotFoundError:
        result["kind"] = "missing"
    except OSError:
        pass  # Unknown is not missing, and neither authorizes a mutation.
    else:
        result.update(kind="character" if stat.S_ISCHR(value.st_mode) else "symlink" if stat.S_ISLNK(value.st_mode) else "other",
                      identity=[value.st_dev, value.st_ino, value.st_rdev], owner_uid=value.st_uid,
                      group_gid=value.st_gid, mode=stat.S_IMODE(value.st_mode),
                      runner_in_group=value.st_gid in [os.getgid(), *os.getgroups()])
    return result


def kvm_rule_record():
    value = KVM_RULE.lstat()
    require(stat.S_ISREG(value.st_mode) and value.st_uid == 0 and value.st_nlink == 1 and
            not value.st_mode & 0o022, "Unknown or writable KVM rule; preserve")
    data = regular(KVM_RULE, 1024)
    return dict(identity=[value.st_dev, value.st_ino], sha256=hashlib.sha256(data).hexdigest(),
                mode=stat.S_IMODE(value.st_mode), group_gid=value.st_gid), data


def kvm_command(lane, argv, name, deadline):
    require(os.environ.get("APP10_KVM_PERMISSION_POLICY") == KVM_POLICY and os.geteuid() != 0,
            "Explicit hosted permission policy and non-root controller required")
    require(min(lane.end(), deadline) - time.monotonic() >= 8, "No bounded privileged-command cleanup budget")
    # Root's own timeout can stop its privileged descendants; the unchanged Lane still observes
    # their ancestry, bounded output and final absence. No root controller/emulator, sudo -E or shell.
    return lane.run(["/usr/bin/sudo", "-n", "/usr/bin/timeout", "--signal=TERM", "--kill-after=1s", "5s", *argv],
                    name, WORK, seconds=8, cap=4096, deadline=deadline)


def prepare_kvm(lane, state, inputs):
    report, before = inputs["kvm"], inputs["kvm"]["before"]
    state["phase"] = "preflight-kvm-current"
    require(kvm_observation() == before, "KVM observation changed; no capability retry")
    if before["readable"] and before["writable"]:
        report["grant"] = "already-readable-writable"
        report["after"] = before
        return
    state["phase"] = "preflight-kvm-rule"
    state["kvm_changes"] = dict(original=before, create_attempted=False, permission_attempted=False,
                                 expected_group=grp.getgrnam("kvm").gr_gid)
    changes = state["kvm_changes"]
    deadline = min(lane.end(), time.monotonic() + 30)
    try:
        rule, data = kvm_rule_record()
    except FileNotFoundError:
        changes["create_attempted"] = True
        report["grant"] = "creating-provider-rule"
        report["cleanup"] = dict(status="pending")
        state["phase"] = "preflight-kvm-create-rule"
        output = kvm_command(lane, ["/usr/bin/python3", "-I", "-S", "-B", "-c", KVM_CREATE_RULE], "kvm-create-rule", deadline)
        rule, data = kvm_rule_record()
        require(json.loads(output.splitlines()[0])["created"] == rule["identity"], "Exclusive rule identity changed")
        report["rule"] = dict(ownership="created", **rule)
    else:
        report["rule"] = dict(ownership="preexisting-not-owned", **rule)
    require(data == KVM_RULE_BYTES, "Preexisting or created rule differs; preserve")
    changes["rule"] = rule
    changes["permission_attempted"] = True  # Restoration debt precedes every fallible permission command.
    report["grant"] = "applying-provider-rule"
    report["cleanup"] = dict(status="pending")
    state["phase"] = "preflight-kvm-reload-rules"
    kvm_command(lane, ["/usr/bin/udevadm", "control", "--reload-rules"], "kvm-reload-rules", deadline)
    state["phase"] = "preflight-kvm-apply-rule"
    # --settle waits only for this targeted trigger's events, not all unrelated udev activity.
    changes["trigger_started"] = True
    kvm_command(lane, ["/usr/bin/udevadm", "trigger", "--name-match=kvm", "--settle"], "kvm-apply-rule", deadline)
    changes["trigger_completed"] = True
    after = report["after"] = kvm_observation()
    state["phase"] = "preflight-kvm-device-after"
    require(after["kind"] == "character" and after["identity"] == before["identity"] and after["owner_uid"] == 0 and
            after["group_gid"] == changes["expected_group"] and after["mode"] == 0o666, "KVM permission target differs")
    state["phase"] = "preflight-kvm-readable"
    require(after["readable"], "KVM is not readable by the ordinary runner")
    state["phase"] = "preflight-kvm-writable"
    require(after["writable"], "KVM is not writable by the ordinary runner")
    report["grant"] = "provider-rule-applied"
    lane.foreground_drain()  # Natural phase closure; reserve the total-owned barrier for final cleanup.


def restore_kvm(lane, state, inputs):
    changes = state.get("kvm_changes")
    if changes is None or not (changes["create_attempted"] or changes["permission_attempted"]):
        return
    report, before = inputs["kvm"], changes["original"]
    receipt = report["cleanup"] = dict(status="pending", rule_removed=False, device_restored=False)
    state["phase"] = "cleanup-kvm-custody"
    require(lane.absent, "No KVM permission cleanup before current child absence")
    current = receipt["before"] = kvm_observation()
    require(current["kind"] == "character" and current["identity"] == before["identity"] and current["owner_uid"] == 0 and
            current["group_gid"] in (before["group_gid"], changes["expected_group"]) and
            current["mode"] in (before["mode"], 0o666), "KVM changed beyond our permission grant; preserve")
    deadline = min(lane.end(), time.monotonic() + 35)
    if changes["create_attempted"]:
        state["phase"] = "cleanup-kvm-created-rule"
        job = next(j for j in lane.jobs if j["row"]["name"] == "kvm-create-rule")
        lines = regular(job["path"], 4096).splitlines()
        created = json.loads(lines[0])["created"]  # No receipt means unknown ownership: preserve, fail.
        require(isinstance(created, list) and len(created) == 2 and all(type(n) is int and n >= 0 for n in created),
                "Invalid exclusive rule creation receipt")
        receipt["created_rule_identity"] = created
        rule, data = kvm_rule_record()
        require(rule["identity"] == created and rule["mode"] == 0o644 and rule["group_gid"] == 0 and
                KVM_RULE_BYTES.startswith(data), "Created rule custody changed; preserve")
        require("rule" not in changes or rule == changes["rule"], "Complete owned rule changed; preserve")
        receipt["rule"] = dict(ownership="created", **rule)
        kvm_command(lane, ["/usr/bin/python3", "-I", "-S", "-B", "-c", KVM_REMOVE_RULE,
                          *map(str, created), rule["sha256"]], "kvm-remove-created-rule", deadline)
        require(not KVM_RULE.exists() and not KVM_RULE.is_symlink(), "Created rule removal unproved")
        receipt["rule_removed"] = True
        state["phase"] = "cleanup-kvm-reload-rules"
        kvm_command(lane, ["/usr/bin/udevadm", "control", "--reload-rules"], "kvm-reload-after-rule-removal", deadline)
    else:
        rule, data = kvm_rule_record()
        require(rule == changes["rule"] and data == KVM_RULE_BYTES, "Preexisting rule changed; preserve")
        receipt["rule"] = dict(ownership="preexisting-not-owned", **rule)
    state["phase"] = "cleanup-kvm-restore-device"
    if (current["group_gid"], current["mode"]) != (before["group_gid"], before["mode"]):
        kvm_command(lane, ["/usr/bin/python3", "-I", "-S", "-B", "-c", KVM_RESTORE_DEVICE,
                          *map(str, [*before["identity"], before["group_gid"], before["mode"],
                                     current["group_gid"], current["mode"]])], "kvm-restore-device", deadline)
    state["phase"] = "cleanup-kvm-readback"
    receipt["after"] = kvm_observation()
    require(receipt["after"] == before, "Original KVM mode/group/access restoration unproved")
    receipt["targeted_events_settled"] = not changes.get("trigger_started") or changes.get("trigger_completed", False)
    require(receipt["targeted_events_settled"], "Interrupted KVM trigger has unproved event completion")
    receipt.update(status="restored", device_restored=True)


def sources(lane, state):
    allowed = list(state["outputs"]) + ([GOOGLE] if state.get("google") else [])
    return {"app": snapshot(lane, APP, APP_PIN, allowed), "engine": snapshot(lane, ENGINE, ENGINE_PIN, allowed),
            "controls": snapshot(lane, CONTROL)}


def delete_build(state):
    for path, identity in state["outputs"].items():
        require(not path.is_symlink() and (not path.exists() or directory_identity(path) == identity), "Unknown output identity")
    if state.get("google") is not None:
        pin = state["google"]
        require(GOOGLE.is_file() and GOOGLE.resolve() == GOOGLE and
                (GOOGLE.stat().st_dev, GOOGLE.stat().st_ino, sha(GOOGLE)) == pin, "Staged example changed; preserve")
    for path in list(state["outputs"]):
        if path.exists():
            shutil.rmtree(path)
        del state["outputs"][path]
    if state.get("google") is not None:
        GOOGLE.unlink()
        state["google"] = None


def adb(lane, sdk, *args, name="adb-control", accepted=(0,), seconds=20, deadline=float("inf")):
    require(owned_listener(lane, 5038), "No owned private ADB server; never auto-start a substitute")
    return lane.run([sdk / "platform-tools/adb", "-H", "127.0.0.1", "-P", "5038", "-s", "emulator-5580", *args],
                    name, WORK, accepted=accepted, seconds=seconds, deadline=deadline)


def start_device(lane, sdk, avdmanager, state):
    device = state["device_observations"] = dict(listener_ports=[], boot_completed=False, api=None,
                                                wake_completed=False, menu_completed=False)
    state["phase"] = "device-avd-create"
    require(not any((WORK / "avd").iterdir()), "Fresh owned AVD directory required")
    for port in (5037, 5038, 5580, 5581):
        vacant(port)
    lane.run([avdmanager, "create", "avd", "--name", "app10-cold-root", "--package",
              "system-images;android-35;default;x86_64", "--device", "pixel_2"], "create-fresh-avd", WORK, seconds=60, stdin=b"no\n")
    state["phase"] = "device-private-adb-launch"
    state["server"] = lane.start([sdk / "platform-tools/adb", "-L", "tcp:localhost:5038", "nodaemon", "server"],
                                  "private-adb-server", WORK, cap=MIB, background=True)
    end = min(lane.work_end, time.monotonic() + 20)
    state["phase"] = "device-private-adb-listener"
    while not owned_listener(lane, 5038):
        require(time.monotonic() < end, "Private ADB server not ready")
        lane.pump()
    device["listener_ports"] = [5038]
    end = min(lane.work_end, time.monotonic() + 180)
    state["phase"] = "device-emulator-launch"
    state["emulator"] = lane.start([sdk / "emulator/emulator", "-avd", "app10-cold-root", "-port", "5580",
        "-accel", "on", "-no-window", "-no-audio", "-no-snapshot", "-no-boot-anim", "-gpu", "swiftshader_indirect",
        "-memory", "2048", "-cores", "2"], "owned-emulator", WORK, background=True)
    def boot_adb(*words, accepted=(0,)):
        require(time.monotonic() < end, "KVM AVD boot deadline exceeded")
        return adb(lane, sdk, *words, accepted=accepted, deadline=end)
    state["phase"] = "device-boot"
    while True:
        require(time.monotonic() < end, "KVM AVD boot deadline exceeded")
        if owned_listener(lane, 5580) and owned_listener(lane, 5581):
            device["listener_ports"] = [5038, 5580, 5581]
            if boot_adb("shell", "getprop", "sys.boot_completed", accepted=(0, 1)).strip() == b"1":
                device["boot_completed"] = True
                break
        lane.pump()
        time.sleep(min(0.5, max(0, end - time.monotonic())))
    state["phase"] = "device-api"
    require(boot_adb("shell", "getprop", "ro.build.version.sdk").strip() == b"35", "Unexpected device API")
    device["api"] = 35
    state["phase"] = "device-wake"
    boot_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    device["wake_completed"] = True
    state["phase"] = "device-menu"
    boot_adb("shell", "input", "keyevent", "KEYCODE_MENU")
    device["menu_completed"] = True


def write_compile_binding(lane, portfolio, avif, phase):
    common = []
    for module in ("source-contract", "source-engine", "source-testkit"):
        for path in sorted((ENGINE / module / "src/commonMain").rglob("*.kt")):
            require(path.resolve() == path, "Aliased original Engine source")
            common.append(dict(path=str(path.relative_to(ENGINE)), sha256=sha(path)))
    binding = dict(status="PRIMARY_ADMITTED", contract=portfolio["contract"], portfolio_sha256=sha(SCRIPTS / "app10-android-portfolio.json"),
        app=dict(repo="app", head=SOURCE, tree=SOURCE_TREE, carrier=os.environ["GITHUB_SHA"]),
        engine=dict(source_path="engine", commit=ENGINE_PIN[0], tree=ENGINE_PIN[1], common_main=common),
        tests=portfolio["tests"], device=portfolio["device"], phase=phase, max_heap="1g", avif=avif)
    data = (json.dumps(binding) + "\n").encode()
    require(len(data) <= 65536, "Runtime binding exceeds64KiB")
    path = WORK / (phase + "-binding.json")
    with path.open("xb") as output:
        output.write(data)
    lane.env.update(KIRA_ORIGINAL_ENGINE_BINDING=str(path), KIRA_ORIGINAL_ENGINE_BINDING_SHA256=sha(path),
                    KIRA_ORIGINAL_ENGINE_WORKSPACE=str(BASE))
    return sha(path)


def gradle_batch(lane, state, inputs, portfolio, avif, device=False):
    phase = "device" if device else "host-apk"
    roots = [portfolio["device"]["task"], "--serial", "emulator-5580"] if device else []
    if not device:
        for spec in portfolio["tests"]:
            roots.append(spec["task"])
            for klass in spec["classes"]:
                roots += ["--tests", klass]
        roots += [":app:assembleDebug"]
    inputs[phase + "_binding_sha256"] = write_compile_binding(lane, portfolio, avif, phase)
    args = ["./gradlew", *roots, "--no-daemon", "--no-parallel", "--max-workers=1", "--no-build-cache",
        "--no-configuration-cache", "--console=plain", "--stacktrace", "--project-cache-dir", WORK / "project-cache",
        "-Pkotlin.compiler.execution.strategy=in-process", "-PkiraUseMavenLocal=false",
        "-Porg.gradle.java.installations.auto-download=false", "-Pandroid.builder.sdkDownload=false", "-Dorg.gradle.vfs.watch=false",
        f"-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g -XX:ActiveProcessorCount=2 -Djava.io.tmpdir={WORK / 'tmp'}",
        "-I", SCRIPTS / "app10-original-engine.init.gradle"]
    if device:
        args += ["-Pandroid.testInstrumentationRunnerArguments.class=" + ",".join(portfolio["device"]["classes"])]
    seconds = 900 if device else 1800
    state["phase"] = phase + "-ordinary-gradle"
    state["build_started"], state["gradle_stopped"] = True, False
    lane.connected_phase = device
    try:
        state[phase + "_job"] = lane.start(args, phase, APP, cap=16*MIB)
        lane.wait(state[phase + "_job"], seconds, drain=False)
        state[phase + "_command_ok"] = True
    except BaseException as error:
        state.setdefault("batch_errors", []).append(error_row(phase + "-ordinary-gradle", error))
        raise
    finally:
        work_phase = state["phase"]
        try:
            state["phase"] = phase + "-immediate-gradle-stop"
            stop_gradle(lane, state, APP, "stop-gradle-immediate-" + phase, seconds=90)
            state[phase + "_stop_ok"] = state.get("gradle_stopped", False)
            require(state[phase + "_stop_ok"], "Successful immediate owned Gradle stop required after each batch")
            state["phase"] = work_phase  # A successful stop must not relabel a prior build failure.
        except BaseException as error:
            state.setdefault("batch_errors", []).append(error_row(phase + "-immediate-gradle-stop", error))
            raise
        finally:
            lane.connected_phase = False
    lane.foreground_drain()
    if device:
        require(lane.endpoint["private_connections"], "No actual owned connected-Gradle connection to private5038 observed")
    state["phase"] = phase + "-capture-evidence"
    started = time.monotonic()
    state[phase + "_capture_attempted"] = True
    state[phase + "_evidence_ok"] = capture_phase(state[phase + "_job"]["path"], device)
    require(state[phase + "_evidence_ok"] and time.monotonic() - started <= 120, "Complete bounded actual Android evidence required")


def retire_host_outputs(state, inputs):
    # Connected consumers keep core/platform compile outputs and the same owned Gradle caches.
    # Other build payloads (incl both staged Robolectric/SQLite copies and original Engine outputs)
    # are disposed now. Empty inode-owned roots remain to account for ordinary configuration writes.
    keep = {APP / "core/build", APP / "platform/build"}
    retired = []
    for path, identity in state["outputs"].items():
        if path.name != "build" or path in keep:
            continue
        require(directory_identity(path) == identity, "Host output custody changed; preserve")
        for child in path.iterdir():
            if child.is_dir() and not child.is_symlink():
                shutil.rmtree(child)
            else:
                child.unlink()
        retired.append(str(path.relative_to(BASE)))
    if state.get("google") is not None:
        pin = state["google"]
        require(GOOGLE.is_file() and GOOGLE.resolve() == GOOGLE and
                (GOOGLE.stat().st_dev, GOOGLE.stat().st_ino, sha(GOOGLE)) == pin, "Staged example changed; preserve")
        GOOGLE.unlink()
        state["google"] = None
    inputs["host_payloads_disposed_after_last_consumer"] = retired
    inputs["retained_until_connected"] = ["app/core/build", "app/platform/build", "owned Gradle normal dependency/DSL/project caches"]


def retain_verification_apks(state, inputs, deadline):
    # Called only after source equality, total child absence and ownership restoration.
    # Preserve exact ordinary inputs, not build caches, signing material or a reuse protocol.
    candidates = [(WORK / "app-debug.apk", "app-debug.apk", inputs["apk"])] if "apk" in inputs else []
    device_report = REPORTS / "device-provenance.json"
    if device_report.exists():
        rows = json.loads(regular(device_report, 8*MIB))["records"].get("DEVICE_RUNTIME", [])
        require(len(rows) <= 1, "Duplicate device APK input")
        if rows:
            row, path = rows[0], Path(rows[0]["apk"]["path"])
            require(row["task"] == ":platform:connectedAndroidDeviceTest" and row["libraryType"] is True and
                    row["applicationId"] != "me.manga.kira" and row["targetPackage"] != "me.manga.kira" and
                    APP / "platform/build/outputs" in path.parents and
                    directory_identity(APP / "platform/build") == state["outputs"][APP / "platform/build"],
                    "Only the observed ordinary library-test APK may be retained")
            candidates.append((path, "platform-device-test.apk", row["apk"]))
    retained = inputs.setdefault("verification_apks", [])
    require(not retained, "Verification APK capture is once only")
    for path, name, pin in candidates:
        require(path.is_file() and not path.is_symlink() and path.resolve() == path and
                type(pin["bytes"]) is int and 0 < path.stat().st_size == pin["bytes"] <= APK_CAPS[name],
                "Exact bounded ordinary APK required")
        staged = WORK / ("retain-" + name)
        with path.open("rb") as source, staged.open("xb") as target:
            remaining = pin["bytes"]
            while remaining:
                require(time.monotonic() < deadline, "APK retention cleanup deadline exceeded")
                chunk = source.read(min(MIB, remaining))
                require(chunk and target.write(chunk) == len(chunk), "Incomplete APK copy")
                remaining -= len(chunk)
            require(source.read(1) == b"", "APK grew during retention")
        require(sha(staged) == pin["sha256"] and time.monotonic() < deadline, "Retained APK hash/deadline differs")
        os.link(staged, REPORTS / name, follow_symlinks=False)  # Complete, exclusive publication only.
        retained.append(dict(file=name, bytes=pin["bytes"], sha256=pin["sha256"]))
        staged.unlink()  # Roster first: a fallible unlink cannot orphan a published complete APK.


def execute(lane, sdk, state, inputs):
    state["phase"] = "source-and-carrier"
    inputs["before"] = sources(lane, state)
    require(inputs["before"]["controls"]["head"] == os.environ["GITHUB_SHA"], "Carrier/event mismatch")
    prefix = ["git", "--no-optional-locks", "-C", CONTROL]
    require(lane.run(prefix + ["rev-parse", "HEAD^@"], "carrier-parent", CONTROL).decode().strip() == SOURCE,
            "Control carrier must directly extend the accepted App source")
    changed = lane.run(prefix + ["diff", "--name-status", SOURCE, "HEAD"], "carrier-diff", CONTROL).decode().splitlines()
    require(changed == ["A\t" + p for p in CONTROLS], "Carrier must add exactly the nine reviewed controls")
    inputs["control_sha256"] = {name: sha(CONTROL / name) for name in CONTROLS}
    portfolio = json.loads(regular(SCRIPTS / "app10-android-portfolio.json", 65536))
    require(portfolio["status"] == "PRIMARY_BOUND_FOR_REVIEW" and portfolio["source"] == dict(head=SOURCE, tree=SOURCE_TREE) and
            portfolio["branch"] == BRANCH, "Separate final portfolio/source admission required")
    configure(APP, ENGINE, REPORTS, portfolio)
    state["portfolio_ready"] = True
    avif = candidate_input(APP)
    inputs["candidate_aar_sha256"], inputs["budgets"] = avif["aar"]["sha256"], portfolio["budgets"]
    inputs["carry_scope"] = portfolio["carry"]
    prepare_kvm(lane, state, inputs)
    state["phase"] = "sdk"
    inputs["sdk"], avdmanager = sdk_inputs(lane, sdk)
    require(shutil.disk_usage(WORK).free >= 8 * 1024**3, "8GiB floor required before shared host/APK batch")
    for path in OUTPUTS:
        path.mkdir(mode=0o700)
        state["outputs"][path] = directory_identity(path)
    example = regular(APP / "app/google-services.json.example", 65536)
    with GOOGLE.open("xb") as output:
        value = os.fstat(output.fileno())
        state["google"] = (value.st_dev, value.st_ino, hashlib.sha256(example).hexdigest())
        output.write(example)
    require(sha(GOOGLE) == state["google"][2], "Staged public example differs; preserve")
    inputs["google_example_sha256"] = state["google"][2]
    gradle_batch(lane, state, inputs, portfolio, avif)
    state["phase"] = "host-source-readback"
    require(sources(lane, state) == inputs["before"], "Host batch changed source/control inputs")
    lane.foreground_drain()  # No retained services yet; still not the final total-owned barrier.
    state["phase"] = "exact-apk"
    output = APP / "app/build/outputs/apk/debug/app-debug.apk"
    require(list(output.parent.glob("*.apk")) == [output] and output.is_file() and output.resolve() == output and
            output.stat().st_size <= 512*MIB, "One bounded ordinary debug APK required")
    apk = WORK / "app-debug.apk"
    with output.open("rb") as source, apk.open("xb") as target:
        shutil.copyfileobj(source, target, MIB)
    require(sha(output) == sha(apk), "Exact APK staging changed bytes")
    inputs["apk"] = dict(sha256=sha(apk), bytes=apk.stat().st_size, build_root=":app:assembleDebug")
    native_apk(apk, avif)
    require(directory_identity(WORK) == state["work_identity"], "Private work root changed; preserve")
    retire_host_outputs(state, inputs)
    require(shutil.disk_usage(WORK).free >= 8 * 1024**3, "8GiB floor required before AVD")
    state["phase"] = "fresh-kvm-avd"
    start_device(lane, sdk, avdmanager, state)
    lane.services = [state["server"], state["emulator"]]
    binding = dict(status="PRIMARY_ADMITTED", source_commit=SOURCE, source_tree=SOURCE_TREE,
                   engine_commit=ENGINE_PIN[0], engine_tree=ENGINE_PIN[1], apk_sha256=inputs["apk"]["sha256"])
    path = WORK / "runtime-binding.json"
    with path.open("x") as target:
        target.write(json.dumps(binding) + "\n")
    inputs["runtime_binding_sha256"] = sha(path)
    state["phase"] = "real-ui-before-device-tests"
    ui_end = min(lane.end(), time.monotonic() + 420)
    require(time.monotonic() < ui_end, "No command phase budget")
    ui_job = lane.start([sys.executable, "-B", SCRIPTS / "app10_cold_root_ui.py", "--adb", sdk / "platform-tools/adb",
              "--apk", apk, "--binding", path, "--out", WORK / "ui"], "real-ui-traversal", CONTROL, cap=65536)
    lane.wait(ui_job, 420, accepted=(0, 1), deadline=ui_end)
    lane.foreground_drain()
    state["observation"] = retain_ui(WORK, REPORTS)
    require(state["observation"] is not None and
            state["observation"]["status"] == {0: "OBSERVED_PASS", 1: "UI_EXPECTATION_FAILED"}[ui_job["row"]["exit"]] and
            (ui_job["row"]["exit"] == 0 or state["observation"].get("failure", {}).get("type") == "UiExpectationFailure"),
            "Only a settled pass or recognized UI-only failure may precede device tests")
    state["ui_completed"] = ui_job["row"]["exit"] == 0
    if not state["ui_completed"]:
        state["ui_failure"] = dict(stage="real-ui-before-device-tests", type="ObservedUiFailure")
    require(sha(apk) == inputs["apk"]["sha256"], "Staged ordinary APK changed during UI")
    # Keep this exact input for final bounded retention, even if UI failed; never rebuild it here.
    adb(lane, sdk, "shell", "am", "force-stop", "--user", "0", "me.manga.kira", name="stop-app-after-ui")
    state["phase"] = "connected-preflight"
    devices = adb(lane, sdk, "devices", name="owned-device-roster").decode().strip().splitlines()
    require(len(devices) == 2 and devices[0] == "List of devices attached" and devices[1].split() == ["emulator-5580", "device"],
            "Only the one fresh owned online device")
    inputs["device_runtime"] = dict(api=35, abi=adb(lane, sdk, "shell", "getprop", "ro.product.cpu.abi").decode().strip(),
        page_size=adb(lane, sdk, "shell", "getconf", "PAGESIZE").decode().strip(), serial="emulator-5580", adb_port=5038)
    require(inputs["device_runtime"]["abi"] == "x86_64", "Only the admitted x86_64 runtime cohort")
    require(shutil.disk_usage(WORK).free >= 8 * 1024**3, "8GiB floor required before connected task")
    gradle_batch(lane, state, inputs, portfolio, avif, device=True)
    state["phase"] = "connected-native-readback"
    rows = json.loads(regular(REPORTS / "device-provenance.json", 8*MIB))["records"]["DEVICE_RUNTIME"]
    require(len(rows) == 1, "One actual connected APK input required")
    actual = rows[0]["apk"]
    device_apk = Path(actual["path"])
    require(APP / "platform/build" in device_apk.parents and sha(device_apk) == actual["sha256"] and
            device_apk.stat().st_size == actual["bytes"], "Actual pre-install device APK bytes changed")
    native_apk(device_apk, avif, device=True)
    require(sources(lane, state) == inputs["before"], "Connected task changed source/control inputs")
    lane.foreground_drain()
    # Final owned cleanup retains the exact APK inputs before removing these build roots.
    for name in ("gradle", "konan", "project-cache"):
        shutil.rmtree(WORK / name)
    state["device_completed"] = True
    inputs["connected_endpoint"] = lane.endpoint

def main():
    started = time.monotonic()
    require(all(len(p) == 40 and all(c in "0123456789abcdef" for c in p) for p in APP_PIN) and BRANCH not in ("UNBOUND", "main", "testing", "release", "internal-testing"),
            "Separate primary binding of a nonrelease scoped ref required")
    for key, value in {"GITHUB_ACTIONS": "true", "GITHUB_EVENT_NAME": "push", "GITHUB_REPOSITORY": "kira-manga/kira-app",
                       "GITHUB_REF": "refs/heads/" + BRANCH, "GITHUB_RUN_ATTEMPT": "1", "RUNNER_OS": "Linux", "RUNNER_ARCH": "X64",
                       "RUNNER_ENVIRONMENT": "github-hosted"}.items():
        require(os.environ.get(key) == value, "Unexpected GitHub execution context")
    require(json.loads(regular(Path(os.environ["GITHUB_EVENT_PATH"]), MIB))["repository"]["private"] is False,
            "Only the admitted public repository")
    require(SCRIPTS.parent == CONTROL == Path.cwd() and BASE.resolve() == BASE and WORK.parent.resolve() == WORK.parent and
            BASE not in WORK.parents and all(p.resolve() == p for p in (APP, ENGINE, CONTROL)), "Unexpected source/work paths")
    require(all(not p.exists() and not p.is_symlink() for p in OUTPUTS + [GOOGLE, WORK, REPORTS]), "Preserve preexisting inputs/outputs")
    os.umask(0o077)
    lane, sdk, work_id, report_id = None, None, None, None
    state, errors = {"outputs": {}, "phase": "acquire-and-preflight"}, []
    inputs = dict(source=APP_PIN, engine=ENGINE_PIN, carrier=os.environ["GITHUB_SHA"], ref=BRANCH,
                  run_id=os.environ["GITHUB_RUN_ID"], attempt=1, admission=ADMISSION, mode=MODE,
                  assembly="NOT_STARTED")
    same, restored, observation = False, None, None
    try:
        WORK.mkdir(mode=0o700)
        work_id = directory_identity(WORK)
        state["work_identity"] = work_id
        REPORTS.mkdir(mode=0o700)
        report_id = directory_identity(REPORTS)
        lane = Lane(WORK, started, work_seconds=4500)
        lane.owner.activate()  # Protected activation, before any child or later preflight failure.
        # Fixed stage codes only; error_row retains no exception text or environment/file values.
        state["phase"] = "preflight-java-home"
        java = Path(os.environ["JAVA_HOME_21_X64"]).resolve()
        state["phase"] = "preflight-sdk-home"
        sdk = Path(os.environ["ANDROID_HOME"]).resolve()
        state["phase"] = "preflight-environment"
        lane.env = environment(WORK, APP, ENGINE, java, sdk)
        state["phase"] = "preflight-os-file"
        os_release = regular(Path("/etc/os-release").resolve(), 65536)
        state["phase"] = "preflight-os-parse"
        release = properties(os_release)
        state["phase"] = "preflight-os-match"
        require(release.get("ID") == "ubuntu" and release.get("VERSION_ID") == '"24.04"', "Only Ubuntu24.04")
        state["phase"] = "preflight-jdk-file"
        jdk_release = regular(java / "release", 65536)
        state["phase"] = "preflight-jdk-match"
        require(b'JAVA_VERSION="21.' in jdk_release, "Installed JDK21 required")
        state["phase"] = "preflight-kvm-observe"
        before_kvm = kvm_observation()
        inputs["kvm"] = dict(policy=KVM_POLICY, before=before_kvm, grant="not-attempted", cleanup=dict(status="not-needed"))
        state["phase"] = "preflight-kvm-device"
        require(before_kvm["kind"] == "character" and before_kvm["identity"][2] == os.makedev(10, 232),
                "Existing real KVM character node required; no creation, install or fallback")
        state["phase"] = "preflight-kvm-owner"
        require(before_kvm["owner_uid"] == 0 and before_kvm["mode"] <= 0o777 and os.getuid() == os.geteuid() != 0,
                "Known root-owned device and ordinary non-root runner required")
        state["phase"] = "preflight-kvm-policy"
        require(os.environ.get("APP10_KVM_PERMISSION_POLICY") == KVM_POLICY, "Exact primary-admitted hosted KVM policy required")
        state["phase"] = "preflight-disk"
        require(shutil.disk_usage(WORK).free >= 16 * 1024**3, "Initial16GiB hosted admission floor required")
        state["phase"] = "preflight-jdk-release-digest"
        jdk_release_sha256 = sha(java / "release")
        state["phase"] = "preflight-java-binary-digest"
        inputs["jdk"] = dict(release_sha256=jdk_release_sha256, java_sha256=sha(java / "bin/java"))
        execute(lane, sdk, state, inputs)
    except BaseException as error:
        errors.extend(state.get("batch_errors", []))
        row = error_row(state["phase"], error)
        if row not in errors:
            errors.append(row)
    finally:
        if state.get("ui_failure") is not None:
            errors.append(state["ui_failure"])  # Independent device evidence never clears a UI failure.
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        if lane is not None:
            lane.cleanup_end = min(lane.total_end, time.monotonic() + 150)
            cleanup(lane, sdk, state, errors, inputs)
        if lane is not None and lane.absent:
            try:
                require(directory_identity(REPORTS) == report_id, "Public report root changed; preserve")
                observation = state.get("observation")
                if observation is None:
                    observation = retain_ui(WORK, REPORTS)
            except BaseException as error:
                errors.append(error_row("capture-ui", error))
            for phase, device in (("host-apk", False), ("device", True)):
                try:
                    require(directory_identity(REPORTS) == report_id, "Public report root changed; preserve")
                    job = state.get(phase + "_job")
                    if job is not None and not state.get(phase + "_capture_attempted") and state.get("portfolio_ready"):
                        state[phase + "_capture_attempted"] = True
                        state[phase + "_evidence_ok"] = capture_phase(job["path"], device)
                except BaseException as error:
                    errors.append(error_row("capture-" + phase, error))
            try:
                require(directory_identity(REPORTS) == report_id, "Public report root changed; preserve")
                matches = [(index, job) for index, job in enumerate(lane.jobs)
                           if job["row"]["name"] == "private-adb-server"]
                require(len(matches) <= 1, "Duplicate private ADB log owners")
                if matches:
                    index, server = matches[0]
                    row, path = server["row"], server["path"]
                    require(directory_identity(WORK) == work_id and path == WORK / "logs" / f"{index}.log" and
                            server["eof"] and server["output"].closed, "Private ADB log custody/drain differs")
                    value = path.lstat()
                    require(stat.S_ISREG(value.st_mode) and value.st_nlink == 1 and value.st_uid == os.geteuid() and
                            stat.S_IMODE(value.st_mode) == 0o600 and row["cap"] == MIB and not row["overflow"],
                            "Private ADB log identity/cap differs")
                    data = regular(path, MIB)
                    require(len(data) == row["bytes"] and hashlib.sha256(data).hexdigest() == row["sha256"],
                            "Private ADB log bytes differ from the drained command row")
                    with (REPORTS / "adb-server.log").open("xb") as target:
                        target.write(data)
                    require(sha(REPORTS / "adb-server.log") == row["sha256"], "Retained private ADB log differs")
                    row["public_log"] = "adb-server.log"
            except BaseException as error:
                errors.append(error_row("capture-adb-server-log", error))
            if "before" in inputs:
                try:
                    inputs["after"] = sources(lane, state)
                    same = inputs["before"] == inputs["after"]
                except BaseException as error:
                    errors.append(error_row("source-readback", error))
                finally:
                    try:
                        lane.barrier()  # Git readback children invalidate the earlier cleanup receipt.
                    except BaseException as error:
                        errors.append(error_row("post-readback-barrier", error))
        try:
            restored = lane.owner.restore() if lane is not None else {"active": False, "never_created": True}
        except BaseException as error:
            errors.append(error_row("restore", error))
        no_children = restored is not None and not restored.get("active", True) and (lane is None or lane.absent or not lane.jobs)
        if work_id is not None and no_children and (same or not state["outputs"] and not state.get("google")):
            try:
                require(directory_identity(WORK) == work_id, "Private work root changed; preserve")
                if same:
                    try:
                        require(directory_identity(REPORTS) == report_id, "Public report root changed; preserve")
                        retain_verification_apks(state, inputs, lane.cleanup_end)
                    except BaseException as error:
                        errors.append(error_row("retain-verification-apks", error))
                delete_build(state)
                shutil.rmtree(WORK)
            except BaseException as error:
                errors.append(error_row("owned-files", error))
        if lane is not None:
            lane.poller.close()
    clean = not WORK.exists() and all(not p.exists() and not p.is_symlink() for p in OUTPUTS + [GOOGLE])
    rows, barriers = (lane.rows, lane.barriers) if lane is not None else ([], [])
    clean &= no_children and all(b["absent"] and not b["forced"] and not b["errors"] for b in barriers)
    clean &= time.monotonic() <= (lane.cleanup_end if lane is not None else started + 150)
    clean &= not any(r["overflow"] for r in rows)
    clean &= inputs.get("kvm", {}).get("cleanup", {}).get("status", "not-needed") in ("not-needed", "restored")
    observed = state.get("ui_completed", False) and observation is not None and observation.get("status") == "OBSERVED_PASS"
    validation_ok = observed and state.get("device_completed") and all(state.get(phase + suffix, False)
        for phase in ("host-apk", "device") for suffix in ("_command_ok", "_stop_ok", "_evidence_ok"))
    passed = same and clean and validation_ok and not errors
    inputs["assembly"] = "SUCCEEDED" if state.get("host-apk_command_ok") else "FAILED_OR_NOT_STARTED"
    inputs["device_observations"] = state.get("device_observations", {})
    inputs["operation_notes"] = lane.operation_notes if lane is not None else dict(events=[], omitted=0)
    inputs["phase_stops"] = {phase: state.get(phase + "_stop_ok", False) for phase in ("host-apk", "device")}
    inputs["connected_endpoint"] = lane.endpoint if lane is not None else {}
    payloads = {"inputs.json": inputs, "commands.json": rows,
                "cleanup.json": dict(barriers=barriers, foreground_phase_receipts=lane.phase_receipts if lane else [],
                                     restored=restored, clean=clean, errors=errors)}
    scope = "No host tests (6 qualified +31 raw prior passes carried, not re-executed) / one ordinary APK / existing fresh App10 UI first / ordinary native-device9; no Linux41, carry rerun, API26/27, other-ABI runtime,16KiB device or release closure"
    return publish_reports(REPORTS, report_id, payloads, dict(source_unchanged=same, cleanup=clean, mode=MODE,
        ui_observed_pass=observed, device_completed=state.get("device_completed", False), scope=scope), passed, errors)


def cleanup(lane, sdk, state, errors, inputs):
    if not lane.owner.active:
        return
    for phase in ("gradle", "emulator", "server"):
        try:
            if phase == "gradle":
                stop_gradle(lane, state, APP, "stop-gradle-final", seconds=20)
            elif state.get(phase) is not None:
                if state[phase]["process"].poll() is not None:
                    errors.append(dict(stage="unexpected-" + phase + "-exit"))
                elif phase == "emulator" and owned_listener(lane, 5580) and owned_listener(lane, 5581):
                    adb(lane, sdk, "emu", "kill", name="stop-owned-emulator", seconds=10)
                elif phase == "server" and owned_listener(lane, 5038):
                    adb(lane, sdk, "kill-server", name="stop-private-adb", seconds=10)
                lane.wait(state[phase], 10)
        except BaseException as error:
            errors.append(error_row("cleanup-" + phase, error))
    try:
        lane.barrier()
    except BaseException as error:
        errors.append(error_row("final-barrier", error))
    try:
        restore_kvm(lane, state, inputs)
    except BaseException as error:
        inputs["kvm"]["cleanup"]["status"] = "failed"
        errors.append(error_row(state["phase"], error))
    finally:
        if state.get("kvm_changes") is not None:
            try:
                lane.barrier()  # Privileged cleanup invalidates the earlier child-absence receipt.
            except BaseException as error:
                errors.append(error_row("post-kvm-cleanup-barrier", error))


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, signal.default_int_handler)
    signal.signal(signal.SIGINT, signal.default_int_handler)
    raise SystemExit(main())
