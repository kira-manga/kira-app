#!/usr/bin/env python3
"""One source-bound App13 Android/Desktop batch; no automatic integration approval."""
import os
import sys

if os.environ.get("APP13_ADMISSION") != "primary-reviewed-app13-linux-04" or len(sys.argv) != 1:
    raise SystemExit("admission UNBOUND or unexpected caller arguments")
sys.dont_write_bytecode = True
from pathlib import Path
import hashlib
import json
import re
import selectors
import shutil
import signal
import subprocess
import time
import app13_evidence as evidence
from app13_evidence import ROOT, ENGINE, WORK, REPORTS, OUTPUTS, require, record, sha

GROUPS, COMMANDS, ERRORS, ENV = {}, [], [], {}
WORK_OWNED, OWNERSHIP_SAFE, PORTFOLIO = False, True, None
DEADLINE = time.monotonic() + 25 * 60
MARKER = f"-Dapp13.validation.root={WORK}"
GRADLE = ["./gradlew", "--gradle-user-home", str(WORK / "gradle"), "--console=plain"]

def check_cases(portfolio):
    specs = [(evidence.TEST_PATHS[0], "android", "androidJvm", ":composeApp:compileAndroidHostTest", 24, 5),
             (evidence.TEST_PATHS[1], "desktop", "jvm", ":composeApp:compileTestKotlinDesktop", 8, 3)]
    require(len(portfolio["tests"]) == 2 and portfolio["locale_task"] == ":composeApp:checkLocaleKeyParity", "Only the three App13 roots")
    sources, selectors_seen = portfolio["affected"] | portfolio["retained_test_sources"], []
    for test, spec in zip(portfolio["tests"], specs):
        require(tuple(test[k] for k in ("task", "target", "platform", "compiler", "count")) == spec[:5], "Wrong Test owner/model")
        cases = test["cases"]
        require(len(cases) == spec[4] and len({c["selector"].rsplit(".", 1)[0] for c in cases}) == spec[5], "Wrong literal method/suite roster")
        require(all(re.fullmatch(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+", c["selector"]) and
                    c["source"] in sources and c["source"].endswith(".kt") for c in cases), "No wildcard/unbound selector source")
        selectors_seen += [c["selector"] for c in cases]
    require(len(set(selectors_seen)) == 32, "No duplicate/cross-root replay")
    sandbox = "composeApp/src/commonTest/kotlin/me/manga/kira/core/webview/WebViewUrlSandboxTest.kt"
    require(set(portfolio["retained_test_sources"]) == {sandbox} and
            all(c["source"] != sandbox for c in portfolio["tests"][0]["cases"]) and
            sum(c["source"] == sandbox for c in portfolio["tests"][1]["cases"]) == 2, "Sandbox2 only once, on Desktop")

def read_portfolio():
    data = evidence.bounded(evidence.PORTFOLIO_PATH, 65536)
    require(len(data) <= 65536, "Portfolio exceeds64KiB")
    portfolio = json.loads(data)
    require(portfolio["contract"] == "app13-public-linux-original-engine" and
            all(re.fullmatch(r"[0-9a-f]{40}", portfolio["source"][k]) for k in ("head", "tree")), "Source UNBOUND")
    affected, retained, statics = portfolio["affected"], portfolio["retained_test_sources"], portfolio["statics"]
    require(affected and not set(affected) & set(retained), "Exact disjoint affected/retained source map required")
    for name, digest in (affected | retained).items():
        path = ROOT / name
        require(not Path(name).is_absolute() and ".." not in Path(name).parts and not any(c in name for c in "\\\n\t") and
                path.resolve() == path and path.is_file() and re.fullmatch(r"[0-9a-f]{64}", digest) and sha(path) == digest,
                "Affected/retained file hash UNBOUND or changed")
    require(statics["status"] == "PASS" and statics["source"] == portfolio["source"]["head"] and
            statics["source_tree"] == portfolio["source"]["tree"] and all(re.fullmatch(r"[0-9a-f]{64}", statics[k]) for k in
                ("input_map_sha256", "receipt_sha256", "ktlint_baseline_sha256", "detekt_baseline_sha256")), "Primary static carry UNBOUND")
    kotlin = {p: digest for p, digest in affected.items() if p.endswith(".kt")}
    rows = "".join(f"{name}\t{kotlin[name]}\n" for name in sorted(kotlin)).encode()
    require(kotlin and hashlib.sha256(rows).hexdigest() == statics["input_map_sha256"], "Carried Kotlin map does not bind exact affected source")
    for tool in ("ktlint", "detekt"):
        require(sha(ROOT / f"config/{tool}-baseline.xml") == statics[f"{tool}_baseline_sha256"], "Carried baseline bytes changed")
    check_cases(portfolio)
    return portfolio

def prepare_environment():
    global WORK_OWNED, ENV
    java, sdk = Path(os.environ["JAVA_HOME_21_X64"]).resolve(), Path(os.environ["ANDROID_HOME"]).resolve()
    inputs = [java / "release", java / "bin/java", sdk / "platforms/android-37.0/android.jar",
              sdk / "build-tools/36.0.0/aapt2", sdk / "build-tools/36.0.0/source.properties"]
    release = evidence.bounded(inputs[0], 65536)
    require(all(p.is_file() and p.resolve() == p for p in inputs) and len(release) <= 65536 and b'JAVA_VERSION="21.' in release,
            "Installed JDK21/stable37.0/build-tools36.0.0 only; no SDK provisioning/alias/substitution")
    WORK.mkdir(mode=0o700)
    WORK_OWNED = True
    for name in ("home", "gradle", "konan", "tmp", "project-cache"):
        (WORK / name).mkdir(mode=0o700)
    ENV = dict(PATH=f"{java / 'bin'}:/usr/bin:/bin", HOME=str(WORK / "home"), JAVA_HOME=str(java),
        ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk), GRADLE_USER_HOME=str(WORK / "gradle"),
        KONAN_DATA_DIR=str(WORK / "konan"), TMPDIR=str(WORK / "tmp"), TMP=str(WORK / "tmp"), TEMP=str(WORK / "tmp"),
        JAVA_OPTS="-Xmx512m -XX:ActiveProcessorCount=2", JAVA_TOOL_OPTIONS=f"-Djava.io.tmpdir={WORK / 'tmp'}",
        KIRA_SOURCE_CONFIG_BASE_URL="", KIRA_SOURCE_CONFIG_PINNED_KEYS="", KIRA_APP_VERSION="1.0.5",
        KIRA_ORIGINAL_ENGINE_WORKSPACE=str(ROOT.parent), KIRA_ORIGINAL_ENGINE_BINDING=str(WORK / "binding.json"),
        KIRA_ORIGINAL_ENGINE_BINDING_SHA256=evidence.write_binding(PORTFOLIO), LANG="C.UTF-8", TZ="UTC")
    return {str(p): sha(p) for p in inputs}

