"""App5 compile-only evidence; fixed source/Native boundaries, no replay or tool launch."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import time

TASK = ':composeApp:compileKotlinIosSimulatorArm64'
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
             'engine-tasks.json': 524288, 'proof.json': 131072, 'commands.json': 1048576}
LOGS = ('compile', 'gradle-stop-immediate', 'gradle-stop-final')
PUBLIC_CAPS = dict(JSON_CAPS, **{name + '.log': 1048576 for name in LOGS})


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def environment(run, xcode, control):
    java = os.environ['JAVA_HOME_21_arm64']
    require(Path(java).is_absolute() and (Path(java) / 'release').is_file(), 'Installed JDK21 ARM64 required')
    env = dict(PATH=java + '/bin:/usr/bin:/bin:/usr/sbin:/sbin', JAVA_HOME=java, HOME=str(run / 'home'),
        GRADLE_USER_HOME=str(run / 'gradle-home'), KONAN_DATA_DIR=str(run / 'konan'), TMPDIR=str(run / 'tmp') + '/',
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
    require(len(expected) == (62 if role == 'app' else 51) and all(not Path(n).is_absolute() and
        '..' not in Path(n).parts and (root / n).resolve().is_relative_to(root) for n in expected), 'Unsafe input pin paths/count')
    pinned = {name: sha(root / name) for name in expected}
    require(pinned == expected, 'Pinned source/build input changed: ' + role)
    mains = {}
    for module in MODULES if role == 'app' else ENGINE_MODULES:
        files = {p for name in SOURCE_SETS for p in (root / module / 'src' / name / 'kotlin').rglob('*.kt')}
        require(0 < len(files) <= 2048 and all(p.resolve().is_relative_to(root) for p in files), 'Invalid main inventory')
        mains[':' + module.replace('/', ':')] = {str(p.relative_to(root)): sha(p) for p in sorted(files)}
    return {'root': str(root), 'sha': pins[role]['sha'], 'tree': pins[role]['tree'], 'inputs': pinned, 'mains': mains}


def fingerprint(path, end):
    require(time.monotonic() < end and path.exists() and path.resolve() == path, 'Missing/aliased/late output')
    if path.is_file():
        require(path.stat().st_size <= 268435456, 'Oversized output')
        return {'file': str(path), 'kind': 'file', 'bytes': path.stat().st_size, 'sha256': sha(path)}
    rows, size = [], 0
    for item in sorted(path.rglob('*')):
        require(time.monotonic() < end and not item.is_symlink(), 'Late/symlink output tree')
        if item.is_file():
            size += item.stat().st_size
            require(len(rows) < 4096 and size <= 268435456, 'Oversized unpacked output')
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
    return {'status': 'SOURCE_COMPILE_EVIDENCE_REVIEW_REQUIRED', 'task': TASK, 'mainCount': 16,
            'outputs': outputs, 'native': native_markers(run, proofs, end),
            'scope': 'One simulator compile; ordinary metadata/cinterop only, no tests/framework/host/runtime'}


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


def retain(commands, reports, result):
    require(result.get('afterFinalStop', {}).get('absent') is True, 'No retention before final owned absence')
    for label in LOGS:
        matches = [t for t in commands.tasks if t['receipt']['label'] == label] if commands else []
        require(len(matches) <= 1, 'Repeated command label')
        if not matches:
            continue
        task, target = matches[0], reports / (label + '.log')
        path = task['log']
        require(not task['leaf'] and path.parent == reports and path.name == task['receipt']['output']
                and path.is_file() and path.resolve() == path and not target.exists(), 'Unowned log alias')
        require(path.stat().st_size <= 1048576, 'Oversized retained command log')
        with path.open('rb') as stream, target.open('xb') as output:
            data = stream.read(1048577)
            require(len(data) <= 1048576, 'Log grew after absence')
            output.write(data)
    present = []
    for name, cap in PUBLIC_CAPS.items():
        path = reports / name
        require(not path.is_symlink(), 'Symlink public report')
        if path.exists():
            require(path.is_file() and path.resolve() == path and path.stat().st_size <= cap, 'Report cap/type')
            present.append(name)
    if result.get('compileSucceeded') and result.get('proofPreserved'):
        require(set(present) == set(PUBLIC_CAPS), 'Missing required bounded compile evidence')
    return {'files': sorted(present), 'maximumFiles': len(PUBLIC_CAPS), 'maximumBytes': sum(PUBLIC_CAPS.values())}
