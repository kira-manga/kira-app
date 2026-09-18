"""One hosted full-composition ARM main KLIB compile; no Simulator, test, link or host entry."""
import importlib.util
import json
import os
from pathlib import Path
import plistlib
import shutil
import signal
import sys
import time

CONTROL = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('app29_admission', CONTROL / 'ci/app29-apple-compile-admission.py')
a = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a)
require = a.require


def gradle_argv(c):
    run = c['run']
    return [str(c['roots']['app'] / 'gradlew'), '-p', str(c['roots']['app']), a.TASK,
            '--no-daemon', '--no-parallel', '--max-workers=1', '--no-build-cache',
            '--no-configuration-cache', '--no-scan', '--console=plain', '--stacktrace',
            '--project-cache-dir', str(run / 'project-cache'), '-I', str(CONTROL / 'ci/app29-apple-compile.init.gradle'),
            '-Pkotlin.compiler.execution.strategy=in-process', '-Pkotlin.native.disableCompilerDaemon=true',
            '-Pkotlin.native.parallelThreads=1', '-Pkotlin.incremental=false',
            '-Pkotlin.native.jvmArgs=-Xmx3g -XX:MaxMetaspaceSize=768m',
            '-Pkotlin.mpp.commonizerJvmArgs=-Xmx3g -XX:MaxMetaspaceSize=768m',
            '-PkiraIntelSimulatorTests=false', '-PkiraUseMavenLocal=false',
            '-Porg.gradle.java.installations.auto-download=false', '-Pandroid.builder.sdkDownload=false',
            '-Dorg.gradle.vfs.watch=false', '-Pkotlin.project.persistent.dir=' + str(run / 'kotlin'),
            '-Duser.home=' + str(run / 'home'),
            f'-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=768m -Dapp5.apple.owner={run.name} '
            f'-Duser.home={run / "home"} -Djava.io.tmpdir={run / "tmp"}']


def absence(c, cleaning=False):
    settled = c['commands'].drain()
    rows = c['commands'].census(cleaning=cleaning)
    markers = [str(c['run']), str(c['roots']['app']), 'app5.apple.owner=' + c['run'].name]
    remaining = [r for r in rows if r['pid'] != os.getpid() and not r['state'].startswith('Z')
                 and any(marker in r['command'] for marker in markers)]
    return {'absent': settled and not remaining, 'groupsSettled': settled,
            'remaining': [{k: v for k, v in r.items() if k != 'command'} for r in remaining]}


def stop(c, label, cleaning=False):
    if c['started']:
        require(label not in c['stopAttempts'], 'Repeated Gradle stop label')
        c['stopAttempts'].append(label)
        c['commands'].retire_observer()
        c['commands'].call([str(c['roots']['app'] / 'gradlew'), '--stop'], label,
                           seconds=a.LIMITS['stopSeconds'], end=c['end'] if cleaning else c['workEnd'],
                           cleaning=cleaning)
        c['needsStop'] = False
    return True


def attempt(c, label, operation):
    try:
        return operation()
    except Exception as error:
        c['result']['errors'].append(label + ': ' + a.diagnostic('cleanup', error))
        return None


def dependency_intake(c, token):
    require(token, 'Authenticated original4 artifact credential unavailable; no Gradle fallback')
    c['inputs'].manifest(CONTROL)
    c['commands'].call([sys.executable, '-B', str(CONTROL / 'ci/app29-apple-compile-inputs.py')],
                       'original4-intake', seconds=a.LIMITS['artifactSeconds'], end=c['workEnd'],
                       extra={'APP29_ARTIFACT_TOKEN': token})
    c['dependenciesBefore'] = c['inputs'].snapshot(c['run'], c['inputs'].manifest(CONTROL))
    c['e'].save(c['reports'] / 'dependencies-before.json', c['dependenciesBefore'])
    c['result']['dependenciesReady'] = True


