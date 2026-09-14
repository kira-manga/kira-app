"""Existing Reader UIKit leaf only; same owner/simulator/host products, no new harness or intake."""
import hashlib
import os
from pathlib import Path
import plistlib
import re
import shlex

LEAF = 'iosApp/reader-controls-tests'
TARGET, HOST, CLASS = 'ReaderChromeControlsTests', 'ReaderChromeControlsHost', 'WebtoonReaderBoundaryTests'
PROJECT = LEAF + '/' + TARGET + '.xcodeproj'
HOST_INPUT = LEAF + '/Host/AppDelegate.swift'
RESOLVED = 'project.xcworkspace/xcshareddata/swiftpm/Package.resolved'
PRODUCTS = {'FirebaseCore', 'FirebaseAnalyticsCore', 'FirebaseCrashlytics', 'FirebaseMessaging', 'FirebaseInAppMessaging-Beta'}
# Actual default-schema bytes observed in App44 Apple11, not a guessed numeric schema version.
SCHEMAS = {'summary': 'b58076b9fd20285fba040974e56514ae6b5754cd28944939aa9eab6e636ddc59',
           'tests': '18adcaa0c1bb43c466c0e0587816dbbe127c86e69e0f07fcde601ab7398d47da'}


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def source_inputs(c):
    pins = c['e'].read(Path(c['env']['KIRA_APP5_CONTROL']) / 'ci/app-seven-apple.uikit-tests.json', 65536)
    require(set(pins) == {'schema', 'status', 'app', 'target', 'class', 'caseCount', 'methods', 'testSwiftInputs', 'inputs'}
            and pins['schema'] == 'app-seven-apple-uikit-tests-v1' and pins['status'] == 'PRIMARY_BOUND_FOR_REVIEW'
            and pins['app'] == c['request']['app'] and (pins['target'], pins['class']) == (TARGET, CLASS)
            and type(pins['caseCount']) is int and pins['caseCount'] == 6
            and {key: pins[key] for key in ('target', 'class', 'caseCount')} == c['request']['uikitTests']
            and len(pins['methods']) == len(set(pins['methods'])) == pins['caseCount']
            and all(re.fullmatch(r'test[A-Za-z0-9_]+', name) for name in pins['methods']), 'UIKit roster remains UNBOUND')
    expected = pins['inputs']
    require(isinstance(expected, dict) and set(expected) == set(pins['testSwiftInputs']) |
            {HOST_INPUT, LEAF + '/project.yml', LEAF + '/.gitignore'} and len(expected) <= 32
            and all(not Path(name).is_absolute() and '..' not in Path(name).parts for name in expected), 'Wrong UIKit input roster')
    actual = {name: c['e'].sha(c['roots']['app'] / name) for name in expected}
    require(actual == expected, 'Changed/unbound UIKit source/spec/fixture input')
    return pins


