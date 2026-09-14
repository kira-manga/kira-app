"""Only the new static-framework -> actual shipping Swift host/built-plist boundary."""
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import shlex
import stat
import time
from urllib.parse import urlsplit

LINK = ':composeApp:linkDebugFrameworkIosSimulatorArm64'
EMBED = ':composeApp:embedAndSignAppleFrameworkForXcode'
OWN_MAIN = ':composeApp:compileKotlinIosSimulatorArm64'
TASK_IDS = ['me.manga.kira.download.processing', 'me.manga.kira.download.continued', 'me.manga.kira.library.refresh']


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def same_path(value, expected):
    path = Path(value)
    return path.is_absolute() and path.resolve() == expected


def search_paths(tokens, flag):
    return [tokens[i + 1] if v == flag else v[len(flag):]
            for i, v in enumerate(tokens[:-1]) if v.startswith(flag)]


def log(c):
    rows = [t for t in c['commands'].tasks if t['receipt']['label'] == 'host-build']
    require(len(rows) == 1 and rows[0]['receipt']['normalJoin'], 'No single successful owned Xcode build')
    return c['h'].bytes_of(rows[0]['log'], 8388608).decode('utf-8', errors='replace')


def own_main_include(c, proof, app, link):
    output = proof['outputs']['app'][OWN_MAIN]
    require(output == app['observed'][OWN_MAIN]['output'] and c['e'].completed(app['states'][OWN_MAIN]),
            'Different/unexecuted own Native include producer')
    root = Path(output['file'])
    require(output['kind'] == 'directory' and root.is_absolute() and root.is_dir()
            and not root.is_symlink() and root.resolve() == root, 'Missing/aliased own Native include directory')
    inputs = link['inputs']
    require(0 < len(inputs) <= 4096 and len(inputs) == len(set(inputs)), 'Own Native include link-input cap/duplicates')
    leaves, pending, entries = set(), [root], 0
    while pending:
        directory = pending.pop()
        require(time.monotonic() < c['end'] and directory.is_dir() and not directory.is_symlink()
                and directory.resolve() == directory, 'Late/aliased own Native include directory')
        with os.scandir(directory) as children:
            for child in children:
                entries += 1
                item = Path(child.path)
                require(time.monotonic() < c['end'] and entries <= 4096,
                        'Own Native include tree cap/deadline')
                require(not child.is_symlink() and item.resolve() == item, 'Aliased own Native include entry')
                if child.is_dir(follow_symlinks=False):
                    pending.append(item)
                else:
                    require(child.is_file(follow_symlinks=False), 'Nonregular own Native include leaf')
                    leaves.add(str(item))
    require(leaves and len(leaves) == output['files'], 'Missing/changed own Native include leaves')
    require(c['e'].fingerprint(root, c['end']) == output and time.monotonic() < c['end'],
            'Own Native include output changed')
    # Only this exact producer is KGP's source FileTree; other libraries retain literal-root joins.
    captured = {n for n in inputs if Path(n).is_relative_to(root)}
    require(captured == leaves, 'Own Native include leaves do not exactly match captured link inputs')
    return {'task': OWN_MAIN, 'output': output, 'linkInputLeaves': sorted(leaves)}


def producer_joins(c, proof, app):
    e = c['e']
    require(e.completed(app['states'][LINK]), 'Real fresh framework link did not execute')
    embed = app['states'][EMBED]
    require(embed.get('executed') is True and not embed.get('failure'), 'Real embed lifecycle did not complete')
    row = app['observed'][LINK]
    expected = [r for group in proof['outputs'].values() for r in group.values()]
    interop = app['observed'][':platform:cinteropLibwebpIosSimulatorArm64']['output']
    expected.append(interop)
    literal = {r['file'] for role, group in proof['outputs'].items() for task, r in group.items()
               if (role, task) != ('app', OWN_MAIN)}
    literal.add(interop['file'])
    require(literal <= set(row['inputs']), 'A real main/Engine/libwebp producer is missing from link inputs')
    own = own_main_include(c, proof, app, row)
    neutral = row['neutral']
    require({r['project'] for r in neutral} == {':source-contract', ':source-engine'}, 'Different Engine link boundary')
    for supplier in neutral:
        producer = supplier['project'] + ':compileKotlinIosSimulatorArm64'
        require(supplier['output'] == proof['outputs']['engine'][producer] and
                supplier['version'] == '0.1.0-SNAPSHOT' and supplier['target'] == 'ios_simulator_arm64',
                'Final link did not consume the same genuine original Native Engine outputs')
    require(e.fingerprint(Path(row['output']['file']), c['end'], 1073741824) == row['output'], 'Framework output changed')
    crash = [Path(n) for n in row['inputs'] if 'crashlytics' in Path(n).name.lower()]
    require(crash, 'No actual CrashKiOS Native link input')
    return {'mainAndInteropInputs': expected, 'ownMainInclude': own, 'originalEngine': neutral, 'embedTaskState': embed,
            'crashkios': [e.fingerprint(n, c['end']) for n in crash]}


