"""Reduced deletion Native8 evidence; the existing bounded retention/cleanup controls, no framework proof."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import sys
import time

MODULES = ('core', 'data/remote')
SOURCE_SETS = ('commonMain', 'nativeMain', 'appleMain', 'iosMain', 'iosSimulatorArm64Main', 'nonAndroidMain')
SCRATCH = ('gradle-home', 'konan', 'home', 'tmp', 'project-cache', 'kotlin', 'work')
OUTPUTS = {'app': ('build', '.gradle', '.kotlin', 'core/build', 'data/remote/build')}
SWIFTPM_MODULES = ('composeApp', 'core', 'data', 'domain', 'platform', 'presentation', 'ui',
                  'data/download', 'data/local', 'data/remote', 'sources/config', 'sources/contracts',
                  'sources/engine', 'sources/legacy')
SWIFTPM_METADATA = 'build/kotlin/swiftPMDependenciesMetadataForLockFiles'
SWIFTPM_SKIPPED = (':composeApp:generateUmbrellaPackageIdentifierBasedResolutionForDefault',
    ':data:remote:generateSyntheticLinkageSwiftPMImportProjectForCinteropsAndLdDump',
    ':composeApp:fetchUmbrellaPackageIdentifierForDefault', ':data:remote:fetchSyntheticImportProjectPackages',
    ':data:remote:syncPersistedPackageResolvedToSynthetic')
JSON_CAPS = {'request.json': 65536, 'result.json': 131072, 'identity.json': 131072,
             'source.json': 1048576, 'tools.json': 131072, 'commands.json': 1048576,
             'native-source.json': 1048576, 'native-app-tasks.json': 1048576,
             'native-proof.json': 1048576, 'simulator.json': 131072, 'native-xml-diagnostics.json': 131072}
NATIVE_LOGS = ('native-tests', 'gradle-stop-native', 'native-data-remote-arch', 'native-data-remote-platform')
NATIVE_XML = (
    'native-xml/data/remote/TEST-iosSimulatorArm64Test.me.manga.kira.data.remote.complaint.IosComplaintDeletionEngineTest.xml',
    'native-xml/data/remote/TEST-iosSimulatorArm64Test.me.manga.kira.data.remote.complaint.IosComplaintDeletionReceiveGuardTest.xml',
)
LOGS = NATIVE_LOGS + ('gradle-stop-final', 'gradle-stop-cleanup')
PUBLIC_CAPS = dict(JSON_CAPS, **{name + '.log': 1048576 for name in LOGS})
PUBLIC_CAPS.update({'native-tests.log': 4194304, **{name: 1048576 for name in NATIVE_XML}})
PUBLIC_CAPS.update({'native-xml-unexpected/data/remote/' + str(index).zfill(2) + '.xml': 1048576
                   for index in range(1, 5)})
NATIVE_REQUIRED = set(JSON_CAPS) | set(NATIVE_XML) | {name + '.log' for name in NATIVE_LOGS} | {'gradle-stop-final.log'}


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
    require(role == 'app' and len(expected) == 26 and all(not Path(n).is_absolute() and
        '..' not in Path(n).parts and (root / n).resolve().is_relative_to(root) for n in expected), 'Unsafe input pin paths/count')
    pinned = {name: sha(root / name) for name in expected}
    require(pinned == expected, 'Pinned source/build input changed: ' + role)
    mains = {}
    for module in MODULES:
        files = {p for name in SOURCE_SETS for p in (root / module / 'src' / name / 'kotlin').rglob('*.kt')}
        require(0 < len(files) <= 2048 and all(p.resolve().is_relative_to(root) for p in files), 'Invalid main inventory')
        mains[':' + module.replace('/', ':')] = {str(p.relative_to(root)): sha(p) for p in sorted(files)}
    result = {'root': str(root), 'sha': pins[role]['sha'], 'tree': pins[role]['tree'], 'inputs': pinned, 'mains': mains}
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


def prepare_swiftpm(root):
    names = [module + '/' + SWIFTPM_METADATA for module in SWIFTPM_MODULES if module not in MODULES]
    for name in names:
        folder = (root / name).parent.parent
        require(folder.resolve() == folder and not folder.exists() and not folder.is_symlink(),
                'Preexisting metadata output roots are not owned')
    locks = root / '.swiftpm-locks'
    require(not locks.exists() and not locks.is_symlink(), 'Persisted SwiftPM material is not an owned output')
    return {'metadataFiles': names, 'moduleBuildRootsInitiallyAbsent': True, 'persistedLocksInitiallyAbsent': True}


def remove_swiftpm_metadata(c):
    # Only exact newly owned serializer files; rmdir removes empty ancestors, never arbitrary module builds.
    # No cleanup authority over Package.resolved, manifests, checkouts, config or .swiftpm-locks.
    root, end = c['roots']['app'], c['end']
    names = [module + '/' + SWIFTPM_METADATA for module in SWIFTPM_MODULES if module not in MODULES]
    require(c['identity']['swiftPM'] == dict(metadataFiles=names, moduleBuildRootsInitiallyAbsent=True,
            persistedLocksInitiallyAbsent=True), 'Missing initial SwiftPM output ownership')
    locks = root / '.swiftpm-locks'
    require(not locks.exists() and not locks.is_symlink(), 'Unexpected persisted SwiftPM material; preserve it')
    report_path = c['reports'] / 'native-app-tasks.json'
    report = read(report_path) if report_path.exists() else {}
    removed, directories = [], []
    for name in names:
        path = root / name
        require(time.monotonic() < end and path.resolve() == path and not path.is_symlink(),
                'Late/aliased SwiftPM metadata cleanup')
        if path.exists():
            task = ':' + name.removesuffix('/' + SWIFTPM_METADATA).replace('/', ':') + ':serializeSwiftPMDependenciesMetadataForLockFiles'
            row = report.get('observed', {}).get(task, {})
            require(report.get('role') == 'app' and report.get('source') == c['request']['app']['sha']
                    and {'path': task, 'type': 'org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.'
                    'SerializeSwiftPMDependenciesMetadataForLockFiles_Decorated'} in report.get('graph', [])
                    and row.get('kind') == 'swiftpm-empty-prerequisite' and row.get('dependenciesEmpty') is True
                    and row.get('actionAuthorized') is True and row.get('outputPath') == str(path),
                    'Unproven SwiftPM metadata writer; preserve output')
            info = path.lstat()
            require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_uid == os.geteuid()
                    and info.st_size <= 65536, 'Unexpected SwiftPM metadata file custody/cap')
            path.unlink()
        for folder in (path.parent, path.parent.parent):
            require(time.monotonic() < end and folder.resolve() == folder and not folder.is_symlink(),
                    'Late/aliased SwiftPM metadata ancestor')
            if folder.exists():
                folder.rmdir()  # Fails on any nonempty directory; preserve all unlisted material.
                directories.append(str(folder.relative_to(root)))
        require(not path.exists() and time.monotonic() < end, 'SwiftPM metadata cleanup incomplete')
        removed.append(name)
    return {'files': removed, 'emptyDirectories': directories, 'persistedLocksAbsent': True}


def evidence_barrier(result):
    # A failed build's settled command evidence must survive unrelated Simulator
    # cleanup failure. This grants retention only: PASS still requires final
    # absence, normal completion, owned Simulator removal and scratch cleanup.
    return (result.get('afterFinalStop', {}).get('absent') is True or
            (result.get('nativeTestsSucceeded') is not True and
             result.get('afterWorkStop', {}).get('absent') is True))


def preserve_log(task, reports, label, result, end):
    def identity(info):
        return (info.st_dev, info.st_ino, info.st_mode, info.st_nlink, info.st_uid,
                info.st_size, info.st_mtime_ns, info.st_ctime_ns)

    target = reports / (label + '.log')
    path, receipt = task['log'], task['receipt']
    require(time.monotonic() < end and evidence_barrier(result),
            'No bounded log capture before a proven evidence barrier')
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
    phase_success = {'native-tests': 'nativeTestsSucceeded'}
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
        key = 'nativeFailureLogPrefix'
        result[key] = {
            'truncated': True, 'rawBytes': raw_size, 'retainedBytes': len(data),
            'retainedSha256': hashlib.sha256(data).hexdigest(), 'sourceStableAtCapture': True,
            'rawUnmodified': True, 'fullLogRetained': False, 'successCredit': False}


def retain(commands, reports, result):
    require(evidence_barrier(result), 'No retention before a proven evidence barrier')
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
    if result.get('nativeTestsSucceeded') and result.get('nativeProofPreserved'):
        require(NATIVE_REQUIRED <= set(present), 'Missing required bounded Native evidence')
    return {'files': sorted(present), 'maximumFiles': len(PUBLIC_CAPS), 'maximumBytes': sum(PUBLIC_CAPS.values())}

def bytes_of(path, cap):
    require(path.is_file() and not path.is_symlink() and path.resolve() == path and path.stat().st_size <= cap,
            'Missing/aliased/oversized evidence file')
    with path.open('rb') as stream:
        data = stream.read(cap + 1)
    require(len(data) <= cap, 'Evidence file grew beyond cap')
    return data


def retained(c, name, data):
    require(name in c['e'].PUBLIC_CAPS and len(data) <= c['e'].PUBLIC_CAPS[name], 'Unlisted/oversized bounded evidence')
    fd = os.open(c['reports'] / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(data)


def tools(c):
    commands, env = c['commands'], c['env']
    java = commands.call([env['JAVA_HOME'] + '/bin/java', '-version'], 'java-version', end=c['workEnd'])
    xcode = commands.call(['/usr/bin/xcodebuild', '-version'], 'xcode-version', end=c['workEnd'])
    sdk = commands.call(['/usr/bin/xcrun', '--sdk', 'iphonesimulator', '--show-sdk-version'], 'sdk-version', end=c['workEnd'])
    require(xcode.strip() == 'Xcode 26.4.1\nBuild version 17E202' and sdk.strip() == '26.4', 'Wrong installed Xcode/SDK')
    c['e'].save(c['reports'] / 'tools.json', {'python': sys.version, 'java': java, 'xcode': xcode, 'sdk': sdk,
        'developerDir': env['DEVELOPER_DIR'], 'javaReleaseSha256': c['e'].sha(Path(env['JAVA_HOME']) / 'release')})

