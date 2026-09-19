#!/usr/bin/env python3
"""One fixed public App32 gate; no source snapshot, private artifact or dependency substitution."""
import json
import os
from pathlib import Path
import re
import select
import shutil
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

# The sole selector roster. Primary may extend it only with reviewed source/binding changes.
METHODS = {'Migration13To14RoomTest': ['retainedVersion13MigratesAndReopensWithoutChangingExistingTablesOrRows', 'retainedVersion16MigratesAndReopensWithoutChangingExistingTablesOrRows', 'freshAndMigratedCreationHaveIdenticalGuardsAndAbortTheWholeWriter'], 'ChapterArtifactSchema15Test': ['roomMigratesExported14WithoutChangingDownloadHistoryAndCustodySurvivesParentDeletion', 'compilerExportRetainsEvery14EntityAndAddsOnlyArtifactCustody', 'roomMigratesExported15RetainingLegacyReceiptWithoutInventingSourceCleanupAuthority', 'compilerExport16AddsOnlyTheNullableConversionRosterToHistorical15', 'roomMigratesExported16RetainingExactCustodyAndConversionRoster'], 'EffectiveSourceSelectionSchemaTest': ['freshAndMigratedCreationSeedOnlyOneAllocatorWithoutSelectingACatalog', 'invalidSelectionAndAllocatorWritesRollBackEveryEarlierWrite', 'reopenDoesNotRewindTheAllocatorOrRepairMissingAuthority', 'overflowAbortsWithoutPublishingAnUncoupledSelection', 'compilerExport17RetainsEvery16EntityAndAddsOnlyReaderAndSelectionTables'], 'NotificationSchema14Test': ['roomAcceptsMigratedExportedV13AndBothSchemasEnforceNotificationUniqueness']}
PACKAGE = 'me.manga.kira.data.local.'
TESTS = [PACKAGE + cls + '.' + method for cls, methods in METHODS.items() for method in methods]
TASK = ':data:local:desktopTest'
PROJECTS = (':core', ':data:local')
REQUIRED = [p + ':compileKotlinDesktop' for p in PROJECTS] + [':data:local:compileTestKotlinDesktop', ':data:local:kspKotlinDesktop', ':data:local:copyRoomSchemas', TASK]
MODULES = ('app', 'composeApp', 'desktopApp', 'core', 'domain', 'data', 'data/local', 'data/remote',
           'data/download', 'platform', 'presentation', 'ui', 'sources/contracts', 'sources/engine',
           'sources/config', 'sources/legacy')
CONTROLS = ('.github/workflows/app32-schema17-linux.yml', 'scripts/ci/app32-schema17-linux.init.gradle',
            'scripts/ci/app32-schema17-linux.py')
ROOT = Path.cwd().resolve()
GENERATED = ROOT / 'data/local/schemas/me.manga.kira.data.local.MangaDatabase/17.json'
EXPORT = ROOT / 'data/local/build/intermediates/room/schemas/kspKotlinDesktop/me.manga.kira.data.local.MangaDatabase/17.json'
WORK = Path(os.environ['RUNNER_TEMP']) / 'app32-schema17-work'
OUT = WORK.parent / 'app32-schema17-evidence'
OUTPUTS = [ROOT / name for name in ('build', '.gradle', '.kotlin')] + [ROOT / m / 'build' for m in MODULES]
# Explicit argv ownership for this fixed wrapper/daemon/Test-JVM graph, not all same-UID processes.
MARKER = ('-Dapp32.owned.root=' + str(WORK)).encode()
OWNER = dict(run=os.environ['GITHUB_RUN_ID'], sha=os.environ['GITHUB_SHA'], root=str(ROOT))
OUT_OWNED = False
RESOURCES = []
os.umask(0o077)


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def record(name, value):
    text = json.dumps(value, indent=2) + '\n'
    require(len(text.encode()) <= 65536, 'JSON evidence exceeds 64KiB')
    (OUT / name).write_text(text)


def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args], text=True,
                                   env={**os.environ, 'GIT_OPTIONAL_LOCKS': '0'}).strip()


