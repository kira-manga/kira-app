#!/usr/bin/env python3
"""UNBOUND App10 exact debug APK / one fresh KVM AVD; independent result review required."""
import os
import sys

ADMISSION, BRANCH, SOURCE, SOURCE_TREE = "kira-app10-cold-root-admitted-20260914-02", "remediation/app-10-cold-root-validation-02", "3bf84b408828cd370c30b72ecf9014d2763c3f5a", "5260345586754b1c69ea2172044e0f0d1149bc6f"
if ADMISSION == "UNBOUND" or os.environ.get("APP10_COLD_ROOT_ADMISSION") != ADMISSION or len(sys.argv) != 1:
    raise SystemExit("Separate primary admission is UNBOUND or caller arguments differ")
sys.dont_write_bytecode = True
import hashlib
import json
from pathlib import Path
import shutil
import signal
import time

SCRIPTS = Path(__file__).resolve().parent
CARRIED = {"app10_cold_root_ui.py": "9c8da1b1ee53f749ea4b0d334d9c731f04416ae56544213989bdcbf965ca10a2",
           "app10-original-engine.init.gradle": "ad683d459ad2cd8c37cbfd816c969aad7bba58f005e9d39329cf7ede0d1b0962",
           "app29_linux_owned_processes.py": "ac6fb4b61186c09ab06f9b4a543cb58e65dcbcdfe6ba405f6afbe1814b7fc455"}
for name, digest in CARRIED.items():
    path = SCRIPTS / name
    if not path.is_file() or path.resolve() != path or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
        raise SystemExit("Carried control identity differs; nothing may launch")
from app10_cold_root_support import (
    Lane, MIB, directory_identity, environment, error_row, owned_listener, properties,
    publish_reports, regular, require, retain_ui, sdk_inputs, sha, snapshot, stop_gradle, vacant,
)

APP_PIN = ("3bf84b408828cd370c30b72ecf9014d2763c3f5a", "5260345586754b1c69ea2172044e0f0d1149bc6f")
ENGINE_PIN = ("ed184165ebd3ee7f0d1db533cc40ca5a0868fdda", "14e46a1ead24b5757d612fd55031e440f5304661")
BASE = Path(os.environ["GITHUB_WORKSPACE"])
CONTROL, APP, ENGINE = [BASE / name for name in ("controls", "app", "engine")]
WORK = Path(os.environ["RUNNER_TEMP"]) / "app10-cold-root-work"
REPORTS = WORK.parent / "app10-cold-root-reports"
GOOGLE = APP / "app/google-services.json"
MODULES = ("", "app", "desktopApp", "core", "domain", "presentation", "ui", "platform", "data", "data/local",
           "data/remote", "data/download", "sources/legacy", "sources/contracts", "sources/engine", "sources/config", "composeApp")
OUTPUTS = [APP / name / "build" for name in MODULES] + [APP / ".gradle", APP / ".kotlin"]
OUTPUTS += [ENGINE / name for name in ("build", ".gradle", ".kotlin", "source-contract/build", "source-engine/build", "source-testkit/build")]
CONTROLS = sorted([".github/workflows/app10-cold-root.yml"] + ["scripts/" + n for n in
    (*CARRIED, "app10_cold_root.py", "app10_cold_root_support.py")])


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
    require(not any((WORK / "avd").iterdir()), "Fresh owned AVD directory required")
    for port in (5038, 5580, 5581):
        vacant(port)
    lane.run([avdmanager, "create", "avd", "--name", "app10-cold-root", "--package",
              "system-images;android-35;default;x86_64", "--device", "pixel_2"], "create-fresh-avd", WORK, seconds=60, stdin=b"no\n")
    state["server"] = lane.start([sdk / "platform-tools/adb", "-L", "tcp:127.0.0.1:5038", "nodaemon", "server"],
                                  "private-adb-server", WORK, background=True)
    end = min(lane.work_end, time.monotonic() + 20)
    while not owned_listener(lane, 5038):
        require(time.monotonic() < end, "Private ADB server not ready")
        lane.pump()
    end = min(lane.work_end, time.monotonic() + 180)
    state["emulator"] = lane.start([sdk / "emulator/emulator", "-avd", "app10-cold-root", "-port", "5580",
        "-accel", "on", "-no-window", "-no-audio", "-no-snapshot", "-no-boot-anim", "-gpu", "swiftshader_indirect",
        "-memory", "2048", "-cores", "2"], "owned-emulator", WORK, background=True)
    def boot_adb(*words, accepted=(0,)):
        require(time.monotonic() < end, "KVM AVD boot deadline exceeded")
        return adb(lane, sdk, *words, accepted=accepted, deadline=end)
    while True:
        require(time.monotonic() < end, "KVM AVD boot deadline exceeded")
        if owned_listener(lane, 5580) and owned_listener(lane, 5581):
            if boot_adb("shell", "getprop", "sys.boot_completed", accepted=(0, 1)).strip() == b"1":
                break
        lane.pump()
        time.sleep(min(0.5, max(0, end - time.monotonic())))
    require(boot_adb("shell", "getprop", "ro.build.version.sdk").strip() == b"35", "Unexpected device API")
    boot_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    boot_adb("shell", "input", "keyevent", "KEYCODE_MENU")


