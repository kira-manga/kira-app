"""Fixed-purpose Apple63 evidence; no product mutation, UI automation, or release operation."""
from collections import Counter
import difflib
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import plistlib
import signal
import stat
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile

app = Path(os.environ['APP'])
run = Path(os.environ['RUN'])
reports = Path('reports')


def save(name, value):
    (reports / name).write_text(json.dumps(value, indent=2) + '\n')


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def generator():
    with zipfile.ZipFile(run / 'xcodegen.zip') as archive:
        items = archive.infolist()
        assert len(items) == 47 and sum(i.file_size for i in items) == 14237480
        assert len({i.filename for i in items}) == len(items)
        for item in items:
            path = PurePosixPath(item.filename)
            assert not path.is_absolute() and '..' not in path.parts and '\\' not in item.filename
            mode = item.external_attr >> 16
            assert stat.S_IFMT(mode) in (stat.S_IFREG, stat.S_IFDIR)
        archive.extractall(run / 'xcodegen')
    binary = run / 'xcodegen/xcodegen.artifactbundle/xcodegen-2.46.0-macosx/bin/xcodegen'
    assert sha(binary) == '8774da746668bc18fe74e54cbaf10f2631a1fb05947cd374179aa912f14f99db'
    binary.chmod(0o700)
    (run / 'xcodegen.zip').unlink()


def selection():
    selected = json.loads(Path('control/ci/app63-native-tests.json').read_text())
    source = subprocess.check_output(['git', '-C', str(app), 'rev-parse', 'HEAD', 'HEAD^{tree}'], text=True).splitlines()
    assert source == [selected['app']['sha'], selected['app']['tree']]
    assert source[0] == os.environ['SOURCE_SHA']
    identities = [(row['task'], row['class'], method)
                  for row in selected['suites'] for method in row['methods']]
    assert len(identities) == len(set(identities)) == 42
    assert Counter(identity[0] for identity in identities) == {
        ':platform:iosSimulatorArm64Test': 30, ':data:iosSimulatorArm64Test': 12}
    for row in selected['suites']:
        assert sha(app / row['source']) == row['sourceSha256']


def project():
    path = app / 'iosApp/iosApp.xcodeproj/project.pbxproj'
    before = path.read_text()
    model = json.loads(subprocess.check_output(['plutil', '-convert', 'json', '-o', '-', str(path)]))
    old = 'cd "$SRCROOT/.."\n./gradlew :composeApp:embedAndSignAppleFrameworkForXcode\n'
    phases = [(k, v) for k, v in model['objects'].items()
              if v.get('isa') == 'PBXShellScriptBuildPhase' and v.get('name') == 'Build & embed Kotlin/Compose framework']
    assert len(phases) == 1 and phases[0][1]['shellScript'] == old
    new = old.rstrip('\n') + ' --include-build "$ENGINE" --no-daemon --no-parallel --max-workers=1' \
        ' --no-build-cache --no-configuration-cache --console=plain --stacktrace' \
        ' -Pkotlin.compiler.execution.strategy=in-process -PkiraUseMavenLocal=false' \
        ' -Dorg.gradle.vfs.watch=false "-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g"\n'
    old_literal = 'shellScript = ' + json.dumps(old) + ';'
    assert before.count(old_literal) == 1
    after = before.replace(old_literal, 'shellScript = ' + json.dumps(new) + ';', 1)
    path.write_text(after)
    changed = json.loads(subprocess.check_output(['plutil', '-convert', 'json', '-o', '-', str(path)]))
    assert changed['objects'][phases[0][0]]['shellScript'] == new
    changed['objects'][phases[0][0]]['shellScript'] = old
    assert changed == model
    (reports / 'generated-project.patch').write_text(''.join(difflib.unified_diff(
        before.splitlines(True), after.splitlines(True), fromfile='pristine/project.pbxproj', tofile='validation/project.pbxproj')))
    save('project.json', {'only_embed_argv_changed': True, 'augmented_sha256': sha(path), 'model': model})


