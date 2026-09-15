"""App70 bounded source/output custody; reused Apple08 primitives, no tool launcher."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import time

MODULES = ('core', 'platform')
SOURCE_SETS = ('commonMain', 'nativeMain', 'appleMain', 'iosMain', 'iosSimulatorArm64Main', 'nonAndroidMain')
SCRATCH = ('gradle-home', 'konan', 'home', 'tmp', 'project-cache', 'kotlin', 'work')
OUTPUTS = {'app': ('build', '.gradle', '.kotlin', 'core/build', 'platform/build')}
JSON_CAPS = {'request.json': 65536, 'result.json': 131072, 'identity.json': 131072,
    'source.json': 131072, 'tools.json': 131072, 'commands.json': 1048576,
    'native-source.json': 131072, 'native-app-tasks.json': 1048576,
    'native-proof.json': 131072, 'simulator.json': 131072, 'apple-writer-capture.json': 131072}
LOGS = ('native-tests', 'gradle-stop-native', 'gradle-stop-final', 'gradle-stop-cleanup',
    'native-platform-arch', 'native-platform-platform', 'apple-log-help')
RAW_OUTPUTS = {'apple-writer.stdout.log': ('apple-writer-canary', False),
    'apple-writer.stderr.log': ('apple-writer-canary', True),
    'apple-writer.unified.json': ('apple-writer-unified', False),
    'apple-writer.unified.stderr.log': ('apple-writer-unified', True)}
PUBLIC_CAPS = dict(JSON_CAPS, **{name + '.log': 1048576 for name in LOGS},
    **{name: 1048576 for name in RAW_OUTPUTS})
PUBLIC_CAPS['native-tests.log'] = 4194304


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
    require(role == 'app' and root.resolve() == root and root.is_dir(), 'Only exact ordinary App checkout')
    expected = pins['app']['inputs']
    require(0 < len(expected) <= 64 and all(not Path(n).is_absolute() and '..' not in Path(n).parts
            and (root / n).resolve().is_relative_to(root) for n in expected), 'Unsafe scoped input pins')
    pinned = {name: sha(root / name) for name in expected}
    require(pinned == expected, 'Pinned ordinary source/build input changed')
    mains = {}
    for module in MODULES:
        files = {p for name in SOURCE_SETS for p in (root / module / 'src' / name / 'kotlin').rglob('*.kt')}
        require(0 < len(files) <= 512 and all(p.resolve().is_relative_to(root) for p in files), 'Invalid ordinary main inputs')
        mains[':' + module] = {str(p.relative_to(root)): sha(p) for p in sorted(files)}
    return {'root': str(root), 'sha': pins['app']['sha'], 'tree': pins['app']['tree'], 'inputs': pinned, 'mains': mains}


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
    phase_success = {'native-tests': 'nativeLinkSucceeded'}
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
        result['nativeFailureLogPrefix'] = {
            'truncated': True, 'rawBytes': raw_size, 'retainedBytes': len(data),
            'retainedSha256': hashlib.sha256(data).hexdigest(), 'sourceStableAtCapture': True,
            'rawUnmodified': True, 'fullLogRetained': False, 'successCredit': False}

def raw_bytes(task, stderr=False):
    """Read an already-settled descriptor file, binding creation and both read-side identities."""
    receipt = task['receipt']
    require(receipt.get('splitOutput') is True and not task['leaf'] and not receipt['ownershipLost']
            and receipt['leaderReaped'] and receipt['groupQuiet'], 'Unsettled/unbound raw descriptor')
    path = task['stderrLog'] if stderr else task['log']
    bound = receipt['stderrFileIdentity' if stderr else 'outputFileIdentity']
    def identity(info):
        return {'device': info.st_dev, 'inode': info.st_ino, 'uid': info.st_uid, 'mode': info.st_mode,
            'links': info.st_nlink, 'bytes': info.st_size, 'mtimeNs': info.st_mtime_ns, 'ctimeNs': info.st_ctime_ns}
    before = path.lstat()
    require(path.resolve() == path and stat.S_ISREG(before.st_mode) and before.st_nlink == 1
            and before.st_uid == os.geteuid() and before.st_size <= 1048576
            and (before.st_dev, before.st_ino, before.st_uid) ==
                (bound['device'], bound['inode'], bound['uid']), 'Raw descriptor custody/cap mismatch')
    with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), 'rb') as stream:
        require(identity(os.fstat(stream.fileno())) == identity(before), 'Raw descriptor replaced before read')
        raw = stream.read(1048577)
        require(len(raw) == before.st_size and identity(os.fstat(stream.fileno())) == identity(before)
                and identity(path.lstat()) == identity(before), 'Raw descriptor changed during read')
    return raw, dict(file=path.name, sha256=hashlib.sha256(raw).hexdigest(), identity=identity(before),
                     complete=True, truncated=False, commandLabel=receipt['label'], stderr=stderr)


def retain(commands, reports, result):
    require(result.get('afterFinalStop', {}).get('absent') is True, 'No retention before final owned absence')
    errors, raw_records = [], {}
    for label in LOGS:
        matches = [t for t in commands.tasks if t['receipt']['label'] == label]
        require(len(matches) <= 1, 'Repeated command label')
        if matches:
            try:
                preserve_log(matches[0], reports, label, result, commands.end)
            except Exception as error:
                errors.append({'stage': label, 'type': type(error).__name__})
    for name, (label, stderr) in RAW_OUTPUTS.items():
        matches = [t for t in commands.tasks if t['receipt']['label'] == label]
        require(len(matches) <= 1, 'Repeated raw producer')
        if not matches:
            continue
        try:
            require(time.monotonic() < commands.end, 'Raw retention deadline exceeded')
            raw, record = raw_bytes(matches[0], stderr)
            expected = result.get('privacyRaw', {}).get(name)
            require(expected is None or record == expected, 'Raw evidence changed after privacy verdict')
            if expected is None:
                errors.append({'stage': name, 'type': 'RawNotAnalyzed'})
            target = reports / name
            with os.fdopen(os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600), 'wb') as stream:
                stream.write(raw)
                stream.flush()
                os.fsync(stream.fileno())
                info = os.fstat(stream.fileno())
            require(target.lstat().st_ino == info.st_ino and target.stat().st_size == len(raw)
                    and sha(target) == record['sha256'], 'Retained raw bytes/custody changed')
            raw_records[name] = dict(record, analyzed=expected is not None, retainedFile=name, retainedDevice=info.st_dev,
                retainedInode=info.st_ino, retainedSha256=record['sha256'])
        except Exception as error:
            # A missing/oversized raw file never becomes a retained prefix or a privacy PASS.
            errors.append({'stage': name, 'type': type(error).__name__})
    present = []
    for name, cap in PUBLIC_CAPS.items():
        path = reports / name
        require(not path.is_symlink(), 'Symlink retained report')
        if path.exists():
            require(path.is_file() and path.resolve() == path and path.stat().st_size <= cap, 'Retained report cap/type')
            present.append(name)
    complete = not errors and set(RAW_OUTPUTS) <= set(raw_records)
    return {'files': sorted(present), 'rawLineage': raw_records, 'errors': errors, 'privacyRawComplete': complete,
        'maximumFiles': len(PUBLIC_CAPS), 'maximumBytes': sum(PUBLIC_CAPS.values())}