def execute(lane, sdk, state, inputs):
    state["phase"] = "source-and-carrier"
    inputs["before"] = sources(lane, state)
    require(inputs["before"]["controls"]["head"] == os.environ["GITHUB_SHA"], "Carrier/event mismatch")
    prefix = ["git", "--no-optional-locks", "-C", CONTROL]
    require(lane.run(prefix + ["rev-parse", "HEAD^@"], "carrier-parent", CONTROL).decode().strip() == SOURCE,
            "Control carrier must directly extend the accepted App source")
    changed = lane.run(prefix + ["diff", "--name-status", SOURCE, "HEAD"], "carrier-diff", CONTROL).decode().splitlines()
    require(changed == ["A\t" + p for p in CONTROLS], "Carrier must add exactly six reviewed controls")
    inputs["control_sha256"] = {name: sha(CONTROL / name) for name in CONTROLS}
    state["phase"] = "sdk"
    inputs["sdk"], avdmanager = sdk_inputs(lane, sdk)
    require(shutil.disk_usage(WORK).free >= 8 * 1024**3, "8GiB floor required before assembly")
    state["phase"] = "assembly"
    for path in OUTPUTS:
        path.mkdir(mode=0o700)  # Exact, initially absent roots; no ownership inferred from a later directory.
        state["outputs"][path] = directory_identity(path)
    example = regular(APP / "app/google-services.json.example", 65536)
    with GOOGLE.open("xb") as output:
        value = os.fstat(output.fileno())
        state["google"] = (value.st_dev, value.st_ino, hashlib.sha256(example).hexdigest())
        output.write(example)
    require(sha(GOOGLE) == state["google"][2], "Staged public example differs; preserve")
    inputs["google_example_sha256"] = state["google"][2]
    args = ["./gradlew", ":app:assembleDebug", "--no-daemon", "--no-parallel", "--max-workers=1", "--no-build-cache",
        "--no-configuration-cache", "--console=plain", "--stacktrace", "--project-cache-dir", WORK / "project-cache",
        "-Pkotlin.compiler.execution.strategy=in-process", "-PkiraUseMavenLocal=false",
        "-Porg.gradle.java.installations.auto-download=false", "-Pandroid.builder.sdkDownload=false", "-Dorg.gradle.vfs.watch=false",
        f"-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g -XX:ActiveProcessorCount=2 -Djava.io.tmpdir={WORK / 'tmp'}",
        "-I", SCRIPTS / "app10-original-engine.init.gradle"]
    try:
        state["build_started"] = True
        end = min(lane.work_end, time.monotonic() + 1200)
        state["build"] = lane.start(args, "assembleDebug", APP, cap=8*MIB)
        lane.wait(state["build"], 1200, drain=False, deadline=end)  # Stop at wrapper exit, not delayed descendant pipe EOF.
        state["assembly_ok"] = True
    finally:
        stop_gradle(lane, state, APP, "stop-gradle-immediate")
        state["immediate_gradle_stop_ok"] = state.get("gradle_stopped", False)
    require(state.get("immediate_gradle_stop_ok"), "Successful immediate owned Gradle stop required")
    receipt = lane.barrier()
    require(receipt["absent"] and not receipt["forced"] and not receipt["errors"], "Assembly ownership did not close naturally")
    require(sources(lane, state) == inputs["before"], "Assembly changed source/control inputs")
    require(lane.barrier()["absent"] and not lane.owner.signals and not any(r["overflow"] for r in lane.rows),
            "Current natural absence and bounded output required before APK/caches")
    state["phase"] = "exact-apk"
    output = APP / "app/build/outputs/apk/debug/app-debug.apk"
    require(list(output.parent.glob("*.apk")) == [output] and output.is_file() and output.resolve() == output and
            output.stat().st_size <= 512*MIB, "One bounded ordinary debug APK required")
    apk = WORK / "app-debug.apk"
    with output.open("rb") as source, apk.open("xb") as target:
        shutil.copyfileobj(source, target, MIB)
    require(sha(output) == sha(apk), "Exact APK staging changed bytes")
    inputs["apk"] = dict(sha256=sha(apk), bytes=apk.stat().st_size, build_root=":app:assembleDebug")
    require(directory_identity(WORK) == state["work_identity"], "Private work root changed; preserve")
    delete_build(state)
    for name in ("gradle", "konan", "project-cache", "home", "tmp", "android"):
        shutil.rmtree(WORK / name)
        (WORK / name).mkdir(mode=0o700)
    require(shutil.disk_usage(WORK).free >= 8 * 1024**3, "8GiB floor required before AVD")
    state["phase"] = "fresh-kvm-avd"
    start_device(lane, sdk, avdmanager, state)
    binding = dict(status="PRIMARY_ADMITTED", source_commit=SOURCE, source_tree=SOURCE_TREE,
                   engine_commit=ENGINE_PIN[0], engine_tree=ENGINE_PIN[1], apk_sha256=inputs["apk"]["sha256"])
    path = WORK / "runtime-binding.json"
    with path.open("x") as target:
        target.write(json.dumps(binding) + "\n")
    inputs["runtime_binding_sha256"] = sha(path)
    state["phase"] = "real-ui"
    lane.run([sys.executable, "-B", SCRIPTS / "app10_cold_root_ui.py", "--adb", sdk / "platform-tools/adb",
              "--apk", apk, "--binding", path, "--out", WORK / "ui"], "real-ui-traversal", CONTROL, seconds=420, cap=65536)
    state["ui_completed"] = True