def settings():
    rows = {}
    for config in ('Debug', 'Release'):
        targets = json.loads((reports / f'settings-{config}.json').read_text())
        selected = [t['buildSettings'] for t in targets if t['target'] == 'iosApp']
        assert len(selected) == 1
        rows[config] = selected[0]
    debug, release = rows['Debug'], rows['Release']
    assert debug['PRODUCT_BUNDLE_IDENTIFIER'] == 'me.manga.kira.debug'
    assert debug.get('DEVELOPMENT_TEAM', '') == ''
    assert debug['INFOPLIST_FILE'] == 'iosApp/Info-Debug.plist'
    assert debug['CODE_SIGN_ENTITLEMENTS'] == 'iosApp/iosApp-nopush.entitlements'
    assert 'DEBUG' in debug['SWIFT_ACTIVE_COMPILATION_CONDITIONS'].split()
    assert release['PRODUCT_BUNDLE_IDENTIFIER'] == 'me.manga.kira'
    assert release['DEVELOPMENT_TEAM'] == '7CGZ2343AA'
    assert release['INFOPLIST_FILE'] == 'iosApp/Info.plist'
    assert release['CODE_SIGN_ENTITLEMENTS'] == 'iosApp/iosApp.entitlements'
    assert 'DEBUG' not in release.get('SWIFT_ACTIVE_COMPILATION_CONDITIONS', '').split()
    save('settings-proof.json', {'status': 'PASS', 'effective_debug_release_settings': 'PASS',
         'inputs': ['settings-Debug.json', 'settings-Release.json']})


def host():
    built = run / 'DerivedData/Build/Products/Debug-iphonesimulator/Kira.app'
    info = plistlib.loads((built / 'Info.plist').read_bytes())
    assert info['CFBundleIdentifier'] == 'me.manga.kira.debug'
    assert info['CFBundleDisplayName'] == 'Kira Manga Debug'
    assert info['KiraFirebaseServicesEnabled'] is False
    assert info['FIREBASE_ANALYTICS_COLLECTION_DEACTIVATED'] is True
    for key in ('FirebaseCrashlyticsCollectionEnabled', 'FirebaseMessagingAutoInitEnabled',
                'FirebaseInAppMessagingAutomaticDataCollectionEnabled', 'FirebaseAppDelegateProxyEnabled'):
        assert info[key] is False
    assert not info.get('CFBundleURLTypes') and 'remote-notification' not in info['UIBackgroundModes']
    assert set(info['BGTaskSchedulerPermittedIdentifiers']) == {
        'me.manga.kira.debug.' + suffix for suffix in ('download.processing', 'download.continued', 'library.refresh')}
    assert not list(built.rglob('GoogleService-Info.plist'))
    assert plistlib.loads((app / 'iosApp/iosApp/iosApp-nopush.entitlements').read_bytes()) == {}
    binary = built / info['CFBundleExecutable']
    assert subprocess.check_output(['xcrun', 'lipo', '-archs', str(binary)], text=True).strip() == 'arm64'
    path = app / 'iosApp/iosApp.xcodeproj/project.pbxproj'
    assert sha(path) == json.loads((reports / 'project.json').read_text())['augmented_sha256']
    save('host-proof.json', {'unsigned_debug_host': 'PASS', 'settings_verification': 'SEPARATE_COMPONENT',
         'built_info': info, 'binary_sha256': sha(binary), 'firebase_plist_absent': True,
         'physical_coinstall_production_services': 'EXTERNAL_VERIFICATION_REQUIRED'})


def shipping_lock():
    # Retain only this shipping project's ordinary resolve output; no resolve or lock acceptance.
    path = app / 'iosApp/iosApp.xcodeproj/project.pbxproj'
    lock_path = 'iosApp/iosApp.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved'
    lock = app / lock_path
    captured = {}
    if lock.is_file():
        assert path.is_file(), 'Canonical lock exists without its generated project'
        raw = lock.read_bytes()
        (reports / 'shipping-Package.resolved').write_bytes(raw)
        captured = {'artifact': 'shipping-Package.resolved', 'bytes': len(raw),
                    'sha256': hashlib.sha256(raw).hexdigest()}
    source = subprocess.check_output(['git', '-C', str(app), 'rev-parse', 'HEAD', 'HEAD^{tree}'], text=True).splitlines()
    save('shipping-swiftpm-input.json', {
        'status': 'CAPTURED_UNVERIFIED' if captured else 'NOT_PRODUCED',
        'scope': 'Future App61 input only; no lock verification or acceptance',
        'path': lock_path, 'source_sha': source[0], 'source_tree': source[1],
        'project_path': str(path.relative_to(app)),
        'generated_project_sha256': sha(path) if path.is_file() else None,
        'project_spec_path': 'iosApp/project.yml', 'project_spec_sha256': sha(app / 'iosApp/project.yml'),
        'developer_dir': os.environ['DEVELOPER_DIR'], 'actual_tool_identity_record': 'identity.txt',
        **captured,
    })


