"""Targeted six-case Native adapter; uses the existing command owner, not a second controller.

Simulator custody/HOME handling is the finite Reader Apple04 precedent. No PF,
compiler/source replacement, app installation, simulator-wide reset or new owner.
"""
import hashlib
from itertools import islice
import json
import os
from pathlib import Path
import pwd
import re
import stat
import time
import xml.etree.ElementTree as ET

RUNTIME = 'com.apple.CoreSimulator.SimRuntime.iOS-26-4'
DEVICE_TYPE = 'com.apple.CoreSimulator.SimDeviceType.iPhone-17'
TASKS = (':platform:iosSimulatorArm64Test', ':data:download:iosSimulatorArm64Test')
CLASSES = ('me.manga.kira.platform.media.IosPngIntegrityTest',
           'me.manga.kira.presentation.features.download.domain.clean.DownloadManifestStoreTest')
COUNTS = (4, 2)
SUITE_TASKS = TASKS
TEST_SETS = ('commonTest', 'nativeTest', 'appleTest', 'iosTest', 'iosSimulatorArm64Test', 'nonAndroidTest')


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def runner_home(run, roots):
    home = Path(os.environ['HOME']).resolve()
    require(home == Path(pwd.getpwuid(os.getuid()).pw_dir).resolve() and home.is_dir()
            and not home.is_relative_to(run) and home not in roots.values(), 'Invalid real runner HOME')
    return home


def source_inputs(c):
    e, root = c['e'], c['roots']['app']
    binding = e.read(Path(c['env']['KIRA_APP5_CONTROL']) / 'ci/app-seven-apple.native-tests.json', 65536)
    require(set(binding) == {'schema', 'status', 'app', 'suites'}
            and binding['schema'] == 'app-seven-apple-native-tests-v1'
            and binding['status'] == 'PRIMARY_BOUND_FOR_REVIEW' and binding['app'] == c['request']['app'],
            'Native source/selector binding remains UNBOUND')
    suites = binding['suites']
    require(isinstance(suites, list) and len(suites) == 2, 'Exactly two targeted native suites')
    inventories = {}
    for spec, task, cls, count in zip(suites, SUITE_TASKS, CLASSES, COUNTS):
        require(set(spec) == {'task', 'class', 'source', 'sourceSha256', 'methods'}
                and spec['task'] == task and spec['class'] == cls and len(spec['methods']) == count
                and len(set(spec['methods'])) == count and all(re.fullmatch(r'[A-Za-z_]\w*', n) for n in spec['methods']),
                'Wrong/nonliteral native method roster')
        module = task.rsplit(':', 1)[0][1:].replace(':', '/')
        compile_task = task.replace('iosSimulatorArm64Test', 'compileTestKotlinIosSimulatorArm64')
        if compile_task not in inventories:
            files = {file for name in TEST_SETS for file in (root / module / 'src' / name / 'kotlin').rglob('*.kt')}
            require(0 < len(files) <= 2048 and all(file.resolve().is_relative_to(root) for file in files),
                    'Invalid ordinary test-source inventory')
            inventories[compile_task] = {str(file.relative_to(root)): e.sha(file) for file in sorted(files)}
        require(inventories[compile_task].get(spec['source']) == spec['sourceSha256']
                and re.fullmatch('[0-9a-f]{64}', spec['sourceSha256']), 'Unbound/changed authored native test')
    require([len(files) for files in inventories.values()] == [29, 13], 'Complete ordinary platform29/download13 test inputs required')
    return {'app': binding['app'], 'root': str(root), 'suites': suites, 'compilations': inventories}


def task_arguments(c):
    result = []
    for task in TASKS:
        result.append(task)
        for spec in c['nativeSource']['suites']:
            if spec['task'] == task:
                for method in spec['methods']:
                    result.extend(('--tests', spec['class'] + '.' + method))
        result.extend(('--device', c['simulator']['udid']))
    return result