def settings(app, c):
    s = app['xcodeSettings']
    fixed = {'CONFIGURATION': 'Debug', 'PLATFORM_NAME': 'iphonesimulator', 'ARCHS': 'arm64',
        'TARGET_NAME': 'iosApp', 'PRODUCT_NAME': 'Kira', 'PRODUCT_BUNDLE_IDENTIFIER': 'me.manga.kira',
        'WRAPPER_NAME': 'Kira.app', 'EXECUTABLE_NAME': 'Kira', 'SWIFT_VERSION': '5.0',
        'IPHONEOS_DEPLOYMENT_TARGET': '15.0', 'CODE_SIGNING_ALLOWED': 'NO'}
    require(all(s.get(k) == v for k, v in fixed.items()) and s['SDK_NAME'] == 'iphonesimulator26.4', 'Wrong actual host settings')
    expected = c['run'] / 'work/DerivedData/Build/Products/Debug-iphonesimulator'
    require(Path(s['TARGET_BUILD_DIR']) == expected and Path(s['BUILT_PRODUCTS_DIR']) == expected,
            'Host products outside owned DerivedData')
    require(Path(s['SRCROOT']) == c['roots']['app'] / 'iosApp', 'Not the real shipping project')
    return s


def metadata_observation(label, path, cap):
    # Metadata only: collect all four rows even when one path cannot be inspected.
    # This does not read file contents or replace/weaken bytes_of's acceptance guards.
    row = {'label': label, 'path': str(path)[:4096], 'readCapBytes': cap, 'errors': []}
    try:
        info = path.lstat()
        row.update(regularFile=stat.S_ISREG(info.st_mode), symlink=stat.S_ISLNK(info.st_mode),
                   bytes=info.st_size, links=info.st_nlink, mode=stat.S_IMODE(info.st_mode))
    except (OSError, ValueError) as error:
        row['errors'].append({'operation': 'lstat', 'type': type(error).__name__, 'errno': getattr(error, 'errno', None)})
    try:
        canonical = path.resolve()
        row.update(canonicalPath=str(canonical)[:4096], canonicalEqualsLexical=canonical == path)
    except (OSError, ValueError, RuntimeError) as error:
        row['errors'].append({'operation': 'resolve', 'type': type(error).__name__, 'errno': getattr(error, 'errno', None)})
    return row


def framework(c, app, s):
    e, h = c['e'], c['h']
    original = Path(app['observed'][LINK]['output']['file'])
    exported = c['roots']['app'] / 'composeApp/build/xcode-frameworks/Debug' / s['SDK_NAME'] / 'ComposeApp.framework'
    require(any(same_path(n, exported.parent) for n in shlex.split(s['FRAMEWORK_SEARCH_PATHS'])),
            'Actual framework search path not observed')
    source_binary, exported_binary = original / 'ComposeApp', exported / 'ComposeApp'
    a, b = e.fingerprint(source_binary, c['end'], 1073741824), e.fingerprint(exported_binary, c['end'], 1073741824)
    e.save(c['reports'] / 'framework-metadata.json', {
        'phase': 'before-bounded-header-module-reads', 'causeAdjudication': 'NOT_PERFORMED',
        'binaryFingerprints': {'original': a, 'exported': b},
        'metadataObservations': [
            metadata_observation('original-header', original / 'Headers/ComposeApp.h', 1048576),
            metadata_observation('exported-header', exported / 'Headers/ComposeApp.h', 1048576),
            metadata_observation('original-module-map', original / 'Modules/module.modulemap', 65536),
            metadata_observation('exported-module-map', exported / 'Modules/module.modulemap', 65536),
        ],
    })
    require(a['sha256'] == b['sha256'] and a['bytes'] == b['bytes'], 'Exported framework is not the fresh original link output')
    with source_binary.open('rb') as stream:
        require(stream.read(8) == b'!<arch>\n', 'Expected real static framework archive, not a dynamic/substitute framework')
    require(c['commands'].call(['/usr/bin/xcrun', 'lipo', '-archs', str(exported_binary)],
            'framework-arch', end=c['end'], cleaning=c.get('evidenceCleaning', True)).strip() == 'arm64',
            'Wrong static framework architecture')
    header, module = h.bytes_of(original / 'Headers/ComposeApp.h', 1048576), h.bytes_of(original / 'Modules/module.modulemap', 65536)
    require(header == h.bytes_of(exported / 'Headers/ComposeApp.h', 1048576) and
            module == h.bytes_of(exported / 'Modules/module.modulemap', 65536), 'Exported header/module map drift')
    h.retained(c, 'ComposeApp.h', header)
    h.retained(c, 'ComposeApp.modulemap', module)
    bridge = re.findall(r'@interface[^\n]*IosLibraryRefreshBridge\b.*?@end', header.decode(), re.S)
    require(len(bridge) == 1 and 'runOnComplete:' in bridge[0] and re.search(r'\b[A-Za-z_]\w*Boolean\s*\*', bridge[0])
            and 'swift_name("KotlinBoolean")' in header.decode() and 'run(onComplete:)' in bridge[0]
            and bridge[0].count('(^') >= 2, 'Actual exported bridge/cancel shape needs review')
    return {'original': a, 'exported': b, 'frameworkDirectory': str(exported), 'staticArchive': True,
            'headerSha256': hashlib.sha256(header).hexdigest(), 'moduleMapSha256': hashlib.sha256(module).hexdigest(),
            'actualBridgeDeclaration': bridge[0]}


