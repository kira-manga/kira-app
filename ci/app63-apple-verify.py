"""Existing Apple evidence helper, scoped to Native53 and locked unsigned host inputs."""
from collections import Counter
import difflib
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import plistlib
import re
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
    assert all(re.fullmatch('[0-9a-f]{40}', selected['app'][key]) for key in ('sha', 'tree')), 'Source is not bound'
    assert source == [selected['app']['sha'], selected['app']['tree']]
    assert source[0] == os.environ['SOURCE_SHA']
    identities = [(row['task'], row['class'], method)
                  for row in selected['suites'] for method in row['methods']]
    assert len(identities) == len(set(identities)) == selected['native_cases'] == 53
    assert Counter(identity[0] for identity in identities) == {
        ':platform:iosSimulatorArm64Test': 34, ':data:iosSimulatorArm64Test': 19}
    assert selected['native_module_counts'] == {'platform': 34, 'data': 19}
    for row in selected['suites']:
        assert sha(app / row['source']) == row['sourceSha256']
        text = (app / row['source']).read_text()
        assert re.findall(r'@Test\s+fun (\w+)\(', text) == row['methods']
    for relative, digest in selected['swiftpm_inputs'].items():
        assert sha(app / relative) == digest


def swiftpm_negatives():
    # Fixed existing-helper negatives, in this disposable checkout only. The original inode is
    # retained outside the source path and atomically restored even on an assertion/CLI failure.
    lock = app / 'iosApp/Package.resolved'
    generated = app / 'iosApp/iosApp.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved'
    project = app / 'iosApp/iosApp.xcodeproj/project.pbxproj'
    backup = run / 'reviewed-Package.resolved.before-negatives'
    selected = json.loads(Path('control/ci/app63-native-tests.json').read_text())
    assert lock.is_file() and not lock.is_symlink()
    assert not backup.exists() and not backup.is_symlink()
    assert not generated.exists() and not generated.is_symlink()
    raw, mode, project_sha = lock.read_bytes(), stat.S_IMODE(lock.stat().st_mode), sha(project)
    digest = hashlib.sha256(raw).hexdigest()
    assert digest == selected['swiftpm_inputs']['iosApp/Package.resolved']
    command = ['/usr/bin/ruby', 'scripts/release/verify-toolchain-inputs.rb', '--restore-swiftpm']
    cases, error, restored = [], None, False
    lock.rename(backup)
    try:
        for case, reason in (
            ('missing', 'reviewed shipping SwiftPM lock is missing, oversized, or symlinked'),
            ('stale', 'shipping SwiftPM lock digest mismatch'),
        ):
            if case == 'stale':
                with lock.open('xb') as stream:
                    stream.write(raw + b'\n')
                lock.chmod(mode)
            result = subprocess.run(command, cwd=app, stdin=subprocess.DEVNULL,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
            (reports / f'swiftpm-{case}.stdout.log').write_bytes(result.stdout)
            (reports / f'swiftpm-{case}.stderr.log').write_bytes(result.stderr)
            unchanged = (not lock.exists() and not lock.is_symlink()) if case == 'missing' else (
                not lock.is_symlink() and lock.read_bytes() == raw + b'\n')
            refused = (result.returncode == 1 and result.stdout == b'' and
                       result.stderr == ('Toolchain input verification failed: ' + reason + '\n').encode() and
                       unchanged and not generated.exists() and not generated.is_symlink())
            cases.append({'case': case, 'command': command, 'exit': result.returncode,
                          'status': 'PASS' if refused else 'FAIL', 'rejected_input_unchanged': unchanged,
                          'generated_lock_absent': not generated.exists() and not generated.is_symlink()})
            assert refused, 'Existing helper did not refuse the exact invalid shipping lock'
    except Exception as failure:
        error = f'{type(failure).__name__}: {failure}'
    finally:
        try:
            backup.replace(lock)
            restored = (lock.read_bytes() == raw and stat.S_IMODE(lock.stat().st_mode) == mode and
                        sha(project) == project_sha and not backup.exists())
        except Exception as failure:
            error = f'{error or ""}; restoration: {type(failure).__name__}: {failure}'
        qualified = len(cases) == 2 and all(case['status'] == 'PASS' for case in cases) and restored and error is None
        save('swiftpm-negative-proof.json', {'status': 'PASS' if qualified else 'FAIL', 'cases': cases,
             'error': error, 'canonical_bytes_and_mode_restored': restored, 'canonical_sha256': digest,
             'scope': 'Actual shipping helper refusal before any Xcode/protected-input command; no mocked resolver'})
    assert qualified, 'Shipping lock negative controls or restoration failed'


def swiftpm():
    phase = sys.argv[2]
    assert phase in ('preflight', 'host')
    selected = json.loads(Path('control/ci/app63-native-tests.json').read_text())
    canonical = app / 'iosApp/Package.resolved'
    generated = app / 'iosApp/iosApp.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved'
    assert canonical.is_file() and not canonical.is_symlink()
    assert generated.is_file() and not generated.is_symlink()
    raw = canonical.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    assert digest == selected['swiftpm_inputs']['iosApp/Package.resolved']
    assert generated.read_bytes() == raw
    assert sha(app / 'iosApp/iosApp.xcodeproj/project.pbxproj') == json.loads(
        (reports / 'project.json').read_text())['augmented_sha256']
    assert (reports / 'swiftpm-preflight-status.txt').read_text() == 'command_exit=0\n'
    for relative, expected in selected['swiftpm_inputs'].items():
        assert sha(app / relative) == expected
    save(f'swiftpm-{phase}-proof.json', {'status': 'PASS', 'phase': phase,
         'canonical_and_generated_lock_sha256': digest, 'bytes': len(raw),
         'pins': len(json.loads(raw)['pins']), 'source_sha': os.environ['SOURCE_SHA'],
         'helper_sha256': selected['swiftpm_inputs']['scripts/release/verify-toolchain-inputs.rb'],
         'preflight_script_sha256': sha(reports / 'swiftpm-preflight-command.sh'),
         'preflight_command_exit': 0, 'preflight_log': 'swiftpm-preflight.log',
         'host_command_status': 'host-status.txt' if phase == 'host' else 'NOT_RUN_YET',
         'scope': 'Real supported-Xcode locked resolve and byte-stable generated input; host outcome is independent; no signing/release or Gradle graph integrity claim'})


def swiftpm_restore():
    # EXIT-trap fallback if the negative-check Python process was interrupted before its finally.
    backup = run / 'reviewed-Package.resolved.before-negatives'
    if backup.exists() or backup.is_symlink():
        selected = json.loads(Path('control/ci/app63-native-tests.json').read_text())
        assert backup.is_file() and not backup.is_symlink()
        assert sha(backup) == selected['swiftpm_inputs']['iosApp/Package.resolved']
        backup.replace(app / 'iosApp/Package.resolved')


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
        'status': 'CAPTURED_RAW_ONLY' if captured else 'NOT_PRODUCED',
        'scope': 'Diagnostic capture only; actual App61 evidence is in swiftpm-*-proof.json and command statuses',
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
    assert len(expected) == 53
    selected_suites = {(row['task'].split(':')[1], row['class']): set(row['methods'])
                       for row in selection['suites']}
    prefix, suffix = 'iosSimulatorArm64Test.', '[iosSimulatorArm64]'
    actual = []
    cases, parse_errors, identity_errors = [], [], []
    for module in ('platform', 'data'):
        files = sorted((app / module / 'build/test-results/iosSimulatorArm64Test').glob('*.xml'))
        for file in files:
            try:
                root = ET.parse(file).getroot()
            except (OSError, ET.ParseError) as error:
                parse_errors.append({'module': module, 'file': str(file.relative_to(app)), 'error': str(error)})
                continue
            raw_suite = root.get('name', '')
            suite_class = raw_suite.removeprefix(prefix)
            wanted_methods = selected_suites.get((module, suite_class), set())
            suite_cases = list(root.iter('testcase'))
            errors = []
            if (root.tag != 'testsuite' or not raw_suite.startswith(prefix) or not wanted_methods or
                    file.name != 'TEST-' + raw_suite + '.xml'):
                errors.append('module/file/suite identity does not match the exact selected Native task prefix')
            counts = {'tests': len(suite_cases),
                      'failures': sum(case.find('failure') is not None for case in suite_cases),
                      'errors': sum(case.find('error') is not None for case in suite_cases),
                      'skipped': sum(case.find('skipped') is not None for case in suite_cases)}
            if any(root.get(key) != str(value) for key, value in counts.items()):
                errors.append('suite outcome counts disagree with retained testcase outcomes')
            for case in suite_cases:
                raw_name = case.get('name', '')
                raw_class = case.get('classname', '')
                method = next((method for method in wanted_methods if raw_name == method + suffix), raw_name)
                identity = (module, raw_class.removeprefix(prefix), method)
                if raw_class != raw_suite or method not in wanted_methods or raw_name != method + suffix:
                    errors.append({'raw_class': raw_class, 'raw_name': raw_name,
                                   'error': 'testcase class or exact selected method/target suffix mismatch'})
                actual.append(identity)
                status = ('FAIL' if any(case.find(k) is not None for k in ('failure', 'error'))
                          else 'SKIPPED' if case.find('skipped') is not None else 'PASS')
                cases.append({'identity': identity, 'raw_name': raw_name, 'raw_class': raw_class,
                              'raw_suite': raw_suite, 'status': status,
                              'file': str(file.relative_to(app))})
            if errors:
                identity_errors.append({'module': module, 'file': str(file.relative_to(app)), 'errors': errors})
    modules = {}
    for module in ('platform', 'data'):
        wanted = {identity for identity in expected if identity[0] == module}
        observed = [identity for identity in actual if identity[0] == module]
        outcomes = [case for case in cases if case['identity'][0] == module]
        errors = [error for error in parse_errors if error['module'] == module]
        mapping_errors = [error for error in identity_errors if error['module'] == module]
        qualified = (len(observed) == len(set(observed)) == len(wanted) and set(observed) == wanted
                     and all(case['status'] == 'PASS' for case in outcomes) and not errors and not mapping_errors)
        modules[module] = {
            'status': 'PASS' if qualified else 'NOT_RUN' if not observed and not errors and not mapping_errors else 'FAIL',
            'expected_cases': len(wanted),
            'observed_cases': len(observed), 'passing_cases': sum(case['status'] == 'PASS' for case in outcomes),
            'failed_cases': sum(case['status'] == 'FAIL' for case in outcomes),
            'skipped_cases': sum(case['status'] == 'SKIPPED' for case in outcomes),
            'missing': sorted(wanted - set(observed)), 'unexpected': sorted(set(observed) - wanted),
            'duplicates': [identity for identity, count in Counter(observed).items() if count > 1],
            'parse_errors': errors, 'identity_errors': mapping_errors,
        }
    qualified = all(module['status'] == 'PASS' for module in modules.values())
    # Write exact partial outcomes before rejecting missing/failed/extra XML; no passing subset masks failure.
    save('native-proof.json', {'status': 'PASS' if qualified else 'FAIL', 'expected_cases': 53,
        'cases': actual, 'case_results': cases, 'modules': modules,
        'identity_mapping': {'exact_leading_class_prefix': prefix, 'exact_method_suffix': suffix,
                             'module_file_suite_case_agreement_required': True},
        'scope': 'Native platform34/data19; App24/57/77/78 and App23/26 increments; no physical device, OS wake, App70 privacy or SQL migration claim'})
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
 'host': host, 'native': native, 'lock': shipping_lock, 'cleanup': cleanup,
 'swiftpm-negatives': swiftpm_negatives, 'swiftpm': swiftpm,
 'swiftpm-restore': swiftpm_restore}[sys.argv[1]]()