def environment(c):
    return {'KIRA_APP7_SIMULATOR_HOME': str(c['runnerHome']), 'KIRA_APP7_DEVICE': c['simulator']['udid']}


def executable_names(c):
    path = c['reports'] / 'native-app-tasks.json'
    if not path.exists():
        return set()
    observed = c['e'].read(path)['observed']
    names = set()
    for task in TASKS:
        module = task.rsplit(':', 1)[0][1:].replace(':', '/')
        row = observed.get(task.replace('iosSimulatorArm64Test', 'linkDebugTestIosSimulatorArm64'), {})
        if row.get('output'):
            binary = Path(row['output']['file'])
            require(binary.is_relative_to(c['roots']['app'] / module / 'build'), 'Unowned executable receipt')
            names.add(binary.name)
    return names


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


def task_proof(c):
    e, root = c['e'], c['roots']['app']
    report = e.read(c['reports'] / 'native-app-tasks.json')
    require(report['role'] == 'app' and report['source'] == c['request']['app']['sha']
            and report['testTasks'] == list(TASKS), 'Different actual native graph/source')
    main_paths = sorted(row['path'] for row in report['graph'] if row['path'].endswith(':compileKotlinIosSimulatorArm64'))
    require({task.replace('iosSimulatorArm64Test', 'compileKotlinIosSimulatorArm64') for task in TASKS}
            <= set(main_paths), 'Missing ordinary platform/download main producers')
    mains = {}
    for task in main_paths:
        compiled = report['observed'][task]
        module = task.rsplit(':', 1)[0]
        expected = c['before']['app']['mains'][module]
        digest = hashlib.sha256(''.join(value + '  ' + name + '\n' for name, value in sorted(expected.items())).encode()).hexdigest()
        output = compiled['output']
        require(e.completed(report['states'][task]) and compiled['sourcesMatched'] is True
                and compiled['sourceDigest'] == digest and compiled['target'] == 'ios_simulator_arm64'
                and Path(output['file']).is_relative_to(root / module[1:].replace(':', '/') / 'build')
                and e.fingerprint(Path(output['file']), c['end']) == output,
                'Wrong/pruned/cached/changed ordinary main producer: ' + task)
        mains[task] = compiled
    result = {'ordinaryMains': mains}
    for task in TASKS:
        module = task.rsplit(':', 1)[0][1:].replace(':', '/')
        compile_task = task.replace('iosSimulatorArm64Test', 'compileTestKotlinIosSimulatorArm64')
        link_task = task.replace('iosSimulatorArm64Test', 'linkDebugTestIosSimulatorArm64')
        selectors = sorted(spec['class'] + '.' + name for spec in c['nativeSource']['suites']
                           if spec['task'] == task for name in spec['methods'])
        require(all(e.completed(report['states'][name]) for name in (compile_task, link_task, task)),
                'Native test compile/link/run skipped, cached, failed or incomplete: ' + task)
        compiled, linked, invocation = (report['observed'][name] for name in (compile_task, link_task, task))
        expected = c['nativeSource']['compilations'][compile_task]
        source_digest = hashlib.sha256(''.join(value + '  ' + name + '\n' for name, value in sorted(expected.items())).encode()).hexdigest()
        require(compiled['sourcesMatched'] is True and compiled['sourceDigest'] == source_digest
                and compiled['target'] == 'ios_simulator_arm64', 'Wrong/pruned actual Native test sources')
        for output in (compiled['output'], linked['output']):
            require(Path(output['file']).is_relative_to(root / module / 'build')
                    and e.fingerprint(Path(output['file']), c['end'], 1073741824) == output, 'Changed/unowned Native output')
        require(linked['includes'] == [compiled['output']] and linked['target'] == 'ios_simulator_arm64'
                and invocation['executable'] == linked['output']
                and invocation['selectors'] == selectors
                and invocation['device'] == c['simulator']['udid'] and invocation['standalone'] is False
                and invocation['targetName'] == 'iosSimulatorArm64' and invocation['simulatorHome'] == str(c['runnerHome']),
                'Wrong test KLIB/link/binary/device/selector join')
        main = mains[task.replace('iosSimulatorArm64Test', 'compileKotlinIosSimulatorArm64')]['output']['file']
        require(main in compiled['libraries'] and main in linked['libraries'], 'Test did not consume its fresh ordinary main KLIB')
        binary = linked['output']['file']
        label = 'native-' + module.replace('/', '-')
        arch = c['commands'].call(['/usr/bin/xcrun', 'lipo', '-archs', binary], label + '-arch',
            seconds=15, end=c['end'], cleaning=c.get('evidenceCleaning', False)).strip()
        build = c['commands'].call(['/usr/bin/xcrun', 'vtool', '-show-build', binary], label + '-platform',
            seconds=15, end=c['end'], cleaning=c.get('evidenceCleaning', False))
        minimum = re.findall(r'(?m)^\s*minos\s+(\S+)\s*$', build)
        require(arch == 'arm64' and re.findall(r'(?m)^\s*platform\s+(\S+)\s*$', build) == ['IOSSIMULATOR']
                and re.findall(r'(?m)^\s*sdk\s+(\S+)\s*$', build) == ['26.4'] and len(minimum) == 1
                and re.fullmatch(r'[0-9]+(?:\.[0-9]+){1,2}', minimum[0]), 'Wrong Native Mach-O architecture/platform/SDK')
        result[task] = {'compile': compiled, 'link': linked, 'invocation': invocation,
                        'architecture': arch, 'platform': 'IOSSIMULATOR', 'sdk': '26.4', 'minimumOS': minimum[0]}
    return result


