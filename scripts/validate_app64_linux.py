#!/usr/bin/env python3
"""One public App64 source-bound batch; not device/AVIF or production qualification."""
import hashlib
import json
import os
from pathlib import Path
import selectors
import shutil
import signal
import subprocess
import time
import xml.etree.ElementTree as ET
SOURCE = "d3fff2dd59981e52f9b4b3fd72fac1100a2a6431"
SOURCE_TREE = "911ec4d6d3c030ae99af7d3dca9483c2dc523212"
CONTROLS = [".github/workflows/app64-public-linux.yml", "scripts/validate_app64_linux.py"]
CBZ = "data/download/src/androidHostTest/kotlin/me/manga/kira/core/cbz/"
SERVICE = "data/download/src/androidHostTest/kotlin/me/manga/kira/presentation/features/download/ui/test2/"
PLATFORM = "platform/src/androidMain/kotlin/me/manga/kira/core/cbz/"
FILES = [CBZ + name + ".kt" for name in (
    "CbzFault", "CbzHostArchiveOutput", "CbzHostFixture", "CbzModeledAvifCase", "CbzObservedDecoder", "CbzTestConstants",
    "CbzReturnDispatcher", "OptimizedCbzCancellationTest", "OptimizedCbzRollbackTest", "OptimizedCbzStreamingTest",
)] + [SERVICE + name + ".kt" for name in ("CancellationFixtureService", "CancellationFixtureServiceInputs", "CbzServiceFailureTest")] + [
    PLATFORM + name + ".kt" for name in ("CbzArchiveOutput", "CbzImageDecoder", "CbzSourceBounds", "OptimizedCbzManager")]
TESTS = [
    "me.manga.kira.core.cbz.OptimizedCbzStreamingTest.allTiersStreamRealWebpBeforeLaterPagesAndPublishOnce",
    "me.manga.kira.core.cbz.OptimizedCbzStreamingTest.modeledRegionsAreRequestedOnlyAfterPreviousChunkIsRecycled",
    "me.manga.kira.core.cbz.OptimizedCbzStreamingTest.modeledAvifOwnsOneParentAndAtMostOneCropIncludingUnsplitAlias",
    "me.manga.kira.core.cbz.OptimizedCbzCancellationTest.sameManagerSerializesCallsAndCancelledWaiterDoesNoDecode",
    "me.manga.kira.core.cbz.OptimizedCbzCancellationTest.activeCancellationAfterWrittenRegionRecyclesAndRollsBackOnlyTemp",
    "me.manga.kira.core.cbz.OptimizedCbzCancellationTest.publishedArchiveSurvivesCancelledWithContextReturn",
    "me.manga.kira.core.cbz.OptimizedCbzRollbackTest.sourceCodecAndArchiveFailuresPreserveInputsAndPreviousFinal",
    "me.manga.kira.core.cbz.OptimizedCbzRollbackTest.modeledAvifCropFailuresReleaseParentAndPreserveTypedFailure",
    "me.manga.kira.core.cbz.OptimizedCbzRollbackTest.emptyInputDoesNotPublishAnEmptyArchiveOrReplaceExistingFinal",
    "me.manga.kira.presentation.features.download.ui.test2.CbzServiceFailureTest.cbzEncoderOomPersistsAndEmitsReadableLoosePaths",
    "me.manga.kira.presentation.features.download.ui.test2.CbzServiceFailureTest.cbzCancellationIsNotConvertedToLooseSuccessOrServiceDeletion",
]
XMLS = [f"TEST-{owner}.xml" for owner in dict.fromkeys(test.rsplit(".", 1)[0] for test in TESTS)]
MODULES = ("", "core", "domain", "platform", "presentation", "ui", "composeApp", "app", "desktopApp", "data", "data/local",
           "data/remote", "data/download", "sources/contracts", "sources/engine", "sources/config", "sources/legacy")
ROOT, ENV = Path.cwd().resolve(), os.environ.copy()
WORK = Path(os.environ["RUNNER_TEMP"]) / "app64-public-work"
REPORTS = WORK.parent / "app64-public-reports"
GROUPS, COMMANDS, ERRORS = {}, [], []
WORK_OWNED, OWNERSHIP_SAFE = False, True
DEADLINE = time.monotonic() + 21 * 60  # Leave four minutes for shutdown/artifact upload.
MARKER = f"-Dapp64.validation.root={WORK}"
GRADLE = ["./gradlew", "--gradle-user-home", str(WORK / "gradle"), "--console=plain"]
def require(condition, message):
    if not condition:
        raise RuntimeError(message)