def prepare(c):
    e, root, work = c['e'], c['roots']['app'], c['run'] / 'work'
    project = root / PROJECT
    require(not project.exists() and not project.is_symlink(), 'Preexisting UIKit generated project')
    executable = work / 'xcodegen-intake' / c['gen'].XCODEGEN['executable']
    require(e.sha(executable) == c['gen'].XCODEGEN['executableSha256'], 'Changed/missing already-acquired XcodeGen')
    c['commands'].call([str(executable), 'generate', '--spec', str(root / LEAF / 'project.yml'),
        '--project', str(root / LEAF)], 'generate-uikit-project', seconds=60, end=c['workEnd'],
        extra={'USER': c['h'].generator_user(c)})
    require(e.sha(executable) == c['gen'].XCODEGEN['executableSha256'], 'XcodeGen changed during leaf generation')
    model = c['h'].project_object(c, project / 'project.pbxproj', 'uikit-generated-project')
    objects = list(model['objects'].values())
    require({row['name'] for row in objects if row.get('isa') == 'PBXNativeTarget'} == {HOST, TARGET}
            and not any(row.get('isa') == 'PBXShellScriptBuildPhase' for row in objects), 'No new target/framework/Gradle/bootstrap script')
    packages = [row for row in objects if row.get('isa') == 'XCRemoteSwiftPackageReference']
    products = [row['productName'] for row in objects if row.get('isa') == 'XCSwiftPackageProductDependency']
    require(len(packages) == 1 and packages[0]['repositoryURL'] == 'https://github.com/firebase/firebase-ios-sdk'
            and packages[0]['requirement'] == {'kind': 'exactVersion', 'version': '12.15.0'}
            and len(products) == len(PRODUCTS) and set(products) == PRODUCTS, 'Only the exact existing host Firebase products')
    original = root / c['h'].PROJECT / RESOLVED
    source = c['products'].resolution(c, original)
    host_report = c['reports'] / 'host-proof.json'
    require(e.sha(host_report) == c['hostEvidence'].get('host-proof.json'), 'Changed preserved host proof')
    host_proof = e.read(host_report)
    require('swiftpm' in host_proof and
            [row for row in host_proof['swiftpm']['resolutions'] if row['path'] == str(original)] == [source],
            'UIKit requires the unchanged package resolution proved by the ordinary host')
    firebase = [pin for pin in source['pins'] if pin['identity'] == 'firebase-ios-sdk']
    require(len(firebase) == 1 and firebase[0]['state'].get('version') == '12.15.0', 'Missing exact already-resolved host Firebase closure')
    target = project / RESOLVED
    require(not target.exists() and not target.is_symlink() and target.resolve() == target, 'Unowned leaf Package.resolved')
    target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    data = c['h'].bytes_of(original, 262144)
    c['h'].retained(c, 'uikit-Package.resolved', data)
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(data)
    require(e.sha(target) == source['sha256'], 'Package.resolved copy changed')
    context = dict(c, end=c['workEnd'], evidenceCleaning=False)
    c['uikitPackages'] = c['products'].package_checkouts(context)
    require(c['uikitPackages'] == host_proof['swiftpm']['checkouts'], 'Native changed the host package checkout inputs')
    c['uikitProject'] = {'app': c['request']['app'], 'project': str(project), 'sourceResolution': source,
        'leafResolution': str(target), 'samePackageBytes': True, 'checkoutsBefore': c['uikitPackages'],
        'projectSha256': e.sha(project / 'project.pbxproj'),
        'schemeSha256': e.sha(project / 'xcshareddata/xcschemes' / (TARGET + '.xcscheme')),
        'acquisition': 'reuse sole host intake and exact Package.resolved; no second acquisition/version selection'}
    e.save(c['reports'] / 'uikit-project.json', c['uikitProject'])
    c['nativeExecutables'].add(HOST)  # Bound real host executable name, observation only, never signal authority.


def argv(c):
    work = c['run'] / 'work'
    return ['/usr/bin/xcodebuild', 'test', '-project', str(c['roots']['app'] / PROJECT), '-scheme', TARGET,
        '-configuration', 'Debug', '-sdk', 'iphonesimulator', '-destination', 'platform=iOS Simulator,id=' + c['simulator']['udid'],
        '-destination-timeout', '60', '-jobs', '1', '-parallel-testing-enabled', 'NO',
        '-maximum-concurrent-test-simulator-destinations', '1', '-test-timeouts-enabled', 'YES',
        '-default-test-execution-time-allowance', '30', '-maximum-test-execution-time-allowance', '60',
        '-derivedDataPath', str(work / 'DerivedData'), '-clonedSourcePackagesDirPath', str(work / 'SourcePackages'),
        '-packageCachePath', str(work / 'swiftpm-cache'), '-resultBundlePath', str(work / 'uikit-tests.xcresult'),
        '-disableAutomaticPackageResolution', '-onlyUsePackageVersionsFromResolvedFile', '-skipPackageUpdates',
        *['-only-testing:' + TARGET + '/' + CLASS + '/' + method for method in c['uikitSource']['methods']],
        'ARCHS=arm64', 'ONLY_ACTIVE_ARCH=YES', 'CODE_SIGNING_ALLOWED=NO', 'CODE_SIGNING_REQUIRED=NO',
        'CODE_SIGN_IDENTITY=', 'DEVELOPMENT_TEAM=', 'CLANG_MODULE_CACHE_PATH=' + str(work / 'clang-module-cache'),
        'SWIFT_MODULE_CACHE_PATH=' + str(work / 'swift-module-cache')]