def main():
    started = time.monotonic()
    require((SOURCE, SOURCE_TREE) == APP_PIN and BRANCH not in ("UNBOUND", "main", "testing", "release", "internal-testing"),
            "Separate primary binding of a nonrelease scoped ref required")
    for key, value in {"GITHUB_ACTIONS": "true", "GITHUB_EVENT_NAME": "push", "GITHUB_REPOSITORY": "kira-manga/kira-app",
                       "GITHUB_REF": "refs/heads/" + BRANCH, "GITHUB_RUN_ATTEMPT": "1", "RUNNER_OS": "Linux", "RUNNER_ARCH": "X64"}.items():
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
                  run_id=os.environ["GITHUB_RUN_ID"], attempt=1, admission=ADMISSION)
    same, restored, observation = False, None, None
    try:
        WORK.mkdir(mode=0o700)
        work_id = directory_identity(WORK)
        state["work_identity"] = work_id
        REPORTS.mkdir(mode=0o700)
        report_id = directory_identity(REPORTS)
        lane = Lane(WORK, started)
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
        state["phase"] = "preflight-kvm"
        require(Path("/dev/kvm").is_char_device() and os.access("/dev/kvm", os.R_OK | os.W_OK), "Usable KVM required; no fallback/ACL mutation")
        state["phase"] = "preflight-disk"
        require(shutil.disk_usage(WORK).free >= 8 * 1024**3, "Initial 8GiB free-space floor required")
        state["phase"] = "preflight-jdk-release-digest"
        jdk_release_sha256 = sha(java / "release")
        state["phase"] = "preflight-java-binary-digest"
        inputs["jdk"] = dict(release_sha256=jdk_release_sha256, java_sha256=sha(java / "bin/java"))
        execute(lane, sdk, state, inputs)
    except BaseException as error:
        errors.append(error_row(state["phase"], error))
    finally:
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        if lane is not None:
            lane.cleanup_end = min(lane.total_end, time.monotonic() + 90)
            cleanup(lane, sdk, state, errors)
        if lane is not None and lane.absent:
            try:
                require(directory_identity(REPORTS) == report_id, "Public report root changed; preserve")
                observation = retain_ui(WORK, REPORTS)
            except BaseException as error:
                errors.append(error_row("capture-ui", error))
            try:
                require(directory_identity(REPORTS) == report_id, "Public report root changed; preserve")
                build = next((j for j in lane.jobs if j["row"]["name"] == "assembleDebug"), None)
                if build is not None:
                    with (REPORTS / "build.log").open("xb") as target:
                        target.write(regular(build["path"], 8*MIB))
            except BaseException as error:
                errors.append(error_row("capture-build-log", error))
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
                delete_build(state)
                shutil.rmtree(WORK)
            except BaseException as error:
                errors.append(error_row("owned-files", error))
        if lane is not None:
            lane.poller.close()
    clean = not WORK.exists() and all(not p.exists() and not p.is_symlink() for p in OUTPUTS + [GOOGLE])
    rows, barriers = (lane.rows, lane.barriers) if lane is not None else ([], [])
    clean &= no_children and all(b["absent"] and not b["forced"] and not b["errors"] for b in barriers)
    clean &= time.monotonic() <= min(started + 1980, lane.cleanup_end if lane is not None else started + 90)
    clean &= not any(r["overflow"] for r in rows)
    observed = state.get("ui_completed", False) and observation is not None and observation.get("status") == "OBSERVED_PASS"
    passed = same and clean and observed and state.get("assembly_ok") and state.get("immediate_gradle_stop_ok") and not errors
    inputs["immediate_gradle_stop_ok"] = state.get("immediate_gradle_stop_ok", False)
    payloads = {"inputs.json": inputs, "commands.json": rows,
                "cleanup.json": dict(barriers=barriers, restored=restored, clean=clean, errors=errors)}
    return publish_reports(REPORTS, report_id, payloads, dict(source_unchanged=same, cleanup=clean, ui_observed_pass=observed,
        scope="Exact-source debug APK / fresh install / new PID+task only; no Android8/static replay, release or closure"), passed, errors)


def cleanup(lane, sdk, state, errors):
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


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, signal.default_int_handler)
    signal.signal(signal.SIGINT, signal.default_int_handler)
    raise SystemExit(main())
