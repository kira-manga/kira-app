"""Targeted deletion AppleNative8 on the exact source; no framework, Engine checkout or host/UI execution."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import signal
import sys
import time

CONTROL = Path(__file__).resolve().parents[1]
BRANCH = 'candidate/app29-apple-native8-20260917-02'
REQUEST = 'ci/app5-ios-host.request.json'
FILES = ('.github/workflows/app5-ios-host.yml', 'ci/app-seven-apple-native.init.gradle', 'ci/app-seven-apple-native.py', 'ci/app-seven-apple.native-tests.json', 'ci/app5-ios-host-commands.py', 'ci/app5-ios-host-evidence.py', 'ci/app5-ios-host.py', 'ci/app5-ios-host.source-pins.json', 'ci/app8-apple.py')
APP = {'repository': 'kira-manga/kira-app', 'sha': '7aed03749876668fb0ebb9f434fd22fdafe27907', 'tree': '4005fe82a3b2ddc2b37e3080862d18bef0935ced'}
OWNER_SHA = '9f69c87182eba29030fcd65c49b4e8459a41749440dab9767f3fbfe0c02db6cf'
LIMITS = {'nativeSeconds': 1200, 'workSeconds': 1440, 'controllerSeconds': 1680, 'cleanupSeconds': 240, 'stopSeconds': 40, 'jobMinutes': 35, 'gradleWorkers': 1, 'nativeThreads': 1, 'jvmHeapGiB': 3, 'metaspaceMiB': 768, 'diskFloorGiB': 8, 'nativeCommandLogBytes': 4194304, 'otherCommandLogBytes': 1048576, 'aggregateCommandLogBytes': 8388608, 'otherAggregateCommandLogBytes': 4194304, 'cleanupCommandLogBytes': 1048576, 'nativeBinaryFingerprintBytes': 1073741824}
NATIVE_TASKS = (':data:remote:iosSimulatorArm64Test',)
XCODE = '/Applications/Xcode_26.4.1.app/Contents/Developer'


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def error_diagnostic(stage, error):
    # No exception text, source lines, function names, locals, environment or tool output.
    # Unknown names are fixed tokens, not attacker-controlled labels or path basenames.
    try:
        kinds = {'RuntimeError', 'ValueError', 'TypeError', 'KeyError', 'IndexError', 'AssertionError',
                 'OSError', 'FileNotFoundError', 'PermissionError', 'TimeoutError', 'TimeoutExpired',
                 'CalledProcessError', 'JSONDecodeError', 'UnicodeDecodeError'}
        row = {'stage': stage if stage in ('attempt', 'native-tests', 'native-preparation', 'entry') else 'unknown',
               'type': type(error).__name__ if type(error).__name__ in kinds else 'OtherException',
               'frames': []}
        admitted = {Path(name).name for name in FILES}
        trace = error.__traceback__
        for _ in range(8):
            if trace is None:
                break
            name, line = Path(trace.tb_frame.f_code.co_filename).name, trace.tb_lineno
            row['frames'].append({'source': name if name in admitted else 'external',
                                  'line': line if type(line) is int and 0 < line <= 1000000 else 0})
            trace = trace.tb_next
        row['truncated'] = trace is not None
        print('APP5_NATIVE_ERROR ' + json.dumps(row, sort_keys=True), flush=True)
    except Exception:
        pass  # Diagnostic failure must never replace the original failure or skip owned cleanup.


def final_diagnostic(result, scratch):
    try:
        count = result.get('kotlinTestsExecuted')
        known = type(count) is int and 0 <= count <= 8
        row = {key: result.get(key) is True for key in (
            'nativeAttempted', 'nativeTestsSucceeded', 'nativeProofPreserved', 'simulatorRemoved',
            'sourcePreserved', 'normalOwnedCompletion', 'cancelled', 'passed')}
        row.update(afterWorkAbsent=result.get('afterWorkStop', {}).get('absent') is True,
                   afterFinalAbsent=result.get('afterFinalStop', {}).get('absent') is True,
                   scratchRemoved=result.get('scratchRemoved') == list(scratch),
                   retentionReady=isinstance(result.get('retention'), dict) and bool(result['retention']),
                   testCountKnown=known, testCount=count if known else 0,
                   errorCount=len(result['errors']) if type(result.get('errors')) is list else 0)
        print('APP5_NATIVE_FINAL ' + json.dumps(row, sort_keys=True), flush=True)
    except Exception:
        pass  # Best-effort booleans/counts only; never an admission/retention/ownership bypass.


def unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'Duplicate JSON key')
        result[key] = value
    return result


def read(path):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 65536, 'Invalid bounded input')
    return json.loads(path.read_text(), object_pairs_hook=unique)


def controls(request):
    require(set(request['controls']) == set(FILES), 'Exactly nine non-request control hashes required')
    for name, expected in request['controls'].items():
        path = CONTROL / name
        require(re.fullmatch('[0-9a-f]{64}', expected or '') and path.is_file() and not path.is_symlink(),
                'Unbound/missing control')
        require(hashlib.sha256(path.read_bytes()).hexdigest() == expected, 'Changed control: ' + name)
    require(request['controls']['ci/app8-apple.py'] == OWNER_SHA, 'Different ownership implementation')


def admit():
    request = read(CONTROL / REQUEST)
    fixed = dict(schema='app-29-apple-native8-v1', status='PRIMARY_BOUND_FOR_REVIEW',
        authorization='APP_29_APPLE_NATIVE8_ONE_ATTEMPT_AUTHORIZED',
        acquisitionAuthorization='PUBLIC_DECLARED_WRAPPER_GRADLE_NATIVE_ONLY',
        mode='NATIVE_ONLY', ref=BRANCH, limits=LIMITS,
        app=APP, nativeTasks=list(NATIVE_TASKS), expectedRunAttempt=1)
    require(BRANCH != 'UNBOUND' and all(re.fullmatch('[0-9a-f]{40}', APP[key]) for key in ('sha', 'tree')),
            'Candidate/reference remains UNBOUND')
    require(set(request) == set(fixed) | {'controls'} and type(request['expectedRunAttempt']) is int
            and all(request.get(k) == v for k, v in fixed.items()), 'UNBOUND/unauthorized request')
    expected = {'GITHUB_ACTIONS': 'true', 'GITHUB_REPOSITORY': APP['repository'], 'GITHUB_EVENT_NAME': 'push',
        'GITHUB_REF': 'refs/heads/' + BRANCH, 'GITHUB_RUN_ATTEMPT': '1', 'RUNNER_OS': 'macOS',
        'RUNNER_ARCH': 'ARM64', 'RUNNER_ENVIRONMENT': 'github-hosted',
        'GITHUB_WORKFLOW_REF': APP['repository'] + '/.github/workflows/app5-ios-host.yml@refs/heads/' + BRANCH}
    require(all(os.environ.get(k) == v for k, v in expected.items()), 'Wrong public hosted invocation')
    require(re.fullmatch('[1-9][0-9]*', os.environ.get('GITHUB_RUN_ID', '')), 'Invalid run identity')
    require(re.fullmatch('[0-9a-f]{40}', os.environ.get('GITHUB_SHA', ''))
            and os.environ.get('GITHUB_WORKFLOW_SHA') == os.environ['GITHUB_SHA'], 'Wrong workflow/event carrier')
    event = read(Path(os.environ['GITHUB_EVENT_PATH']))
    require(event['repository']['private'] is False and event['repository']['full_name'] == APP['repository']
            and event['after'] == event['head_commit']['id'] == os.environ['GITHUB_SHA']
            and event['ref'] == os.environ['GITHUB_REF'] and event['deleted'] is False and event['forced'] is False,
            'Not the bound ordinary public push')
    controls(request)
    return request


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)  # Definitions only; never the accepted owner's App8 entry point.
    return module


def git(c, root, label, args, cleaning=False):
    return c['commands'].call(['/usr/bin/git', '-C', str(root), *args], label,
        seconds=15, end=c['end'] if cleaning else c['workEnd'], cleaning=cleaning).strip()


def checkout(c, root, expected, label, cleaning=False):
    require(git(c, root, label + '-sha', ['rev-parse', 'HEAD'], cleaning) == expected['sha'], 'Wrong source SHA')
    require(git(c, root, label + '-tree', ['rev-parse', 'HEAD^{tree}'], cleaning) == expected['tree'], 'Wrong source tree')
    require(not git(c, root, label + '-clean', ['status', '--porcelain', '--untracked-files=all'], cleaning), 'Dirty checkout')
    require(not git(c, root, label + '-ignored', ['ls-files', '--others', '--ignored', '--exclude-standard'], cleaning),
            'Ignored/private inputs or unexpected output remain')
    return dict(expected, root=str(root), clean=True)


def carrier(c):
    sha = os.environ['GITHUB_SHA']
    require(git(c, CONTROL, 'carrier-parent', ['rev-list', '--parents', '-n', '1', 'HEAD']) == sha + ' ' + APP['sha'],
            'Carrier must directly extend the exact source')
    require(git(c, CONTROL, 'carrier-parent-tree', ['rev-parse', 'HEAD^1^{tree}']) == APP['tree'], 'Wrong parent tree')
    changed = git(c, CONTROL, 'carrier-delta', ['diff-tree', '--no-commit-id', '--name-status', '--no-renames',
                                             '-r', 'HEAD^1', 'HEAD']).splitlines()
    require(sorted(changed) == sorted('A\t' + name for name in (*FILES, REQUEST)), 'Carrier must add only ten controls')
    require(not git(c, CONTROL, 'carrier-clean', ['status', '--porcelain', '--untracked-files=all']), 'Dirty carrier')
    return {'sha': sha, 'parent': APP, 'addedControls': sorted((*FILES, REQUEST)), 'hashes': c['request']['controls']}


def prepare(c):
    e, roots, reports = c['e'], c['roots'], c['reports']
    pins = e.read(CONTROL / 'ci/app5-ios-host.source-pins.json')
    require(pins['status'] == 'PRIMARY_BOUND_FOR_REVIEW' and pins['schema'] == 'app5-ios-host-source-pins-v1',
            'Source pins remain UNBOUND')
    require(pins['app_modules'] == list(e.MODULES), 'Wrong module scope')
    identities = {'carrier': carrier(c), 'before': {}}
    for role, expected in (('app', APP),):
        require(all(pins[role][key] == value for key, value in expected.items()), 'Different source pins')
        identities['before'][role] = checkout(c, roots[role], expected, role)
        require(all(not (roots[role] / p).exists() and not (roots[role] / p).is_symlink() for p in e.OUTPUTS[role]),
                'Preexisting outputs are not owned')
    c['pins'], c['identity'] = pins, identities
    c['before'] = {role: e.inputs(root, role, pins) for role, root in roots.items()}
    c['nativeSource'] = c['native'].source_inputs(c)
    e.save(reports / 'source.json', c['before'])
    e.save(reports / 'native-source.json', c['nativeSource'])
    e.save(reports / 'identity.json', identities)
    c['sourceReady'] = True


def gradle_argv(c):
    run = c['run']
    tasks = c['native'].task_arguments(c)
    init = 'ci/app-seven-apple-native.init.gradle'
    return [str(c['roots']['app'] / 'gradlew'), '-p', str(c['roots']['app']), *tasks,
        '--no-daemon', '--no-parallel', '--max-workers=1', '--no-build-cache', '--no-configuration-cache',
        '--console=plain', '--stacktrace', '--project-cache-dir', str(run / 'project-cache'),
        '-I', str(CONTROL / init),
        '-Pkotlin.compiler.execution.strategy=in-process', '-Pkotlin.native.parallelThreads=1',
        '-Pkotlin.incremental=false', '-PkiraUseMavenLocal=false',
        '-Porg.gradle.java.installations.auto-download=false', '-Pandroid.builder.sdkDownload=false',
        '-Dorg.gradle.vfs.watch=false', '-Pkotlin.project.persistent.dir=' + str(run / 'kotlin'),
        '-Duser.home=' + str(run / 'home'),
        f'-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=768m -Dapp5.apple.owner={run.name} '
        f'-Duser.home={run / "home"} -Djava.io.tmpdir={run / "tmp"}']


def absence(c, cleaning=False, final=False):
    commands = c['commands']
    settled = commands.drain()
    rows = commands.census(cleaning=cleaning)
    markers = [str(c['run']), *(str(p) for p in c['roots'].values()), 'app5.apple.owner=' + c['run'].name]
    if final and c['simulator'].get('udid'):
        markers.append(c['simulator']['udid'])
    executables = c['nativeExecutables']
    remaining = [r for r in rows if r['pid'] != os.getpid() and not r['state'].startswith('Z')
                 and (any(marker in r['command'] for marker in markers) or r['executable'] in executables)]
    # Native executable-name matches are observation/refusal only, never new signal authority.
    return {'absent': settled and not remaining, 'groupsSettled': settled,
            'remaining': [{k: v for k, v in r.items() if k != 'command'} for r in remaining]}


def stop(c, label, cleaning=False):
    if c['started']:
        require(label not in c['stopAttempts'], 'Repeated Gradle stop label')
        c['stopAttempts'].append(label)
        c['commands'].retire_observer()
        c['commands'].call([str(c['roots']['app'] / 'gradlew'), '--stop'], label, seconds=40,
                          end=c['end'] if cleaning else c['workEnd'], cleaning=cleaning)
        c['needsStop'] = False
    return True


def attempt(c, label, operation):
    try:
        return operation()
    except Exception as error:
        error_diagnostic('attempt', error)
        c['result']['errors'].append(label + ': ' + str(error)[:500])
        return None


def unchanged_inputs(c):
    after = {role: c['e'].inputs(root, role, c['pins']) for role, root in c['roots'].items()}
    native_after = c['native'].source_inputs(c)
    c['identity']['sourceInputsUnchanged'] = after == c['before'] and native_after == c['nativeSource']
    c['e'].save(c['reports'] / 'identity.json', c['identity'])
    require(c['identity']['sourceInputsUnchanged'], 'Source inputs changed; retain outputs')
    controls(c['request'])


def native_phase(c):
    try:
        require(time.monotonic() < c['workEnd'] and not c['owner'].CANCELLED, 'No Native work window')
        require(shutil.disk_usage(c['run']).free >= 8 * 1024**3, 'Native phase needs the same 8GiB disk floor')
        c['native'].create_simulator(c)
        # One Gradle invocation, only data:remote deletion8, one already-owned simulator.
        c['result']['nativeAttempted'] = c['started'] = c['needsStop'] = True
        c['commands'].call(gradle_argv(c), 'native-tests', seconds=LIMITS['nativeSeconds'],
            end=c['workEnd'], extra=c['native'].environment(c))
        c['result']['nativeTestsSucceeded'] = True
    except Exception as error:
        error_diagnostic('native-tests', error)
        c['result']['errors'].append('native tests: ' + str(error)[:500])
    finally:
        if c['result'].get('nativeAttempted'):
            attempt(c, 'native stop', lambda: stop(c, 'gradle-stop-native'))
            names = attempt(c, 'native executable identities', lambda: c['native'].executable_names(c))
            if names is not None:
                c['nativeExecutables'].update(names)
            result = attempt(c, 'native absence', lambda: absence(c)) or {'absent': False}
            c['result']['afterNativeStop'] = result
            if result['absent']:
                collect_native(c, cleaning=False)


def collect_native(c, cleaning):
    if c.get('nativeCollected') or not c['result'].get('nativeAttempted'):
        return
    c['nativeCollected'] = True
    context = dict(c, end=c['end'] if cleaning else c['workEnd'], evidenceCleaning=cleaning)
    proof = attempt(c, 'native evidence', lambda: c['native'].collect(context))
    if proof is not None:
        c['result']['nativeProofPreserved'] = proof['passed']
        c['result']['kotlinTestsExecuted'] = proof['testsExecuted']
        c['result']['nativeXmlCounts'] = proof['xmlCounts']
        c['result']['errors'].extend('native evidence: ' + error for error in proof['errors'])


def collect_and_remove(c):
    e, result = c['e'], c['result']
    if c['needsStop']:
        attempt(c, 'cleanup stop', lambda: stop(c, 'gradle-stop-cleanup', cleaning=True))
    result['afterWorkStop'] = absence(c, cleaning=True)
    require(result['afterWorkStop']['absent'], 'Ambiguous/live ownership: retain outputs')
    collect_native(c, cleaning=True)
    if not c['sourceReady']:
        return
    unchanged_inputs(c)
    result['outputsRemoved'] = {role: e.remove_scoped(root, e.OUTPUTS[role], c['end']) for role, root in c['roots'].items()}


def final_source(c):
    if c['sourceReady'] and c['result'].get('outputsRemoved'):
        c['identity']['after'] = {role: checkout(c, root, APP, role + '-final', True)
                                  for role, root in c['roots'].items()}
        require(not git(c, CONTROL, 'carrier-final-clean', ['status', '--porcelain', '--untracked-files=all'], True),
                'Carrier changed')
        controls(c['request'])
        c['e'].save(c['reports'] / 'identity.json', c['identity'])
        return True
    return False


def finish(c):
    result, e = c['result'], c['e']
    attempt(c, 'final stop', lambda: stop(c, 'gradle-stop-final', cleaning=True))
    result['sourcePreserved'] = attempt(c, 'final source', lambda: final_source(c)) is True
    result['afterFinalStop'] = attempt(c, 'final absence', lambda: absence(c, cleaning=True, final=True)) or {'absent': False}
    if result['afterFinalStop']['absent'] and result.get('simulatorRemoved') and (result['sourcePreserved'] or not c['sourceReady']):
        result['scratchRemoved'] = attempt(c, 'scratch cleanup', lambda: e.remove_scoped(c['run'], e.SCRATCH, c['end']))
    else:
        c['commands'].retire_observer()  # Existing owned leaf only; never loop or signal inferred workers.
    result.update(cancelled=c['owner'].CANCELLED, normalOwnedCompletion=c['commands'].normal(), attempted=c['started'])
    result['commandLogBudget'] = dict(c['commands'].log_budget)
    result['stopAttempts'] = c['stopAttempts']
    result['nativeExecutableNames'] = sorted(c['nativeExecutables'])
    result['testsExecuted'] = result.get('kotlinTestsExecuted', 0)
    retention = attempt(c, 'bounded public retention', lambda: e.retain(c['commands'], c['reports'], result))
    result['retention'] = retention
    result['passed'] = bool(result.get('nativeTestsSucceeded') and result.get('nativeProofPreserved') and result.get('simulatorRemoved')
        and result.get('kotlinTestsExecuted') == 8 and result['sourcePreserved']
        and result.get('scratchRemoved') == list(e.SCRATCH) and not result['errors'] and not result['cancelled']
        and result['normalOwnedCompletion'] and retention)
    result['status'] = 'RESULT_REVIEW_REQUIRED' if result['passed'] else 'FAIL'
    final_diagnostic(result, e.SCRATCH)
    e.save(c['reports'] / 'result.json', result)
    if retention:
        with Path(os.environ['GITHUB_OUTPUT']).open('a') as stream:
            stream.write('retention_ready=true\n')


def run_recipe(c):
    c['e'].save(c['reports'] / 'request.json', c['request'])
    c['e'].save(c['reports'] / 'result.json', c['result'])
    try:
        c['owner'].deadline_capabilities()
        prepare(c)
        c['h'].tools(c)
        native_phase(c)
    except Exception as error:
        error_diagnostic('native-preparation', error)
        c['result']['errors'].append('native preparation: ' + str(error)[:500])
    finally:
        c['end'] = min(c['end'], time.monotonic() + LIMITS['cleanupSeconds'])
        c['commands'].begin_cleanup(c['end'])
        # Dispose only the single owned Native simulator. No app installation or UI phase.
        # No app/process-name match grants signaling authority; the existing UUID custody does.
        attempt(c, 'work owner settlement', lambda: c['commands'].drain())
        c['result']['simulatorRemoved'] = attempt(c, 'owned simulator cleanup', lambda: c['native'].dispose_simulator(c)) is True
        attempt(c, 'collect/cleanup', lambda: collect_and_remove(c))
        finish(c)
    return 0 if c['result']['passed'] else 1


def host_once(request):
    require(os.uname().sysname == 'Darwin' and os.uname().machine == 'arm64' and Path(XCODE).is_dir(), 'Installed Apple tools required')
    with Path('/System/Library/CoreServices/SystemVersion.plist').open('rb') as stream:
        require(plistlib.load(stream)['ProductVersion'].startswith('26.'), 'macOS26 required')
    workspace, temporary = Path(os.environ['GITHUB_WORKSPACE']).resolve(), Path(os.environ['RUNNER_TEMP']).resolve()
    run = Path(os.environ['APP5_RUN'])
    require(CONTROL == workspace / 'control' and run == temporary / ('app5-ios-host-' + os.environ['GITHUB_RUN_ID'] + '-1')
            and run.resolve() == run and not run.exists() and not any(x.isspace() for x in str(run)), 'Unsafe/nonfresh run root')
    require(shutil.disk_usage(temporary).free >= 8 * 1024**3, 'Existing 8GiB disk floor required')
    e = load('app5_evidence', CONTROL / 'ci/app5-ios-host-evidence.py')
    owner = load('app5_accepted_commands', CONTROL / 'ci/app8-apple.py')
    adapter = load('app5_host_output_budget', CONTROL / 'ci/app5-ios-host-commands.py')
    native = load('app_seven_native', CONTROL / 'ci/app-seven-apple-native.py')
    roots = {'app': workspace / 'app'}
    home = native.runner_home(run, roots)
    env, end = e.environment(run, XCODE, CONTROL), time.monotonic() + LIMITS['controllerSeconds']
    run.mkdir(mode=0o700)
    for name in (*e.SCRATCH, 'reports'):
        (run / name).mkdir(mode=0o700)
    for number in (signal.SIGINT, signal.SIGTERM):
        signal.signal(number, owner.interrupted)
    c = dict(e=e, owner=owner, h=e, native=native, unique=unique, run=run, roots=roots,
        request=request, env=env, end=end, workEnd=end - LIMITS['cleanupSeconds'], reports=run / 'reports', started=False, sourceReady=False,
        runnerHome=home, simulator={'name': 'app29-apple-native8-' + os.environ['GITHUB_RUN_ID'] + '-1', 'runnerHome': str(home)},
        needsStop=False, stopAttempts=[], nativeExecutables=set(),
        commands=adapter.commands(owner, run, env, end), result={'status': 'INCOMPLETE', 'passed': False, 'errors': [],
        'app': APP, 'mode': 'NATIVE_ONLY', 'nativeTasks': list(NATIVE_TASKS), 'carrier': os.environ['GITHUB_SHA'],
        'limits': LIMITS, 'testsExecuted': 0})
    return run_recipe(c)


def main():
    os.umask(0o077)
    require(sys.argv[1:] in (['request'], ['host']), 'One fixed deletion AppleNative8 phase required')
    request = admit()
    if sys.argv[1] == 'host':
        return host_once(request)
    with Path(os.environ['GITHUB_OUTPUT']).open('a') as stream:
        stream.write('source_sha=' + APP['sha'] + '\n')
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        error_diagnostic('entry', error)
        raise SystemExit(1)
