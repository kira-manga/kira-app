#!/usr/bin/env python3
"""UNBOUND Desktop4 compile; App13 bounded retained-leader Linux ownership, no Engine/Test lane."""
import hashlib
import json
import os
from pathlib import Path
import selectors
import shutil
import signal
import subprocess
import sys
import time
from app472569_apple import (CONTROL, SOURCE, INPUTS, SCRATCH, DESKTOP_TASKS, checked_request, carrier,
                            source_snapshot, read_json, digest, require, remove_scoped, retain_public)

ROOT = Path(os.environ['GITHUB_WORKSPACE']).resolve() / 'app'
WORK = Path(os.environ.get('APP472569_RUN', '/UNBOUND'))
REPORTS = WORK / 'reports'
OUTPUTS = ('build', 'core/build', 'domain/build', 'platform/build', 'presentation/build', '.gradle', '.kotlin')
MAINS = [':' + m + ':compileKotlinDesktop' for m in ('core', 'domain', 'platform', 'presentation')]
GROUPS, COMMANDS, ENV = {}, [], {}
WORK_OWNED, OWNERSHIP_SAFE = False, True
END, DEADLINE = 0, 0
MARKER = f'-Dapp472569.validation.root={WORK}'
GRADLE = ['./gradlew', '--gradle-user-home', str(WORK / 'gradle-home'), '--console=plain']


def record(path, value, limit=131072):
    data = (json.dumps(value, indent=2) + '\n').encode()
    require(len(data) <= limit and path.resolve() == path and not path.is_symlink(), 'JSON cap/alias')
    pending = path.with_name(path.name + '.pending')
    with pending.open('xb') as stream:
        stream.write(data)
    pending.replace(path)


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
    limit = 1048576
    deadline = min(time.monotonic() + seconds, END if shutdown else DEADLINE)
    COMMANDS.append(dict(argv=argv, log=name, exit=None))
    command, completed = COMMANDS[-1], False
    record(REPORTS / "commands.json", COMMANDS)
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
                if name != "compile.log" or not completed:
                    command["exit"] = stop_group(process)
            finally:
                process.stdout.close()
                record(REPORTS / "commands.json", COMMANDS)
    return command["exit"]

def stop_gradle(name, started):
    global OWNERSHIP_SAFE
    try:
        if started:
            require(run(GRADLE + ["--stop"], name, seconds=40, shutdown=True) == 0, "Gradle --stop failed")
    finally:
        for process in list(GROUPS.values()):
            stop_group(process)
        safe, OWNERSHIP_SAFE = OWNERSHIP_SAFE, False
        for entry in (WORK / "gradle-home/daemon/9.6.1").glob("daemon-*.out.log"):
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


def readgit(root, label, args, cleaning=False):
    name = label + '.log'
    require(run(['/usr/bin/git', '--no-optional-locks', '-C', str(root), *args], name, seconds=15, shutdown=cleaning) == 0,
            'Source/carrier read failed: ' + label)
    return (REPORTS / name).read_text().rstrip('\n')


def arguments():
    return GRADLE + DESKTOP_TASKS + ['--no-daemon', '--no-parallel', '--max-workers=1', '--no-build-cache',
        '--no-configuration-cache', '--stacktrace', '--project-cache-dir', str(WORK / 'project-cache'),
        '-Pkotlin.compiler.execution.strategy=in-process', '-Pkotlin.incremental=false', '-PkiraUseMavenLocal=false',
        '-Porg.gradle.java.installations.auto-download=false', '-Pandroid.builder.sdkDownload=false',
        '-Dorg.gradle.vfs.watch=false', '-Pkotlin.project.persistent.dir=' + str(WORK / 'kotlin'),
        f'-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g -XX:ActiveProcessorCount=2 {MARKER} '
        f'-Duser.home={WORK / "home"} -Djava.io.tmpdir={WORK / "tmp"}',
        '-I', str(CONTROL / 'ci/app472569-linux.init.gradle')]