def swift_command(raw, filelist, framework_dir, module):
    rows = []
    for line in raw.splitlines():
        if str(filelist) not in line or 'swiftc' not in line:
            continue
        tokens = shlex.split(line)
        # Xcode also echoes this argv in Compilation-Requirements/Compilation descriptions.
        # Only the actual driver envelope for this exact module/file list is invocation evidence.
        if (len(tokens) > 4 and tokens[:2] == ['builtin-SwiftDriver', '--']
                and Path(tokens[2]).is_absolute() and Path(tokens[2]).name == 'swiftc'
                and tokens.count('-module-name') == 1 and tokens[tokens.index('-module-name') + 1] == module
                and '@' + str(filelist) in tokens):
            rows.append(tokens)
    require(len(rows) == 1, 'No unique actual Swift driver invocation for ' + module)
    frameworks = search_paths(rows[0], '-F')
    require(any(same_path(n, Path(framework_dir).parent) for n in frameworks),
            'Actual Swift compiler did not search the fresh exported framework')
    return {'module': module, 'argv': rows[0], 'frameworkSearchPaths': frameworks, 'headerSearchPaths': search_paths(rows[0], '-I')}


def swift_inputs(c, raw, fw):
    folder = c['run'] / 'work/DerivedData/Build/Intermediates.noindex/iosApp.build/Debug-iphonesimulator/iosApp.build'
    lists = list((folder / 'Objects-normal/arm64').glob('*.SwiftFileList'))
    require(len(lists) == 1 and str(lists[0]) in raw, 'Missing/ambiguous actual shipping SwiftFileList invocation')
    names = shlex.split(c['h'].bytes_of(lists[0], 65536).decode())
    expected = {str(c['roots']['app'] / n) for n in c['pins']['host']['inputs'] if n.endswith('.swift')}
    extra = set(names) - expected
    require(len(expected) == 15 and len(names) == len(set(names)) and expected <= set(names)
            and extra <= {str(folder / 'DerivedSources/GeneratedAssetSymbols.swift')}, 'Pruned/extra shipping Swift inputs')
    return {'fileList': str(lists[0]), 'sha256': c['e'].sha(lists[0]), 'shippingInputs': sorted(expected),
            'compiler': swift_command(raw, lists[0], fw['frameworkDirectory'], 'Kira'),
            'ordinaryXcodeGeneratedInputs': {n: c['e'].sha(Path(n)) for n in sorted(extra)}}


def built_plist(c, s):
    bundle = Path(s['TARGET_BUILD_DIR']) / s['WRAPPER_NAME']
    path = bundle / 'Info.plist'
    data = c['h'].bytes_of(path, 65536)
    info = plistlib.loads(data)
    require(info['CFBundleIdentifier'] == 'me.manga.kira' and info['CFBundleExecutable'] == 'Kira'
            and info['CFBundlePackageType'] == 'APPL' and info['MinimumOSVersion'] == '15.0'
            and info['UIBackgroundModes'] == ['fetch', 'processing', 'remote-notification']
            and info['BGTaskSchedulerPermittedIdentifiers'] == TASK_IDS, 'Wrong incorporated host plist')
    example = c['h'].bytes_of(c['roots']['app'] / c['h'].EXAMPLE, 65536)
    require(c['h'].bytes_of(bundle / 'GoogleService-Info.plist', 65536) == example, 'Built Firebase example mismatch')
    return {'path': str(path), 'sha256': hashlib.sha256(data).hexdigest(), 'identity': {
        k: info[k] for k in ('CFBundleIdentifier', 'CFBundleExecutable', 'CFBundlePackageType',
                            'MinimumOSVersion', 'UIBackgroundModes', 'BGTaskSchedulerPermittedIdentifiers')},
        'firebaseExampleSha256': hashlib.sha256(example).hexdigest()}