def result_outputs(c):
    tool = Path(c['env']['DEVELOPER_DIR']) / 'usr/bin/xcresulttool'
    digest = c['e'].sha(tool)
    bundle = c['run'] / 'work/uikit-tests.xcresult'
    require(bundle.is_dir() and bundle.resolve() == bundle, 'Missing/aliased actual UIKit result bundle')
    outputs, values = {}, {}
    for kind in ('summary', 'tests'):
        for schema in (True, False):
            label = 'uikit-xcresult-' + kind + ('-schema' if schema else '')
            args = [str(tool), 'get', 'test-results', kind, '--path', str(bundle)] + (['--schema'] if schema else [])
            c['commands'].call(args, label, seconds=30, end=c['end'], cleaning=c.get('evidenceCleaning', False))
            task = next(t for t in c['commands'].tasks if t['receipt']['label'] == label)
            name = label + '.json'
            raw = c['h'].bytes_of(task['log'], c['e'].PUBLIC_CAPS[name])
            c['h'].retained(c, name, raw)  # Exact raw bytes; no invented XML, reserialization or test marker.
            values[kind + ('Schema' if schema else '')] = c['e'].read(c['reports'] / name)
            outputs[name] = {'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest(), 'argv': args}
    require(c['e'].sha(tool) == digest, 'Selected xcresulttool changed')
    c['e'].save(c['reports'] / 'uikit-results.json', {'app': c['request']['app'], 'tool': str(tool),
        'toolSha256': digest, 'schemaVersionArgument': None, 'outputs': outputs,
        'bundle': c['e'].fingerprint(bundle, c['end'], 67108864)})
    require(all(outputs['uikit-xcresult-' + kind + '-schema.json']['sha256'] == value for kind, value in SCHEMAS.items()),
            'Unknown actual xcresult schema; preserve raw evidence, no guessed result grammar')
    return values


def case_results(c, values):
    summary, tests = values['summary'], values['tests']
    keys = ('totalTestCount', 'passedTests', 'failedTests', 'skippedTests', 'expectedFailures')
    counts = {key: summary[key] for key in keys}
    require(all(type(n) is int and 0 <= n <= 64 for n in counts.values())
            and counts['skippedTests'] <= counts['totalTestCount'], 'Invalid real UIKit counts')
    c['result']['uikitTestCounts'] = counts
    c['result']['uikitTestsExecuted'] = counts['totalTestCount'] - counts['skippedTests']
    count = c['uikitSource']['caseCount']
    require(counts == dict(totalTestCount=count, passedTests=count, failedTests=0, skippedTests=0, expectedFailures=0)
            and summary['result'] == 'Passed' and summary['testFailures'] == [], 'UIKit cases failed/skipped/repeated/missing')
    device = {'architecture': 'arm64', 'deviceId': c['simulator']['udid'], 'deviceName': c['simulator']['name'],
        'modelName': 'iPhone 17', 'osBuildNumber': '23E254a', 'osVersion': '26.4.1', 'platform': 'iOS Simulator'}
    config = {'configurationId': '1', 'configurationName': 'Test Scheme Action'}
    require(tests['devices'] == [device] and tests['testPlanConfigurations'] == [config]
            and summary['devicesAndConfigurations'] == [dict(device=device, testPlanConfiguration=config,
                passedTests=count, failedTests=0, skippedTests=0, expectedFailures=0)], 'Wrong/multiple UIKit device or configuration')
    nodes = tests['testNodes']
    for kind, name in (('Test Plan', TARGET), ('Unit test bundle', TARGET), ('Test Suite', CLASS)):
        require(len(nodes) == 1 and nodes[0]['nodeType'] == kind and nodes[0]['name'] == name
                and nodes[0]['result'] == 'Passed', 'Wrong/multiple UIKit plan/bundle/class')
        nodes = nodes[0]['children']
    methods = c['uikitSource']['methods']
    require(len(nodes) == count and {node['name'] for node in nodes} == {name + '()' for name in methods},
            'Missing/extra UIKit case names')
    for node in nodes:
        method = node['name'].removesuffix('()')
        require(node['nodeType'] == 'Test Case' and node['result'] == 'Passed' and not node.get('children')
                and node['nodeIdentifier'] == CLASS + '/' + method + '()'
                and node['nodeIdentifierURL'] == 'test://com.apple.xcode/' + TARGET + '/' + TARGET + '/' + CLASS + '/' + method,
                'Wrong/repeated/hidden UIKit execution')
    return {'counts': counts, 'cases': nodes, 'device': device, 'configuration': config}


def compilation(c):
    e, root, work = c['e'], c['roots']['app'], c['run'] / 'work'
    folder = work / 'DerivedData/Build/Intermediates.noindex' / (TARGET + '.build') / 'Debug-iphonesimulator'
    filelist = folder / (TARGET + '.build/Objects-normal/arm64') / (TARGET + '.SwiftFileList')
    host_filelist = folder / (HOST + '.build/Objects-normal/arm64') / (HOST + '.SwiftFileList')
    files = shlex.split(c['h'].bytes_of(filelist, 65536).decode())
    require(len(files) == len(set(files)) and set(files) == {str(root / name) for name in c['uikitSource']['testSwiftInputs']}
            and shlex.split(c['h'].bytes_of(host_filelist, 65536).decode()) == [str(root / HOST_INPUT)], 'Pruned/extra/substitute UIKit Swift inputs')
    log = next(t['log'] for t in c['commands'].tasks if t['receipt']['label'] == 'uikit-tests')
    raw = c['h'].bytes_of(log, 4194304).decode(errors='replace')
    require(str(host_filelist) in raw and not any(s in raw for s in
            ('Unable to simultaneously satisfy constraints', 'UIViewAlertForUnsatisfiableConstraints')), 'Missing real host compile/unexplained UIKit layout diagnostic')
    framework = e.read(c['reports'] / 'framework-metadata.json')['binaryFingerprints']['exported']
    require(e.fingerprint(Path(framework['file']), c['end'], 1073741824) == framework, 'Changed original host ComposeApp link input')
    swift = c['products'].swift_command(raw, filelist, str(Path(framework['file']).parent), TARGET)
    products = work / 'DerivedData/Build/Products/Debug-iphonesimulator'
    host_bundle = products / (HOST + '.app')
    bundle = host_bundle / 'PlugIns' / (TARGET + '.xctest')
    linked_paths = {str(bundle / TARGET), str(products / (TARGET + '.xctest') / TARGET)}
    links = []
    for line in raw.splitlines():
        if 'ComposeApp' in line and ' -o ' in line:
            tokens = shlex.split(line)
            if tokens and Path(tokens[0]).name in ('clang', 'clang++') and tokens[tokens.index('-o') + 1] in linked_paths:
                links.append(tokens)
    require(len(links) == 1 and '-filelist' in links[0] and any(c['products'].same_path(path, Path(framework['file']).parent.parent)
            for path in c['products'].search_paths(links[0], '-F')), 'Missing actual UIKit link against the host ComposeApp framework')
    tokens = links[0]
    require(any(value == '-framework' and tokens[index + 1] == 'ComposeApp' for index, value in enumerate(tokens[:-1]))
            or any(c['products'].same_path(value, Path(framework['file'])) for value in tokens), 'UIKit link does not consume ComposeApp')
    object_file = Path(tokens[tokens.index('-filelist') + 1])
    require(object_file.is_relative_to(work / 'DerivedData'), 'Unowned UIKit link file list')
    objects = shlex.split(c['h'].bytes_of(object_file, 131072).decode())
    require(0 < len(objects) <= 512 and all(Path(path).is_relative_to(work / 'DerivedData') for path in objects), 'Unowned/oversized UIKit object input set')
    packages = c['products'].package_link_inputs(c, tokens, objects)
    binaries = {}
    for label, path, identifier, executable in (('uikit-binary', bundle, 'me.manga.kira.readercontrols.tests', TARGET),
            ('uikit-host', host_bundle, 'me.manga.kira.readercontrols.testhost', HOST)):
        info = plistlib.loads(c['h'].bytes_of(path / 'Info.plist', 65536))
        require(info['CFBundleIdentifier'] == identifier and info['CFBundleExecutable'] == executable, 'Wrong real UIKit binary identity')
        c['owner'].inspect_macho(c['commands'], path / executable, label, c['end'])
        binaries[label] = e.fingerprint(path / executable, c['end'], 1073741824)
    project = root / PROJECT
    before = c['uikitProject']
    require(e.sha(project / 'project.pbxproj') == before['projectSha256'] and
            e.sha(project / 'xcshareddata/xcschemes' / (TARGET + '.xcscheme')) == before['schemeSha256'] and
            e.sha(project / RESOLVED) == before['sourceResolution']['sha256'] == e.sha(root / c['h'].PROJECT / RESOLVED) and
            c['products'].package_checkouts(c) == c['uikitPackages'], 'UIKit project or pinned host package inputs changed')
    return {'swiftInputs': files, 'swiftCompiler': swift, 'hostInput': HOST_INPUT, 'framework': framework,
        'linkArgv': tokens, 'packageInputs': packages, 'binaries': binaries, 'sameResolvedPackageInputs': True}


def collect(c):
    proof = {'app': c['request']['app'], 'status': 'INCOMPLETE', 'errors': []}
    for label, operation in (('cases', lambda: case_results(c, result_outputs(c))), ('compilation', lambda: compilation(c))):
        try:
            proof[label] = operation()
        except Exception as error:
            proof['errors'].append(label + ': ' + str(error)[:500])
    proof['passed'] = bool(c['result'].get('uikitTestsSucceeded') and not proof['errors'])
    proof['status'] = 'UIKIT_RESULT_REVIEW_REQUIRED' if proof['passed'] else 'FAIL'
    c['e'].save(c['reports'] / 'uikit-proof.json', proof)
    return proof
