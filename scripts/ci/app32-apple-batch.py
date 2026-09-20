#!/usr/bin/env python3
"""One App84 iOS Arm64 MAIN compile; no simulator or Native test replay. App61 hold remains."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import app32_apple_owned as o
import app32_apple_host as host

CONTROLS = ('.github/workflows/app32-apple-batch.yml', 'scripts/ci/app32-apple-batch.init.gradle',
            'scripts/ci/app32-apple-batch.py', 'scripts/ci/app32_apple_host.py', 'scripts/ci/app32_apple_owned.py')
COMPILES = [':composeApp:compileKotlinIosArm64']
TESTS = {}  # Changed common Share-to-platform wiring; native implementations unchanged.
TASKS = COMPILES + list(TESTS)
OBSERVED = TASKS + [task.replace('iosSimulatorArm64Test', name) for name in
                   ('compileTestKotlinIosSimulatorArm64', 'linkDebugTestIosSimulatorArm64') for task in TESTS]


def git(*args):
    return subprocess.check_output(['git', '-C', str(o.ROOT), *args], text=True, timeout=10,
                                   env={**host.environment(), 'GIT_OPTIONAL_LOCKS': '0'}).strip()


def source():
    return dict(head=git('rev-parse', 'HEAD'), tree=git('rev-parse', 'HEAD^{tree}'),
                clean=not git('status', '--porcelain', '--untracked-files=all'))


def bind_source():
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    branch, sha, tree = (os.environ[key] for key in ('APP32_BRANCH', 'APP32_SOURCE_SHA', 'APP32_SOURCE_TREE'))
    o.require(not branch.startswith('UNBOUND') and branch not in ('main', 'testing', 'release', 'internal-testing'),
              'Primary must bind a fresh isolated validation branch')
    o.require(os.environ['GITHUB_EVENT_NAME'] == 'push' and os.environ['GITHUB_REPOSITORY'] == 'kira-manga/kira-app'
              and os.environ['GITHUB_REF'] == 'refs/heads/' + branch and event.get('created') is True
              and os.environ['GITHUB_RUN_ATTEMPT'] == '1' and event['repository']['private'] is False,
              'Only the bound new public branch push, first attempt')
    o.require(os.environ['RUNNER_OS'] == 'macOS' and os.environ['RUNNER_ARCH'] == 'ARM64'
              and o.ROOT == Path(os.environ['GITHUB_WORKSPACE']).resolve(), 'Hosted native ARM macOS app checkout only')
    o.require(re.fullmatch('[0-9a-f]{40}', sha) and re.fullmatch('[0-9a-f]{40}', tree), 'Unbound source SHA/tree')
    before = source()
    o.record('source-before.json', dict(before, reviewed_source_sha=sha, reviewed_source_tree=tree, branch=branch))
    o.require(before['clean'] and before['head'] == os.environ['GITHUB_SHA'] and git('rev-parse', 'HEAD^@') == sha
              and git('rev-parse', sha + '^{tree}') == tree, 'Clean carrier must have exactly the reviewed source-only parent/tree')
    o.require(git('diff-tree', '--no-commit-id', '--no-renames', '--name-status', '-r', sha, 'HEAD').splitlines()
              == ['A\t' + path for path in CONTROLS], 'Carrier may add only these five reviewed controls')
    o.require(not (o.ROOT / '.swiftpm-locks').exists() and not (o.ROOT / '.swiftpm-locks').is_symlink(),
              'No preexisting SwiftPM material')


def prepare(commands):
    bind_source()
    host.prepare_work()
    host.resources(commands, admission=True)
    java = host.tools(commands)
    env = host.environment(java, packages=True)
    env.update(APP32_TEST_FILTERS=json.dumps(TESTS), APP32_REQUESTED_TASKS=json.dumps(TASKS))
    commands.monitor = lambda: host.resources(commands)
    return env


def command():
    args = ['./gradlew', o.MARKER, *COMPILES]
    for task, filters in TESTS.items():
        args.append(task)
        for test in filters:
            args += ['--tests', test]
    jvm = '-Xmx3g -XX:MaxMetaspaceSize=768m -XX:ActiveProcessorCount=2 ' + o.MARKER + \
          f' -Duser.home={o.WORK / "home"} -Djava.io.tmpdir={o.WORK / "tmp"}'
    return args + ['--no-daemon', '--no-parallel', '--max-workers=1', '--no-build-cache', '--no-configuration-cache',
                   '--no-configure-on-demand', '--no-scan', '--console=plain', '--stacktrace',
                   '-Pkotlin.compiler.execution.strategy=in-process', '-Pkotlin.native.disableCompilerDaemon=true',
                   '-Pkotlin.native.parallelThreads=1', '-Pkotlin.native.jvmArgs=' + jvm,
                   '-Pkotlin.mpp.commonizerJvmArgs=' + jvm, '-Dorg.gradle.jvmargs=' + jvm,
                   '-Porg.gradle.java.installations.auto-download=false', '-Pandroid.builder.sdkDownload=false',
                   '-PkiraUseMavenLocal=false', '-Dorg.gradle.vfs.watch=false',
                   '-Pkotlin.project.persistent.dir=' + str(o.WORK / 'kotlin'),
                   '--project-cache-dir', str(o.WORK / 'project-cache'), '-I', CONTROLS[1]]


def retain_reports():
    reports, total = [], 0
    for task in TESTS:
        module = task.split(':')[1]
        directory = o.WORK / 'native-xml' / module
        o.require(directory.resolve() == directory, 'Do not follow Native report directory links')
        for path in sorted(directory.glob('*.xml')):
            o.require(len(reports) < 64 and path.is_file() and not path.is_symlink()
                      and path.stat().st_size <= 1024 * 1024, 'Regular XML only, <=1MiB each, <=64 files')
            raw = o.redact(path.read_bytes())
            total += len(raw)
            o.require(total <= 8 * 1024 * 1024, 'Native XML aggregate cap: 8MiB')
            target = o.OUT / 'reports' / module / path.name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(raw)
            reports.append((task, target))
    return reports


def report_cases(task, path):
    raw = path.read_bytes()
    o.require(b'<!DOCTYPE' not in raw and b'<!ENTITY' not in raw, 'Unexpected XML declarations')
    suite = ET.fromstring(raw)
    cases, observed = suite.findall('testcase'), []
    good = suite.tag == 'testsuite' and bool(cases) and int(suite.get('tests', '-1')) == len(cases)
    good &= all(int(suite.get(key, '-1')) == 0 for key in ('errors', 'failures', 'skipped'))
    good &= list(suite.iter('testcase')) == cases and not any(node.tag in ('error', 'failure', 'skipped') for node in suite.iter())
    for case in cases:
        cls, name = case.get('classname', ''), case.get('name', '')
        original = cls.removeprefix('iosSimulatorArm64Test.')
        test = original + '.' + name.removesuffix('[iosSimulatorArm64]')
        good &= cls.startswith('iosSimulatorArm64Test.') and name.endswith('[iosSimulatorArm64]')
        good &= suite.get('name') == cls and path.name == 'TEST-' + cls + '.xml'
        good &= original in TESTS[task] or test in TESTS[task]
        observed.append(dict(task=task, classname=original, name=name, test=test))
    return observed, good


def verify_execution():
    observed, errors = [], []
    logs = list(o.OUT.glob('*-gradle.log'))
    o.require(len(logs) == 1, 'Exactly one ordinary Gradle invocation log')
    completions = [json.loads(line.removeprefix('APP32_COMPLETE ')) for line in logs[0].read_text().splitlines()
                   if line.startswith('APP32_COMPLETE ')]
    o.record('task-completions.json', completions)
    for task, path in retain_reports():
        try:
            cases, good = report_cases(task, path)
            observed.extend(cases)
            if not good:
                errors.append(task + '/' + path.name + ': empty/failing/skipped/unselected/malformed suite')
        except Exception as error:
            errors.append(task + '/' + path.name + ': ' + type(error).__name__)
    o.record('observed-tests.json', dict(cases=observed, errors=errors, deferred=not bool(TESTS)))
    ids = [(row['task'], row['classname'], row['name']) for row in observed]
    covered = all(any(row['task'] == task and test in (row['classname'], row['test']) for row in observed)
                  for task, filters in TESTS.items() for test in filters)
    o.require(not TESTS or (not errors and bool(ids) and len(ids) == len(set(ids)) and covered),
              'Every exact selected filter must have fresh unskipped passing XML, with no extra/duplicate cases')
    o.require(sorted(row['path'] for row in completions) == sorted(OBSERVED) and all(
        row['executed'] and row['didWork'] and not row['skipped'] and not row['upToDate']
        and not row['noSource'] and row['failure'] is None for row in completions),
        'The iOS Arm64 MAIN compile must actually execute; Native tests remain deferred')


def verify_source():
    after = source()
    o.record('source-after.json', after)
    before = json.loads((o.OUT / 'source-before.json').read_text())
    o.require(after['clean'] and all(after[key] == before[key] for key in ('head', 'tree', 'clean'))
              and not (o.ROOT / '.swiftpm-locks').exists() and not (o.ROOT / '.swiftpm-locks').is_symlink(),
              'Source/schema or SwiftPM material changed; preserve it')


def attempt(result, label, action):
    try:
        return True, action()
    except BaseException as error:
        result['errors'].append(label + ': ' + type(error).__name__ + ': ' + str(error)[:512])
        return False, None


def finish(commands, result, result_name='result.json'):
    if (o.OUT / 'owned.json').is_file() and o.WORK.exists():
        attempt(result, 'stop', lambda: host.stop(commands))
        settled, _ = attempt(result, 'groups', commands.drain)
        disposed, udid = attempt(result, 'simulator', lambda: host.dispose_simulator(commands)) if settled else (False, None)
        absent, _ = attempt(result, 'workers', lambda: commands.absence(udid)) if disposed else (False, None)
        if absent:
            attempt(result, 'evidence', verify_execution)
            attempt(result, 'cleanup', host.cleanup)
    attempt(result, 'source', verify_source)
    if o.CANCELLED or any(job['forced'] for job in commands.jobs):
        result['errors'].append('Cancelled or forced command shutdown; not a passing ordinary execution')
    result['status'] = 'PASS_REVIEW_REQUIRED' if result['exit_code'] == 0 and not result['errors'] else 'FAIL'
    result.update(app61_provenance_hold=True, scope='ordinary iOS Arm64 MAIN only; Native tests deferred; no authenticated-input qualification',
                  not_proven=['Native test compilation/link/runtime', 'shipping framework/host', 'UI',
                              'OS-restored URLSession/process relaunch', 'physical device'])
    o.record(result_name, result)
    print('App32 Apple batch:', result['status'], '(App61 provenance hold remains)')
    return 0 if result['status'] == 'PASS_REVIEW_REQUIRED' else 1


def previous_cleanup():
    path, simulator = o.OUT / 'cleanup.json', o.OUT / 'simulator.json'
    return path.is_file() and json.loads(path.read_text()) == dict(fresh_outputs_absent=True) and all(
        not item.exists() and not item.is_symlink() for item in host.OUTPUTS + [o.WORK]) and (
        not simulator.is_file() or json.loads(simulator.read_text())['deleted'] is True)


def main():
    o.require(sys.argv[1:] in (['run'], ['cleanup'], ['discard']), 'Use run, cleanup or discard only')
    if sys.argv[1:] != ['run']:
        if not o.OUT.exists():
            return 0
        o.require(json.loads((o.OUT / 'evidence-owned.json').read_text()) == o.identity(o.OUT), 'Evidence ownership changed')
        if sys.argv[1:] == ['discard']:
            o.require(previous_cleanup(), 'Keep evidence when owned runtime/output cleanup is incomplete')
            shutil.rmtree(o.OUT)
            return 0
        if previous_cleanup():
            return 0
        runtime = json.loads((o.OUT / 'runtime.json').read_text()) if (o.OUT / 'runtime.json').is_file() else {}
        commands = o.Commands(host.environment(runtime.get('java_home')), seconds=4 * 60)
        return finish(commands, dict(exit_code=None, errors=['Interrupted/incomplete run; fallback has no old PID authority']),
                      result_name='fallback-result.json')
    o.OUT.mkdir(exist_ok=False)
    o.record('evidence-owned.json', o.identity(o.OUT))
    commands, result = o.Commands(host.environment()), dict(exit_code=None, errors=[])
    try:
        env = prepare(commands)
        args = command()
        o.record('command.json', args)
        result['exit_code'], _ = commands.capture(args, 'gradle', seconds=45 * 60, cap=16 * 1024 * 1024,
                                                  env=env, monitor=True)
    except BaseException as error:
        result['errors'].append(type(error).__name__ + ': ' + str(error)[:512])
    return finish(commands, result)


if __name__ == '__main__':
    sys.exit(main())