def source():
    return dict(head=git('rev-parse', 'HEAD'), tree=git('rev-parse', 'HEAD^{tree}'),
                clean=not git('status', '--porcelain', '--untracked-files=all'))


def resources(admission=False):
    memory = int(next(line.split()[1] for line in Path('/proc/meminfo').read_text().splitlines()
                      if line.startswith('MemAvailable:'))) * 1024
    disk = shutil.disk_usage(ROOT).free
    RESOURCES.append(dict(time_utc=time.time(), memory_available_bytes=memory, disk_free_bytes=disk))
    record('resources.json', RESOURCES)
    require(disk >= (8 if admission else 3) * 2**30 and memory >= (6 if admission else 1) * 2**30,
            'Resource floor reached; stop without reclaiming shared images/caches')


def prepare():
    global OUT_OWNED
    OUT.mkdir(exist_ok=False)
    OUT_OWNED = True
    record('evidence-owned.json', dict(owner=OWNER, device=OUT.stat().st_dev, inode=OUT.stat().st_ino))
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    require(os.environ['GITHUB_EVENT_NAME'] == 'push' and os.environ['GITHUB_REPOSITORY'] == 'kira-manga/kira-app'
            and os.environ['GITHUB_REF'] == 'refs/heads/remediation/app32-schema17-linux-20260919-02'
            and os.environ['GITHUB_RUN_ATTEMPT'] == '1' and event.get('created') is True
            and event['repository']['private'] is False, 'Sole admitted new public branch push/attempt required')
    require(os.environ['RUNNER_OS'] == 'Linux' and os.environ['RUNNER_ARCH'] == 'X64'
            and ROOT == Path(os.environ['GITHUB_WORKSPACE']).resolve(), 'Hosted Linux x64 checkout required')
    sha, tree = os.environ['APP32_SOURCE_SHA'], os.environ['APP32_SOURCE_TREE']
    require(re.fullmatch('[0-9a-f]{40}', sha) and re.fullmatch('[0-9a-f]{40}', tree), 'Bind reviewed source pins')
    before = source()
    require(before['clean'] and before['head'] == os.environ['GITHUB_SHA']
            and git('rev-parse', 'HEAD^@') == sha and git('rev-parse', sha + '^{tree}') == tree,
            'Clean event commit must have exactly the reviewed source-only parent/tree')
    require(git('diff-tree', '--no-commit-id', '--no-renames', '--name-status', '-r', sha, 'HEAD').splitlines()
            == ['A\t' + p for p in CONTROLS], 'Carrier may add only the three reviewed controls')
    require(all(not p.exists() and not p.is_symlink() for p in OUTPUTS), 'Preserve preexisting build/cache paths')
    require(not GENERATED.exists() and not GENERATED.is_symlink(), 'Schema17 must be genuinely generated')
    record('source-before.json', before)
    java = Path(os.environ['JAVA_HOME_21_X64'])
    require(re.search(r'^JAVA_VERSION="21[.\"]', (java / 'release').read_text(), re.M), 'Installed JDK21 only')
    sdk = Path(os.environ['ANDROID_HOME']) if os.environ.get('ANDROID_HOME') else None
    platforms = [str(sdk / 'platforms' / name) for name in ('android-37', 'android-37.0')
                 if sdk and (sdk / 'platforms' / name / 'android.jar').is_file()]
    record('runtime.json', dict(java_home=str(java), installed_sdk37=platforms,
                               sdk_policy='Use installed SDK only if required; never acquire or substitute'))
    resources(admission=True)
    require(WORK.resolve() == WORK and not WORK.is_symlink(), 'Canonical private scratch required')
    WORK.mkdir(exist_ok=False)
    record('owned.json', dict(owner=OWNER, device=WORK.stat().st_dev, inode=WORK.stat().st_ino))
    for name in ('home', 'tmp', 'gradle', 'project-cache', 'konan'):
        (WORK / name).mkdir()
    return runtime_env()


