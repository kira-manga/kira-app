"""Single App70 default-writer stimulus over the ordinary platform Native test binary.

Reuses Apple08 simulator custody and Commands. No app/Swift/UI/Engine work, writer
replacement, private-logging setting, persistent collector or second process owner.
Finite post-hoc log persistence/readability is a required observation, never assumed.
"""
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import stat
import time

RUNTIME = 'com.apple.CoreSimulator.SimRuntime.iOS-26-4'
DEVICE_TYPE = 'com.apple.CoreSimulator.SimDeviceType.iPhone-17'
LINK_TASK = ':platform:linkDebugTestIosSimulatorArm64'
COMPILE_TASK = ':platform:compileTestKotlinIosSimulatorArm64'
CLASS = 'me.manga.kira.platform.download.BgDownloadLogAppleWriterCanaryTest'
METHOD = 'defaultAppleWriterEmitsCanaryForExternalCapture'
SELECTOR = CLASS + '.' + METHOD
CANARY_SOURCE = 'platform/src/iosTest/kotlin/me/manga/kira/platform/download/BgDownloadLogAppleWriterCanaryTest.kt'
CANARY_SHA = 'dc9b763ee3f9554bea33d6f4bccedae8c8ba32306b8f50bd4ce37b4f597d3405'
TEST_SETS = ('commonTest', 'nativeTest', 'appleTest', 'iosTest', 'iosSimulatorArm64Test', 'nonAndroidTest')
PHASES = (('WARN', 'BEGIN'), ('WARN', 'END'), ('INFO', 'BEGIN'), ('INFO', 'END'))
LEAK_MARKERS = ('APP70_FAKE_COOKIE_BEARER_TOKEN', 'App70PrivateCanaryThrowable')


def require(ok, message):
    if not ok:
        raise RuntimeError(message)

def runner_home(run, roots):
    home = Path(os.environ['HOME']).resolve()
    require(home == Path(pwd.getpwuid(os.getuid()).pw_dir).resolve() and home.is_dir()
            and not home.is_relative_to(run) and home not in roots.values(), 'Invalid real runner HOME')
    return home

def simctl(c, args, label, seconds=15, cleaning=False, end=None):
    # Gradle/JVM stays scratch-isolated; only CoreSimulator sees the real account HOME.
    return c['commands'].call(['/usr/bin/xcrun', 'simctl', *args], label, seconds=seconds,
        end=end if end is not None else (c['end'] if cleaning else c['workEnd']),
        extra={'HOME': str(c['runnerHome']), 'CFFIXED_USER_HOME': str(c['runnerHome'])}, cleaning=cleaning)

def devices(c, cleaning=False):
    value = json.loads(simctl(c, ['list', 'devices', '--json'], 'devices', seconds=5, cleaning=cleaning))
    return [dict(row, runtime=runtime) for runtime, rows in value['devices'].items() for row in rows]

def save_simulator(c):
    c['e'].save(c['reports'] / 'simulator.json', c['simulator'])

def simulator_identity(c, row=None):
    state, home = c['simulator'], c['runnerHome']
    require(state.get('creating') is False and state['runnerHome'] == str(home)
            and state['runtime'] == RUNTIME and state['deviceType'] == DEVICE_TYPE
            and re.fullmatch(r'[0-9A-F]{8}(?:-[0-9A-F]{4}){3}-[0-9A-F]{12}', state['udid']),
            'Invalid created simulator binding')
    root = home / 'Library/Developer/CoreSimulator/Devices' / state['udid']
    data, info = root / 'data', root.lstat()
    require(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid() and root.resolve() == root
            and data.is_dir() and data.resolve() == data, 'Foreign/missing/noncanonical simulator root')
    identity = {'udid': state['udid'], 'name': state['name'], 'runtime': RUNTIME, 'deviceType': DEVICE_TYPE,
                'runnerHome': str(home), 'dataPath': str(data), 'root': str(root),
                'rootDevice': info.st_dev, 'rootInode': info.st_ino, 'rootUid': info.st_uid}
    if row is not None:
        require((row['udid'], row['name'], row['runtime'], row['deviceTypeIdentifier'], row['dataPath'])
                == (identity['udid'], identity['name'], RUNTIME, DEVICE_TYPE, identity['dataPath']),
                'Owned simulator inventory identity/path mismatch')
    return identity