def compile_phase(c):
    try:
        require(time.monotonic() < c['workEnd'] and not c['owner'].CANCELLED, 'No compile work window')
        require(c['result'].get('dependenciesReady') and shutil.disk_usage(c['run']).free >= 8 * 1024**3,
                'Exact dependency intake and same 8GiB disk floor required before Gradle')
        c['result']['compileAttempted'] = c['started'] = c['needsStop'] = True
        c['commands'].call(gradle_argv(c), 'host-build', seconds=a.LIMITS['compileSeconds'], end=c['workEnd'])
        c['result']['compileSucceeded'] = True
    except Exception as error:
        c['result']['errors'].append(a.diagnostic('compile', error))
    finally:
        if c['started']:
            attempt(c, 'compile stop', lambda: stop(c, 'gradle-stop-compile'))
            c['result']['afterCompileStop'] = attempt(c, 'compile absence', lambda: absence(c)) or {'absent': False}


def collect_and_remove(c):
    e, result = c['e'], c['result']
    if c['needsStop']:
        attempt(c, 'cleanup stop', lambda: stop(c, 'gradle-stop-cleanup', cleaning=True))
    result['afterWorkStop'] = absence(c, cleaning=True)
    require(result['afterWorkStop']['absent'], 'Ambiguous/live ownership: retain outputs')
    if c['started']:
        proof = attempt(c, 'compile evidence', lambda: c['proof'].collect(c))
        result['compileProofPreserved'] = proof is not None and proof['passed']
        result['mainKlibsProven'] = proof['mainKlibsProven'] if proof else 0
    if result.get('dependenciesReady'):
        after = c['inputs'].snapshot(c['run'], c['inputs'].manifest(CONTROL))
        e.save(c['reports'] / 'dependencies-after.json', after)
        result['dependenciesPreserved'] = after == c['dependenciesBefore']
        require(result['dependenciesPreserved'], 'Dependency bytes changed; retain outputs')
    if c['sourceReady']:
        a.unchanged_inputs(c)
        result['outputsRemoved'] = {'app': e.remove_scoped(c['roots']['app'], e.OUTPUTS['app'], c['end'])}


def finish(c):
    result, e = c['result'], c['e']
    attempt(c, 'final stop', lambda: stop(c, 'gradle-stop-final', cleaning=True))
    result['sourcePreserved'] = attempt(c, 'final source', lambda: a.final_source(c)) is True
    result['afterFinalStop'] = attempt(c, 'final absence', lambda: absence(c, cleaning=True)) or {'absent': False}
    if result['afterFinalStop']['absent'] and (result['sourcePreserved'] or not c['sourceReady']):
        result['scratchRemoved'] = attempt(c, 'scratch cleanup', lambda: e.remove_scoped(c['run'], e.SCRATCH, c['end']))
    else:
        c['commands'].retire_observer()  # No inferred process name or global worker signaling authority.
    result.update(cancelled=c['owner'].CANCELLED, normalOwnedCompletion=c['commands'].normal(), attempted=c['started'])
    result['commandLogBudget'], result['stopAttempts'] = dict(c['commands'].log_budget), c['stopAttempts']
    result['retention'] = attempt(c, 'bounded retention', lambda: e.retain(c['commands'], c['reports'], result))
    result['passed'] = bool(result.get('compileSucceeded') and result.get('compileProofPreserved')
                            and result.get('dependenciesPreserved') and result['sourcePreserved']
                            and result['afterFinalStop']['absent'] and result.get('scratchRemoved') == list(e.SCRATCH)
                            and not result['errors'] and not result['cancelled']
                            and result['normalOwnedCompletion'] and result['retention'])
    result['status'] = 'RESULT_REVIEW_REQUIRED' if result['passed'] else 'FAIL'
    e.save(c['reports'] / 'result.json', result)
    print('APP29_COMPILE_FINAL ' + json.dumps({key: result.get(key) is True for key in
          ('compileAttempted', 'compileSucceeded', 'compileProofPreserved', 'dependenciesPreserved',
           'sourcePreserved', 'normalOwnedCompletion', 'cancelled', 'passed')}, sort_keys=True), flush=True)
    if result['retention']:
        with Path(os.environ['GITHUB_OUTPUT']).open('a') as stream:
            stream.write('retention_ready=true\n')