def record(name, value):
    data = (json.dumps(value, indent=2) + "\n").encode()
    require(len(data) <= 65536, "JSON report exceeds 64 KiB")
    (REPORTS / name).write_bytes(data)

def git(*args):
    return subprocess.check_output(["git", "--no-optional-locks", *args], cwd=ROOT).decode().strip()

def source_record():
    paths = FILES + CONTROLS + ["config/ktlint-baseline.xml", "config/detekt-baseline.xml"]
    return {"head": git("rev-parse", "HEAD"), "tree": git("rev-parse", "HEAD^{tree}"),
            "source": SOURCE, "source_tree": SOURCE_TREE,
            "clean": not git("status", "--porcelain=v1", "--untracked-files=all"),
            "sha256": {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in paths}}

def preflight():
    global WORK_OWNED
    require(ENV.get("APP64_ADMISSION") == "primary-reviewed-app64-linux-05", "admission UNBOUND")
    for key, expected in {"GITHUB_ACTIONS": "true", "GITHUB_EVENT_NAME": "push",
                          "GITHUB_REPOSITORY": "kira-manga/kira-app", "GITHUB_RUN_ATTEMPT": "1",
                          "GITHUB_REF": "refs/heads/remediation/app-64-public-validation-05",
                          "RUNNER_OS": "Linux", "RUNNER_ARCH": "X64"}.items():
        require(ENV.get(key) == expected, f"unexpected {key}")
    require(ROOT == Path(ENV["GITHUB_WORKSPACE"]).resolve(), "unexpected checkout directory")
    require(git("rev-parse", "HEAD") == ENV["GITHUB_SHA"], "event/checkout SHA mismatch")
    require(git("rev-parse", "HEAD^@") == SOURCE, "single source parent mismatch")
    require(git("rev-parse", f"{SOURCE}^{{tree}}") == SOURCE_TREE, "source tree mismatch")
    require(git("diff", "--name-status", SOURCE, "HEAD").splitlines() ==
            [f"A\t{name}" for name in CONTROLS], "carrier changes more than the two new controls")
    require(source_record()["clean"], "checkout is not clean")
    java = Path(ENV["JAVA_HOME_21_X64"])
    require('JAVA_VERSION="21.' in (java / "release").read_text(), "installed Java 21 required")
    require((Path(ENV["ANDROID_HOME"]) / "platforms/android-37.0/android.jar").is_file(),
            "installed stable Android platform 37.0 required; no SDK substitution/download")
    ENV.update(JAVA_HOME=str(java), PATH=f"{java / 'bin'}:{ENV['PATH']}",
               KONAN_DATA_DIR=str(WORK / "konan"),
               GRADLE_USER_HOME=str(WORK / "gradle"), JAVA_OPTS="-Xmx512m -XX:ActiveProcessorCount=2")
    WORK.mkdir(exist_ok=False)
    WORK_OWNED = True
    for name in ("tools", "tmp", "project-cache"):
        (WORK / name).mkdir(parents=True, exist_ok=False)

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
        raise RuntimeError("leader reaped; refuse stale PGID signalling/cleanup")
    safe, OWNERSHIP_SAFE = OWNERSHIP_SAFE, False
    code = process.wait(timeout=5)
    GROUPS.pop(process.pid, None)
    require(not alive(process.pid), "group survives reap; no further force authority or cleanup")
    OWNERSHIP_SAFE = safe
    return code

def capture(process, log, limit, deadline):
    with selectors.DefaultSelector() as poll:
        poll.register(process.stdout, selectors.EVENT_READ)
        while poll.get_map():
            require(time.monotonic() < deadline, "command deadline exceeded")
            for key, _ in poll.select(0.2):
                chunk = os.read(key.fd, 65536)
                if not chunk:
                    poll.unregister(key.fileobj)
                    continue
                available = max(0, limit - log.tell())
                log.write(chunk[:available])
                require(len(chunk) <= available, "command log cap reached; retained prefix only")
    while (ended := os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)) is None:
        require(time.monotonic() < deadline, "command deadline exceeded")
        time.sleep(0.1)
    return ended.si_status if ended.si_code == os.CLD_EXITED else -ended.si_status