def create_simulator(c):
    state = c['simulator']
    runtimes = json.loads(simctl(c, ['list', 'runtimes', '--json'], 'runtimes'))['runtimes']
    matches = [row for row in runtimes if row['identifier'] == RUNTIME and row.get('isAvailable') is True]
    require(len(matches) == 1 and matches[0]['version'] == '26.4.1' and matches[0]['buildversion'] == '23E254a'
            and 'arm64' in matches[0]['supportedArchitectures'], 'Missing exact installed runtime; no download/fallback')
    types = json.loads(simctl(c, ['list', 'devicetypes', '--json'], 'device-types'))['devicetypes']
    require(sum(row['identifier'] == DEVICE_TYPE for row in types) == 1, 'Missing installed iPhone17 type')
    require(not any(row['name'] == state['name'] for row in devices(c)), 'Owned name already exists')
    state.update(creating=True, runtime=RUNTIME, runtimeVersion='26.4.1', runtimeBuild='23E254a', deviceType=DEVICE_TYPE)
    save_simulator(c)
    state['udid'] = simctl(c, ['create', state['name'], DEVICE_TYPE, RUNTIME], 'create-owned-simulator').strip()
    require(re.fullmatch(r'[0-9A-F]{8}(?:-[0-9A-F]{4}){3}-[0-9A-F]{12}', state['udid']), 'Invalid created UUID')
    state['creating'] = False
    save_simulator(c)
    matches = [row for row in devices(c) if row['udid'] == state['udid'] or row['name'] == state['name']]
    require(len(matches) == 1 and matches[0]['state'] == 'Shutdown', 'Created simulator is not uniquely shut down')
    state['createdOwnership'] = simulator_identity(c, matches[0])
    state['dataPath'], state['bootIntended'] = state['createdOwnership']['dataPath'], False
    save_simulator(c)
    boot_end = min(c['workEnd'], time.monotonic() + 180)
    require(not c['owner'].CANCELLED and time.monotonic() < boot_end, 'Owned simulator boot cancelled/expired')
    state['bootIntended'] = True
    save_simulator(c)  # Intent precedes any boot side effect, including a failed command join.
    simctl(c, ['boot', state['udid']], 'boot-owned-simulator', end=boot_end)
    simctl(c, ['bootstatus', state['udid'], '-b'], 'owned-bootstatus', seconds=180, end=boot_end)
    require(simulator_identity(c) == state['createdOwnership'], 'Owned simulator root changed during boot')
    require(not c['owner'].CANCELLED and time.monotonic() < boot_end, 'Owned simulator readiness cancelled/expired')
    state['bootReadinessEvidence'] = 'normal-same-uuid-bootstatus-and-same-owned-root'
    save_simulator(c)

