"""Direct framework product proof, reduced from the reviewed shipping-host adapter; no Xcode build."""
import hashlib
import os
from pathlib import Path
import stat
import sys
import time

LINK = ':composeApp:linkDebugFrameworkIosSimulatorArm64'
OWN_MAIN = ':composeApp:compileKotlinIosSimulatorArm64'


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


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
    return {'mainAndInteropInputs': expected, 'ownMainInclude': own, 'originalEngine': neutral,
            'crashkios': [e.fingerprint(n, c['end']) for n in crash]}


def metadata_observation(label, path, cap):
    # Metadata only: collect both rows even when one path cannot be inspected.
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


def prove(c, proof):
    e = c['e']
    app = e.read(c['reports'] / 'app-tasks.json')
    result = {'status': 'FRAMEWORK_EVIDENCE_INCOMPLETE', 'task': LINK, 'testsExecuted': 0,
              'xcodeHostBuilt': False, 'scope': 'Direct unsigned Debug simulator framework only; no Swift host/UI credit'}
    e.save(c['reports'] / 'framework-proof.json', result)
    result['producerJoins'] = producer_joins(c, proof, app)
    original = Path(app['observed'][LINK]['output']['file'])
    expected = c['roots']['app'] / 'composeApp/build/bin/iosSimulatorArm64/debugFramework/ComposeApp.framework'
    require(original == expected and original.resolve() == original and not original.is_symlink(),
            'Different/aliased direct framework output')
    binary = e.fingerprint(original / 'ComposeApp', c['end'], 1073741824)
    e.save(c['reports'] / 'framework-metadata.json', {
        'phase': 'before-bounded-header-module-reads', 'causeAdjudication': 'NOT_PERFORMED',
        'binaryFingerprint': binary,
        'metadataObservations': [
            metadata_observation('original-header', original / 'Headers/ComposeApp.h', 4194304),
            metadata_observation('original-module-map', original / 'Modules/module.modulemap', 65536),
        ],
    })
    with (original / 'ComposeApp').open('rb') as stream:
        require(stream.read(8) == b'!<arch>\n', 'Expected real static Native framework archive')
    architecture = c['commands'].call(['/usr/bin/xcrun', 'lipo', '-archs', str(original / 'ComposeApp')],
        'framework-arch', end=c['end'], cleaning=c.get('evidenceCleaning', False)).strip()
    require(architecture == 'arm64', 'Wrong direct framework architecture')
    header = bytes_of(original / 'Headers/ComposeApp.h', 4194304)
    module = bytes_of(original / 'Modules/module.modulemap', 65536)
    require(header and b'@interface' in header and b'framework module "ComposeApp"' in module
            and b'umbrella header "ComposeApp.h"' in module, 'Invalid normal framework headers/module map')
    retained(c, 'ComposeApp.h', header)
    retained(c, 'ComposeApp.modulemap', module)
    result['framework'] = {'output': app['observed'][LINK]['output'], 'binary': binary,
        'staticArchive': True, 'architecture': architecture,
        'headerSha256': hashlib.sha256(header).hexdigest(), 'moduleMapSha256': hashlib.sha256(module).hexdigest()}
    result['status'] = 'FRAMEWORK_RESULT_REVIEW_REQUIRED'
    e.save(c['reports'] / 'framework-proof.json', result)
    return result
