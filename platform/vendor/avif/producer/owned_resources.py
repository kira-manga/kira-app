#!/usr/bin/env python3
"""Hosted-only producer lifecycle. SOURCE_ONLY / NOT_RUN, including cleanup and syntax checks."""

from dataclasses import dataclass
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time


OWNER_ENV = "KIRA_AVIF_PRODUCER_OWNER"
MARKER = ".kira-avif-owner"
LOG_BYTES = 1024 * 1024
MAX_NATIVE_LOGS = 24
NATIVE_LOGS = (
    "source-preparation.json",
    "gradle-home/daemon/7.5/daemon-*.out.log",
    "libavif/ext/dav1d/build/*/meson-logs/meson-log.txt",
    "libavif/ext/libyuv/build/*/CMakeFiles/CMakeError.log",
    "libavif/ext/libyuv/build/*/CMakeFiles/CMakeOutput.log",
    "libavif/android_jni/avifandroidjni/.cxx/RelWithDebInfo/*/*/CMakeFiles/CMakeError.log",
    "libavif/android_jni/avifandroidjni/.cxx/RelWithDebInfo/*/*/CMakeFiles/CMakeOutput.log",
)


@dataclass(frozen=True)
class OwnedRun:
    work: Path
    evidence: Path
    owner: str


def context():
    if (os.environ.get("GITHUB_ACTIONS"), os.environ.get("RUNNER_OS"), os.environ.get("RUNNER_ARCH")) != (
        "true", "Linux", "X64"
    ) or os.environ.get("KIRA_AVIF_PRODUCER_ADMITTED") != "native15-reviewed-source-only":
        raise RuntimeError("Cleanup is restricted to the admitted hosted producer")
    run_id, attempt = os.environ["GITHUB_RUN_ID"], os.environ["GITHUB_RUN_ATTEMPT"]
    commit = os.environ.get("EXPECTED_RECIPE_COMMIT", "")
    if not all(re.fullmatch(r"[1-9][0-9]*", value) for value in (run_id, attempt)):
        raise RuntimeError("Invalid hosted run identity")
    if not re.fullmatch(r"[0-9a-f]{40}", commit) or commit != os.environ.get("GITHUB_SHA"):
        raise RuntimeError("Cleanup recipe revision mismatch")
    temporary = Path(os.environ["RUNNER_TEMP"]).resolve(strict=True)
    if temporary == Path("/"):
        raise RuntimeError("RUNNER_TEMP must not be the filesystem root")
    work = temporary / f"avif-native-{run_id}-{attempt}"
    evidence = temporary / f"avif-native-evidence-{run_id}-{attempt}"
    return OwnedRun(work, evidence, f"{run_id}:{attempt}:{commit}:{work}")


def require_owned(path, owner):
    marker = path / MARKER
    if path.is_symlink() or not path.is_dir() or marker.is_symlink() or not marker.is_file():
        raise RuntimeError(f"Refusing an unmarked or linked producer directory: {path.name}")
    if path.stat().st_uid != os.getuid() or marker.read_text() != owner + "\n":
        raise RuntimeError(f"Producer directory ownership mismatch: {path.name}")


def initialize(run):
    for path in (run.work, run.evidence):
        if path.exists() or path.is_symlink():
            raise RuntimeError(f"Producer path is not fresh: {path.name}")
    for path in (run.work, run.evidence):
        path.mkdir(mode=0o700)
        (path / MARKER).write_text(run.owner + "\n")


def stream_log(source, destination):
    """Retain a 1 MiB tail while still forwarding normal step output."""
    tail = b""
    with destination.open("wb") as log:
        while chunk := source.read1(65536):
            tail = (tail + chunk)[-LOG_BYTES:]
            log.seek(0)
            log.write(tail)
            log.truncate()
            log.flush()
            sys.stdout.buffer.write(chunk)
            sys.stdout.buffer.flush()


def run_stage(run, stage):
    if os.environ.get(OWNER_ENV) != run.owner:
        # Tag the logger/supervisor at exec too, not just its shell/tools: /proc sees initial env.
        os.execve(sys.executable, [sys.executable, str(Path(__file__).resolve()), "run", stage],
                  {**os.environ, OWNER_ENV: run.owner})
    if stage == "setup":
        initialize(run)
    for path in (run.work, run.evidence):
        require_owned(path, run.owner)
    script = "setup-hosted-tools.sh" if stage == "setup" else "rebuild.sh"
    environment = {**os.environ, OWNER_ENV: run.owner}
    # No detached process group: the existing step timeout still covers the stage.
    with subprocess.Popen(
        ["bash", str(Path(__file__).with_name(script)), "--owned-stage"], env=environment,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    ) as process:
        stream_log(process.stdout, run.evidence / f"{stage}.log")
        status = process.wait()
    return status if status >= 0 else 128 - status