def runtime_env():
    # Deliberately exclude Actions/package/signing/source-remote credentials and external init options.
    java = Path(os.environ['JAVA_HOME_21_X64'])
    env = {k: os.environ[k] for k in ('PATH', 'LANG', 'LC_ALL', 'ANDROID_HOME', 'ANDROID_SDK_ROOT') if k in os.environ}
    env.update(JAVA_HOME=str(java), PATH=str(java / 'bin') + ':' + env['PATH'], HOME=str(WORK / 'home'),
               TMPDIR=str(WORK / 'tmp'), GRADLE_USER_HOME=str(WORK / 'gradle'), KONAN_DATA_DIR=str(WORK / 'konan'),
               APP32_OWNED_ROOT=str(WORK), APP32_APP_ROOT=str(ROOT), APP32_METHODS=json.dumps(TESTS),
               JAVA_OPTS='-Xmx256m -XX:ActiveProcessorCount=2', GIT_TERMINAL_PROMPT='0',
               JAVA_TOOL_OPTIONS=f'-Duser.home={WORK / "home"} -Djava.io.tmpdir={WORK / "tmp"}')
    return env


def run_gradle(env):
    command = ['./gradlew', MARKER.decode(), TASK]
    for test in TESTS:
        command += ['--tests', test]
    command += ['--no-daemon', '--no-parallel', '--max-workers=1', '--no-build-cache', '--no-configuration-cache',
                '--no-configure-on-demand', '--console=plain', '--stacktrace',
                '-Pkotlin.compiler.execution.strategy=in-process', '-Porg.gradle.java.installations.auto-download=false',
                '-Pandroid.builder.sdkDownload=false', '-PkiraUseMavenLocal=false', '-Dorg.gradle.vfs.watch=false',
                '-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g -XX:ActiveProcessorCount=2 ' + MARKER.decode(),
                '--project-cache-dir', str(WORK / 'project-cache'), '-I', CONTROLS[1]]
    record('command.json', command)
    return capture(command, env, 'gradle.log', 18 * 60, 16 * 1024 * 1024, monitor=True)


def capture(command, env, name, seconds, remaining, monitor=False):
    deadline, next_check = time.monotonic() + seconds, time.monotonic() + 60
    process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, start_new_session=True)
    try:
        with (OUT / name).open('wb') as log:
            while True:
                require(time.monotonic() < deadline, name + ': command deadline exceeded')
                if monitor and time.monotonic() >= next_check:
                    resources()
                    next_check = time.monotonic() + 60
                if not select.select([process.stdout], [], [], 0.2)[0]:
                    continue
                chunk = os.read(process.stdout.fileno(), 65536)
                if not chunk:
                    break
                log.write(chunk[:remaining])
                remaining -= len(chunk)
                require(remaining >= 0, name + ': log limit reached; retained prefix only')
        return process.wait(timeout=max(1, deadline - time.monotonic()))
    finally:
        process.stdout.close()


def owned_processes(sig=None):
    """Signal exact argv-marked launchers/JVMs only; unreadable cmdlines fail closed, never mean absence."""
    count = 0
    for entry in Path('/proc').iterdir():
        if not entry.name.isdigit() or int(entry.name) == os.getpid():
            continue
        fd = None
        try:
            if entry.stat().st_uid != os.getuid() or MARKER not in (entry / 'cmdline').read_bytes().split(b'\0'):
                continue
            fd = os.pidfd_open(int(entry.name))
            if entry.stat().st_uid != os.getuid() or MARKER not in (entry / 'cmdline').read_bytes().split(b'\0'):
                continue
            count += 1
            if sig is not None:
                signal.pidfd_send_signal(fd, sig)
        except (FileNotFoundError, ProcessLookupError):
            pass
        finally:
            if fd is not None:
                os.close(fd)
    return count


