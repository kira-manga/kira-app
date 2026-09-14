"""UNBOUND seven-App Apple preparation: reused host, Kotlin9 and material UIKit6."""
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
BRANCH = 'remediation/app-seven-apple-public-20260914-02'
REQUEST = 'ci/app5-ios-host.request.json'
FILES = ('ci/app5-ios-host.py', 'ci/app5-ios-host-evidence.py', 'ci/app5-ios-host.init.gradle', 'ci/app5-original-engine.init.gradle', 'ci/app8-apple.py', 'ci/app5-ios-host.source-pins.json', 'ci/app5-ios-host-commands.py', 'ci/app5-ios-host-xcodegen.py', 'ci/app5-ios-host-xcode.py', 'ci/app5-ios-host-products.py', '.github/workflows/app5-ios-host.yml', 'ci/app-seven-apple-native.py', 'ci/app-seven-apple-native.init.gradle', 'ci/app-seven-apple.native-tests.json', 'ci/app-seven-apple-uikit.py', 'ci/app-seven-apple.uikit-tests.json')
APP = {'repository': 'kira-manga/kira-app', 'sha': '84cacf8fb7b4f43220498d5abde9c4cbc30dd899', 'tree': '9e81cd6bc3b47c84d9970cbd3344e44930e97785'}
ENGINE = {'repository': 'kira-manga/kira-source-engine', 'sha': 'ed184165ebd3ee7f0d1db533cc40ca5a0868fdda',
          'tree': '14e46a1ead24b5757d612fd55031e440f5304661'}
OWNER_SHA = '9f69c87182eba29030fcd65c49b4e8459a41749440dab9767f3fbfe0c02db6cf'
PLAN = 'c15e58d0ca183d37fef58fd2d746585bc55bfb5c1e7e035f0c6f8c80fe64d5e8'
AGREEMENT = '15756eaec69dc539b597aaaf9571a02c5d484e231635a097a5f481976011d892'
LIMITS = {'buildSeconds': 2400, 'nativeSeconds': 1200, 'uikitSeconds': 600, 'workSeconds': 4080, 'controllerSeconds': 4320, 'cleanupSeconds': 240, 'stopSeconds': 40, 'jobMinutes': 80, 'xcodeWorkers': 1, 'gradleWorkers': 1, 'nativeThreads': 1, 'jvmHeapGiB': 3, 'metaspaceMiB': 768, 'diskFloorGiB': 8, 'hostCommandLogBytes': 8388608, 'nativeCommandLogBytes': 4194304, 'uikitCommandLogBytes': 4194304, 'otherCommandLogBytes': 1048576, 'aggregateCommandLogBytes': 20971520, 'otherAggregateCommandLogBytes': 4194304, 'cleanupCommandLogBytes': 1048576, 'staticFrameworkFingerprintBytes': 1073741824, 'nativeBinaryFingerprintBytes': 1073741824}
TASK = ':composeApp:embedAndSignAppleFrameworkForXcode'
NATIVE_TASKS = (':composeApp:iosSimulatorArm64Test', ':data:iosSimulatorArm64Test', ':presentation:iosSimulatorArm64Test')
UIKIT_TESTS = {'target': 'ReaderChromeControlsTests', 'class': 'WebtoonReaderBoundaryTests', 'caseCount': 6}
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
    require(set(request['controls']) == set(FILES), 'Exactly sixteen non-request control hashes required')
    for name, expected in request['controls'].items():
        path = CONTROL / name
        require(re.fullmatch('[0-9a-f]{64}', expected or '') and path.is_file() and not path.is_symlink(),
                'Unbound/missing control')
        require(hashlib.sha256(path.read_bytes()).hexdigest() == expected, 'Changed control: ' + name)
    require(request['controls']['ci/app8-apple.py'] == OWNER_SHA, 'Different ownership implementation')