def dispose_simulator(c):
    state = c['simulator']
    if not state.get('creating') and not state.get('udid'):
        return True
    udid, bound = state.get('udid'), state.get('createdOwnership')
    if bound is not None:
        require(simulator_identity(c) == bound, 'Owned simulator root changed before cleanup')
        require(type(state.get('bootIntended')) is bool, 'Missing owned simulator boot intent')
        if state['bootIntended']:
            simctl(c, ['shutdown', udid], 'shutdown-owned-simulator', cleaning=True)
    matches = [row for row in devices(c, cleaning=True) if row['name'] == state['name'] or row['udid'] == udid]
    require(len(matches) <= 1 and all(row['name'] == state['name'] and row['runtime'] == RUNTIME
            and row['deviceTypeIdentifier'] == DEVICE_TYPE and (not udid or row['udid'] == udid) for row in matches),
            'Ambiguous owned simulator; retain scratch')
    if bound is not None:
        require(len(matches) == 1 and matches[0]['state'] == 'Shutdown', 'Bound simulator shutdown unproven')
        require(simulator_identity(c, matches[0]) == bound, 'Owned simulator changed during cleanup')
    if matches:
        device = matches[0]
        udid = state['udid'] = device['udid']
        require(re.fullmatch(r'[0-9A-F]{8}(?:-[0-9A-F]{4}){3}-[0-9A-F]{12}', udid), 'Invalid disposal UUID')
        save_simulator(c)
        if bound is None:  # Recover only the uniquely created name/runtime/UUID, never a global reset.
            if device['state'] != 'Shutdown':
                simctl(c, ['shutdown', udid], 'shutdown-owned-simulator', cleaning=True)
            current = [row for row in devices(c, cleaning=True) if row['udid'] == udid]
            require(len(current) == 1 and current[0]['state'] == 'Shutdown', 'Owned shutdown unproven')
        simctl(c, ['delete', udid], 'delete-owned-simulator', cleaning=True)
    require(not any(row['name'] == state['name'] or row['udid'] == udid for row in devices(c, cleaning=True)),
            'Owned simulator remains')
    state['deleted'] = True
    save_simulator(c)
    return True

def source_inputs(c):
    e, root = c['e'], c['roots']['app']
    binding = e.read(Path(c['env']['KIRA_APP5_CONTROL']) / 'ci/app-seven-apple.native-tests.json', 65536)
    require(binding['schema'] == 'app70-single-default-writer-v1' and binding['app'] == c['request']['app']
            and binding['linkTask'] == LINK_TASK and binding['selector'] == SELECTOR
            and binding['source'] == CANARY_SOURCE and binding['sourceSha256'] == CANARY_SHA,
            'Different source-bound App70 stimulus')
    files = {file for name in TEST_SETS for file in (root / 'platform/src' / name / 'kotlin').rglob('*.kt')}
    require(0 < len(files) <= 128 and all(f.resolve().is_relative_to(root) for f in files), 'Ordinary platform tests required')
    inventory = {str(file.relative_to(root)): e.sha(file) for file in sorted(files)}
    require(inventory.get(CANARY_SOURCE) == CANARY_SHA, 'Default writer canary source changed')
    return {'app': binding['app'], 'root': str(root), 'selector': SELECTOR, 'source': CANARY_SOURCE,
        'sourceSha256': CANARY_SHA, 'compilations': {COMPILE_TASK: inventory}}


def task_arguments(_c):
    return [LINK_TASK]


def environment(c):
    return {'KIRA_APP7_SIMULATOR_HOME': str(c['runnerHome']), 'KIRA_APP7_DEVICE': c['simulator']['udid']}


def executable_names(c):
    path = c['reports'] / 'native-app-tasks.json'
    if not path.exists():
        return set()
    row = c['e'].read(path)['observed'].get(LINK_TASK, {})
    if not row.get('output'):
        return set()
    binary = Path(row['output']['file'])
    require(binary.is_relative_to(c['roots']['app'] / 'platform/build'), 'Unowned executable receipt')
    return {binary.name}


