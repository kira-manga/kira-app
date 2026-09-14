"""UNBOUND App13 one-shot Apple compile controller. Uses accepted Commands only."""
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
BRANCH = 'remediation/app-13-public-apple-validation-02'
REQUEST = 'ci/app13-public-apple.request.json'
FILES = ('ci/app13-public-apple.py', 'ci/app13-public-apple-evidence.py', 'ci/app13-public-apple.init.gradle',
         'ci/app13-original-engine.init.gradle', 'ci/app8-apple.py', 'ci/app13-public-apple.source-pins.json',
         '.github/workflows/app13-public-apple.yml')
APP = {'repository': 'kira-manga/kira-app', 'sha': '070812dcafdc1c8ecbf25489946d1ff9f88a8996',
       'tree': '99bf3a131d95162141a267beeaeb2a544e80ebc7'}
ENGINE = {'repository': 'kira-manga/kira-source-engine', 'sha': 'ed184165ebd3ee7f0d1db533cc40ca5a0868fdda',
          'tree': '14e46a1ead24b5757d612fd55031e440f5304661'}
OWNER_SHA = '9f69c87182eba29030fcd65c49b4e8459a41749440dab9767f3fbfe0c02db6cf'
PLAN = 'da9c59f84af12f265bde2e88d0312edd523533ec64b921df9399a084a0494f7a'
TASK = ':composeApp:compileKotlinIosSimulatorArm64'
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
    require(set(request['controls']) == set(FILES), 'Exactly seven non-request control hashes required')
    for name, expected in request['controls'].items():
        path = CONTROL / name
        require(re.fullmatch('[0-9a-f]{64}', expected or '') and path.is_file() and not path.is_symlink(),
                'Unbound/missing control')
        require(hashlib.sha256(path.read_bytes()).hexdigest() == expected, 'Changed control: ' + name)
    require(request['controls']['ci/app8-apple.py'] == OWNER_SHA, 'Different ownership implementation')


def admit():
    request = read(CONTROL / REQUEST)
    fixed = dict(schema='app13-public-apple-v1', status='PRIMARY_BOUND_FOR_REVIEW',
        authorization='APP13_APPLE_COMPILE_ONE_ATTEMPT_AUTHORIZED',
        acquisitionAuthorization='PUBLIC_DECLARED_WRAPPER_GRADLE_NATIVE_ONLY',
        app=APP, engine=ENGINE, task=TASK, expectedRunAttempt=1, planSha256=PLAN)
    require(set(request) == set(fixed) | {'controls'} and type(request['expectedRunAttempt']) is int
            and all(request.get(k) == v for k, v in fixed.items()), 'UNBOUND/unauthorized request')
    expected = {'GITHUB_ACTIONS': 'true', 'GITHUB_REPOSITORY': APP['repository'], 'GITHUB_EVENT_NAME': 'push',
        'GITHUB_REF': 'refs/heads/' + BRANCH, 'GITHUB_RUN_ATTEMPT': '1', 'RUNNER_OS': 'macOS',
        'RUNNER_ARCH': 'ARM64', 'RUNNER_ENVIRONMENT': 'github-hosted',
        'GITHUB_WORKFLOW_REF': APP['repository'] + '/.github/workflows/app13-public-apple.yml@refs/heads/' + BRANCH}
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
    require(sorted(changed) == sorted('A\t' + name for name in (*FILES, REQUEST)), 'Carrier must add only eight controls')
    require(not git(c, CONTROL, 'carrier-clean', ['status', '--porcelain', '--untracked-files=all']), 'Dirty carrier')
    return {'sha': sha, 'parent': APP, 'addedControls': sorted((*FILES, REQUEST)), 'hashes': c['request']['controls']}