def admit():
    request = read(CONTROL / REQUEST)
    fixed = dict(schema='app-seven-apple-v1', status='PRIMARY_BOUND_FOR_REVIEW',
        authorization='APP_SEVEN_APPLE_ONE_ATTEMPT_AUTHORIZED',
        acquisitionAuthorization='PUBLIC_DECLARED_WRAPPER_GRADLE_NATIVE_FIREBASE_SWIFTPM_ONLY',
        xcodegenIntakeAuthorization='PINNED_XCODEGEN_2_46_0_ONE_INTAKE_AUTHORIZED', ref=BRANCH, limits=LIMITS,
        app=APP, engine=ENGINE, task=TASK, nativeTasks=list(NATIVE_TASKS), uikitTests=UIKIT_TESTS,
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
    require(sorted(changed) == sorted('A\t' + name for name in (*FILES, REQUEST)), 'Carrier must add only seventeen controls')
    require(not git(c, CONTROL, 'carrier-clean', ['status', '--porcelain', '--untracked-files=all']), 'Dirty carrier')
    return {'sha': sha, 'parent': APP, 'addedControls': sorted((*FILES, REQUEST)), 'hashes': c['request']['controls']}


def prepare(c):
    e, roots, reports = c['e'], c['roots'], c['reports']
    pins = e.read(CONTROL / 'ci/app5-ios-host.source-pins.json')
    require(pins['status'] == 'PRIMARY_BOUND_FOR_REVIEW' and pins['schema'] == 'app5-ios-host-source-pins-v1',
            'Source pins remain UNBOUND')
    require(pins['app_modules'] == list(e.MODULES) and pins['engine_modules'] == list(e.ENGINE_MODULES), 'Wrong module scope')
    identities = {'carrier': carrier(c), 'before': {}}
    for role, expected in (('app', APP), ('engine', ENGINE)):
        require(all(pins[role][key] == value for key, value in expected.items()), 'Different source pins')
        identities['before'][role] = checkout(c, roots[role], expected, role)
        require(all(not (roots[role] / p).exists() and not (roots[role] / p).is_symlink() for p in e.OUTPUTS[role]),
                'Preexisting outputs are not owned')
    c['pins'], c['identity'] = pins, identities
    c['before'] = {role: e.inputs(root, role, pins) for role, root in roots.items()}
    c['nativeSource'] = c['native'].source_inputs(c)
    c['uikitSource'] = c['uikit'].source_inputs(c)
    e.save(reports / 'source.json', c['before'])
    e.save(reports / 'native-source.json', c['nativeSource'])
    e.save(reports / 'uikit-source.json', c['uikitSource'])
    e.save(reports / 'identity.json', identities)
    c['sourceReady'] = True


def gradle_argv(c, native=False):
    run = c['run']
    tasks = c['native'].task_arguments(c) if native else [TASK]
    init = 'ci/app-seven-apple-native.init.gradle' if native else 'ci/app5-ios-host.init.gradle'
    return [str(c['roots']['app'] / 'gradlew'), '-p', str(c['roots']['app']), *tasks,
        '--no-daemon', '--no-parallel', '--max-workers=1', '--no-build-cache', '--no-configuration-cache',
        '--console=plain', '--stacktrace', '--project-cache-dir', str(run / 'project-cache'),
        '-I', str(CONTROL / init),
        '-I', str(CONTROL / 'ci/app5-original-engine.init.gradle'),
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
    uikit_after = c['uikit'].source_inputs(c)
    c['identity']['sourceInputsUnchanged'] = after == c['before'] and native_after == c['nativeSource'] and uikit_after == c['uikitSource']
    c['e'].save(c['reports'] / 'identity.json', c['identity'])
    require(c['identity']['sourceInputsUnchanged'], 'Source inputs changed; retain outputs')
    controls(c['request'])


def collect_host(c):
    # Snapshot the original host producer evidence BEFORE Native can change build outputs.
    e, result = c['e'], c['result']
    context = dict(c, end=c['workEnd'], evidenceCleaning=False)
    c['h'].verify_project(context)
    proof = attempt(c, 'ordinary main evidence', lambda: e.prove(c['run'], c['roots'], c['workEnd']))
    if proof is not None:
        e.save(c['reports'] / 'proof.json', proof)
        result['mainProofPreserved'] = True
        result['proofPreserved'] = attempt(c, 'actual host evidence', lambda: c['products'].prove(context, proof)) is not None
    unchanged_inputs(c)
    names = ('app-tasks.json', 'engine-tasks.json', 'proof.json', 'host-proof.json', 'framework-metadata.json')
    c['hostEvidence'] = {name: e.sha(c['reports'] / name) for name in names if (c['reports'] / name).exists()}
    result['hostEvidenceBeforeNative'] = dict(c['hostEvidence'])


def native_phase(c):
    try:
        require(time.monotonic() < c['workEnd'] and not c['owner'].CANCELLED, 'No Native work window')
        require(shutil.disk_usage(c['run']).free >= 8 * 1024**3, 'Preserved-output Native phase needs the same 8GiB disk floor')
        c['native'].create_simulator(c)
        # One Gradle invocation, all three real Native tasks, one already-owned simulator.
        c['result']['nativeAttempted'] = c['needsStop'] = True
        c['commands'].call(gradle_argv(c, native=True), 'native-tests', seconds=LIMITS['nativeSeconds'],
            end=c['workEnd'], extra=c['native'].environment(c))
        c['result']['nativeTestsSucceeded'] = True
    except Exception as error:
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


def uikit_phase(c):
    try:
        require(c['native'].simulator_identity(c) == c['simulator']['createdOwnership'], 'Same owned simulator required for UIKit')
        c['uikit'].prepare(c)
        c['result']['uikitAttempted'] = True
        c['commands'].call(c['uikit'].argv(c), 'uikit-tests', seconds=LIMITS['uikitSeconds'], end=c['workEnd'],
            extra={'HOME': str(c['runnerHome']), 'CFFIXED_USER_HOME': str(c['runnerHome'])})
        c['result']['uikitTestsSucceeded'] = True
    except Exception as error:
        c['result']['errors'].append('UIKit tests: ' + str(error)[:500])
    finally:
        result = attempt(c, 'UIKit absence', lambda: absence(c)) or {'absent': False}
        c['result']['afterUIKitWork'] = result
        if result['absent']:
            collect_uikit(c, cleaning=False)


def collect_uikit(c, cleaning):
    if c.get('uikitCollected') or not c['result'].get('uikitAttempted'):
        return
    c['uikitCollected'] = True
    proof = attempt(c, 'UIKit evidence', lambda: c['uikit'].collect(
        dict(c, end=c['end'] if cleaning else c['workEnd'], evidenceCleaning=cleaning)))
    if proof is not None:
        c['result']['uikitProofPreserved'] = proof['passed']
        c['result']['errors'].extend('UIKit evidence: ' + error for error in proof['errors'])


def collect_and_remove(c):
    e, result = c['e'], c['result']
    if c['needsStop']:
        attempt(c, 'cleanup stop', lambda: stop(c, 'gradle-stop-cleanup', cleaning=True))
    result['afterWorkStop'] = absence(c, cleaning=True)
    require(result['afterWorkStop']['absent'], 'Ambiguous/live ownership: retain outputs')
    collect_native(c, cleaning=True)
    collect_uikit(c, cleaning=True)
    if not c['sourceReady']:
        return
    attempt(c, 'generated project readback', lambda: c['h'].verify_project(c))
    unchanged_inputs(c)
    require(all(e.sha(c['reports'] / name) == value for name, value in c['hostEvidence'].items()),
            'Native invocation changed preserved host proof')
    c['h'].remove_example(c)
    result['outputsRemoved'] = {role: e.remove_scoped(root, e.OUTPUTS[role], c['end']) for role, root in c['roots'].items()}


def final_source(c):
    if c['sourceReady'] and c['result'].get('outputsRemoved'):
        c['identity']['after'] = {role: checkout(c, root, APP if role == 'app' else ENGINE, role + '-final', True)
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
    result['testsExecuted'] = result.get('kotlinTestsExecuted', 0) + result.get('uikitTestsExecuted', 0)
    retention = attempt(c, 'bounded public retention', lambda: e.retain(c['commands'], c['reports'], result))
    result['retention'] = retention
    result['passed'] = bool(result.get('hostBuildSucceeded') and result.get('proofPreserved')
        and result.get('nativeTestsSucceeded') and result.get('nativeProofPreserved') and result.get('simulatorRemoved')
        and result.get('uikitTestsSucceeded') and result.get('uikitProofPreserved')
        and result.get('kotlinTestsExecuted') == 9 and result.get('uikitTestsExecuted') == c['uikitSource']['caseCount'] and result['sourcePreserved']
        and result.get('scratchRemoved') == list(e.SCRATCH) and not result['errors'] and not result['cancelled']
        and result['normalOwnedCompletion'] and retention)
    result['status'] = 'RESULT_REVIEW_REQUIRED' if result['passed'] else 'FAIL'
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
        c['h'].prepare(c, gradle_argv(c))
        c['started'] = c['needsStop'] = True
        c['commands'].call(c['h'].argv(c), 'host-build', seconds=2400, end=c['workEnd'])
        c['result']['hostBuildSucceeded'] = True
    except Exception as error:
        c['result']['errors'].append('host build: ' + str(error)[:500])
    try:
        attempt(c, 'immediate stop', lambda: stop(c, 'gradle-stop-immediate'))
        c['result']['afterImmediateStop'] = absence(c)
        if c['result']['afterImmediateStop']['absent'] and c['result'].get('hostBuildSucceeded'):
            collect_host(c)
            # A header/module metadata failure remains failure, but need not discard useful native evidence.
            if c['result'].get('mainProofPreserved'):
                native_phase(c)
                if c['result'].get('afterNativeStop', {}).get('absent') and c['simulator'].get('bootReadinessEvidence'):
                    uikit_phase(c)
    except Exception as error:
        c['result']['errors'].append('host/native evidence: ' + str(error)[:500])
    finally:
        c['end'] = min(c['end'], time.monotonic() + LIMITS['cleanupSeconds'])
        c['commands'].begin_cleanup(c['end'])
        # Dispose the single owned device even if its UIKit test-host lifecycle outlives xcodebuild.
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
    h = load('app5_host_xcode', CONTROL / 'ci/app5-ios-host-xcode.py')
    gen = load('app5_host_xcodegen', CONTROL / 'ci/app5-ios-host-xcodegen.py')
    products = load('app5_host_products', CONTROL / 'ci/app5-ios-host-products.py')
    native = load('app_seven_native', CONTROL / 'ci/app-seven-apple-native.py')
    uikit = load('app_seven_uikit', CONTROL / 'ci/app-seven-apple-uikit.py')
    roots = {'app': workspace / 'app', 'engine': workspace / 'engine'}
    home = native.runner_home(run, roots)
    env, end = e.environment(run, XCODE, CONTROL), time.monotonic() + LIMITS['controllerSeconds']
    run.mkdir(mode=0o700)
    for name in (*e.SCRATCH, 'reports'):
        (run / name).mkdir(mode=0o700)
    for number in (signal.SIGINT, signal.SIGTERM):
        signal.signal(number, owner.interrupted)
    c = dict(e=e, owner=owner, h=h, gen=gen, products=products, native=native, uikit=uikit, unique=unique, run=run, roots=roots,
        request=request, env=env, end=end, workEnd=end - 240, reports=run / 'reports', started=False, sourceReady=False,
        runnerHome=home, simulator={'name': 'app-seven-apple-' + os.environ['GITHUB_RUN_ID'] + '-1', 'runnerHome': str(home)},
        needsStop=False, stopAttempts=[], nativeExecutables=set(), hostEvidence={},
        commands=adapter.commands(owner, run, env, end), result={'status': 'INCOMPLETE', 'passed': False, 'errors': [],
        'app': APP, 'engine': ENGINE, 'task': TASK, 'nativeTasks': list(NATIVE_TASKS), 'uikitTests': UIKIT_TESTS, 'carrier': os.environ['GITHUB_SHA'],
        'limits': LIMITS, 'testsExecuted': 0})
    return run_recipe(c)


def main():
    os.umask(0o077)
    require(sys.argv[1:] in (['request'], ['host']), 'One fixed phase required')
    request = admit()
    if sys.argv[1] == 'host':
        return host_once(request)
    with Path(os.environ['GITHUB_OUTPUT']).open('a') as stream:
        stream.write('source_sha=' + APP['sha'] + '\nengine_sha=' + ENGINE['sha'] + '\n')
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        print('APP5 IOS HOST INCOMPLETE: ' + str(error), file=sys.stderr)
        raise SystemExit(1)
