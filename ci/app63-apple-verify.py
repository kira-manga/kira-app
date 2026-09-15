"""Exact corrected iOS retained-page retry; unchanged passing cases are carried."""
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

app = Path(os.environ['APP'])
run = Path(os.environ['RUN'])
reports = Path('reports')
# Literal owning source/output roots: data/download must never collapse to data.
TASK_MODULES = {':data:iosSimulatorArm64Test': 'data'}


def save(name, value):
    (reports / name).write_text(json.dumps(value, indent=2) + '\n')

def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def selection():
    selected = json.loads(Path('control/ci/app63-native-tests.json').read_text())
    source = subprocess.check_output(['git', '-C', str(app), 'rev-parse', 'HEAD', 'HEAD^{tree}'], text=True).splitlines()
    assert all(re.fullmatch('[0-9a-f]{40}', selected['app'][key]) for key in ('sha', 'tree')), 'Source is not bound'
    assert source == [selected['app']['sha'], selected['app']['tree']]
    assert source[0] == os.environ['SOURCE_SHA']
    assert selected['task_module_mapping'] == TASK_MODULES
    assert selected['compile_task'] is None
    identities = [(TASK_MODULES[row['task']], row['class'], method)
                  for row in selected['suites'] for method in row['methods']]
    assert len(identities) == len(set(identities)) == selected['native_cases'] == 1
    assert Counter(identity[0] for identity in identities) == selected['native_module_counts'] == {'data': 1}
    for row in selected['suites']:
        assert row['source'].startswith(TASK_MODULES[row['task']] + '/')
        assert sha(app / row['source']) == row['sourceSha256']
        declared = re.findall(r'@Test\s+fun (\w+)\(', (app / row['source']).read_text())
        assert all(declared.count(method) == 1 for method in row['methods'])

def native():
    selection = json.loads(Path('control/ci/app63-native-tests.json').read_text())
    expected = {(TASK_MODULES[row['task']], row['class'], method)
                for row in selection['suites'] for method in row['methods']}
    assert len(expected) == 1
    selected_suites = {(TASK_MODULES[row['task']], row['class']): set(row['methods'])
                       for row in selection['suites']}
    prefix, suffix = 'iosSimulatorArm64Test.', '[iosSimulatorArm64]'
    actual = []
    cases, parse_errors, identity_errors = [], [], []
    for module in ('data',):
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
    for module in ('data',):
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
    save('native-proof.json', {'status': 'PASS' if qualified else 'FAIL', 'expected_cases': 1,
        'cases': actual, 'case_results': cases, 'modules': modules,
        'identity_mapping': {'exact_leading_class_prefix': prefix, 'exact_method_suffix': suffix,
                             'module_file_suite_case_agreement_required': True},
        'scope': 'One corrected retained-page retry; no unchanged passing Native/Compose/Swift replay, physical device, OS wake or SQL migration claim'})
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


{'selection': selection, 'native': native, 'cleanup': cleanup}[sys.argv[1]]()