def prepare_inputs():
    inputs = prepare_environment()
    record("inputs.json", dict(installed_input_sha256=inputs, portfolio_sha256=sha(evidence.PORTFOLIO_PATH),
        binding_sha256=ENV["KIRA_ORIGINAL_ENGINE_BINDING_SHA256"], compiler_tasks=evidence.COMPILERS, tests=PORTFOLIO["tests"],
        carried_statics=PORTFOLIO["statics"], carried_kotlin_count=sum(p.endswith(".kt") for p in PORTFOLIO["affected"]),
        gradle_heap="3g", gradle_metaspace="1g", test_heap="1g", work_seconds=1200, stop_seconds=90,
        native="Default App2.4.0/Engine2.2.21 PREBUILT configuration inputs only; no Native task or CEF bundle",
        bootstrap="Unchanged normal HTTPS wrapper; no distribution-byte authenticity claim; no static rerun or Google/JNI staging"))
    (REPORTS / "inputs.log").write_text("Installed inputs hashed; only product SDK35 staging; no SDK provisioning or executable probe.\n")

def alive(group):
    try:
        os.killpg(group, 0)
        return True
    except ProcessLookupError:
        return False

def stop_group(process):
    global OWNERSHIP_SAFE
    try:
        for sig in (signal.SIGTERM, signal.SIGKILL):
            os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
            try:
                os.killpg(process.pid, sig)
            except ProcessLookupError:
                break
            time.sleep(0.2)
    except ChildProcessError:
        OWNERSHIP_SAFE = False
        GROUPS.pop(process.pid, None)
        raise RuntimeError("Leader reaped; refuse stale PGID signalling/cleanup")
    safe, OWNERSHIP_SAFE = OWNERSHIP_SAFE, False
    code = process.wait(timeout=5)
    GROUPS.pop(process.pid, None)
    require(not alive(process.pid), "Group survives reap; no further force authority or cleanup")
    OWNERSHIP_SAFE = safe
    return code