def stop_gradle(run, phase):
    home = run.work / "gradle-home"
    log = run.evidence / f"gradle-stop-{phase}.log"
    if not (home / "daemon").exists():
        log.write_text("SKIPPED: no isolated daemon home was created.\n")
        return 0
    if home.resolve() != home or not (home / "daemon/7.5").resolve().is_relative_to(home):
        raise RuntimeError("Refusing an escaping isolated Gradle daemon registry")
    installed = list((home / "wrapper/dists/gradle-7.5-bin").glob("*/gradle-7.5/bin/gradle"))
    if len(installed) != 1 or not installed[0].resolve().is_relative_to(run.work):
        raise RuntimeError("No unique installed Gradle 7.5; cleanup will not download a wrapper")
    environment = {**os.environ, "GRADLE_USER_HOME": str(home), OWNER_ENV: run.owner}
    # Use only the already-installed distribution, never a wrapper download/global daemon registry.
    with subprocess.Popen(
        ["timeout", "--kill-after=3s", "10s", str(installed[0]), "--offline", "--no-daemon",
         "--gradle-user-home", str(home), "--stop"], cwd=run.work, env=environment,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    ) as process:
        stream_log(process.stdout, log)
        status = process.wait()
    footer = f"\nstop_exit_status={status}\n".encode()
    log.write_bytes((log.read_bytes() + footer)[-LOG_BYTES:])
    return status


def signal_owned(owner, signum):
    """Use exact inherited run markers and pidfds; never process names or broad pkill."""
    count = 0
    marker = f"{OWNER_ENV}={owner}".encode()
    for proc in Path("/proc").iterdir():
        if not proc.name.isdigit() or int(proc.name) == os.getpid():
            continue
        descriptor = None
        try:
            if proc.stat().st_uid != os.getuid():
                continue
            descriptor = os.pidfd_open(int(proc.name))
            if proc.stat().st_uid != os.getuid() or marker not in (proc / "environ").read_bytes().split(b"\0"):
                continue
            signal.pidfd_send_signal(descriptor, signum)
            count += 1
        except (FileNotFoundError, ProcessLookupError):
            pass  # Process exited during inspection; pidfd prevents signalling a recycled PID.
        finally:
            if descriptor is not None:
                os.close(descriptor)
    return count


def terminate_owned(run):
    counts = []
    for signum, grace in ((signal.SIGTERM, 5), (signal.SIGKILL, 2)):
        counts.append(signal_owned(run.owner, signum))
        deadline = time.monotonic() + grace
        while signal_owned(run.owner, 0):
            if time.monotonic() >= deadline:
                break
            time.sleep(0.25)
        else:
            return f"owned_processes_signalled={counts}"
    raise RuntimeError("Run-marked processes remain after bounded TERM/KILL; work retained")


def preserve_native_logs(run):
    target = run.evidence / "native-logs"
    target.mkdir(exist_ok=True)
    index = []
    for pattern in NATIVE_LOGS:
        for source in sorted(run.work.glob(pattern)):
            if len(index) >= MAX_NATIVE_LOGS:
                break
            if not source.is_file() or source.is_symlink() or not source.resolve().is_relative_to(run.work):
                raise RuntimeError("Refusing a linked/escaping producer log")
            name = f"native-{len(index):02d}.log"
            with source.open("rb") as original:
                original.seek(max(0, source.stat().st_size - LOG_BYTES))
                (target / name).write_bytes(original.read(LOG_BYTES))
            index.append(f"{name}\t{source.relative_to(run.work)}\n")
    (run.evidence / "native-log-index.txt").write_text("".join(index))


def cleanup_work(run):
    events, failed = [], False
    try:
        require_owned(run.work, run.owner)
        try:
            status = stop_gradle(run, "final")
            events.append(f"final_gradle_stop_exit_status={status}")
            failed = status != 0
        except Exception as error:
            events.append(f"final_gradle_stop_error={error}")
            failed = True
        events.append(terminate_owned(run))
        preserve_native_logs(run)
        events.append("bounded_evidence_copied=true")
        require_owned(run.work, run.owner)
        shutil.rmtree(run.work)  # Only this marked run root; result/evidence/SDK/global caches stay.
        events.append("disposable_work_removed=true")
    except Exception as error:
        events.append(f"cleanup_error={error}")
        failed = True
    return events, failed


def cleanup(run):
    if not run.work.exists() and not run.work.is_symlink() and not run.evidence.exists():
        print("SKIPPED: no owned producer directories were initialized.")
        return 0
    require_owned(run.evidence, run.owner)
    events, failed = cleanup_work(run)
    events.append(f"cleanup_exit_status={int(failed)}")
    report = "\n".join(events) + "\n"
    (run.evidence / "cleanup-status.txt").write_text(report)
    print(report, end="")
    return int(failed)


def main():
    run = context()
    command = sys.argv[1:]
    if command in (["run", "setup"], ["run", "rebuild"]):
        return run_stage(run, command[1])
    if command == ["cleanup"]:
        return cleanup(run)
    for path in (run.work, run.evidence):
        require_owned(path, run.owner)
    if command == ["check-stage"] and os.environ.get(OWNER_ENV) == run.owner:
        return 0
    if command == ["stop-gradle"]:
        return stop_gradle(run, "after-batch")
    raise RuntimeError("Unknown producer lifecycle action or missing stage ownership")


if __name__ == "__main__":
    sys.exit(main())