def stop_owned():
    if not (OUT / 'owned.json').is_file():
        return  # Admission failed before creating anything owned.
    identity = json.loads((OUT / 'owned.json').read_text())
    require(identity['owner'] == OWNER, 'Ownership receipt belongs to another job/source')
    if WORK.exists():
        require(not WORK.is_symlink() and WORK.resolve() == WORK and
                identity['device'] == WORK.stat().st_dev and identity['inode'] == WORK.stat().st_ino,
                'Scratch ownership changed')
    if (OUT / 'stop.json').is_file():
        stopped = json.loads((OUT / 'stop.json').read_text())
    else:
        stopped = dict(attempted=False, exit_code=None, error=None, term_signals=0, kill_signals=0)
        installed = list((WORK / 'gradle/wrapper/dists').glob('gradle-9.6.1-bin/*/gradle-9.6.1/bin/gradle'))
        if len(installed) == 1 and (installed[0].parents[2] / 'gradle-9.6.1-bin.zip.ok').is_file():
            stopped['attempted'] = True
            try:
                stopped['exit_code'] = capture(['./gradlew', MARKER.decode(), '--offline', '--stop', '--console=plain'],
                                               runtime_env(), 'stop.log', 35, 128 * 1024)
            except BaseException as error:
                stopped['error'] = type(error).__name__ + ': ' + str(error)[:512]
    try:
        for sig, seconds, key in ((signal.SIGTERM, 8, 'term_signals'), (signal.SIGKILL, 4, 'kill_signals')):
            stopped[key] += owned_processes(sig)
            deadline = time.monotonic() + seconds
            while owned_processes() and time.monotonic() < deadline:
                time.sleep(0.2)
        stopped['remaining'] = owned_processes()
        require(stopped['remaining'] == 0, 'Owned runtime remains; preserve files for ephemeral runner disposal')
    finally:
        record('stop.json', stopped)


def cleanup():
    if not (OUT / 'owned.json').is_file():
        return
    stop_owned()
    if GENERATED.exists():
        retained = OUT / '17.json'
        require(retained.is_file() and not GENERATED.is_symlink() and GENERATED.read_bytes() == retained.read_bytes(),
                'Preserve unverified generated schema rather than deleting it')
        GENERATED.unlink()
    for path in OUTPUTS + [WORK]:
        require(not path.is_symlink() and path.resolve() == path and not path.is_mount(), 'Ambiguous cleanup path')
        if path.exists():
            require(path.is_dir() and path.stat().st_uid == os.getuid(), 'Not an owned output directory')
            shutil.rmtree(path)
    record('cleanup.json', dict(owned_processes_absent=True, fresh_outputs_absent=all(not p.exists() for p in OUTPUTS + [WORK]), generated_schema_absent=not GENERATED.exists() and not GENERATED.is_symlink()))


def verify():
    after = source()
    record('source-after.json', after)
    before = json.loads((OUT / 'source-before.json').read_text())
    generated = GENERATED.is_file() and EXPORT.is_file()
    if generated:
        require(not GENERATED.is_symlink() and not EXPORT.is_symlink() and GENERATED.stat().st_size <= 1024 * 1024,
                'Bounded ordinary compiler export required')
        require(GENERATED.read_bytes() == EXPORT.read_bytes(), 'Copy must equal fresh Room compiler export')
        require(json.loads(GENERATED.read_text())['database']['version'] == 17, 'Schema17 required')
        shutil.copyfile(GENERATED, OUT / '17.json')
    source_ok = before['head'] == after['head'] and before['tree'] == after['tree'] and (
        git('status', '--porcelain', '--untracked-files=all') == '?? ' + str(GENERATED.relative_to(ROOT)) if generated else after['clean'])
    record('schema-generation.json', dict(generated=generated, tracked_source_unchanged=source_ok))
    lines = (OUT / 'gradle.log').read_text(errors='replace').splitlines()
    graphs = [json.loads(line.removeprefix('APP32_GRAPH ')) for line in lines if line.startswith('APP32_GRAPH ')]
    completed = [json.loads(line.removeprefix('APP32_COMPLETE ')) for line in lines if line.startswith('APP32_COMPLETE ')]
    record('graph.json', graphs)
    record('task-completions.json', completed)
    compilers_ok = sorted(c['path'] for c in completed) == sorted(REQUIRED) and all(
        c['executed'] is True and c['didWork'] is True and c['skipped'] is False and c['upToDate'] is False
        and c['noSource'] is False and c['failure'] is None for c in completed)
    report_root = ROOT / 'data/local/build/test-results/desktopTest'
    require(report_root.resolve() == report_root, 'Do not follow report directory links')
    reports = sorted(report_root.glob('*.xml'))
    require(len(reports) <= 8, 'Unexpected XML volume')
    expected = sorted('TEST-' + PACKAGE + cls + '.xml' for cls in METHODS)
    observed, suites_ok = [], True
    (OUT / 'reports').mkdir(exist_ok=True)
    for path in reports:
        require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 1024 * 1024,
                'XML must be regular and <=1MiB')
        shutil.copyfile(path, OUT / 'reports' / path.name)
        suite = ET.parse(path).getroot()
        cases = suite.findall('testcase')
        suites_ok &= suite.tag == 'testsuite' and int(suite.get('tests', '-1')) == len(cases) and all(
            int(suite.get(k, '-1')) == 0 for k in ('errors', 'failures', 'skipped'))
        for case in cases:
            outcomes = [child.tag for child in case if child.tag in ('failure', 'error', 'skipped')]
            observed.append(dict(test=case.get('classname', '') + '.' + case.get('name', '').removesuffix('[desktop]'),
                                 outcomes=outcomes))
    record('observed-tests.json', observed)
    require(source_ok, 'Source changed beyond the sole generated schema17')
    require(generated, 'No fresh schema17 export')
    require(len(graphs) == 1 and len(graphs[0]) == 25 and len(set(graphs[0])) == 25, 'Normal graph evidence missing')
    require(compilers_ok, 'Ordinary compilers, Room generation/copy and Test must execute successfully')
    require([p.name for p in reports] == expected and suites_ok and sorted(c['test'] for c in observed) == sorted(TESTS)
            and all(not c['outcomes'] for c in observed), 'Missing/extra/duplicate/failing/skipped method evidence')


