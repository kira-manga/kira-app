"""Reduced direct-framework evidence; fixed source/Native boundaries, no replay or tool launch."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import time

TASK = ':composeApp:linkDebugFrameworkIosSimulatorArm64'
MODULES = ('core', 'domain', 'presentation', 'ui', 'platform', 'data/local', 'data/remote',
           'sources/legacy', 'sources/contracts', 'data/download', 'data', 'sources/engine',
           'sources/config', 'composeApp')
ENGINE_MODULES = ('source-contract', 'source-engine')
SOURCE_SETS = ('commonMain', 'nativeMain', 'appleMain', 'iosMain', 'iosSimulatorArm64Main', 'nonAndroidMain')
SCRATCH = ('gradle-home', 'konan', 'home', 'tmp', 'project-cache', 'kotlin', 'work')
OUTPUTS = {
    'app': ('build', '.gradle', '.kotlin') + tuple(p + '/build' for p in (*MODULES, 'app', 'desktopApp')),
    'engine': ('build', '.gradle', '.kotlin', 'source-contract/build', 'source-engine/build', 'source-testkit/build'),
}
JSON_CAPS = {'request.json': 65536, 'result.json': 131072, 'identity.json': 131072,
             'source.json': 1048576, 'tools.json': 131072, 'app-tasks.json': 1048576,
             'engine-tasks.json': 524288, 'proof.json': 131072, 'commands.json': 1048576,
             'framework-proof.json': 262144, 'framework-metadata.json': 65536}
FRAMEWORK_LOGS = ('host-build', 'gradle-stop-immediate', 'framework-arch')
PUBLIC_CAPS = dict(JSON_CAPS, **{name + '.log': 1048576 for name in FRAMEWORK_LOGS})
# Retain the proven reserved8MiB label; host-build is now only the direct framework Gradle command.
PUBLIC_CAPS.update({'host-build.log': 8388608, 'ComposeApp.h': 4194304, 'ComposeApp.modulemap': 65536})
FRAMEWORK_REQUIRED = frozenset(PUBLIC_CAPS)
NATIVE_JSON_CAPS = {'native-source.json': 1048576, 'native-app-tasks.json': 1048576,
                    'native-engine-tasks.json': 1048576, 'native-proof.json': 1048576, 'simulator.json': 131072,
                    'native-xml-diagnostics.json': 131072}
NATIVE_LOGS = ('native-tests', 'gradle-stop-native', 'native-composeApp-arch', 'native-composeApp-platform',
               'native-data-arch', 'native-data-platform')
NATIVE_XML = ('native-xml/composeApp/TEST-iosSimulatorArm64Test.me.manga.kira.reader.ReaderShareCoordinatorTest.xml',
              'native-xml/data/TEST-iosSimulatorArm64Test.me.manga.kira.data.repository.IosRoomCancellationIntegrityTest.xml')
JSON_CAPS.update(NATIVE_JSON_CAPS)
PUBLIC_CAPS.update(NATIVE_JSON_CAPS)
PUBLIC_CAPS.update({name + '.log': 1048576 for name in (*NATIVE_LOGS, 'gradle-stop-final', 'gradle-stop-cleanup')})
PUBLIC_CAPS.update({'native-tests.log': 4194304, **{name: 1048576 for name in NATIVE_XML}})
PUBLIC_CAPS.update({'native-xml-unexpected/' + module + '/' + str(index).zfill(2) + '.xml': 1048576
                   for module in ('composeApp', 'data') for index in range(1, 5)})
LOGS = FRAMEWORK_LOGS + NATIVE_LOGS + ('gradle-stop-final', 'gradle-stop-cleanup')
NATIVE_REQUIRED = set(NATIVE_JSON_CAPS) | set(NATIVE_XML) | {name + '.log' for name in NATIVE_LOGS} | {'gradle-stop-final.log'}


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def environment(run, xcode, control):
    java = os.environ['JAVA_HOME_21_arm64']
    require(Path(java).is_absolute() and (Path(java) / 'release').is_file(), 'Installed JDK21 ARM64 required')
    env = dict(PATH=java + '/bin:/usr/bin:/bin:/usr/sbin:/sbin', JAVA_HOME=java, HOME=str(run / 'home'),
        GRADLE_USER_HOME=str(run / 'gradle-home'), KONAN_DATA_DIR=str(run / 'konan'), TMPDIR=str(run / 'tmp') + '/',
        CFFIXED_USER_HOME=str(run / 'home'), XDG_CACHE_HOME=str(run / 'home/.cache'),
        DEVELOPER_DIR=xcode, LANG='en_US.UTF-8', LC_ALL='en_US.UTF-8', CI='true',
        GIT_OPTIONAL_LOCKS='0', GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL='/dev/null',
        KIRA_SOURCE_CONFIG_BASE_URL='', KIRA_SOURCE_CONFIG_PINNED_KEYS='', KIRA_APP_VERSION='1.0.5',
        KIRA_APP5_RUN=str(run), KIRA_APP5_CONTROL=str(control),
        JAVA_OPTS=f'-Xmx128m -Dapp5.apple.owner={run.name} -Duser.home={run / "home"} -Djava.io.tmpdir={run / "tmp"}')
    for key in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        if os.environ.get(key):
            env[key] = os.environ[key]
    return env


def sha(path):
    require(path.is_file() and not path.is_symlink() and path.resolve() == path, 'Nonregular/aliased file')
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            value.update(block)
    return value.hexdigest()


def read(path, limit=1048576):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= limit, 'Invalid bounded JSON')
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'Duplicate JSON key')
            result[key] = value
        return result
    def constant(_value):
        raise RuntimeError('Nonfinite JSON')
    return json.loads(path.read_text(), object_pairs_hook=unique, parse_constant=constant)


def save(path, value):
    data = (json.dumps(value, sort_keys=True, indent=2) + '\n').encode()
    require(path.name in JSON_CAPS and len(data) <= JSON_CAPS[path.name] and not path.is_symlink(), 'Report cap/type')
    pending = path.with_name(path.name + '.pending')
    with pending.open('xb') as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    pending.replace(path)


def inputs(root, role, pins):
    expected = pins[role]['inputs']
    require(root.resolve() == root and root.is_dir(), 'Aliased/missing source checkout')
    require(len(expected) == (67 if role == 'app' else 51) and all(not Path(n).is_absolute() and
        '..' not in Path(n).parts and (root / n).resolve().is_relative_to(root) for n in expected), 'Unsafe input pin paths/count')
    pinned = {name: sha(root / name) for name in expected}
    require(pinned == expected, 'Pinned source/build input changed: ' + role)
    mains = {}
    for module in MODULES if role == 'app' else ENGINE_MODULES:
        files = {p for name in SOURCE_SETS for p in (root / module / 'src' / name / 'kotlin').rglob('*.kt')}
        require(0 < len(files) <= 2048 and all(p.resolve().is_relative_to(root) for p in files), 'Invalid main inventory')
        mains[':' + module.replace('/', ':')] = {str(p.relative_to(root)): sha(p) for p in sorted(files)}
    result = {'root': str(root), 'sha': pins[role]['sha'], 'tree': pins[role]['tree'], 'inputs': pinned, 'mains': mains}
    if role == 'app':
        expected_host = pins['host']['inputs']
        files = {str(p.relative_to(root)): sha(p) for p in (root / pins['host']['root']).rglob('*')
                 if p.is_file() and p.name != 'GoogleService-Info.plist'}
        require(files == expected_host and len(files) == 48 and
                len([n for n in files if n.endswith('.swift')]) == 21, 'Shipping host input set changed')
        result['host'] = files
    return result


def fingerprint(path, end, maximum=268435456):
    require(time.monotonic() < end and path.exists() and path.resolve() == path, 'Missing/aliased/late output')
    if path.is_file():
        require(path.stat().st_size <= maximum, 'Oversized output')
        return {'file': str(path), 'kind': 'file', 'bytes': path.stat().st_size, 'sha256': sha(path)}
    rows, size = [], 0
    for item in sorted(path.rglob('*')):
        require(time.monotonic() < end and not item.is_symlink(), 'Late/symlink output tree')
        if item.is_file():
            size += item.stat().st_size
            require(len(rows) < 4096 and size <= maximum, 'Oversized unpacked output')
            rows.append((str(item.relative_to(path)), sha(item)))
    require(rows, 'Empty output tree')
    data = ''.join(value + '  ' + name + '\n' for name, value in sorted(rows)).encode()
    return {'file': str(path), 'kind': 'directory', 'bytes': size, 'files': len(rows),
            'sha256': hashlib.sha256(data).hexdigest()}


def completed(row):
    return (row.get('executed') is True and row.get('didWork') is True and row.get('ended') is True
            and not any(row.get(k) for k in ('skipped', 'upToDate', 'noSource', 'failure')))


def main_proof(role, proof, root, end):
    modules = MODULES if role == 'app' else ENGINE_MODULES
    wanted = {':' + m.replace('/', ':') + ':compileKotlinIosSimulatorArm64' for m in modules}
    require(proof['role'] == role and proof['mainTasks'] == sorted(wanted), 'Different main closure')
    require(wanted <= set(proof['states']) and all(completed(proof['states'][p]) for p in wanted), 'Unexecuted/cached main')
    outputs = {}
    for task in sorted(wanted):
        row = proof['observed'][task]
        require(row['target'] == 'ios_simulator_arm64' and row['sourcesMatched'] is True, 'Pruned/wrong main inputs')
        output = row['output']
        require(Path(output['file']).is_relative_to(root / task.rsplit(':', 1)[0][1:].replace(':', '/') / 'build'),
                'Main output outside its producer')
        require(fingerprint(Path(output['file']), end) == output, 'Compiled output changed')
        outputs[task] = output
    return outputs


def generated_proof(proof):
    expected = {}
    for row in proof['observed'].values():
        if row.get('producer'):
            for name, digest in row.get('generated', {}).items():
                require(name not in expected or expected[name] == digest, 'Contradictory generated output')
                expected[name] = digest
    for row in proof['observed'].values():
        if row.get('sourcesMatched'):
            require(all(expected.get(name) == digest for name, digest in row['generated'].items()),
                    'Compiler consumed unattributed/changed generated Kotlin')
    room = ':data:local:kspKotlinIosSimulatorArm64'
    require(completed(proof['states'][room]), 'Room KSP did not execute')
    names = proof['observed'][room]['generated']
    require(any(Path(n).name == 'MangaDatabase_Impl.kt' for n in names)
            and any(Path(n).name == 'ChapterDownloadDao_Impl.kt' for n in names), 'Missing real Room DAO generation')
    for task in proof['required']:
        require(completed(proof['states'][task]), 'Required generator/cinterop did not complete: ' + task)


def engine_boundary(proofs, outputs):
    for role, task, modules in (
            ('engine', ':source-engine:compileKotlinIosSimulatorArm64', {'source-contract'}),
            ('app', ':sources:engine:compileKotlinIosSimulatorArm64', set(ENGINE_MODULES))):
        boundary = proofs[role]['observed'][task]['neutral']
        require({x['project'][1:] for x in boundary} == modules, 'Wrong original Engine Native suppliers')
        for row in boundary:
            producer = row['project'] + ':compileKotlinIosSimulatorArm64'
            require(row['output'] == outputs['engine'][producer], 'Engine consumer did not use its fresh Native output')
            require(row['version'] == '0.1.0-SNAPSHOT' and row['target'] == 'ios_simulator_arm64',
                    'Relabelled/non-Native Engine supplier')


def native_markers(run, proofs, end):
    expected = {'app': '2.4.0', 'engine': '2.2.21'}
    wanted = {f'kotlin-native-prebuilt-macos-aarch64-{v}' for v in expected.values()}
    roots = {p.name for p in (run / 'konan').glob('kotlin-native*')}
    require(roots == wanted, 'Missing/extra default PREBUILT Native distributions')
    result = {}
    for role, version in expected.items():
        home = run / 'konan' / f'kotlin-native-prebuilt-macos-aarch64-{version}'
        observed = proofs[role]['native']
        require(observed['version'] == version and observed['home'] == str(home), 'Wrong actual compiler Native home')
        result[role] = dict(observed, distribution='PREBUILT',
            markers={n: sha(home / n) for n in ('konan/konan.properties', 'bin/konanc')},
            stdlib=fingerprint(home / 'klib/common/stdlib', end))
    return result


def prove(run, roots, end):
    proofs = {role: read(run / 'reports' / (role + '-tasks.json'), JSON_CAPS[role + '-tasks.json']) for role in roots}
    outputs = {role: main_proof(role, proofs[role], roots[role], end) for role in roots}
    generated_proof(proofs['app'])
    engine_boundary(proofs, outputs)
    cinterop = proofs['app']['observed'][':platform:cinteropLibwebpIosSimulatorArm64']
    require(cinterop['target'] == 'ios_simulator_arm64' and cinterop['sourceInputsMatched'] is True, 'Wrong libwebp input slice')
    require(fingerprint(Path(cinterop['output']['file']), end) == cinterop['output'], 'Changed libwebp klib')
    return {'status': 'SOURCE_AND_FRAMEWORK_EVIDENCE_REVIEW_REQUIRED', 'task': TASK, 'mainCount': 16,
            'outputs': outputs, 'native': native_markers(run, proofs, end),
            'scope': 'Normal16 main producers within one direct Debug simulator framework link; zero Swift host/UI/tests in this phase'}


def remove_scoped(root, names, end):
    require(root.is_dir() and root.resolve() == root and not root.is_symlink(), 'Unsafe cleanup owner')
    removed = []
    for name in names:
        require(time.monotonic() < end, 'Owned cleanup deadline exceeded')
        path = root / name
        require(not path.is_symlink() and path.resolve().is_relative_to(root), 'Aliased cleanup root')
        if path.exists():
            require(path.is_dir(), 'Expected owned generated directory')
            shutil.rmtree(path)
        require(not path.exists() and time.monotonic() < end, 'Owned output remains/cleanup exceeded deadline')
        removed.append(name)
    return removed


def preserve_log(task, reports, label, result, end):
    def identity(info):
        return (info.st_dev, info.st_ino, info.st_mode, info.st_nlink, info.st_uid,
                info.st_size, info.st_mtime_ns, info.st_ctime_ns)

    target = reports / (label + '.log')
    path, receipt = task['log'], task['receipt']
    require(time.monotonic() < end and result.get('afterFinalStop', {}).get('absent') is True,
            'No bounded log capture before final owned absence')
    require(not receipt['ownershipLost'] and (task['process'] is None or
            (receipt['leaderReaped'] and receipt['groupQuiet'])), 'Unsettled log writer')
    require(not task['leaf'] and receipt['label'] == label and path.parent == reports and path.name == receipt['output']
            and reports.is_dir() and reports.resolve() == reports and path.resolve() == path
            and not target.exists() and not target.is_symlink(), 'Unowned log alias')
    before = path.lstat()
    require(stat.S_ISREG(before.st_mode) and before.st_nlink == 1 and before.st_uid == os.geteuid(),
            'Nonregular/linked/foreign command log')
    cap = PUBLIC_CAPS[target.name]
    raw_size = before.st_size
    phase_success = {'host-build': 'frameworkBuildSucceeded', 'native-tests': 'nativeTestsSucceeded', 'uikit-tests': 'uikitTestsSucceeded'}
    failure_prefix = label in phase_success and not result.get(phase_success[label]) and raw_size > cap
    require(raw_size <= cap or failure_prefix, 'Oversized retained command log')
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as stream:
        require(identity(os.fstat(stream.fileno())) == identity(before), 'Command log replaced before capture')
        data = stream.read(cap if failure_prefix else cap + 1)
        require(len(data) == min(raw_size, cap) and identity(os.fstat(stream.fileno())) == identity(before)
                and identity(path.lstat()) == identity(before) and time.monotonic() < end,
                'Command log changed or capture exceeded its cap')
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as output:
        output.write(data)
        output.flush()
        captured = os.fstat(output.fileno())
    require(stat.S_ISREG(captured.st_mode) and captured.st_nlink == 1 and captured.st_size == len(data)
            and identity(target.lstat()) == identity(captured) and identity(path.lstat()) == identity(before)
            and time.monotonic() < end, 'Captured log custody/deadline changed')
    if failure_prefix:
        key = {'host-build': 'hostFailureLogPrefix', 'native-tests': 'nativeFailureLogPrefix', 'uikit-tests': 'uikitFailureLogPrefix'}[label]
        result[key] = {
            'truncated': True, 'rawBytes': raw_size, 'retainedBytes': len(data),
            'retainedSha256': hashlib.sha256(data).hexdigest(), 'sourceStableAtCapture': True,
            'rawUnmodified': True, 'fullLogRetained': False, 'successCredit': False}


def retain(commands, reports, result):
    require(result.get('afterFinalStop', {}).get('absent') is True, 'No retention before final owned absence')
    for label in LOGS:
        matches = [t for t in commands.tasks if t['receipt']['label'] == label] if commands else []
        require(len(matches) <= 1, 'Repeated command label')
        if not matches:
            continue
        preserve_log(matches[0], reports, label, result, commands.end)
    present = []
    for name, cap in PUBLIC_CAPS.items():
        path = reports / name
        require(not path.is_symlink(), 'Symlink public report')
        if path.exists():
            require(path.is_file() and path.resolve() == path and path.stat().st_size <= cap, 'Report cap/type')
            present.append(name)
    if result.get('frameworkBuildSucceeded') and result.get('frameworkProofPreserved'):
        require(FRAMEWORK_REQUIRED <= set(present), 'Missing required bounded framework evidence')
    if result.get('nativeTestsSucceeded') and result.get('nativeProofPreserved'):
        require(NATIVE_REQUIRED <= set(present), 'Missing required bounded Native evidence')
    return {'files': sorted(present), 'maximumFiles': len(PUBLIC_CAPS), 'maximumBytes': sum(PUBLIC_CAPS.values())}