def run_recipe(c, token):
    c['e'].save(c['reports'] / 'request.json', c['request'])
    c['e'].save(c['reports'] / 'result.json', c['result'])
    try:
        c['owner'].deadline_capabilities()
        a.prepare(c)
        c['e'].tools(c)
        dependency_intake(c, token)
        token = None
        compile_phase(c)
    except Exception as error:
        c['result']['errors'].append(a.diagnostic('preparation', error))
    finally:
        token = None
        c['end'] = min(c['end'], time.monotonic() + a.LIMITS['cleanupSeconds'])
        c['commands'].begin_cleanup(c['end'])
        attempt(c, 'work owner settlement', lambda: c['commands'].drain())
        attempt(c, 'collect/cleanup', lambda: collect_and_remove(c))
        finish(c)
    return 0 if c['result']['passed'] else 1


def host_once(request, token):
    require(os.uname().sysname == 'Darwin' and os.uname().machine == 'arm64' and Path(a.XCODE).is_dir(),
            'Installed Apple ARM tools required')
    with Path('/System/Library/CoreServices/SystemVersion.plist').open('rb') as stream:
        require(plistlib.load(stream)['ProductVersion'].startswith('26.'), 'macOS26 required')
    workspace, temporary = Path(os.environ['GITHUB_WORKSPACE']).resolve(), Path(os.environ['RUNNER_TEMP']).resolve()
    run = Path(os.environ['APP29_RUN'])
    require(CONTROL == workspace / 'control' and run == temporary / ('app29-apple-compile-' + os.environ['GITHUB_RUN_ID'] + '-1')
            and run.resolve() == run and not run.exists() and not run.is_symlink()
            and not any(x.isspace() for x in str(run)), 'Unsafe/nonfresh run root')
    require(shutil.disk_usage(temporary).free >= 8 * 1024**3, 'Existing 8GiB disk floor required')
    e = a.load('app29_evidence', CONTROL / 'ci/app29-apple-compile-evidence.py')
    owner = a.load('accepted_owner', CONTROL / 'ci/app8-apple.py')
    adapter = a.load('accepted_adapter', CONTROL / 'ci/app5-ios-host-commands.py')
    proof = a.load('app29_proof', CONTROL / 'ci/app29-apple-compile-proof.py')
    inputs = a.load('app29_inputs', CONTROL / 'ci/app29-apple-compile-inputs.py')
    env, end = e.environment(run, a.XCODE, CONTROL), time.monotonic() + a.LIMITS['controllerSeconds']
    run.mkdir(mode=0o700)
    for name in (*e.SCRATCH, 'reports'):
        (run / name).mkdir(mode=0o700)
    for number in (signal.SIGINT, signal.SIGTERM):
        signal.signal(number, owner.interrupted)
    c = dict(e=e, owner=owner, proof=proof, inputs=inputs, run=run, roots={'app': workspace / 'app'},
             request=request, env=env, end=end, workEnd=end - a.LIMITS['cleanupSeconds'], reports=run / 'reports',
             started=False, sourceReady=False, needsStop=False, stopAttempts=[],
             commands=adapter.commands(owner, run, env, end),
             result={'status': 'INCOMPLETE', 'passed': False, 'errors': [], 'app': a.APP, 'mode': 'COMPILE_ONLY',
                     'tasks': [a.TASK], 'carrier': os.environ['GITHUB_SHA'], 'limits': a.LIMITS, 'testsExecuted': 0})
    return run_recipe(c, token)


def main():
    os.umask(0o077)
    # This token is never in the shared command environment, argv, Gradle or a receipt.
    token = os.environ.pop('APP29_ARTIFACT_TOKEN', '')
    require(sys.argv[1:] in (['request'], ['host']), 'One fixed main compile phase required')
    request = a.admit()
    if sys.argv[1] == 'host':
        return host_once(request, token)
    with Path(os.environ['GITHUB_OUTPUT']).open('a') as stream:
        stream.write('source_sha=' + a.APP['sha'] + '\n')
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        a.diagnostic('entry', error)
        raise SystemExit(1)