def run(argv, log_name, seconds=120, shutdown=False):
    global OWNERSHIP_SAFE
    limit = 16 * 1024 * 1024 if log_name == "gradle.log" else 512 * 1024
    deadline = time.monotonic() + seconds if shutdown else min(time.monotonic() + seconds, DEADLINE)
    COMMANDS.append({"argv": argv, "log": log_name, "exit": None})
    command, completed = COMMANDS[-1], False
    record("commands.json", COMMANDS)
    with (REPORTS / log_name).open("ab") as log:
        safe, OWNERSHIP_SAFE = OWNERSHIP_SAFE, False
        process = subprocess.Popen(argv, cwd=ROOT, env=ENV, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        GROUPS[process.pid] = process
        OWNERSHIP_SAFE = safe
        try:
            command["exit"] = capture(process, log, limit, deadline)
            completed = True
        finally:
            try:
                if log_name != "gradle.log" or not completed:
                    command["exit"] = stop_group(process)
            finally:
                process.stdout.close()
                record("commands.json", COMMANDS)
    return command["exit"]

def stop_gradle(started=True):
    global OWNERSHIP_SAFE
    try:
        if started:
            require(run(GRADLE + ["--stop"], "gradle-stop.log", 40, shutdown=True) == 0, "Gradle --stop failed")
    finally:
        for process in list(GROUPS.values()):
            stop_group(process)
        safe, OWNERSHIP_SAFE = OWNERSHIP_SAFE, False
        for entry in (WORK / "gradle/daemon/9.6.1").glob("daemon-*.out.log"):
            require(not alive(int(entry.name[7:-8])), "recorded daemon group remains; refuse force and cleanup")
            try:
                args = Path(f"/proc/{int(entry.name[7:-8])}/cmdline").read_bytes().split(b"\0")
            except (FileNotFoundError, ProcessLookupError):
                continue
            require(MARKER.encode() not in args, "unretained daemon remains; refuse force and cleanup")
        OWNERSHIP_SAFE = safe

def static_checks():
    tools = WORK / "tools"
    pins = [("https://github.com/pinterest/ktlint/releases/download/1.5.0/ktlint", "ktlint",
             "a16be01dcc480aab2f55f444b620142152f66e31564b3b9376506d624c28a2ad"),
            ("https://github.com/detekt/detekt/releases/download/v1.23.7/detekt-cli-1.23.7.zip", "detekt.zip",
             "a641ecd392251336f8a1b7e1192acb603e47e04cbbb994aa3541c5fa9fc0bc87")]
    for url, name, digest in pins:
        code = run(["curl", "--fail", "--silent", "--show-error", "--location", "--proto", "=https", "--proto-redir", "=https",
                    "--max-time", "150", "--max-filesize", "134217728", "--output", str(tools / name), url],
                   "tools.log", 155)
        require(code == 0 and hashlib.sha256((tools / name).read_bytes()).hexdigest() == digest,
                f"pinned tool acquisition failed: {name}")
    require(run(["unzip", "-q", str(tools / "detekt.zip"), "-d", str(tools)], "tools.log") == 0,
            "verified Detekt distribution extraction failed")
    java = [str(Path(ENV["JAVA_HOME"]) / "bin/java"), "-Xmx512m", "-XX:ActiveProcessorCount=2"]
    ktlint = run(java + ["-jar", str(tools / "ktlint"), "--baseline=config/ktlint-baseline.xml",
                         "--relative", *FILES], "ktlint.log")
    detekt = run(java + ["-cp", str(tools / "detekt-cli-1.23.7/lib/*"),
                         "io.gitlab.arturbosch.detekt.cli.Main", "--input", ",".join(FILES),
                         "--build-upon-default-config", "--baseline", "config/detekt-baseline.xml"], "detekt.log")
    return ktlint == 0 and detekt == 0

def retain_and_check_xml():
    directory = ROOT / "data/download/build/test-results/testAndroidHostTest"
    roster, valid = [], set(path.name for path in directory.glob("TEST-*.xml")) == set(XMLS)
    for name in XMLS:
        path = directory / name
        if not path.is_file() or path.is_symlink():
            valid = False
            continue
        with path.open("rb") as stream:
            data = stream.read(1024 * 1024 + 1)
        valid &= len(data) <= 1024 * 1024
        data = data[:1024 * 1024]
        (REPORTS / name).write_bytes(data)
        try:
            suite = ET.fromstring(data)
            cases = suite.findall("testcase")
            valid &= suite.tag == "testsuite" and int(suite.get("tests", "-1")) == len(cases)
            valid &= all(int(suite.get(key, "0")) == 0 for key in ("failures", "errors", "skipped"))
        except (ET.ParseError, ValueError):
            valid = False
            continue
        valid &= all(case.get("classname") == name[5:-4] for case in cases)
        valid &= not any(suite.findall(f".//{tag}") for tag in ("failure", "error", "skipped"))
        roster.extend(f"{case.get('classname')}.{case.get('name')}" for case in cases)
    return valid and sorted(roster) == sorted(TESTS)

def cleanup():
    require(OWNERSHIP_SAFE and not GROUPS, "unverified process ownership; defer file cleanup")
    for name in [str(Path(module) / "build") for module in MODULES] + [".gradle", ".kotlin"]:
        path = ROOT / name
        require(not path.is_symlink(), "refuse cleanup of symlinked build/cache directory")
        if path.exists():
            shutil.rmtree(path)

def main():
    REPORTS.mkdir(exist_ok=False)
    result = dict(static=False, host=False, xml_exact=False, source_unchanged=False, cleanup=False)
    before, started = None, False
    try:
        preflight()
        before = source_record()
        record("source-before.json", before)
        result["static"] = static_checks()
        if not result["static"]:
            return 1  # Preserve failed static evidence without starting an unrelated expensive build.
        args = GRADLE + [":data:download:testAndroidHostTest"]
        for test in TESTS:
            args += ["--tests", test]
        args += ["--no-daemon", "--no-parallel", "--max-workers=1", "--no-build-cache",
                 "--no-configuration-cache", "--stacktrace", "--project-cache-dir", str(WORK / "project-cache"),
                 "-Pkotlin.compiler.execution.strategy=in-process", "-PkiraUseMavenLocal=false",
                 "-Porg.gradle.java.installations.auto-download=false", "-Pandroid.builder.sdkDownload=false",
                 "-Dorg.gradle.vfs.watch=false", f"-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g "
                 f"-XX:ActiveProcessorCount=2 {MARKER} -Djava.io.tmpdir={WORK / 'tmp'}"]
        started = True
        try:
            result["host"] = run(args, "gradle.log", 18 * 60) == 0
        finally:
            stop_gradle()  # Immediately after the only Gradle command batch, including failure.
    except BaseException as error:
        ERRORS.append(f"{type(error).__name__}: {error}"[:512])
    finally:
        finish(result, before, started)
    return 0 if all(result.values()) and not ERRORS else 1

def finish(result, before, started):
    for action in (lambda: result.update(xml_exact=retain_and_check_xml()) if started else None,
                   lambda: cleanup() if before is not None else None,
                   lambda: stop_gradle(started) if WORK_OWNED else None):
        try:
            action()
        except BaseException as error:
            ERRORS.append(f"{type(error).__name__}: {error}"[:512])
    try:
        require(OWNERSHIP_SAFE and not GROUPS, "unverified process ownership; defer scratch cleanup")
        if WORK_OWNED:
            require(not WORK.is_symlink(), "refuse cleanup of symlinked scratch directory")
            shutil.rmtree(WORK)
    except BaseException as error:
        ERRORS.append(f"{type(error).__name__}: {error}"[:512])
    try:
        after = source_record()
        record("source-after.json", after)
        result["source_unchanged"] = before is not None and after == before
    except BaseException as error:
        ERRORS.append(f"{type(error).__name__}: {error}"[:512])
    result["cleanup"] = OWNERSHIP_SAFE and not GROUPS and not ERRORS
    record("result.json", {"checks": result, "errors": ERRORS, "requested_tasks": [":data:download:testAndroidHostTest"],
                           "selectors": TESTS, "source_checkpoint": SOURCE,
                           "limits": "16MiB Gradle, 512KiB each other log, 1MiB each XML, 64KiB each JSON; <=23MiB total",
                           "external": "Android native region/AVIF and device qualification not established"})

if __name__ == "__main__":
    signal.signal(signal.SIGTERM, signal.default_int_handler)
    signal.signal(signal.SIGINT, signal.default_int_handler)
    raise SystemExit(main())