def linker_tokens(c, s, fw, raw):
    bundle = Path(s['TARGET_BUILD_DIR']) / s['WRAPPER_NAME']
    allowed = {str(bundle / 'Kira'), str(bundle / 'Kira.debug.dylib')}
    direct = Path(fw['frameworkDirectory']) / 'ComposeApp'
    found = []
    for line in raw.splitlines():
        if 'ComposeApp' not in line or ' -o ' not in line:
            continue
        tokens = shlex.split(line)
        framework = any(v == '-framework' and tokens[i + 1] == 'ComposeApp' for i, v in enumerate(tokens[:-1]))
        direct_input = any(same_path(n, direct) for n in tokens)
        if (tokens and Path(tokens[0]).name in ('clang', 'clang++') and (framework or direct_input)
                and tokens[tokens.index('-o') + 1] in allowed):
            found.append(tokens)
    require(len(found) == 1, 'No unique actual host linker command consuming ComposeApp')
    tokens = found[0]
    search = search_paths(tokens, '-F')
    require((any(same_path(n, Path(fw['frameworkDirectory']).parent) for n in search)
            or any(same_path(n, direct) for n in tokens)) and '-filelist' in tokens,
            'Unjoined host framework/file-list inputs')
    return tokens


def package_link_inputs(c, tokens, objects):
    names = [tokens[i + 1] for i, v in enumerate(tokens[:-1]) if v in ('-framework', '-weak_framework')]
    searches = search_paths(tokens, '-F')
    require(len(names) <= 128 and len(searches) <= 128, 'Host package framework input cap')
    rows = []
    for name in names:
        require(re.fullmatch(r'[A-Za-z0-9_.+-]+', name), 'Invalid actual framework name')
        for folder in searches:
            path = Path(folder) / (name + '.framework') / name
            if path.is_file():
                actual = path.resolve()
                if actual.is_relative_to(c['run'] / 'work'):
                    rows.append({'framework': name, 'linkSearchPath': str(path),
                                 'output': c['e'].fingerprint(actual, c['end'])})
                break  # Preserve real -F priority; never substitute a later same-name framework.
    firebase_objects = [n for n in objects if 'firebase' in Path(n).name.lower()]
    require(firebase_objects or any(r['framework'].lower().startswith('firebase') for r in rows),
            'No actual Firebase object/framework input joined to the host link')
    return {'ownedFrameworkInputs': rows, 'firebaseObjectInputs': firebase_objects,
            'otherSystemInputs': 'Actual link argv retained; no system-library byte equivalence claimed'}


def host_binary(c, s, fw, raw):
    tokens = linker_tokens(c, s, fw, raw)
    linked = Path(tokens[tokens.index('-o') + 1])
    binary = Path(s['TARGET_BUILD_DIR']) / s['WRAPPER_NAME'] / s['EXECUTABLE_NAME']
    filelist = Path(tokens[tokens.index('-filelist') + 1])
    require(filelist.is_relative_to(c['run'] / 'work/DerivedData'), 'Unowned linker file list')
    objects = shlex.split(c['h'].bytes_of(filelist, 131072).decode())
    require(0 < len(objects) <= 512 and all(Path(n).is_relative_to(c['run'] / 'work/DerivedData') for n in objects),
            'Unknown/oversized host object inputs')
    c['owner'].inspect_macho(c['commands'], binary, 'host-binary', c['end'])
    c['owner'].inspect_macho(c['commands'], linked, 'host-linked-binary', c['end'])
    return {'executable': c['e'].fingerprint(binary, c['end'], 1073741824),
        'actualStaticLinkMachO': c['e'].fingerprint(linked, c['end'], 1073741824), 'linkArgv': tokens,
        'linkFileList': {'path': str(filelist), 'sha256': c['e'].sha(filelist)},
        'objectInputs': [c['e'].fingerprint(Path(n), c['end']) for n in objects],
        'packageLinkInputs': package_link_inputs(c, tokens, objects),
        'debugDylibModel': linked != binary, 'dynamicComposeAppEntryRequired': False}