def linked_binary(c):
    e = c['e']
    report = e.read(c['reports'] / 'native-app-tasks.json')
    wanted = (':core:compileKotlinIosSimulatorArm64', ':platform:compileKotlinIosSimulatorArm64',
              COMPILE_TASK, LINK_TASK)
    require(report['source'] == c['request']['app']['sha'] and report['linkTasks'] == [LINK_TASK]
            and report['nativeTestTaskActions'] == 0
            and all(e.completed(report['states'].get(name, {})) for name in wanted)
            and all(report['observed'][name]['sourcesMatched'] is True for name in wanted[:-1]),
            'Fresh ordinary Native link required')
    compile_row, row = report['observed'][COMPILE_TASK], report['observed'][LINK_TASK]
    require(row['target'] == 'ios_simulator_arm64' and row['includes'] == [compile_row['output']]
            and compile_row['sourcesMatched'] is True, 'Link did not consume ordinary Native test KLIB')
    binary = Path(row['output']['file'])
    require(binary.is_relative_to(c['roots']['app'] / 'platform/build') and re.fullmatch(r'/[A-Za-z0-9_./-]+', str(binary))
            and e.fingerprint(binary, c['workEnd'], c['request']['limits']['nativeBinaryFingerprintBytes']) == row['output'],
            'Wrong/changed linked Native executable')
    arch = c['commands'].call(['/usr/bin/xcrun', 'lipo', '-archs', str(binary)], 'native-platform-arch', end=c['workEnd']).strip()
    platform = c['commands'].call(['/usr/bin/xcrun', 'vtool', '-show-build', str(binary)], 'native-platform-platform', end=c['workEnd'])
    require(arch == 'arm64' and re.search(r'platform\s+IOSSIMULATOR\b', platform), 'Wrong Native architecture/platform')
    c['nativeExecutables'].add(binary.name)
    c['linkProof'] = {'binary': row['output'], 'source': report['source'], 'linkTask': LINK_TASK,
        'ordinaryPlatformTestSources': compile_row['sourceCount'], 'nativeTestTaskActions': 0}
    return binary


def boundary_pid(stdout, stderr):
    pids = set()
    for text, channel in ((stdout, 'STDOUT'), (stderr, 'STDERR')):
        pattern = re.compile(r'^APP70_CONTROL_' + channel + r' phase=(WARN|INFO) boundary=(BEGIN|END) pid=([1-9][0-9]*)$', re.M)
        found = pattern.findall(text)
        require([(phase, edge) for phase, edge, _ in found] == list(PHASES)
                and text.count('APP70_CONTROL_' + channel) == 4, 'Missing/extra/reordered descriptor phase boundaries')
        pids.update(int(pid) for _, _, pid in found)
    require(len(pids) == 1, 'Mismatched canary guest PID')
    require('APP70_CONTROL_STDERR' not in stdout and 'APP70_CONTROL_STDOUT' not in stderr,
            'Merged/crossed canary descriptor controls')
    return pids.pop()


def teamcity(stdout):
    """Exact raw native protocol; never creates XML or infers assertions from exit0."""
    rows = [(index, line) for index, line in enumerate(stdout.splitlines()) if '##teamcity[' in line]
    if any('testFailed ' in line or 'testIgnored ' in line for _, line in rows):
        return 'FAIL'
    expected = [f"##teamcity[testSuiteStarted name='{CLASS}' locationHint='ktest:suite://{CLASS}']",
        f"##teamcity[testStarted name='{METHOD}' locationHint='ktest:test://{SELECTOR}']",
        None, f"##teamcity[testSuiteFinished name='{CLASS}']"]
    if len(rows) != 4:
        return 'INCOMPLETE' if len(rows) < 4 else 'FAIL'
    if any(rows[i][1] != expected[i] for i in (0, 1, 3)) or not re.fullmatch(
            re.escape(f"##teamcity[testFinished name='{METHOD}' duration='") + r"[0-9]+'\]", rows[2][1]):
        return 'INCOMPLETE'
    boundary_rows = [index for index, line in enumerate(stdout.splitlines()) if line.startswith('APP70_CONTROL_STDOUT ')]
    return 'COMPLETE' if len(boundary_rows) == 4 and rows[1][0] < min(boundary_rows) <= max(boundary_rows) < rows[2][0] else 'INCOMPLETE'


