"""Public App17 Native3 preparation. UNBOUND request; primary admission is separate."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import plistlib
import pwd
import re
import shutil
import signal
import stat
import sys
import time
import xml.etree.ElementTree as ET

CONTROL = Path(__file__).resolve().parents[1]
# Prepared source commitments only: the request remains unapproved and unbound.
CANDIDATE = '092b5d7cf019c2833d614c7af7f627e8800a007c'
TREE = 'd3b1eed92d9277c6798daeeb9fbe0c2fe486e3d7'
PLAN = '70ba3adb7fb37ab27c8ca02d8a7a160fd88c745a2e75b2a08484ceff895a1b2c'
PINS_HASH = 'd5492c3a28e3a8346e5392015aed7c875879994e1b01e3674fe8d7197cfe8c0b'
TASK = ':platform:iosSimulatorArm64Test'
LINK_TASK = ':platform:linkDebugTestIosSimulatorArm64'
CLASS = 'me.manga.kira.platform.image.IosSvgDecoderRoutingTest'
METHODS = ('svgMimeUsesServiceLoadedDecoder', 'untypedSvgUsesServiceLoadedDecoder',
           'avifFactoryKeepsFirstClaimWithoutConsumingSource')
SELECTORS = tuple(CLASS + '.' + method for method in METHODS)
OWNER_HASH = '9f69c87182eba29030fcd65c49b4e8459a41749440dab9767f3fbfe0c02db6cf'
XCODE = '/Applications/Xcode_26.4.1.app/Contents/Developer'
RUNTIME = 'com.apple.CoreSimulator.SimRuntime.iOS-26-4'
DEVICE_TYPE = 'com.apple.CoreSimulator.SimDeviceType.iPhone-17'
SCRATCH = ('gradle-home', 'konan', 'home', 'tmp', 'project-cache', 'kotlin', 'work')
MODULES = ('core', 'platform', 'composeApp', 'domain', 'data', 'data/local', 'data/remote', 'data/download',
           'presentation', 'ui', 'sources/contracts', 'sources/engine', 'sources/config', 'sources/legacy')
OUTPUTS = ('build', '.gradle', '.kotlin') + tuple(module + '/build' for module in MODULES)
SOURCE_ROOTS = {
    ':core:compileKotlinIosSimulatorArm64': ('core/src/commonMain/kotlin', 'core/src/iosMain/kotlin'),
    ':platform:compileKotlinIosSimulatorArm64': ('platform/src/commonMain/kotlin', 'platform/src/nonAndroidMain/kotlin', 'platform/src/iosMain/kotlin'),
    ':platform:compileTestKotlinIosSimulatorArm64': ('platform/src/commonTest/kotlin', 'platform/src/iosTest/kotlin'),
}
CINTEROP = ':platform:cinteropLibwebpIosSimulatorArm64'
REQUIRED_TASKS = tuple(SOURCE_ROOTS) + (CINTEROP, LINK_TASK, TASK)
CONTROL_FILES = {
    'controllerSha256': 'ci/app17-ios-native.py',
    'initSha256': 'ci/app17-ios-native.init.gradle',
    'workflowSha256': '.github/workflows/app17-ios-native.yml',
    'ownerHelperSha256': 'ci/app8-apple.py',
    'sourcePinsSha256': 'ci/app17-ios-native.source-pins.sha256',
}
REQUEST_PATH = 'ci/app17-ios-native.request.json'
PUBLIC_JSON = ('request.json', 'result.json', 'carrier.json', 'source.json', 'source-after.json',
    'tools.json', 'simulator.json', 'simulator-cleanup.json', 'native-proof.json', 'native-markers.json',
    'commands.json', 'task-graph.json', 'compilation-inputs.json', 'native-closure.json', 'link-inputs.json',
    'linked-binary.json', 'test-invocation.json', 'task-proof.json')
PUBLIC_LOGS = ('native3', 'gradle-stop-immediate', 'gradle-stop-final')
PUBLIC_XML = 'xml/TEST-iosSimulatorArm64Test.' + CLASS + '.xml'
PUBLIC_FILES = {**dict.fromkeys(PUBLIC_JSON, 1048576),
                **{label + '.log': 1048576 for label in PUBLIC_LOGS}, PUBLIC_XML: 4194304}


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def digest(path):
    require(path.is_file() and not path.is_symlink(), 'Missing/nonregular bound file: ' + str(path))
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            result.update(block)
    return result.hexdigest()


def read_json(path):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 1048576, 'Invalid JSON input')
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'Duplicate JSON key')
            result[key] = value
        return result
    def constant(_value):
        raise RuntimeError('Non-finite JSON')
    return json.loads(path.read_text(), object_pairs_hook=unique, parse_constant=constant)


def source_inputs():
    path = CONTROL / 'ci/app17-ios-native.source-pins.sha256'
    require(digest(path) == PINS_HASH, 'Changed prepared source38 manifest')
    rows = [line.split(maxsplit=1) for line in path.read_text().splitlines()]
    require(all(len(row) == 2 for row in rows), 'Malformed source38 row')
    result = {name: sha for sha, name in rows}
    require(len(rows) == len(result) == 38 and all(re.fullmatch('[0-9a-f]{64}', sha)
            and not Path(name).is_absolute() and '..' not in Path(name).parts for name, sha in result.items()),
            'Invalid source38 manifest')
    return result


def validate_request(request, context):
    fixed = {'schema': 'app17-ios-native-public-v1', 'authorization': 'APP17_NATIVE3_ONE_ATTEMPT_AUTHORIZED',
             'acquisitionAuthorization': 'PUBLIC_DECLARED_WRAPPER_GRADLE_NATIVE_ONLY',
             'sourceRepository': 'kira-manga/kira-app', 'task': TASK, 'expectedRunAttempt': 1,
             'planSha256': PLAN, 'sourceTree': TREE, 'sourcePinsSha256': PINS_HASH, 'selectors': list(SELECTORS),
             'ownerHelperSha256': OWNER_HASH, 'developerDir': XCODE}
    require(isinstance(request, dict)
            and set(request) == set(fixed) | {'sourceSha', 'controllerSha256', 'initSha256', 'workflowSha256'},
            'Unknown/missing request field')
    require(type(request['expectedRunAttempt']) is int, 'Run attempt must be an integer, not a boolean')
    require(all(request[key] == value for key, value in fixed.items()), 'Unbound or unauthorized request')
    require(request['sourceSha'] == CANDIDATE, 'Only the exact prepared source checkpoint may be bound')
    for key in ('controllerSha256', 'initSha256', 'workflowSha256'):
        require(isinstance(request[key], str) and re.fullmatch('[0-9a-f]{64}', request[key]), 'Invalid control hash')
    expected = {'GITHUB_ACTIONS': 'true', 'GITHUB_REPOSITORY': 'kira-manga/kira-app',
                'GITHUB_REF': 'refs/heads/remediation/app-17-public-native-validation-02', 'GITHUB_EVENT_NAME': 'push',
                'GITHUB_RUN_ATTEMPT': '1', 'RUNNER_OS': 'macOS', 'RUNNER_ARCH': 'ARM64',
                'RUNNER_ENVIRONMENT': 'github-hosted',
                'GITHUB_WORKFLOW_REF': 'kira-manga/kira-app/.github/workflows/app17-ios-native.yml@'
                                       'refs/heads/remediation/app-17-public-native-validation-02'}
    require(all(context.get(key) == value for key, value in expected.items()), 'Wrong public hosted invocation')
    require(re.fullmatch('[1-9][0-9]*', context.get('GITHUB_RUN_ID', '')) is not None, 'Invalid run identity')
    carrier = context.get('GITHUB_SHA', '')
    require(re.fullmatch('[0-9a-f]{40}', carrier) is not None and carrier != '0' * 40
            and context.get('GITHUB_WORKFLOW_SHA') == carrier, 'Invalid event/workflow carrier SHA')


def checked_event():
    event = read_json(Path(os.environ['GITHUB_EVENT_PATH']))
    require(event['repository']['private'] is False and event['repository']['full_name'] == 'kira-manga/kira-app',
            'Carrier is not the public App repository')
    require(event['after'] == event['head_commit']['id'] == os.environ['GITHUB_SHA']
            and event['ref'] == os.environ['GITHUB_REF'] and event['deleted'] is False and event['forced'] is False,
            'Wrong/deleted/forced public push event')
    require(re.fullmatch('[0-9a-f]{40}', event['before']) is not None, 'Invalid prior event SHA')
    return {key: event[key] for key in ('before', 'after', 'ref', 'deleted', 'forced')}


def verify_controls(request):
    require(all(digest(CONTROL / path) == request[key] for key, path in CONTROL_FILES.items()),
            'Bound controller/helper/source manifest changed')
    require(read_json(CONTROL / REQUEST_PATH) == request, 'Request changed after the admission gate')


def verify_carrier(owner, commands, request, work_end):
    git = ['/usr/bin/git', '-C', str(CONTROL)]
    sha = commands.call(git + ['rev-parse', 'HEAD'], 'carrier-sha', end=work_end).strip()
    require(sha == os.environ['GITHUB_SHA'], 'Control checkout is not the event commit')
    parents = commands.call(git + ['rev-list', '--parents', '-n', '1', 'HEAD'], 'carrier-parent', end=work_end).split()
    require(parents == [sha, CANDIDATE], 'Carrier must be one source-only checkpoint child, not other ancestry')
    tree = commands.call(git + ['rev-parse', 'HEAD^{tree}'], 'carrier-tree', end=work_end).strip()
    parent_tree = commands.call(git + ['rev-parse', 'HEAD^1^{tree}'], 'carrier-source-tree', end=work_end).strip()
    require(re.fullmatch('[0-9a-f]{40}', tree) is not None and parent_tree == TREE, 'Carrier source tree mismatch')
    changed = commands.call(git + ['diff-tree', '--no-commit-id', '--name-status', '--no-renames', '-r',
                                   'HEAD^1', 'HEAD'], 'carrier-six-path-delta', end=work_end).splitlines()
    expected = sorted(CONTROL_FILES.values()) + [REQUEST_PATH]
    require(sorted(changed) == sorted('A\t' + path for path in expected), 'Carrier changed more than the six new controls')
    require(not commands.call(git + ['status', '--porcelain', '--untracked-files=all'],
                              'carrier-clean', end=work_end), 'Dirty carrier checkout')
    verify_controls(request)
    owner.save(commands.reports / 'carrier.json', {'event': checked_event(), 'sha': sha, 'tree': tree,
        'parent': CANDIDATE, 'parentTree': parent_tree, 'sourcePreserved': True, 'addedControls': sorted(expected),
        'controls': {path: digest(CONTROL / path) for path in expected}}, limit=1048576)


def checked_request():
    request = read_json(CONTROL / REQUEST_PATH)
    validate_request(request, os.environ)
    checked_event()
    verify_controls(request)
    return request


def child_environment(run, original, runner_home):
    marker = 'app17.native.owner=' + run.name
    java = original['JAVA_HOME_21_arm64']  # Installed runner input only; no setup/install/fallback step.
    result = {'PATH': java + '/bin:/usr/bin:/bin:/usr/sbin:/sbin', 'JAVA_HOME': java, 'HOME': str(run / 'home'),
              'GRADLE_USER_HOME': str(run / 'gradle-home'), 'KONAN_DATA_DIR': str(run / 'konan'),
              'TMPDIR': str(run / 'tmp') + '/', 'DEVELOPER_DIR': XCODE, 'CI': 'true',
              'LANG': 'en_US.UTF-8', 'LC_ALL': 'en_US.UTF-8', 'GIT_CONFIG_NOSYSTEM': '1',
              'GIT_CONFIG_GLOBAL': '/dev/null', 'KIRA_SOURCE_CONFIG_BASE_URL': '', 'KIRA_SOURCE_CONFIG_PINNED_KEYS': '',
              'KIRA_APP_VERSION': '1.0.5', 'APP17_RUN': str(run), 'APP17_SIMULATOR_HOME': str(runner_home),
              'JAVA_OPTS': f'-Xmx128m -D{marker} -Duser.home={run / "home"} -Djava.io.tmpdir={run / "tmp"}'}
    for key in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        if original.get(key):
            result[key] = original[key]
    return result


def test_argv(source, run, udid):
    filters = [value for selector in SELECTORS for value in ('--tests', selector)]
    return [str(source / 'gradlew'), '-p', str(source), TASK, *filters, '--device', udid,
            '--no-daemon', '--no-parallel', '--max-workers=1',
            '--no-build-cache', '--no-configuration-cache', '--console=plain', '--stacktrace',
            '--project-cache-dir', str(run / 'project-cache'), '-I', str(CONTROL / 'ci/app17-ios-native.init.gradle'),
            '-Pkotlin.compiler.execution.strategy=in-process', '-Pkotlin.native.disableCompilerDaemon=false',
            '-Pkotlin.native.parallelThreads=1',
            '-Pkotlin.incremental=false', '-PkiraUseMavenLocal=false',
            '-Porg.gradle.java.installations.auto-download=false', '-Pandroid.builder.sdkDownload=false', '-Dorg.gradle.vfs.watch=false',
            '-Pkotlin.project.persistent.dir=' + str(run / 'kotlin'),
            f'-Duser.home={run / "home"}',
            f'-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=768m -Dapp17.native.owner={run.name} '
            f'-Duser.home={run / "home"} -Djava.io.tmpdir={run / "tmp"}']


def absence(commands, run, source, state, final=False):
    settled = commands.drain()
    rows = commands.census(cleaning=True)
    markers = [str(run), str(source), 'app17.native.owner=' + run.name]
    if final and state.get('udid'):
        markers.append(state['udid'])
    workers = [row for row in rows if row['pid'] != os.getpid() and not row['state'].startswith('Z')
               and (any(value in row['command'] for value in markers)
                    or row['executable'] == state.get('executableName'))]
    # Global executable-name matches are observation/refusal only, never signal authority.
    return {'absent': settled and not workers, 'groupsSettled': settled,
            'remainingOwned': [{key: value for key, value in row.items() if key != 'command'} for row in workers]}


def source_inventory(source):
    result = {}
    for task, roots in SOURCE_ROOTS.items():
        files = sorted({file for name in roots for file in (source / name).rglob('*.kt')})
        require(0 < len(files) <= 1024, 'Unexpected compilation source inventory')
        require(all(file.resolve().is_relative_to(source) for file in files), 'Source outside reviewed checkout')
        result[task] = {str(file.relative_to(source)): digest(file) for file in files}
    return result


def simctl(commands, runner_home, args, label, seconds=15, end=None, cleaning=False):
    # One borrowed command owner, but CoreSimulator always sees the real account HOME.
    if cleaning:
        commands.retire_observer()  # Settle the previous owned leaf before this command needs a group join.
    return commands.call(['/usr/bin/xcrun', 'simctl', *args], label, seconds=seconds, end=end,
                         extra={'HOME': str(runner_home)}, cleaning=cleaning)


def devices(commands, runner_home, end=None, cleaning=False):
    value = json.loads(simctl(commands, runner_home, ['list', 'devices', '--json'], 'devices',
                              seconds=5, end=end, cleaning=cleaning))
    return [dict(row, runtime=runtime) for runtime, rows in value['devices'].items() for row in rows]


def simulator_identity(runner_home, state, row=None):
    require(state.get('creating') is False and state['runnerHome'] == str(runner_home)
            and state['runtime'] == RUNTIME and state['deviceType'] == DEVICE_TYPE
            and re.fullmatch(r'[0-9A-F]{8}(?:-[0-9A-F]{4}){3}-[0-9A-F]{12}', state['udid']),
            'Invalid created simulator binding')
    root = runner_home / 'Library/Developer/CoreSimulator/Devices' / state['udid']
    data, info = root / 'data', root.lstat()
    require(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid() and root.resolve() == root
            and data.is_dir() and data.resolve() == data, 'Foreign/missing/noncanonical simulator root')
    identity = {'udid': state['udid'], 'name': state['name'], 'runtime': RUNTIME, 'deviceType': DEVICE_TYPE,
                'runnerHome': str(runner_home), 'dataPath': str(data), 'root': str(root),
                'rootDevice': info.st_dev, 'rootInode': info.st_ino, 'rootUid': info.st_uid}
    if row is not None:
        require((row['udid'], row['name'], row['runtime'], row['deviceTypeIdentifier'], row['dataPath'])
                == (identity['udid'], identity['name'], RUNTIME, DEVICE_TYPE, identity['dataPath']),
                'Owned simulator inventory identity/path mismatch')
    return identity


def create_simulator(owner, commands, runner_home, state, work_end):
    runtimes = json.loads(simctl(commands, runner_home, ['list', 'runtimes', '--json'], 'runtimes', end=work_end))['runtimes']
    matches = [row for row in runtimes if row['identifier'] == RUNTIME and row.get('isAvailable') is True]
    require(len(matches) == 1 and matches[0]['version'] == '26.4.1' and matches[0]['buildversion'] == '23E254a'
            and 'arm64' in matches[0]['supportedArchitectures'], 'Missing exact installed runtime; no download/fallback')
    types = json.loads(simctl(commands, runner_home, ['list', 'devicetypes', '--json'], 'device-types', end=work_end))['devicetypes']
    require(sum(row['identifier'] == DEVICE_TYPE for row in types) == 1, 'Missing installed iPhone17 type')
    require(not any(row['name'] == state['name'] for row in devices(commands, runner_home, work_end)), 'Owned name already exists')
    state.update(creating=True, runtime=RUNTIME, runtimeVersion='26.4.1', runtimeBuild='23E254a', deviceType=DEVICE_TYPE)
    owner.save(commands.run / 'simulator.json', state)
    state['udid'] = simctl(commands, runner_home, ['create', state['name'], DEVICE_TYPE, RUNTIME],
                          'create-owned-simulator', end=work_end).strip()
    require(re.fullmatch(r'[0-9A-F]{8}(?:-[0-9A-F]{4}){3}-[0-9A-F]{12}', state['udid']), 'Invalid created UUID')
    state['creating'] = False
    owner.save(commands.run / 'simulator.json', state)
    matches = [row for row in devices(commands, runner_home, work_end)
               if row['udid'] == state['udid'] or row['name'] == state['name']]
    require(len(matches) == 1 and matches[0]['state'] == 'Shutdown', 'Created simulator is not uniquely shut down')
    state['createdOwnership'] = simulator_identity(runner_home, state, matches[0])
    state['dataPath'] = state['createdOwnership']['dataPath']
    state['bootIntended'] = False
    owner.save(commands.run / 'simulator.json', state)
    boot_end = min(work_end, time.monotonic() + 180)
    require(not owner.CANCELLED and time.monotonic() < boot_end, 'Owned simulator boot cancelled/expired')
    state['bootIntended'] = True  # Persist intent before a launch that may fail after a side effect.
    owner.save(commands.run / 'simulator.json', state)
    simctl(commands, runner_home, ['boot', state['udid']], 'boot-owned-simulator', end=boot_end)
    simctl(commands, runner_home, ['bootstatus', state['udid'], '-b'], 'owned-bootstatus', seconds=180, end=boot_end)
    require(simulator_identity(runner_home, state) == state['createdOwnership'], 'Owned simulator root changed during boot')
    require(not owner.CANCELLED and time.monotonic() < boot_end, 'Owned simulator readiness cancelled/expired')
    state['bootReadinessEvidence'] = 'normal-same-uuid-bootstatus-and-same-owned-root'
    owner.save(commands.run / 'simulator.json', state)
    owner.save(commands.reports / 'simulator.json', state)


def dispose_simulator(owner, commands, runner_home, state, errors):
    if not state.get('creating') and not state.get('udid'):
        return True
    udid = state.get('udid')
    bound = state.get('createdOwnership')
    def shutdown():
        try:
            simctl(commands, runner_home, ['shutdown', udid], 'shutdown-owned-simulator', seconds=30, cleaning=True)
        except Exception as error:
            errors.append('simulator shutdown: ' + str(error))
            # A failed join is an ambiguous side effect, not permission to repeat shutdown.
            require(commands.drain(), 'Owned shutdown work is unsettled; retain device/scratch')
            require(bound is not None, 'Unbound shutdown failure; retain device/scratch')
            # Continue once to the existing fresh same-root Shutdown inventory below.
    if bound is not None:
        require(simulator_identity(runner_home, state) == bound, 'Owned simulator root changed before cleanup')
        require(type(state.get('bootIntended')) is bool, 'Missing owned simulator boot intent')
        if state['bootIntended']:
            shutdown()
    matches = [row for row in devices(commands, runner_home, cleaning=True)
               if row['name'] == state['name'] or row['udid'] == udid]
    require(len(matches) <= 1 and all(row['name'] == state['name'] and row['runtime'] == RUNTIME
            and (not udid or row['udid'] == udid) for row in matches), 'Ambiguous owned simulator; retain scratch')
    if bound is not None:
        require(len(matches) == 1 and matches[0]['state'] == 'Shutdown', 'Bound simulator shutdown unproven')
        require(simulator_identity(runner_home, state, matches[0]) == bound, 'Owned simulator changed during cleanup')
    if matches:
        device = matches[0]
        udid = state['udid'] = device['udid']
        require(re.fullmatch(r'[0-9A-F]{8}(?:-[0-9A-F]{4}){3}-[0-9A-F]{12}', udid), 'Invalid disposal UUID')
        owner.save(commands.run / 'simulator.json', state)
        if bound is None:  # Uncertain create still requires inventory-based ownership recovery first.
            if device['state'] != 'Shutdown':
                shutdown()
            current = [row for row in devices(commands, runner_home, cleaning=True) if row['udid'] == udid]
            require(len(current) == 1 and current[0]['state'] == 'Shutdown', 'Owned shutdown unproven')
        simctl(commands, runner_home, ['delete', udid], 'delete-owned-simulator', cleaning=True)
    require(not any(row['name'] == state['name'] or row['udid'] == udid
                    for row in devices(commands, runner_home, cleaning=True)), 'Owned simulator remains')
    return True


def remove_scoped(root, names):
    require(root.is_dir() and root.resolve() == root and not root.is_symlink(), 'Unsafe cleanup owner')
    removed = []
    for name in names:
        path = root / name
        require(not path.is_symlink() and path.resolve().is_relative_to(root), 'Unsafe cleanup root')
        if path.exists():
            require(path.is_dir(), 'Expected owned generated directory')
            shutil.rmtree(path)
        require(not path.exists(), 'Owned scratch remains')
        removed.append(name)
    return removed


def fingerprint(path):
    require(path.exists() and not path.is_symlink() and path.resolve() == path, 'Unsafe/missing artifact')
    if path.is_file():
        require(path.stat().st_size <= 268435456, 'Oversized artifact')
        return {'file': str(path), 'bytes': path.stat().st_size, 'sha256': digest(path)}
    files = []
    for item in sorted(path.rglob('*')):
        require(not item.is_symlink(), 'Symlink inside klib')
        if item.is_file():
            size = item.stat().st_size
            require(len(files) < 1024 and sum(row['bytes'] for row in files) + size <= 268435456, 'Oversized unpacked klib')
            files.append({'path': str(item.relative_to(path)), 'bytes': size, 'sha256': digest(item)})
    require(files, 'Empty unpacked klib')
    files.sort(key=lambda row: row['path'])  # Match the init's relative-path, not pathlib component, order.
    tree = ''.join(row['sha256'] + '  ' + row['path'] + '\n' for row in files)
    return {'file': str(path), 'bytes': sum(row['bytes'] for row in files), 'files': files,
            'sha256': hashlib.sha256(tree.encode()).hexdigest()}


def output_proof(source, reports, request, state, commands):
    proof = read_json(reports / 'task-proof.json')
    require(proof['schema'] == 'app17-ios-native-task-v1'
            and proof['requestedTask'] == TASK and proof['sourceSha'] == request['sourceSha']
            and proof['gradleVersion'] == '9.6.1', 'Missing real task proof')
    for task in REQUIRED_TASKS:
        row = proof['states'][task]
        require(all(row[key] is True for key in ('executed', 'didWork', 'actionsReachedEnd')) and row['failure'] is None
                and all(row[key] is False for key in ('skipped', 'upToDate', 'noSource')), 'Unproven required action: ' + task)
    before = read_json(reports / 'source.json')['compilations']
    compilations = read_json(reports / 'compilation-inputs.json')
    require(set(compilations) == set(SOURCE_ROOTS) | {CINTEROP}, 'Missing main/test/cinterop receipt')
    for task, row in compilations.items():
        require(row['sourceSha'] == request['sourceSha'] and row['actionsReachedEnd'], 'Incomplete compilation receipt')
        if task in before:
            require(row['sources'] == before[task], 'Actual compile source selection differs from full reviewed sources')
        path = Path(row['output']['file'])
        require(any(path.is_relative_to(source / module / 'build') for module in ('core', 'platform')),
                'Unowned compiled artifact')
        require(fingerprint(path) == row['output'], 'Changed compiled klib')
    closure = read_json(reports / 'native-closure.json')
    require(closure['validation'] == 'PASS' and closure['task'] == LINK_TASK and closure['project'] == ':platform'
            and closure['sourceSha'] == request['sourceSha'] and closure['target'] == 'ios_simulator_arm64', 'Unproven Native closure')
    inputs = read_json(reports / 'link-inputs.json')
    require(inputs['task'] == LINK_TASK and inputs['sourceSha'] == request['sourceSha'] and inputs['libraries']
            and inputs['target'] == 'ios_simulator_arm64'
            and inputs['includes'] == [compilations[':platform:compileTestKotlinIosSimulatorArm64']['output']],
            'Link did not include exactly the just-compiled platform test KLIB')
    linked = read_json(reports / 'linked-binary.json')
    binary = Path(linked['file'])
    require(linked['task'] == LINK_TASK and linked['target'] == 'ios_simulator_arm64'
            and linked['sourceSha'] == request['sourceSha']
            and binary.is_relative_to(source / 'platform/build') and digest(binary) == linked['sha256'], 'Changed/unowned executable')
    invocation = read_json(reports / 'test-invocation.json')
    require(invocation['task'] == TASK and invocation['sourceSha'] == request['sourceSha']
            and invocation['selectors'] == sorted(SELECTORS)
            and invocation['device'] == state['udid'] and invocation['standalone'] is False
            and invocation['targetName'] == 'iosSimulatorArm64' and invocation['simulatorHome'] == state['runnerHome']
            and invocation['executable'] == str(binary) and invocation['sha256'] == linked['sha256'], 'Wrong actual Native invocation')
    arch = commands.call(['/usr/bin/xcrun', 'lipo', '-archs', str(binary)], 'native-arch', seconds=15, cleaning=True).strip()
    build = commands.call(['/usr/bin/xcrun', 'vtool', '-show-build', str(binary)], 'native-platform', seconds=15, cleaning=True)
    require(arch == 'arm64' and re.findall(r'(?m)^\s*platform\s+(\S+)\s*$', build) == ['IOSSIMULATOR']
            and re.findall(r'(?m)^\s*sdk\s+(\S+)\s*$', build) == ['26.4'], 'Wrong Native Mach-O architecture/platform/SDK')
    minimum = re.findall(r'(?m)^\s*minos\s+(\S+)\s*$', build)
    require(len(minimum) == 1 and re.fullmatch(r'[0-9]+(?:\.[0-9]+){1,2}', minimum[0]), 'Missing deployment receipt')
    xmls = list((reports / 'xml').glob('*.xml'))
    require(xmls == [reports / PUBLIC_XML] and xmls[0].is_file() and not xmls[0].is_symlink()
            and xmls[0].stat().st_size <= 4194304, 'Expected the exact one bounded XML suite')
    raw = xmls[0].read_bytes()
    require(b'<!DOCTYPE' not in raw and b'<!ENTITY' not in raw, 'Unexpected XML declarations')
    suite = ET.fromstring(raw)
    cases = suite.findall('testcase')
    native_class = 'iosSimulatorArm64Test.' + CLASS  # Normal KGP2.4 root-suite prefix; no reporter mutation.
    require(suite.tag == 'testsuite' and suite.get('name') == native_class
            and int(suite.get('tests', '-1')) == len(cases) == 3
            and all(int(suite.get(key, '-1')) == 0 for key in ('failures', 'errors', 'skipped'))
            and all(case.get('classname') == native_class for case in cases)
            and {case.get('name') for case in cases} == {method + '[iosSimulatorArm64]' for method in METHODS}
            and list(suite.iter('testcase')) == cases
            and not any(node.tag in ('failure', 'error', 'skipped') for node in suite.iter()), 'XML did not prove exact passing Native3')
    require(time.monotonic() < commands.end, 'Evidence collection exceeded owned deadline')
    return {'tests': [case.attrib for case in cases], 'xmlSha256': digest(xmls[0]), 'linkedSha256': linked['sha256'],
            'architecture': arch, 'platform': 'IOSSIMULATOR', 'sdk': '26.4', 'observedMinimumOS': minimum[0],
            'requiredTasks': list(REQUIRED_TASKS), 'qualification': 'focused Native3 only; no app/framework/device matrix'}


def native_markers(run):
    # File evidence only; never invoke another compiler or hash/copy the whole SDK/distribution.
    roots = list((run / 'konan').glob('kotlin-native*'))
    require(len(roots) == 1 and 'macos-aarch64' in roots[0].name and roots[0].name.endswith('-2.4.0'),
            'Missing/ambiguous Kotlin Native 2.4.0 host distribution')
    root = roots[0]
    require(root.is_dir() and not root.is_symlink(), 'Invalid native distribution directory')
    names = ('konan/konan.properties', 'bin/konanc')
    return {'distribution': root.name, 'kotlinVersionFromBoundCatalog': '2.4.0',
            'markers': {name: digest(root / name) for name in names}}


def public_retention(commands, reports, result):
    # Fixed owned-log aliases only, after the final absence barrier. No upload glob or raw census.
    require(result.get('afterFinalStop', {}).get('absent') is True, 'No public retention before final owned absence')
    aliases = {}
    for label in PUBLIC_LOGS:
        matches = [task for task in commands.tasks if task['receipt']['label'] == label] if commands else []
        require(len(matches) <= 1, 'Repeated primary/stop command label')
        if not matches:
            continue
        task, target = matches[0], reports / (label + '.log')
        source = task['log']
        require(not task['leaf'] and source.parent == reports and source.name == task['receipt']['output']
                and re.fullmatch(r'[0-9]{3}-' + re.escape(label) + r'\.log', source.name), 'Unowned public log source')
        require(source.is_file() and not source.is_symlink() and source.resolve() == source
                and source.stat().st_size <= 1048576 and not target.exists() and not target.is_symlink(),
                'Unsafe/oversized public log alias')
        with source.open('rb') as stream:
            data = stream.read(1048577)
        require(len(data) <= 1048576, 'Public log source grew after absence')
        with target.open('xb') as stream:
            stream.write(data)
        require(digest(target) == digest(source), 'Owned public log copy changed')
        aliases[target.name] = {'ownedCommandOutput': source.name, 'sha256': digest(target), 'bytes': len(data),
            'originalBytes': result['logBytesBeforeRetentionCap'][source.name]}
    present = []
    for name, limit in PUBLIC_FILES.items():
        path = reports / name
        require(not path.is_symlink(), 'Symlink in public report whitelist')
        if path.exists():
            require(path.is_file() and path.resolve() == path and path.stat().st_size <= limit,
                    'Nonregular/oversized public report: ' + name)
            present.append(name)
    if result.get('nativeSucceeded') and result.get('outputProofPreserved'):
        require(set(present) == set(PUBLIC_FILES), 'Missing required public Native3 evidence')
    return {'logAliases': aliases, 'present': sorted(present), 'maxFiles': 22, 'maxBytes': 26214400}


def run_recipe(owner, source, run, request, system, runner_home):
    """Fixed recipe; the borrowed owner supplies all child launch, wait, census and signal logic."""
    end = time.monotonic() + 1440
    work_end = end - 240
    env = child_environment(run, os.environ, runner_home)
    env['APP17_SOURCE_SHA'] = request['sourceSha']
    commands = None
    reports, errors, started, source_ready = run / 'reports', [], False, False
    state = {'name': 'App17-Public-Native01-' + os.environ['GITHUB_RUN_ID'] + '-1', 'runnerHome': str(runner_home)}
    result = {'schema': 'app17-ios-native-public-result-v1', 'passed': False, 'sourceSha': request['sourceSha'],
              'sourceTree': TREE,
              'carrierSha': os.environ['GITHUB_SHA'], 'task': TASK, 'selectors': list(SELECTORS),
              'run': run.name, 'system': system, 'errors': errors,
              'limits': {'workSeconds': 1200, 'cleanupReserveSeconds': 240, 'stopSecondsEach': 40,
                         'simulatorBootSeconds': 180, 'xmlBytes': 4194304,
                         'sampledCommandLogBytes': 1048576, 'sampledTotalCommandLogBytes': 4194304}}
    owner.save(run / 'owner.json', {'run': run.name, 'pid': os.getpid(), 'sourceSha': request['sourceSha']})
    owner.save(reports / 'request.json', request)
    owner.save(reports / 'result.json', result)  # Fail-closed evidence even if the helper capability gate refuses.
    def stop(label):
        try:
            commands.retire_observer()
            commands.call([str(source / 'gradlew'), '--stop'], label, seconds=40, cleaning=True)
        except Exception as error:
            errors.append(label + ': ' + str(error))
    try:
        owner.deadline_capabilities()
        commands = owner.Commands(run, env, end)
        verify_carrier(owner, commands, request, work_end)
        git = ['/usr/bin/git', '-C', str(source)]
        require(commands.call(git + ['rev-parse', 'HEAD'], 'source-sha', end=work_end).strip() == request['sourceSha'], 'Source SHA mismatch')
        require(commands.call(git + ['rev-parse', 'HEAD^{tree}'], 'source-tree', end=work_end).strip() == TREE, 'Source tree mismatch')
        require(not commands.call(git + ['status', '--porcelain', '--untracked-files=all'], 'source-clean', end=work_end), 'Dirty source checkout')
        expected_inputs = source_inputs()
        inputs = {path: digest(source / path) for path in expected_inputs}
        require(inputs == expected_inputs, 'Prepared source/build input changed')
        require(all(not (source / name).exists() and not (source / name).is_symlink() for name in OUTPUTS),
                'Preexisting generated output; refuse Native work and source cleanup')
        source_ready = True
        before = source_inventory(source)
        owner.save(reports / 'source.json', {'sha': request['sourceSha'], 'tree': TREE, 'root': str(source),
            'inputs': inputs, 'compilations': before, 'selectors': list(SELECTORS)}, limit=1048576)
        tools = {'python': sys.version, 'pythonExecutable': sys.executable, 'developerDir': XCODE}
        tools['java'] = commands.call([str(Path(env['JAVA_HOME']) / 'bin/java'), '-version'], 'java-version', end=work_end)
        tools['javaReleaseSha256'] = digest(Path(env['JAVA_HOME']) / 'release')
        tools['xcode'] = commands.call(['/usr/bin/xcodebuild', '-version'], 'xcode-version', end=work_end)
        tools['sdk'] = commands.call(['/usr/bin/xcrun', '--sdk', 'iphonesimulator', '--show-sdk-version'], 'sdk-version', end=work_end)
        require(tools['xcode'].strip() == 'Xcode 26.4.1\nBuild version 17E202' and tools['sdk'].strip() == '26.4',
                'Selected Xcode/SDK differs from the bound toolchain')
        tools['homes'] = {'gradleEnvironment': env['HOME'], 'javaUserHome': str(run / 'home'),
                          'simulatorEnvironment': str(runner_home)}
        owner.save(reports / 'tools.json', tools)
        create_simulator(owner, commands, runner_home, state, work_end)
        env['APP17_DEVICE'] = state['udid']
        started = True
        commands.call(test_argv(source, run, state['udid']), 'native3', seconds=1200, end=work_end)
        result['nativeSucceeded'] = True
    except Exception as error:
        errors.append('validation: ' + str(error))
    finally:
        if started:
            stop('gradle-stop-immediate')
        try:
            if (reports / 'linked-binary.json').is_file():
                binary = Path(read_json(reports / 'linked-binary.json')['file'])
                require(binary.is_relative_to(source / 'platform/build'), 'Unowned executable receipt')
                state['executableName'] = binary.name
            result['afterImmediateStop'] = (absence(commands, run, source, state) if commands else
                                            {'absent': True, 'noChildOwnerCreated': True})
            require(result['afterImmediateStop']['absent'], 'Owned workers remain; retain outputs/scratch')
            if source_ready and started:
                try:
                    if result.get('nativeSucceeded'):
                        owner.save(reports / 'native-proof.json', output_proof(source, reports, request, state, commands))
                        owner.save(reports / 'native-markers.json', native_markers(run))
                        result['outputProofPreserved'] = True
                    after_inputs = {path: digest(source / path) for path in expected_inputs}
                    after = source_inventory(source)
                    owner.save(reports / 'source-after.json', {'sha': request['sourceSha'], 'tree': TREE,
                        'inputs': after_inputs, 'compilations': after}, limit=1048576)
                    require(after_inputs == expected_inputs and after == before, 'Source/build inputs changed during Native work')
                    verify_controls(request)
                    require(not commands.call(['/usr/bin/git', '-C', str(source), 'diff', '--name-only', 'HEAD'],
                                              'source-after', cleaning=True).strip(), 'Tracked source changed during Native work')
                except Exception as error:
                    errors.append('evidence: ' + str(error))
                result['outputsRemoved'] = remove_scoped(source, OUTPUTS)
        except Exception as error:
            errors.append('preserve/clean: ' + str(error))
        try:
            result['simulatorRemoved'] = dispose_simulator(owner, commands, runner_home, state, errors)
        except Exception as error:
            errors.append('simulator cleanup: ' + str(error))
        try:
            owner.save(reports / 'simulator-cleanup.json', {'state': state, 'removed': result.get('simulatorRemoved', False)})
        except Exception as error:
            errors.append('simulator cleanup receipt: ' + str(error))
        if started:
            stop('gradle-stop-final')
        final_source_clean = False
        try:
            if source_ready and result.get('outputsRemoved'):
                commands.retire_observer()
                for checkout, label, expected_sha, expected_tree in (
                        (source, 'source', CANDIDATE, TREE), (CONTROL, 'carrier', os.environ['GITHUB_SHA'], None)):
                    git = ['/usr/bin/git', '-C', str(checkout)]
                    require(not commands.call(git + ['status', '--porcelain', '--untracked-files=all'],
                                              label + '-final-clean', cleaning=True).strip(), 'Unexpected checkout mutation remains')
                    require(commands.call(git + ['rev-parse', 'HEAD'], label + '-final-sha', cleaning=True).strip()
                            == expected_sha, 'Checkout checkpoint moved')
                    if expected_tree:
                        require(commands.call(git + ['rev-parse', 'HEAD^{tree}'], 'source-final-tree', cleaning=True).strip()
                                == expected_tree, 'Source tree moved')
            final_source_clean = True
        except Exception as error:
            errors.append('final source: ' + str(error))
        try:
            # A Git/source failure must not skip the final existing-owner group drain.
            result['afterFinalStop'] = (absence(commands, run, source, state, final=True) if commands else
                                        {'absent': True, 'noChildOwnerCreated': True})
            require(result['afterFinalStop']['absent'] and result.get('simulatorRemoved'), 'Owned workers/device remain; retain scratch')
            require(final_source_clean, 'Final source unverified; retain scratch')
            result['scratchRemoved'] = remove_scoped(run, SCRATCH)
        except Exception as error:
            errors.append('final ownership/clean: ' + str(error))
            if commands is not None:
                try:
                    commands.retire_observer()  # One final existing-owner opportunity; never another census.
                except Exception as retirement_error:
                    errors.append('final observer retirement: ' + str(retirement_error))
                try:
                    commands.checkpoint()  # Also persist errors caught inside retire_observer itself.
                except Exception as checkpoint_error:
                    errors.append('final observer retirement receipt: ' + str(checkpoint_error))
        result['sourceBaselineVerified'] = source_ready
        result['nativeAttempted'] = started
        result['normalOwnedCompletion'] = commands.normal() if commands else False
        result['cancelled'] = owner.CANCELLED
        result['logBytesBeforeRetentionCap'] = {path.name: path.stat().st_size for path in reports.glob('*.log')}
        remaining = 4194304
        for path in sorted(reports.glob('*.log')):
            limit = min(1048576, remaining)
            with path.open('rb') as stream:
                prefix = stream.read(limit)
            if path.stat().st_size > limit:
                errors.append('Log cap exceeded: ' + path.name)
            # Stable bounded prefix even on an incomplete owned-worker cleanup; later writes
            # cannot enlarge the retained inode. This is not an instantaneous execution disk cap.
            pending = path.with_suffix('.retained')
            with pending.open('xb') as stream:
                stream.write(prefix)
            pending.replace(path)
            remaining -= len(prefix)
        remaining = 4194304
        for path in sorted((reports / 'xml').glob('*.xml')):
            limit = min(4194304, remaining)
            require(not path.is_symlink(), 'Symlink in Native XML')
            with path.open('rb') as stream:
                prefix = stream.read(limit)
            if path.stat().st_size > limit:
                errors.append('XML cap exceeded: ' + path.name)
            pending = path.with_suffix('.retained')
            pending.write_bytes(prefix)
            pending.replace(path)
            remaining -= len(prefix)
        retention_ready = False
        try:
            result['publicRetention'] = public_retention(commands, reports, result)
            retention_ready = True
        except Exception as error:
            errors.append('public retention: ' + str(error))
        result['passed'] = bool(result.get('nativeSucceeded') and result.get('outputProofPreserved')
                                and result.get('outputsRemoved') == list(OUTPUTS)
                                and result.get('simulatorRemoved')
                                and result.get('scratchRemoved') == list(SCRATCH)
                                and not errors and result['normalOwnedCompletion'] and not owner.CANCELLED)
        result['status'] = 'RESULT_REVIEW_REQUIRED' if result['passed'] else 'FAIL'
        owner.save(reports / 'result.json', result, limit=1048576)
        if retention_ready:
            with Path(os.environ['GITHUB_OUTPUT']).open('a') as stream:
                stream.write('retention_ready=true\n')
    return 0 if result['passed'] else 1


def test_once(request):
    require(os.uname().sysname == 'Darwin' and os.uname().machine == 'arm64', 'macOS ARM required')
    with Path('/System/Library/CoreServices/SystemVersion.plist').open('rb') as stream:
        system = plistlib.load(stream)
    require(system['ProductVersion'].startswith('26.') and Path(XCODE).is_dir(), 'Required macOS26/Xcode unavailable')
    workspace, temporary = Path(os.environ['GITHUB_WORKSPACE']).resolve(), Path(os.environ['RUNNER_TEMP']).resolve()
    source, run = workspace / 'app17-source', Path(os.environ['APP17_RUN'])
    require(CONTROL == workspace / 'control' and source.is_dir() and not source.is_symlink(), 'Wrong checkout paths')
    require(run == temporary / ('app17-ios-native-' + os.environ['GITHUB_RUN_ID'] + '-1')
            and run.resolve() == run and not run.exists() and not any(c.isspace() for c in str(run)), 'Unsafe/nonfresh run root')
    runner_home = Path(os.environ['HOME']).resolve()
    require(runner_home == Path(pwd.getpwuid(os.getuid()).pw_dir).resolve() and runner_home.is_dir()
            and not runner_home.is_relative_to(run) and runner_home != source, 'Invalid real runner HOME')
    java_home = os.environ.get('JAVA_HOME_21_arm64', '')
    require(java_home and Path(java_home).is_absolute() and (Path(java_home) / 'bin/java').is_file()
            and (Path(java_home) / 'release').is_file(), 'Missing installed hosted JDK21 ARM64; no install/fallback')
    require(shutil.disk_usage(temporary).free >= 8 * 1024**3, 'Existing 8 GiB free-disk floor required')
    spec = importlib.util.spec_from_file_location('app17_existing_darwin_owner', CONTROL / 'ci/app8-apple.py')
    owner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(owner)  # Definitions only; never App8 main/PF/Simulator. No scratch if import fails.
    run.mkdir(mode=0o700)
    for name in (*SCRATCH, 'reports'):
        (run / name).mkdir()
    for number in (signal.SIGINT, signal.SIGTERM):
        signal.signal(number, owner.interrupted)
    return run_recipe(owner, source, run, request, system, runner_home)


def main():
    os.umask(0o077)
    require(sys.argv[1:] in (['request'], ['test']), 'Expected one fixed phase')
    request = checked_request()
    if sys.argv[1] == 'request':
        with Path(os.environ['GITHUB_OUTPUT']).open('a') as stream:
            stream.write('source_sha=' + request['sourceSha'] + '\n')
        return 0
    return test_once(request)


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        print('APP17 PUBLIC NATIVE3 INCOMPLETE: ' + str(error), file=sys.stderr)
        raise SystemExit(1)