def resolved_files(c):
    roots = [c['roots']['app'] / c['h'].PROJECT] + [c['roots']['app'] / n / 'build' for n in c['e'].MODULES]
    result = []
    for root in roots:
        for path in root.rglob('Package.resolved'):
            require(time.monotonic() < c['end'] and len(result) < 32, 'SwiftPM resolution evidence cap/deadline')
            result.append(path)
    require(result, 'No actual resolved SwiftPM identities')
    return sorted(set(result))


def resolution(c, path):
    data = c['h'].bytes_of(path, 262144)
    model = json.loads(data, object_pairs_hook=c['unique'])
    require(model.get('version') in (2, 3) and isinstance(model.get('pins'), list) and len(model['pins']) <= 64,
            'Unsupported actual Package.resolved shape; retain failure, do not invent schema')
    for pin in model['pins']:
        url = urlsplit(pin['location'])
        require(url.scheme == 'https' and url.hostname and not url.username and not url.password and
                not url.query and not url.fragment and re.fullmatch(r'[0-9a-f]{40}', pin['state']['revision']),
                'Nonpublic/nonimmutable SwiftPM input')
    return {'path': str(path), 'sha256': hashlib.sha256(data).hexdigest(), 'schemaVersion': model['version'], 'pins': model['pins']}


def package_checkouts(c):
    root = c['run'] / 'work/SourcePackages/checkouts'
    require(root.is_dir() and root.resolve() == root, 'Missing owned SwiftPM checkout inputs')
    rows = []
    for child in sorted(root.iterdir()):
        require(len(rows) < 64 and child.is_dir() and child.resolve() == child, 'Invalid/too many package checkouts')
        manifest = child / 'Package.swift'
        revision = c['commands'].call(['/usr/bin/git', '-C', str(child), 'rev-parse', 'HEAD'],
            'package-head-' + str(len(rows)), seconds=10, end=c['end'], cleaning=c.get('evidenceCleaning', True)).strip()
        require(re.fullmatch(r'[0-9a-f]{40}', revision), 'Invalid actual package revision')
        rows.append({'directory': child.name, 'revision': revision, 'manifestSha256': c['e'].sha(manifest)})
    require(rows, 'No real package checkouts')
    return rows


def packages(c):
    records = [resolution(c, n) for n in resolved_files(c)]
    pins = [pin for row in records for pin in row['pins']]
    firebase = [pin for pin in pins if pin['identity'] == 'firebase-ios-sdk']
    require(firebase and all(pin['state'].get('version') == '12.15.0' and
        pin['location'].removesuffix('.git') == 'https://github.com/firebase/firebase-ios-sdk' for pin in firebase),
        'The declared exact Firebase12.15.0 closure was not used')
    checkouts = package_checkouts(c)
    require(all(any(row['directory'].lower() == pin['identity'].lower() and
        row['revision'] == pin['state']['revision'] for pin in pins) for row in checkouts),
        'Checkout identity/revision not joined to resolved package inputs')
    result = {'resolutions': records, 'checkouts': checkouts, 'intake': 'normal public resolution inside the sole host build',
              'transitiveAuthentication': 'Actual resolved identities; not previously authenticated package byte equivalence'}
    c['e'].save(c['reports'] / 'swiftpm.json', result)
    return result


def prove(c, proof):
    app = c['e'].read(c['reports'] / 'app-tasks.json')
    raw, selected = log(c), settings(app, c)
    result = {'status': 'HOST_EVIDENCE_INCOMPLETE', 'testsExecuted': 0, 'settings': selected}
    stages = [('producerJoins', lambda: producer_joins(c, proof, app)),
        ('framework', lambda: framework(c, app, selected)), ('swift', lambda: swift_inputs(c, raw, result['framework'])),
        ('builtPlist', lambda: built_plist(c, selected)),
        ('host', lambda: host_binary(c, selected, result['framework'], raw)), ('swiftpm', lambda: packages(c))]
    try:
        for name, operation in stages:
            result['stage'] = name
            c['e'].save(c['reports'] / 'host-proof.json', result)
            result[name] = operation()
        bundle = c['run'] / 'work/host-build.xcresult'
        c['e'].save(c['reports'] / 'build-result.json', {'xcresult': c['e'].fingerprint(bundle, c['end']),
            'schemaAdjudication': 'NOT_PERFORMED; build-only, no invented test/schema credit', 'testsExecuted': 0})
        result['status'] = 'INDEPENDENT_HOST_RESULT_REVIEW_REQUIRED'
        return result
    except Exception as error:
        result['error'] = str(error)[:500]
        raise
    finally:
        c['e'].save(c['reports'] / 'host-proof.json', result)