def run_canary(c, binary):
    require(simulator_identity(c) == c['simulator']['createdOwnership'], 'Canary simulator identity changed')
    # Help records the installed syntax only. It neither enables privacy nor proves log persistence.
    simctl(c, ['spawn', c['simulator']['udid'], '/usr/bin/log', 'help', 'show'], 'apple-log-help', seconds=15)
    start = datetime.now(timezone.utc)
    capture = c['capture'] = {'schema': 'app70-three-channel-capture-v1', 'device': c['simulator']['createdOwnership'],
        'source': c['request']['app'], 'selector': SELECTOR, 'sourceSha256': CANARY_SHA,
        'binaryBefore': c['e'].fingerprint(binary, c['workEnd'], c['request']['limits']['nativeBinaryFingerprintBytes']),
        'startedUtc': start.isoformat(),
        'postHocPersistenceAssumed': False, 'privateOverrides': False, 'queryAttempted': False}
    c['e'].save(c['reports'] / 'apple-writer-capture.json', capture)
    argv = ['/usr/bin/xcrun', 'simctl', 'spawn', c['simulator']['udid'], str(binary),
        '--ktest_no_exit_code', '--ktest_logger=TEAMCITY', '--ktest_gradle_filter=' + SELECTOR]
    extra = {'HOME': str(c['runnerHome']), 'CFFIXED_USER_HOME': str(c['runnerHome']), 'TZ': 'UTC'}
    c['result']['canaryAttempted'] = True
    task = c['commands'].start(argv, 'apple-writer-canary', seconds=c['request']['limits']['canarySeconds'],
        end=c['workEnd'], extra=extra, split_output=True)
    capture['argv'], capture['ownerPid'] = argv, task['receipt']['pid']
    try:
        c['commands'].wait(task)
    finally:
        capture['endedUtc'] = datetime.now(timezone.utc).isoformat()
        capture['binaryAfter'] = c['e'].fingerprint(binary, c['workEnd'], c['request']['limits']['nativeBinaryFingerprintBytes'])
        c['e'].save(c['reports'] / 'apple-writer-capture.json', capture)
    require(capture['binaryAfter'] == capture['binaryBefore'], 'Canary executable changed')
    stdout, _ = c['e'].raw_bytes(task)
    stderr, _ = c['e'].raw_bytes(task, True)
    pid = boundary_pid(stdout.decode('utf-8', 'strict'), stderr.decode('utf-8', 'strict'))
    end = datetime.fromisoformat(capture['endedUtc'])
    require(0 <= (end - start).total_seconds() <= 90, 'Unbounded/changed canary wall-clock interval')
    lower, upper = start.replace(microsecond=0), end.replace(microsecond=0) + timedelta(seconds=1)
    predicate = f'(processID == {pid}) AND (processImagePath == "{binary}")'
    query = ['/usr/bin/xcrun', 'simctl', 'spawn', c['simulator']['udid'], '/usr/bin/log', 'show',
        '--style', 'json', '--timezone', 'UTC', '--start', lower.strftime('%Y-%m-%d %H:%M:%S'),
        '--end', upper.strftime('%Y-%m-%d %H:%M:%S'), '--info', '--debug', '--predicate', predicate]
    capture.update(guestPid=pid, queryStartUtc=lower.isoformat(), queryEndUtc=upper.isoformat(),
        queryArgv=query, queryAttempted=True)
    c['e'].save(c['reports'] / 'apple-writer-capture.json', capture)
    # Exactly one naturally finite query. No repeat, log config, OS_ACTIVITY or private-data flag.
    query_task = c['commands'].start(query, 'apple-writer-unified', seconds=c['request']['limits']['querySeconds'],
        end=c['workEnd'], extra=extra, split_output=True)
    c['commands'].wait(query_task)
    require(simulator_identity(c) == capture['device'], 'Canary/query device identity changed')


def strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for key, item in value.items():
            yield str(key)
            yield from strings(item)
    elif isinstance(value, list):
        for item in value:
            yield from strings(item)