def capture(process, log, limit, deadline):
    with selectors.DefaultSelector() as poll:
        poll.register(process.stdout, selectors.EVENT_READ)
        while poll.get_map():
            require(time.monotonic() < deadline, "Command deadline exceeded")
            for key, _ in poll.select(0.2):
                chunk = os.read(key.fd, 65536)
                if not chunk:
                    poll.unregister(key.fileobj)
                    continue
                available = max(0, limit - log.tell())
                log.write(chunk[:available])
                require(len(chunk) <= available, "Command log cap exceeded; retained prefix")
    while (ended := os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)) is None:
        require(time.monotonic() < deadline, "Command deadline exceeded")
        time.sleep(0.1)
    return ended.si_status if ended.si_code == os.CLD_EXITED else -ended.si_status

def run(argv, name, seconds=90, shutdown=False):
    global OWNERSHIP_SAFE
    limit = 16 * evidence.MIB if name == "gradle.log" else 512 * 1024
    deadline = time.monotonic() + seconds if shutdown else min(time.monotonic() + seconds, DEADLINE)
    COMMANDS.append(dict(argv=argv, log=name, exit=None))
    command, completed = COMMANDS[-1], False
    record("commands.json", COMMANDS)
    with (REPORTS / name).open("ab") as log:
        safe, OWNERSHIP_SAFE = OWNERSHIP_SAFE, False
        process = subprocess.Popen(argv, cwd=ROOT, env=ENV, stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
        GROUPS[process.pid] = process
        OWNERSHIP_SAFE = safe
        try:
            command["exit"] = capture(process, log, limit, deadline)
            completed = True
        finally:
            try:
                if name != "gradle.log" or not completed:
                    command["exit"] = stop_group(process)
            finally:
                process.stdout.close()
                record("commands.json", COMMANDS)
    return command["exit"]

def stop_gradle(name, started):
    global OWNERSHIP_SAFE
    try:
        if started:
            require(run(GRADLE + ["--stop"], name, shutdown=True) == 0, "Gradle --stop failed")
    finally:
        for process in list(GROUPS.values()):
            stop_group(process)
        safe, OWNERSHIP_SAFE = OWNERSHIP_SAFE, False
        for entry in (WORK / "gradle/daemon/9.6.1").glob("daemon-*.out.log"):
            pid = int(entry.name[7:-8])
            require(not alive(pid), "Recorded daemon group remains; observation only, refuse cleanup")
            try:
                with Path(f"/proc/{pid}/cmdline").open("rb") as stream:
                    args = stream.read(65537)
            except (FileNotFoundError, ProcessLookupError):
                continue
            require(len(args) <= 65536 and MARKER.encode() not in args.split(b"\0"),
                    "Unretained daemon/inspection uncertainty; refuse force and cleanup")
        OWNERSHIP_SAFE = safe

def arguments():
    args = GRADLE.copy()
    for test in PORTFOLIO["tests"]:
        args.append(test["task"])
        for case in test["cases"]:
            args += ["--tests", case["selector"]]
    return args + [PORTFOLIO["locale_task"], "--no-daemon", "--no-parallel", "--max-workers=1", "--no-build-cache", "--no-configuration-cache",
        "--stacktrace", "--project-cache-dir", str(WORK / "project-cache"),
        "-Pkotlin.compiler.execution.strategy=in-process", "-PkiraUseMavenLocal=false",
        "-Porg.gradle.java.installations.auto-download=false", "-Pandroid.builder.sdkDownload=false",
        "-Dorg.gradle.vfs.watch=false", f"-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g "
        f"-XX:ActiveProcessorCount=2 {MARKER} -Djava.io.tmpdir={WORK / 'tmp'}",
        "-I", str(ROOT / "scripts/app13-original-engine.init.gradle")]

def cleanup_outputs():
    require(OWNERSHIP_SAFE and not GROUPS, "Unverified ownership; refuse output cleanup")
    for path in OUTPUTS:
        require(not path.is_symlink() and path.resolve() == path, "Aliased output; refuse deletion")
        if path.exists():
            require(path.is_dir(), "Unknown output identity; preserve it")
            shutil.rmtree(path)

def remove_scratch():
    require(OWNERSHIP_SAFE and not GROUPS, "Unverified ownership; refuse scratch deletion")
    if WORK_OWNED:
        require(WORK.is_dir() and WORK.resolve() == WORK, "Unknown owned scratch identity")
        shutil.rmtree(WORK)

def capture_results(result, before, started):
    if not started:
        return
    try:
        require(OWNERSHIP_SAFE and not GROUPS, "No stable evidence without positive shutdown")
        rows = evidence.records()
    except BaseException as error:
        ERRORS.append(f"records: {type(error).__name__}: {error}"[:512])
        return
    for name, action in (("xml_exact", lambda: evidence.retain_xml(PORTFOLIO, rows)),
                         ("linux_provenance", lambda: evidence.prove_linux(rows)), ("source_at_capture", lambda: evidence.snapshot() == before)):
        try:
            result[name] = action()
        except BaseException as error:
            ERRORS.append(f"{name}: {type(error).__name__}: {error}"[:512])

def finish(result, before, started):
    capture_results(result, before, started)
    for action in (lambda: cleanup_outputs() if before is not None else None,
                   lambda: stop_gradle("stop-final.log", started) if WORK_OWNED else None, remove_scratch):
        try:
            action()
        except BaseException as error:
            ERRORS.append(f"cleanup: {type(error).__name__}: {error}"[:512])
    try:
        after = evidence.snapshot()
        record("source-after.json", after)
        result["source_unchanged"] = before is not None and before == after
    except BaseException as error:
        ERRORS.append(f"source-readback: {type(error).__name__}: {error}"[:512])
    result["cleanup"] = OWNERSHIP_SAFE and not GROUPS and all(not p.exists() and not p.is_symlink() for p in OUTPUTS + [WORK])

def main():
    global PORTFOLIO
    PORTFOLIO = read_portfolio()
    evidence.admit(PORTFOLIO)
    os.umask(0o077)
    REPORTS.mkdir(mode=0o700)
    result = dict(hosts=False, xml_exact=False, linux_provenance=False, source_at_capture=False, source_unchanged=False, cleanup=False)
    before, started = None, False
    try:
        before = evidence.snapshot()
        record("source-before.json", before)
        prepare_inputs()
        require(evidence.snapshot() == before, "Admitted inputs changed before batch")
        require(shutil.disk_usage(WORK).free >= 8 * 1024**3, "Fresh8GiB free-space floor required before Gradle")
        started = True
        try:
            result["hosts"] = run(arguments(), "gradle.log", 1200) == 0
        finally:
            stop_gradle("stop-immediate.log", started)
    except BaseException as error:
        ERRORS.append(f"work: {type(error).__name__}: {error}"[:512])
    finally:
        finish(result, before, started)
    return publish_result(result)

def publish_result(result):
    eligible = all(result.values()) and not ERRORS
    record("result.json", dict(status="RESULT_REVIEW_REQUIRED" if eligible else "FAIL", checks=result, errors=ERRORS,
        app_source=PORTFOLIO["source"], engine_source=evidence.ENGINE_COMMIT, tests=PORTFOLIO["tests"], carried_statics=PORTFOLIO["statics"],
        limits="16MiB Gradle;8MiB provenance;512KiB other logs;1MiB XML;64KiB other JSON;18 files<=34MiB",
        scope="Original-source Android/Desktop compilation and32 host methods/locale only; no published-byte, Native/device/Store/integration credit"))
    print("RESULT_REVIEW_REQUIRED" if eligible else "FAIL", flush=True)
    return 0 if eligible else 1

if __name__ == "__main__":
    signal.signal(signal.SIGTERM, signal.default_int_handler)
    signal.signal(signal.SIGINT, signal.default_int_handler)
    raise SystemExit(main())
