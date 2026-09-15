"""App70: ordinary platform test link, then one default-writer capture; approval-gated."""
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
BRANCH = 'remediation/app70-apple-capture-20260915-04'
REQUEST = 'ci/app5-ios-host.request.json'
FILES = ('ci/app5-ios-host.py', 'ci/app5-ios-host-evidence.py', 'ci/app8-apple.py',
    'ci/app5-ios-host.source-pins.json', 'ci/app5-ios-host-commands.py', '.github/workflows/app5-ios-host.yml',
    'ci/app-seven-apple-native.py', 'ci/app-seven-apple-native.init.gradle', 'ci/app-seven-apple.native-tests.json')
APP = {'repository': 'kira-manga/kira-app', 'sha': 'd4a18aac34a32f8332af9aa91d196fb69fd93550',
       'tree': '261f80d6487a26e50e120302642e56bcef7b9241'}
OWNER_SHA = 'acc6794ea1ca226be350feae2d80dcf11a90977370e73e4b592250c39c10ee5e'
PLAN = '54e814090b9c600c9e685922d5f0d1187c4a02ce87e430c34a5a1b95b72e140c'
# Source authorship is not execution permission. Primary must bind a reviewed approval,
# update the request and re-freeze the changed controls before any carrier/dispatch.
AGREEMENT = 'c92ff8d96f13eb271f9b2b66e2189531e1e969bb3aae419bbfce4ff3b933641a'
LIMITS = {'nativeSeconds': 1200, 'canarySeconds': 60, 'querySeconds': 30, 'workSeconds': 1620,
    'controllerSeconds': 1860, 'cleanupSeconds': 240, 'stopSeconds': 40, 'jobMinutes': 40,
    'gradleWorkers': 1, 'nativeThreads': 1, 'jvmHeapGiB': 3, 'metaspaceMiB': 768, 'diskFloorGiB': 8,
    'nativeCommandLogBytes': 4194304, 'otherCommandLogBytes': 1048576,
    'aggregateCommandLogBytes': 8388608, 'otherAggregateCommandLogBytes': 4194304,
    'cleanupCommandLogBytes': 1048576, 'nativeBinaryFingerprintBytes': 1073741824}