def xml_diagnostics(c):
    # Observe before refusing the exact filename set. Fixed slots admit at most four
    # unexpected 1MiB XMLs per root; excess names remain bounded diagnostic-only data.
    diagnostic = {'roots': [], 'errors': [], 'maximumNamesPerRoot': 32, 'unexpectedSlotsPerRoot': 4}
    for task in TASKS:
        module = task.rsplit(':', 1)[0][1:].replace(':', '/')
        expected = sorted('TEST-iosSimulatorArm64Test.' + spec['class'] + '.xml'
                          for spec in c['nativeSource']['suites'] if spec['task'] == task)
        row = {'task': task, 'expected': expected, 'observed': []}
        diagnostic['roots'].append(row)
        try:
            folder = c['run'] / 'work/native-xml' / module
            require(folder.resolve() == folder and not folder.is_symlink(), 'Aliased XML directory')
            paths = sorted(islice(folder.glob('*.xml'), 33))
            row.update(directoryExists=folder.is_dir(), nameLimitExceeded=len(paths) > 32,
                       actualXmlCount=len(paths) if len(paths) <= 32 else None, observedAtLeast=len(paths))
            extra = 0
            for path in paths[:32]:
                require(time.monotonic() < c['end'], 'XML evidence deadline exceeded')
                item = {'filename': path.name}
                row['observed'].append(item)
                try:
                    item['bytes'] = path.lstat().st_size
                    if path.name in expected:
                        name = 'native-xml/' + module + '/' + path.name
                    else:
                        extra += 1
                        if extra > 4:
                            item['notRetained'] = 'Unexpected XML slot limit'
                            continue
                        name = 'native-xml-unexpected/' + module + '/' + str(extra).zfill(2) + '.xml'
                    raw = c['h'].bytes_of(path, 1048576)
                    (c['reports'] / name).parent.mkdir(parents=True, mode=0o700, exist_ok=True)
                    require((c['reports'] / name).parent.resolve() == (c['reports'] / name).parent, 'Aliased XML output directory')
                    c['h'].retained(c, name, raw)
                    item.update(retained=name, sha256=hashlib.sha256(raw).hexdigest())
                    require(b'<!DOCTYPE' not in raw and b'<!ENTITY' not in raw, 'Unexpected XML declarations')
                    suite = ET.fromstring(raw)
                    counts = {key: int(suite.get(key, '-1')) for key in ('tests', 'failures', 'errors', 'skipped')}
                    require(all(0 <= n <= 2048 for n in counts.values()), 'Invalid diagnostic XML counts')
                    item.update(counts=counts, directTestcases=len(suite.findall('testcase')))
                except Exception as error:
                    item['error'] = str(error)[:500]
            require(len(paths) <= 32 and [path.name for path in paths] == expected,
                    'Missing/extra ordinary XML suites: ' + module)
        except Exception as error:
            diagnostic['errors'].append(str(error)[:500])
    c['e'].save(c['reports'] / 'native-xml-diagnostics.json', diagnostic)
    return diagnostic