def collect(c):
    """Partial evidence remains partial; a dummy leak dominates missing-positive INCOMPLETE."""
    proof = {'schema': 'app70-raw-default-writer-proof-v1', 'privacyStatus': 'INCOMPLETE',
        'stimulusStatus': 'INCOMPLETE', 'raw': {}, 'errors': [], 'leaks': [], 'link': c.get('linkProof'),
        'ordinaryNativeXmlTests': 0, 'carriedNativeTests': 16, 'readableUnifiedControl': False}
    raws = {}
    for name, (label, stderr) in c['e'].RAW_OUTPUTS.items():
        tasks = [t for t in c['commands'].tasks if t['receipt']['label'] == label]
        if len(tasks) != 1:
            proof['errors'].append({'stage': name, 'type': 'MissingUniqueProducer'})
            continue
        try:
            raw, record = c['e'].raw_bytes(tasks[0], stderr)
            raws[name], proof['raw'][name] = raw, record
            for marker in LEAK_MARKERS:
                offset = raw.find(marker.encode())
                if offset >= 0:
                    proof['leaks'].append({'channel': name, 'byteOffset': offset})
            require(tasks[0]['receipt']['normalJoin'] and not tasks[0]['receipt']['forced'], 'Raw producer did not complete normally')
        except Exception as error:
            proof['errors'].append({'stage': name, 'type': type(error).__name__})
    decoded = {}
    for name, raw in raws.items():
        try:
            decoded[name] = raw.decode('utf-8', 'strict')
        except UnicodeDecodeError:
            proof['errors'].append({'stage': name, 'type': 'InvalidUtf8'})
    if 'apple-writer.stdout.log' in decoded:
        proof['stimulusStatus'] = teamcity(decoded['apple-writer.stdout.log'])
    # Decode individual JSON string tokens for the negative scan even when the envelope
    # has duplicate keys/trailing damage. Such bytes can never earn positive credit.
    for token in re.finditer(r'"(?:[^"\\\x00-\x1f]|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*"',
                            decoded.get('apple-writer.unified.json', '')):
        value = json.loads(token.group())
        if any(marker in value for marker in LEAK_MARKERS):
            proof['leaks'].append({'channel': 'apple-writer.unified.json', 'stringOffset': token.start()})
    try:
        records = json.loads(decoded['apple-writer.unified.json'], object_pairs_hook=c['unique'],
                             parse_constant=lambda _: (_ for _ in ()).throw(ValueError('Nonfinite log JSON')))
        require(isinstance(records, list) and 0 < len(records) <= 2048, 'Missing/unsupported structured log records')
        for index, row in enumerate(records):
            if any(marker in value for value in strings(row) for marker in LEAK_MARKERS):
                proof['leaks'].append({'channel': 'apple-writer.unified.json', 'record': index})
    except Exception as error:
        records = []
        proof['errors'].append({'stage': 'unified-envelope', 'type': type(error).__name__})
    try:
        stdout, stderr = decoded['apple-writer.stdout.log'], decoded['apple-writer.stderr.log']
        pid = boundary_pid(stdout, stderr)
        capture = c['capture']
        require(pid == capture['guestPid'] and capture['source'] == c['request']['app']
                and capture['sourceSha256'] == CANARY_SHA and capture['selector'] == SELECTOR
                and capture['binaryBefore'] == capture['binaryAfter'] == c['linkProof']['binary'], 'Capture/source/process binding changed')
        require(not decoded['apple-writer.unified.stderr.log'].strip(), 'Unqualified log-show diagnostic output')
        for part in ('ROOT', 'CAUSE', 'SUPPRESSED'):
            require(stdout.count('APP70_CONTROL_' + part) == 2 and
                    all('APP70_CONTROL_' + part not in text for name, text in decoded.items()
                        if name != 'apple-writer.stdout.log'), 'Extra/crossed harmless Throwable control')
            for phase in ('WARN', 'INFO'):
                require(stdout.count(f'APP70_CONTROL_{part} phase={phase} pid={pid}') == 1,
                        'Missing/repeated harmless default-writer Throwable control')
                require(stdout.index(f'APP70_CONTROL_STDOUT phase={phase} boundary=BEGIN pid={pid}') <
                        stdout.index(f'APP70_CONTROL_{part} phase={phase} pid={pid}') <
                        stdout.index(f'APP70_CONTROL_STDOUT phase={phase} boundary=END pid={pid}'),
                        'Harmless Throwable control outside source phase')
        start, end = (datetime.fromisoformat(capture[key]) for key in ('queryStartUtc', 'queryEndUtc'))
        messages = []
        for row in records:
            require(isinstance(row, dict) and type(row.get('processID')) is int and row['processID'] == pid
                    and row.get('processImagePath') == capture['binaryBefore']['file']
                    and row.get('eventType') == 'logEvent' and isinstance(row.get('eventMessage'), str)
                    and isinstance(row.get('timestamp'), str), 'Wrong/unsupported process-scoped log envelope')
            stamp = datetime.fromisoformat(row['timestamp'])
            require(stamp.tzinfo is not None and start <= stamp <= end, 'Log outside exact canary time window')
            require(not any(token in value.lower() for value in strings(row)
                            for token in ('<private>', '<redacted>', '<decode:', 'dropped messages', 'messages dropped',
                                          'lost messages', 'messages lost', 'loss event', '\ufffd')),
                    'Opaque/incomplete target log records')
            messages.append(row['eventMessage'])
        unified_boundaries = [message for message in messages if 'APP70_CONTROL_UNIFIED' in message]
        require(unified_boundaries == [f'APP70_CONTROL_UNIFIED phase={phase} boundary={edge} pid={pid}'
                                      for phase, edge in PHASES], 'Missing/reordered unified channel controls')
        controls = [f'🔴 (App70WriterControl) APP70_CONTROL_MESSAGE phase={phase} pid={pid}' for phase in ('WARN', 'INFO')]
        require([message for message in messages if 'APP70_CONTROL_MESSAGE' in message] == controls,
                'Default writer private-message controls missing/extra/unreadable')
        expected = [
            '🟡 (KiraBgDownload) task.httpError | chapterId=7001 pageIndex=3 httpStatus=403',
            '🔴 (KiraBgDownload) prepare.resolve.failed | chapterId=7001 pageIndex=3 httpStatus=403',
            '🟢 (KiraBgDownload) task.enqueued | chapterId=7002 pageIndex=3 httpStatus=403',
            '🟡 (KiraBgDownload) task.httpError | chapterId=7002 pageIndex=3 httpStatus=403',
            '🔴 (KiraBgDownload) prepare.resolve.failed | chapterId=7002 pageIndex=3 httpStatus=403',
            '🟢 (KiraBgDownload) DLPERF.resolve.ms | chapterId=7002 pageIndex=3 httpStatus=403']
        actual = [message for message in messages if '(KiraBgDownload)' in message]
        if any(message not in expected for message in actual):
            proof['leaks'].append({'channel': 'apple-writer.unified.json', 'kind': 'UnexpectedBackgroundPayload'})
        require(actual == expected, 'Missing/extra safe payload or wrong phase floor')
        # Boundary positions bind each writer control/emission to its own phase, not a prior run.
        for phase, safe in (('WARN', expected[:2]), ('INFO', expected[2:])):
            begin = messages.index(f'APP70_CONTROL_UNIFIED phase={phase} boundary=BEGIN pid={pid}')
            finish = messages.index(f'APP70_CONTROL_UNIFIED phase={phase} boundary=END pid={pid}')
            require(all(begin < messages.index(message) < finish for message in [controls[0 if phase == 'WARN' else 1], *safe]),
                    'Writer payload outside source phase')
        proof.update(readableUnifiedControl=True, safePayloads=6, guestPid=pid,
                     recordCount=len(records), capture=capture)
        if proof['stimulusStatus'] == 'COMPLETE' and not proof['errors']:
            proof['privacyStatus'] = 'PASS_REVIEW_REQUIRED'
    except Exception as error:
        proof['errors'].append({'stage': 'privacy-controls', 'type': type(error).__name__})
    if proof['leaks'] or proof['stimulusStatus'] == 'FAIL':
        proof['privacyStatus'] = 'FAIL'
    c['result']['privacyRaw'] = proof['raw']
    c['result']['privacyStatus'] = proof['privacyStatus']
    c['result']['stimulusStatus'] = proof['stimulusStatus']
    c['e'].save(c['reports'] / 'native-proof.json', proof)
    return proof