NATIVE_TASKS = (':platform:linkDebugTestIosSimulatorArm64',)
XCODE = '/Applications/Xcode_26.4.1.app/Contents/Developer'


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


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
    fixed = dict(schema='app70-apple-capture-v1', status='PRIMARY_BOUND_FOR_REVIEW',
        authorization='APP70_APPLE_CAPTURE_ONE_ATTEMPT_AUTHORIZED',
        acquisitionAuthorization='PUBLIC_DECLARED_WRAPPER_GRADLE_NATIVE_ONLY',
        mode='APP70_CAPTURE_ONLY', ref=BRANCH, limits=LIMITS,
        app=APP, nativeTasks=list(NATIVE_TASKS),
        expectedRunAttempt=1, planSha256=PLAN, agreementSha256=AGREEMENT)
    require(BRANCH != 'UNBOUND' and all(re.fullmatch('[0-9a-f]{40}', APP[key]) for key in ('sha', 'tree'))
            and all(re.fullmatch('[0-9a-f]{64}', value) for value in (PLAN, AGREEMENT)), 'Candidate/reference/approval remains UNBOUND')
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
    require(pins['status'] == 'SOURCE_BOUND_NOT_AUTHORIZATION' and pins['schema'] == 'app70-apple-source-pins-v1',
            'Source pins remain UNBOUND')
    require(pins['app_modules'] == list(e.MODULES) and set(roots) == {'app'}, 'Only ordinary core/platform scope')
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
    return [str(c['roots']['app'] / 'gradlew'), '-p', str(c['roots']['app']), *tasks,
        '--no-daemon', '--no-parallel', '--max-workers=1', '--no-build-cache', '--no-configuration-cache',
        '--console=plain', '--stacktrace', '--project-cache-dir', str(run / 'project-cache'),
        '-I', str(CONTROL / 'ci/app-seven-apple-native.init.gradle'),
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
        require(shutil.disk_usage(c['run']).free >= 8 * 1024**3, 'Preserved-output Native phase needs the same 8GiB disk floor')
        c['native'].save_simulator(c)  # Intent-only state; graph/link failure creates no device.
        # Historical label is retained for the accepted log-budget/stop adapter.
        # This invocation links the ordinary test binary; it runs NO NativeTestTask.
        try:
            c['result']['nativeAttempted'] = c['started'] = c['needsStop'] = True
            c['commands'].call(gradle_argv(c), 'native-tests', seconds=LIMITS['nativeSeconds'],
                end=c['workEnd'])
            c['result']['nativeLinkSucceeded'] = True
        finally:
            attempt(c, 'native stop', lambda: stop(c, 'gradle-stop-native'))
            names = attempt(c, 'native executable identities', lambda: c['native'].executable_names(c))
            if names is not None:
                c['nativeExecutables'].update(names)
            c['result']['afterNativeStop'] = attempt(c, 'native absence', lambda: absence(c)) or {'absent': False}
        require(c['result']['afterNativeStop']['absent'] and not c['needsStop'], 'Native link owner/stop not settled')
        binary = c['native'].linked_binary(c)
        c['native'].create_simulator(c)
        c['native'].run_canary(c, binary)
    except Exception as error:
        c['result']['errors'].append('native link/capture: ' + str(error)[:500])


def collect_native(c, cleaning):
    if c.get('nativeCollected'):
        return
    c['nativeCollected'] = True
    context = dict(c, end=c['end'] if cleaning else c['workEnd'], evidenceCleaning=cleaning)
    proof = attempt(c, 'native evidence', lambda: c['native'].collect(context))
    if proof is not None:
        c['result']['nativeProofPreserved'] = True
        c['result']['canaryStimuliCompleted'] = int(proof['stimulusStatus'] == 'COMPLETE')
        c['result']['errors'].extend('native evidence: ' + error['stage'] + ': ' + error['type'] for error in proof['errors'])


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
    if result['afterFinalStop']['absent'] and not c.get('nativeCollected'):
        # A recovered late absence must still scan any failed/partial capture before retention.
        # No source/build output is removed on this fallback path.
        collect_native(c, cleaning=True)
    if result['afterFinalStop']['absent'] and result.get('simulatorRemoved') and (result['sourcePreserved'] or not c['sourceReady']):
        result['scratchRemoved'] = attempt(c, 'scratch cleanup', lambda: e.remove_scoped(c['run'], e.SCRATCH, c['end']))
    else:
        c['commands'].retire_observer()  # Existing owned leaf only; never loop or signal inferred workers.
    result.update(cancelled=c['owner'].CANCELLED, normalOwnedCompletion=c['commands'].normal(), attempted=c['started'])
    result['commandLogBudget'] = dict(c['commands'].log_budget)
    result['stopAttempts'] = c['stopAttempts']
    result['nativeExecutableNames'] = sorted(c['nativeExecutables'])
    # Direct TeamCity stimulus is NOT ordinary NativeTestTask/XML acceptance.
    result['testsExecuted'] = result['newNativeTestTaskRuns'] = 0
    retention = attempt(c, 'bounded public retention', lambda: e.retain(c['commands'], c['reports'], result))
    result['retention'] = retention
    if not (retention and retention['privacyRawComplete']) and result.get('privacyStatus') != 'FAIL':
        result['privacyStatus'] = 'INCOMPLETE'
    result['passed'] = bool(result.get('nativeLinkSucceeded') and result.get('nativeProofPreserved') and result.get('simulatorRemoved')
        and result.get('canaryStimuliCompleted') == 1 and result.get('privacyStatus') == 'PASS_REVIEW_REQUIRED'
        and result['sourcePreserved']
        and result.get('scratchRemoved') == list(e.SCRATCH) and not result['errors'] and not result['cancelled']
        and result['normalOwnedCompletion'] and retention and not retention['errors'])
    result['status'] = ('RESULT_REVIEW_REQUIRED' if result['passed'] else
                        'FAIL' if result.get('privacyStatus') == 'FAIL' else 'INCOMPLETE')
    e.save(c['reports'] / 'result.json', result)
    if retention:
        with Path(os.environ['GITHUB_OUTPUT']).open('a') as stream:
            stream.write('retention_ready=true\n')


def tools(c):
    # Only the donor's installed-tool readback; no host project/Swift/UIKit harness.
    commands, env = c['commands'], c['env']
    java = commands.call([env['JAVA_HOME'] + '/bin/java', '-version'], 'java-version', end=c['workEnd'])
    xcode = commands.call(['/usr/bin/xcodebuild', '-version'], 'xcode-version', end=c['workEnd'])
    sdk = commands.call(['/usr/bin/xcrun', '--sdk', 'iphonesimulator', '--show-sdk-version'], 'sdk-version', end=c['workEnd'])
    require(xcode.strip() == 'Xcode 26.4.1\nBuild version 17E202' and sdk.strip() == '26.4', 'Wrong installed Xcode/SDK')
    c['e'].save(c['reports'] / 'tools.json', {'python': sys.version, 'java': java, 'xcode': xcode, 'sdk': sdk,
        'developerDir': env['DEVELOPER_DIR'], 'javaReleaseSha256': c['e'].sha(Path(env['JAVA_HOME']) / 'release')})


def run_recipe(c):
    c['e'].save(c['reports'] / 'request.json', c['request'])
    c['e'].save(c['reports'] / 'result.json', c['result'])
    try:
        c['owner'].deadline_capabilities()
        prepare(c)
        tools(c)
        native_phase(c)
    except Exception as error:
        c['result']['errors'].append('native preparation/evidence: ' + str(error)[:500])
    finally:
        c['end'] = min(c['end'], time.monotonic() + LIMITS['cleanupSeconds'])
        c['commands'].begin_cleanup(c['end'])
        # Dispose the single owned device after the existing command-owner settlement.
        # No app/process-name match grants signaling authority; the existing UUID custody does.
        attempt(c, 'work owner settlement', lambda: c['commands'].drain())
        c['result']['simulatorRemoved'] = attempt(c, 'owned simulator cleanup', lambda: c['native'].dispose_simulator(c)) is True
        attempt(c, 'collect/cleanup', lambda: collect_and_remove(c))
        finish(c)
    return 0 if c['result']['passed'] else 1


def native_once(request):
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
    c = dict(e=e, owner=owner, native=native, unique=unique, run=run, roots=roots,
        request=request, env=env, end=end, workEnd=end - 240, reports=run / 'reports', started=False, sourceReady=False,
        runnerHome=home, simulator={'name': 'app70-apple-' + os.environ['GITHUB_RUN_ID'] + '-1', 'runnerHome': str(home)},
        needsStop=False, stopAttempts=[], nativeExecutables=set(),
        commands=adapter.commands(owner, run, env, end), result={'status': 'INCOMPLETE', 'passed': False, 'errors': [],
        'app': APP, 'mode': 'APP70_CAPTURE_ONLY', 'nativeTasks': list(NATIVE_TASKS), 'carrier': os.environ['GITHUB_SHA'],
        'limits': LIMITS, 'testsExecuted': 0, 'newNativeTestTaskRuns': 0, 'carriedNativeTests': 16,
        'canaryStimuliCompleted': 0, 'privacyStatus': 'INCOMPLETE', 'stimulusStatus': 'INCOMPLETE'})
    return run_recipe(c)


def main():
    os.umask(0o077)
    require(sys.argv[1:] in (['request'], ['native']), 'One fixed Native-only phase required')
    request = admit()
    if sys.argv[1] == 'native':
        return native_once(request)
    with Path(os.environ['GITHUB_OUTPUT']).open('a') as stream:
        stream.write('source_sha=' + APP['sha'] + '\n')
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        print('APP70 APPLE CAPTURE INCOMPLETE: ' + str(error), file=sys.stderr)
        raise SystemExit(1)