def main():
    if sys.argv[1:] == ['discard']:
        if OUT.exists():
            identity = json.loads((OUT / 'evidence-owned.json').read_text())
            require(identity == dict(owner=OWNER, device=OUT.stat().st_dev, inode=OUT.stat().st_ino)
                    and not OUT.is_symlink() and OUT.resolve() == OUT, 'Evidence ownership changed')
            require(not owned_processes() and all(not p.exists() for p in OUTPUTS + [WORK]),
                    'Keep evidence when runtime/output cleanup is incomplete')
            shutil.rmtree(OUT)
        return 0
    if sys.argv[1:] == ['cleanup']:
        # Fallback after an interrupted run: stop before reading reports, preserve evidence, then remove outputs.
        if (OUT / 'owned.json').is_file() and WORK.exists():
            stop_owned()
            errors = []
            try:
                verify()
            except BaseException as error:
                errors.append(type(error).__name__ + ': ' + str(error)[:512])
            finally:
                record('result.json', dict(status='FAIL_INTERRUPTED_OR_INCOMPLETE', errors=errors))
                cleanup()
        else:
            cleanup()
        return 0
    require(sys.argv[1:] == ['run'], 'Use run, cleanup or discard only')
    result = dict(status='FAIL', exit_code=None, errors=[], selected_methods=TESTS)
    try:
        result['exit_code'] = run_gradle(prepare())
    except BaseException as error:
        result['errors'].append(type(error).__name__ + ': ' + str(error)[:512])
    finally:
        if OUT_OWNED:
            for label, action in (('stop', stop_owned), ('verify', verify), ('cleanup', cleanup)):
                try:
                    action()
                except BaseException as error:
                    result['errors'].append(label + ': ' + type(error).__name__ + ': ' + str(error)[:512])
            if (OUT / 'stop.json').is_file():
                result['shutdown'] = json.loads((OUT / 'stop.json').read_text())
                if result['shutdown'].get('kill_signals') or (result['shutdown']['attempted'] and
                        (result['shutdown']['exit_code'] != 0 or result['shutdown']['error'])):
                    result['errors'].append('Forced or failed Gradle shutdown; inspect stop.json')
            result['status'] = 'PASS' if result['exit_code'] == 0 and not result['errors'] else 'FAIL'
            record('result.json', result)
            print(json.dumps(result))
    return 0 if result['status'] == 'PASS' else 1


if __name__ == '__main__':
    def interrupted(signum, frame):
        raise InterruptedError('Runner interrupted validation')
    signal.signal(signal.SIGTERM, interrupted)
    sys.exit(main())