def prepare_environment():
    global WORK_OWNED, ENV
    temporary = Path(os.environ['RUNNER_TEMP']).resolve()
    require(os.uname().sysname == 'Linux' and os.uname().machine == 'x86_64', 'Hosted Linux X64 required')
    require(WORK == temporary / ('app472569-desktop-' + os.environ['GITHUB_RUN_ID'] + '-1') and
            WORK.resolve() == WORK and not WORK.exists() and not WORK.is_symlink() and
            not any(c.isspace() for c in str(WORK)) and ROOT.parent not in WORK.parents, 'Fresh isolated owned run required')
    require(all(hasattr(os, name) for name in ('waitid', 'P_PID', 'WEXITED', 'WNOHANG', 'WNOWAIT', 'killpg')) and
            signal.getsignal(signal.SIGCHLD) == signal.SIG_DFL, 'Non-reaping process ownership APIs required')
    java = Path(os.environ['JAVA_HOME_21_X64']).resolve()
    require((java / 'bin/java').is_file() and (java / 'release').is_file() and
            (java / 'release').stat().st_size <= 65536 and 'JAVA_VERSION="21.' in (java / 'release').read_text(),
            'Installed JDK21 only; no JDK setup/download')
    require(shutil.disk_usage(temporary).free >= 8 * 1024**3, 'Fresh8GiB free-space floor required')
    WORK.mkdir(mode=0o700)
    WORK_OWNED = True
    for name in (*SCRATCH, 'reports'):
        (WORK / name).mkdir(mode=0o700)
    ENV = dict(PATH=f'{java / "bin"}:/usr/bin:/bin', HOME=str(WORK / 'home'), JAVA_HOME=str(java),
        GRADLE_USER_HOME=str(WORK / 'gradle-home'), KONAN_DATA_DIR=str(WORK / 'konan'),
        TMPDIR=str(WORK / 'tmp'), TMP=str(WORK / 'tmp'), TEMP=str(WORK / 'tmp'),
        JAVA_OPTS=f'-Xmx512m -XX:ActiveProcessorCount=2 {MARKER} -Duser.home={WORK / "home"} -Djava.io.tmpdir={WORK / "tmp"}',
        JAVA_TOOL_OPTIONS=f'-Djava.io.tmpdir={WORK / "tmp"}', GIT_OPTIONAL_LOCKS='0', GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL='/dev/null',
        KIRA_SOURCE_CONFIG_BASE_URL='', KIRA_SOURCE_CONFIG_PINNED_KEYS='', KIRA_APP_VERSION='1.0.5',
        APP472569_RUN=str(WORK), APP472569_PROOF=str(REPORTS / 'task-proof.json'), APP472569_SOURCE_SHA=SOURCE['sha'],
        LANG='C.UTF-8', LC_ALL='C.UTF-8', TZ='UTC', CI='true')
    for key in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        if os.environ.get(key):
            ENV[key] = os.environ[key]
    record(REPORTS / 'tools.json', {'python': sys.version, 'javaReleaseSha256': digest(java / 'release'),
        'javaBinarySha256': digest(java / 'bin/java'), 'jdk': 'Installed21; actual JVM version is in the task receipt',
        'bootstrap': 'Unchanged normal HTTPS wrapper; no distribution-byte authenticity claim; no restored cache, SDK or Engine intake'})


def output_proof():
    proof = read_json(REPORTS / 'task-proof.json')
    require(proof['schema'] == 'app472569-desktop-task-v1' and proof['sourceSha'] == SOURCE['sha'] and
            proof['requestedTasks'] == DESKTOP_TASKS and proof['requiredTasks'] == MAINS and
            proof['gradleVersion'] == '9.6.1' and set(proof['work']) == set(MAINS), 'Missing actual Desktop4 proof')
    entries, files, total = set(), {}, 0
    for task in MAINS:
        state, row = proof['states'][task], proof['work'][task]
        require(all(state[k] is True for k in ('executed', 'didWork', 'actionsReachedEnd')) and
                all(state[k] is False for k in ('skipped', 'upToDate', 'noSource')) and state['failure'] is None,
                'Required main did not work: ' + task)
        require(row['target'] == 'jvm' and row['jvmTarget'] == '17' and row['strategy'] in ('IN_PROCESS', 'in-process') and
                row['sourceCount'] > 0 and len(row['outputs']) == 1, 'Wrong/empty ordinary Desktop main')
        root = ROOT / task.split(':')[1] / 'build'
        path = ROOT / row['outputs'][0]
        require(path.is_dir() and path.resolve() == path and root in path.parents, 'Missing/aliased compiler output')
        classes = 0
        for item in path.rglob('*'):
            require(time.monotonic() < END and not item.is_symlink() and item.resolve() == item, 'Late/aliased output evidence')
            entries.add(item)
            require(len(entries) <= 4096, 'Output inventory exceeds4096 entries')
            if item.is_file():
                require(item not in files, 'Overlapping compiler ownership')
                total += item.stat().st_size
                require(total <= 67108864, 'Output inventory exceeds64MiB')
                files[item] = {'task': task, 'path': str(item.relative_to(ROOT)), 'bytes': item.stat().st_size, 'sha256': digest(item)}
                classes += item.suffix == '.class'
        require(classes > 0, 'No freshly compiled classes: ' + task)
    record(REPORTS / 'outputs.json', {'sourceSha': SOURCE['sha'], 'fileCount': len(files), 'entryCount': len(entries),
                                    'bytes': total, 'files': [files[path] for path in sorted(files)]}, limit=2097152)