def prepare(c):
    e, roots, reports = c['e'], c['roots'], c['reports']
    pins = e.read(CONTROL / 'ci/app13-public-apple.source-pins.json')
    require(pins['status'] == 'PRIMARY_BOUND_FOR_REVIEW' and pins['schema'] == 'app13-public-apple-source-pins-v1',
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
    e.save(reports / 'source.json', c['before'])
    e.save(reports / 'identity.json', identities)
    c['sourceReady'] = True


def tools(c):
    commands, env = c['commands'], c['env']
    java = commands.call([env['JAVA_HOME'] + '/bin/java', '-version'], 'java-version', end=c['workEnd'])
    xcode = commands.call(['/usr/bin/xcodebuild', '-version'], 'xcode-version', end=c['workEnd'])
    sdk = commands.call(['/usr/bin/xcrun', '--sdk', 'iphonesimulator', '--show-sdk-version'], 'sdk-version', end=c['workEnd'])
    require(xcode.strip() == 'Xcode 26.4.1\nBuild version 17E202' and sdk.strip() == '26.4', 'Wrong installed Xcode/SDK')
    c['e'].save(c['reports'] / 'tools.json', {'python': sys.version, 'java': java, 'xcode': xcode, 'sdk': sdk,
        'developerDir': XCODE, 'javaReleaseSha256': c['e'].sha(Path(env['JAVA_HOME']) / 'release')})


def compile_argv(c):
    run = c['run']
    return [str(c['roots']['app'] / 'gradlew'), '-p', str(c['roots']['app']), TASK,
        '--no-daemon', '--no-parallel', '--max-workers=1', '--no-build-cache', '--no-configuration-cache',
        '--console=plain', '--stacktrace', '--project-cache-dir', str(run / 'project-cache'),
        '-I', str(CONTROL / 'ci/app13-public-apple.init.gradle'),
        '-I', str(CONTROL / 'ci/app13-original-engine.init.gradle'),
        '-Pkotlin.compiler.execution.strategy=in-process', '-Pkotlin.native.parallelThreads=1',
        '-Pkotlin.incremental=false', '-PkiraUseMavenLocal=false',
        '-Porg.gradle.java.installations.auto-download=false', '-Pandroid.builder.sdkDownload=false',
        '-Dorg.gradle.vfs.watch=false', '-Pkotlin.project.persistent.dir=' + str(run / 'kotlin'),
        f'-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=768m -Dapp13.apple.owner={run.name} '
        f'-Duser.home={run / "home"} -Djava.io.tmpdir={run / "tmp"}']


def absence(c):
    commands = c['commands']
    settled = commands.drain()
    rows = commands.census(cleaning=True)
    markers = [str(c['run']), *(str(p) for p in c['roots'].values()), 'app13.apple.owner=' + c['run'].name]
    remaining = [r for r in rows if r['pid'] != os.getpid() and not r['state'].startswith('Z')
                 and any(marker in r['command'] for marker in markers)]
    return {'absent': settled and not remaining, 'groupsSettled': settled,
            'remaining': [{k: v for k, v in r.items() if k != 'command'} for r in remaining]}


def stop(c, label):
    if c['started']:
        c['commands'].retire_observer()
        c['commands'].call([str(c['roots']['app'] / 'gradlew'), '--stop'], label, seconds=40, cleaning=True)


def attempt(c, label, operation):
    try:
        return operation()
    except Exception as error:
        c['result']['errors'].append(label + ': ' + str(error)[:500])
        return None


def collect_and_remove(c):
    e, result = c['e'], c['result']
    attempt(c, 'immediate stop', lambda: stop(c, 'gradle-stop-immediate'))
    result['afterImmediateStop'] = absence(c)
    require(result['afterImmediateStop']['absent'], 'Ambiguous/live ownership: retain outputs')
    if not c['sourceReady']:
        return
    if result.get('compileSucceeded'):
        proof = attempt(c, 'compile evidence', lambda: e.prove(c['run'], c['roots'], c['end']))
        if proof is not None:
            e.save(c['reports'] / 'proof.json', proof)
            result['proofPreserved'] = True
    after = {role: e.inputs(root, role, c['pins']) for role, root in c['roots'].items()}
    c['identity']['sourceInputsUnchanged'] = after == c['before']
    e.save(c['reports'] / 'identity.json', c['identity'])
    require(after == c['before'], 'Source inputs changed; retain outputs')
    controls(c['request'])
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
    attempt(c, 'final stop', lambda: stop(c, 'gradle-stop-final'))
    result['sourcePreserved'] = attempt(c, 'final source', lambda: final_source(c)) is True
    result['afterFinalStop'] = attempt(c, 'final absence', lambda: absence(c)) or {'absent': False}
    if result['afterFinalStop']['absent'] and (result['sourcePreserved'] or not c['sourceReady']):
        result['scratchRemoved'] = attempt(c, 'scratch cleanup', lambda: e.remove_scoped(c['run'], e.SCRATCH, c['end']))
    else:
        c['commands'].retire_observer()  # Existing owned leaf only; never loop or signal inferred workers.
    result.update(cancelled=c['owner'].CANCELLED, normalOwnedCompletion=c['commands'].normal(), attempted=c['started'])
    retention = attempt(c, 'bounded public retention', lambda: e.retain(c['commands'], c['reports'], result))
    result['retention'] = retention
    result['passed'] = bool(result.get('compileSucceeded') and result.get('proofPreserved') and result['sourcePreserved']
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
        tools(c)
        c['started'] = True
        c['commands'].call(compile_argv(c), 'compile', seconds=1200, end=c['workEnd'])
        c['result']['compileSucceeded'] = True
    except Exception as error:
        c['result']['errors'].append('compile: ' + str(error)[:500])
    finally:
        attempt(c, 'collect/cleanup', lambda: collect_and_remove(c))
        finish(c)
    return 0 if c['result']['passed'] else 1


def compile_once(request):
    require(os.uname().sysname == 'Darwin' and os.uname().machine == 'arm64' and Path(XCODE).is_dir(), 'Installed Apple tools required')
    with Path('/System/Library/CoreServices/SystemVersion.plist').open('rb') as stream:
        require(plistlib.load(stream)['ProductVersion'].startswith('26.'), 'macOS26 required')
    workspace, temporary = Path(os.environ['GITHUB_WORKSPACE']).resolve(), Path(os.environ['RUNNER_TEMP']).resolve()
    run = Path(os.environ['APP13_RUN'])
    require(CONTROL == workspace / 'control' and run == temporary / ('app13-apple-' + os.environ['GITHUB_RUN_ID'] + '-1')
            and run.resolve() == run and not run.exists() and not any(x.isspace() for x in str(run)), 'Unsafe/nonfresh run root')
    require(shutil.disk_usage(temporary).free >= 8 * 1024**3, 'Existing 8GiB disk floor required')
    e = load('app13_evidence', CONTROL / 'ci/app13-public-apple-evidence.py')
    owner = load('app13_accepted_commands', CONTROL / 'ci/app8-apple.py')
    env, end = e.environment(run, XCODE, CONTROL), time.monotonic() + 1440
    run.mkdir(mode=0o700)
    for name in (*e.SCRATCH, 'reports'):
        (run / name).mkdir(mode=0o700)
    for number in (signal.SIGINT, signal.SIGTERM):
        signal.signal(number, owner.interrupted)
    c = dict(e=e, owner=owner, run=run, roots={'app': workspace / 'app', 'engine': workspace / 'engine'},
        request=request, env=env, end=end, workEnd=end - 240, reports=run / 'reports', started=False, sourceReady=False,
        commands=owner.Commands(run, env, end), result={'status': 'INCOMPLETE', 'passed': False, 'errors': [],
        'app': APP, 'engine': ENGINE, 'task': TASK, 'carrier': os.environ['GITHUB_SHA'],
        'limits': {'workSeconds': 1200, 'cleanupSeconds': 240, 'stopSeconds': 40, 'workers': 1}})
    return run_recipe(c)


def main():
    os.umask(0o077)
    require(sys.argv[1:] in (['request'], ['compile']), 'One fixed phase required')
    request = admit()
    if sys.argv[1] == 'compile':
        return compile_once(request)
    with Path(os.environ['GITHUB_OUTPUT']).open('a') as stream:
        stream.write('source_sha=' + APP['sha'] + '\nengine_sha=' + ENGINE['sha'] + '\n')
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        print('APP13 APPLE INCOMPLETE: ' + str(error), file=sys.stderr)
        raise SystemExit(1)