def native():
    selection = json.loads(Path('control/ci/app63-native-tests.json').read_text())
    expected = {(row['task'].split(':')[1], row['class'], method)
                for row in selection['suites'] for method in row['methods']}
    assert len(expected) == 42
    actual = []
    cases, parse_errors = [], []
    for module in ('platform', 'data'):
        files = sorted((app / module / 'build/test-results/iosSimulatorArm64Test').glob('*.xml'))
        for file in files:
            try:
                root = ET.parse(file).getroot()
            except (OSError, ET.ParseError) as error:
                parse_errors.append({'module': module, 'file': str(file.relative_to(app)), 'error': str(error)})
                continue
            for case in root.iter('testcase'):
                raw_name = case.get('name', '')
                identity = (module, case.get('classname', ''), raw_name.split('[')[0])
                actual.append(identity)
                status = ('FAIL' if any(case.find(k) is not None for k in ('failure', 'error'))
                          else 'SKIPPED' if case.find('skipped') is not None else 'PASS')
                cases.append({'identity': identity, 'raw_name': raw_name, 'status': status,
                              'file': str(file.relative_to(app))})
    modules = {}
    for module in ('platform', 'data'):
        wanted = {identity for identity in expected if identity[0] == module}
        observed = [identity for identity in actual if identity[0] == module]
        outcomes = [case for case in cases if case['identity'][0] == module]
        errors = [error for error in parse_errors if error['module'] == module]
        qualified = (len(observed) == len(set(observed)) == len(wanted) and set(observed) == wanted
                     and all(case['status'] == 'PASS' for case in outcomes) and not errors)
        modules[module] = {
            'status': 'PASS' if qualified else 'NOT_RUN' if not observed and not errors else 'FAIL',
            'expected_cases': len(wanted),
            'observed_cases': len(observed), 'passing_cases': sum(case['status'] == 'PASS' for case in outcomes),
            'failed_cases': sum(case['status'] == 'FAIL' for case in outcomes),
            'skipped_cases': sum(case['status'] == 'SKIPPED' for case in outcomes),
            'missing': sorted(wanted - set(observed)), 'unexpected': sorted(set(observed) - wanted),
            'duplicates': [identity for identity, count in Counter(observed).items() if count > 1],
            'parse_errors': errors,
        }
    qualified = all(module['status'] == 'PASS' for module in modules.values())
    # Write exact partial outcomes before rejecting missing/failed/extra XML; no passing subset masks failure.
    save('native-proof.json', {'status': 'PASS' if qualified else 'FAIL', 'expected_cases': 42,
        'cases': actual, 'case_results': cases, 'modules': modules,
        'scope': 'Native platform30/data12, including actual Okio backend3; no provider/device/OS or SQL migration claim'})
    assert qualified, modules


def cleanup():
    before = json.loads((reports / 'simulators-before.json').read_text())
    old = {d['udid']: d for group in before['devices'].values() for d in group}
    after = json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', 'devices', '--json']))
    stopped, deleted = [], []
    for d in (d for group in after['devices'].values() for d in group):
        uid = d['udid']
        if d['state'] == 'Booted' and old.get(uid, {}).get('state') != 'Booted':
            subprocess.run(['xcrun', 'simctl', 'shutdown', uid], check=True); stopped.append(uid)
        if uid not in old:
            subprocess.run(['xcrun', 'simctl', 'delete', uid], check=True); deleted.append(uid)
    initial = {int(line.split(None, 1)[0]) for line in (reports / 'processes-before.txt').read_text().splitlines()}
    owned = []
    lines = subprocess.check_output(['ps', '-axo', 'pid=,comm='], text=True).splitlines()
    for line in lines:
        pid, command = line.strip().split(None, 1)
        if int(pid) not in initial and Path(command).name in ('XCBBuildService', 'SwiftDriver', 'swift-frontend'):
            try:
                os.kill(int(pid), signal.SIGTERM); owned.append(int(pid))
            except ProcessLookupError:
                pass
    # These are new compiler services on this job's disposable runner, never preexisting processes.
    deadline = time.monotonic() + 10
    while owned and time.monotonic() < deadline:
        live = {int(p) for p in subprocess.check_output(['ps', '-axo', 'pid='], text=True).split()}
        if not set(owned) & live:
            break
        time.sleep(0.2)
    live = {int(p) for p in subprocess.check_output(['ps', '-axo', 'pid='], text=True).split()}
    assert not set(owned) & live, 'Owned compiler service did not exit; retain outputs'
    commands = subprocess.check_output(['ps', '-axo', 'command='], text=True)
    assert not any('GradleDaemon' in line and str(run) in line for line in commands.splitlines())
    save('cleanup.json', {'newly_booted_stopped': stopped, 'created_simulators_deleted': deleted,
                          'new_compiler_service_pids_stopped': owned, 'owned_gradle_daemons_absent': True})


{'selection': selection, 'generator': generator, 'project': project, 'settings': settings,
 'host': host, 'native': native, 'lock': shipping_lock, 'cleanup': cleanup}[sys.argv[1]]()