def compile_once(request):
    global END, DEADLINE
    END = time.monotonic() + 1440
    DEADLINE = END - 240
    prepare_environment()
    pins = read_json(CONTROL / INPUTS)
    errors, identity, started, source_ready = [], {}, False, False
    result = {'schema': 'app472569-platform-result-v1', 'target': 'desktop', 'source': SOURCE,
              'tasks': DESKTOP_TASKS, 'carrierSha': os.environ['GITHUB_SHA'], 'passed': False, 'errors': errors,
              'scope': 'Four ordinary Desktop mains only; zero tests/statics, no Native/Engine/full app/runtime credit',
              'limits': {'workSeconds': 1200, 'cleanupReserveSeconds': 240, 'stopSecondsEach': 40, 'workers': 1,
                         'commandLogBytesEach': 1048576, 'outputInventoryEntries': 4096, 'outputInventoryBytes': 67108864}}
    record(REPORTS / 'request.json', request)
    record(REPORTS / 'result.json', result)
    try:
        identity['carrier'] = carrier(readgit, request)
        require(all(not (ROOT / name).exists() and not (ROOT / name).is_symlink() for name in OUTPUTS),
                'Preserve preexisting generated output')
        identity['before'] = source_snapshot(readgit, ROOT, pins, OUTPUTS, 'before')
        source_ready = True
        record(REPORTS / 'source.json', {'sha': SOURCE['sha'], 'tree': SOURCE['tree'], 'inputs': pins})
        record(REPORTS / 'identity.json', identity)
        require(shutil.disk_usage(WORK).free >= 8 * 1024**3, 'Fresh8GiB floor before Gradle')
        started = True
        try:
            result['compileSucceeded'] = run(arguments(), 'compile.log', seconds=1200) == 0
        finally:
            stop_gradle('stop-immediate.log', started)
        result['afterImmediateStop'] = {'absent': OWNERSHIP_SAFE and not GROUPS, 'recordedDaemonAbsenceChecked': True}
    except BaseException as error:
        errors.append('work: ' + type(error).__name__ + ': ' + str(error)[:500])
    finally:
        try:
            require(OWNERSHIP_SAFE and not GROUPS, 'Ownership uncertain; preserve evidence/outputs/scratch')
            if source_ready:
                if result.get('compileSucceeded'):
                    try:
                        output_proof()
                        result['outputProofPreserved'] = True
                    except Exception as error:
                        errors.append('evidence: ' + str(error)[:500])
                identity['atCapture'] = source_snapshot(readgit, ROOT, pins, OUTPUTS, 'capture', True)
                checked_request('desktop')
                require(identity['atCapture'] == identity['before'], 'Source changed; preserve outputs')
                record(REPORTS / 'identity.json', identity)
                result['outputsRemoved'] = remove_scoped(ROOT, OUTPUTS, END)
        except BaseException as error:
            errors.append('preserve/clean: ' + type(error).__name__ + ': ' + str(error)[:500])
        try:
            stop_gradle('stop-final.log', started)
            result['afterFinalStop'] = {'absent': OWNERSHIP_SAFE and not GROUPS, 'recordedDaemonAbsenceChecked': True}
            require(OWNERSHIP_SAFE and not GROUPS, 'Final ownership uncertain; preserve scratch')
            if source_ready:
                require(result.get('outputsRemoved') == list(OUTPUTS), 'Incomplete output cleanup; preserve scratch')
                identity['after'] = source_snapshot(readgit, ROOT, pins, (), 'after', True)
                checked_request('desktop')
                require(identity['after'] == identity['before'], 'Final source mismatch; preserve scratch')
                record(REPORTS / 'identity.json', identity)
                result['sourcePreserved'] = True
            require(OWNERSHIP_SAFE and not GROUPS, 'Readback ownership uncertain; preserve scratch')
            result['scratchRemoved'] = remove_scoped(WORK, SCRATCH, END)
        except BaseException as error:
            errors.append('final: ' + type(error).__name__ + ': ' + str(error)[:500])
        result['compileAttempted'] = started
        result['passed'] = bool(result.get('compileSucceeded') and result.get('outputProofPreserved') and
            result.get('afterImmediateStop', {}).get('absent') and result.get('afterFinalStop', {}).get('absent') and
            result.get('sourcePreserved') and result.get('outputsRemoved') == list(OUTPUTS) and
            result.get('scratchRemoved') == list(SCRATCH) and OWNERSHIP_SAFE and not GROUPS and not errors)
        logs = {name: REPORTS / name for name in ('compile.log', 'stop-immediate.log', 'stop-final.log') if (REPORTS / name).is_file()}
        retain_public(record, REPORTS, result, logs)
    return 0 if result['passed'] else 1


def main():
    os.umask(0o077)
    require(sys.argv[1:] in (['request'], ['compile']), 'One fixed phase required')
    request = checked_request('desktop')
    if sys.argv[1] == 'request':
        with Path(os.environ['GITHUB_OUTPUT']).open('a') as stream:
            stream.write('source_sha=' + SOURCE['sha'] + '\n')
        return 0
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, signal.default_int_handler)
    return compile_once(request)


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        print('APP472569 DESKTOP INCOMPLETE: ' + str(error), file=sys.stderr)
        raise SystemExit(1)