def collect(c):
    # Raw XML is kept even if native execution/other evidence fails; it is never a replay or invented count.
    proof = {'status': 'INCOMPLETE', 'app': c['request']['app'], 'suites': [], 'errors': [],
             'xmlCounts': dict(tests=0, failures=0, errors=0, skipped=0), 'testsExecuted': 0}
    proof['errors'].extend(xml_diagnostics(c)['errors'])
    for spec in c['nativeSource']['suites']:
        try:
            module = spec['task'].rsplit(':', 1)[0][1:].replace(':', '/')
            filename = 'TEST-iosSimulatorArm64Test.' + spec['class'] + '.xml'
            name = 'native-xml/' + module + '/' + filename
            raw = c['h'].bytes_of(c['reports'] / name, 1048576)
            require(b'<!DOCTYPE' not in raw and b'<!ENTITY' not in raw, 'Unexpected XML declarations')
            suite = ET.fromstring(raw)
            cases, cls = suite.findall('testcase'), 'iosSimulatorArm64Test.' + spec['class']
            counts = {key: int(suite.get(key, '-1')) for key in proof['xmlCounts']}
            require(suite.tag == 'testsuite' and all(0 <= n <= 2048 for n in counts.values())
                    and counts['tests'] == len(cases) and counts['skipped'] <= counts['tests'], 'Invalid XML counts/shape')
            row = {'task': spec['task'], 'class': suite.get('name'), 'counts': counts, 'xml': name,
                   'sha256': hashlib.sha256(raw).hexdigest(), 'cases': [case.attrib for case in cases]}
            proof['suites'].append(row)
            for key, value in counts.items():
                proof['xmlCounts'][key] += value
            proof['testsExecuted'] += counts['tests'] - counts['skipped']
            require(suite.get('name') == cls and all(case.get('classname') == cls for case in cases)
                    and len(cases) == len(spec['methods'])
                    and {case.get('name') for case in cases} == {name + '[iosSimulatorArm64]' for name in spec['methods']}
                    and list(suite.iter('testcase')) == cases and all(counts[key] == 0 for key in ('failures', 'errors', 'skipped'))
                    and not any(node.tag in ('failure', 'error', 'skipped') for node in suite.iter()),
                    'XML did not prove the exact unskipped native methods: ' + module)
        except Exception as error:
            proof['errors'].append(str(error)[:500])
    try:
        proof['tasks'] = task_proof(c)
    except Exception as error:
        proof['errors'].append('Native task evidence: ' + str(error)[:500])
    proof['passed'] = bool(c['result'].get('nativeTestsSucceeded') and not proof['errors']
                           and proof['xmlCounts'] == dict(tests=6, failures=0, errors=0, skipped=0))
    proof['status'] = 'NATIVE_RESULT_REVIEW_REQUIRED' if proof['passed'] else 'FAIL'
    proof['qualification'] = 'Targeted Native PNG4 and manifest-filesystem2 only; not a host build, full-suite rerun, UIKit runtime or physical-device proof.'
    c['e'].save(c['reports'] / 'native-proof.json', proof)
    return proof
