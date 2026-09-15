#!/usr/bin/env python3
"""Inspect only this fixed gate's real XML/logs; never resolve or run Gradle."""
import json
import os
from collections import Counter
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

os.umask(0o077)
APP = Path(os.environ['KIRA_SEVEN_APP_ROOT'])
OUT = Path(os.environ['KIRA_SEVEN_EVIDENCE'])
SCOPE = json.loads((APP / 'scripts/ci/second-seven-linux-scope.json').read_text())
EXPECTED = SCOPE['tests']
errors = []
observed = []
source_unchanged = xml_exact = completions_exact = image_runtime_exact = False


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def write_json(name, value):
    (OUT / name).write_text(json.dumps(value, indent=2) + '\n')


# Copy every XML under the four real task report directories, including unexpected XML.
# A missing report is not replaced with a synthetic successful suite.
for task in EXPECTED:
    try:
        module, name = task.rsplit(':', 1)
        report_root = APP / module.lstrip(':').replace(':', '/') / 'build/test-results' / name
        for source in report_root.rglob('*.xml'):
            try:
                target = OUT / 'reports' / source.relative_to(APP)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
            except Exception as error:
                errors.append(dict(operation='preserve-xml', file=str(source), error=str(error)))
    except Exception as error:
        errors.append(dict(operation='preserve-xml', task=task, error=str(error)))

try:
    after = {}
    for role in ('APP',):
        root = os.environ[f'KIRA_SEVEN_{role}_ROOT']
        def git(*args):
            return subprocess.check_output(['git', '-C', root, *args], text=True).strip()
        after[role.lower()] = dict(head=git('rev-parse', 'HEAD'), tree=git('rev-parse', 'HEAD^{tree}'),
            tracked_dirty=bool(git('status', '--porcelain', '--untracked-files=no')))
    write_json('source-after.json', after)
    source_unchanged = after == json.loads((OUT / 'source-before.json').read_text()) and \
        all(not source['tracked_dirty'] for source in after.values())
    require(source_unchanged, 'Exact bound source changed')
except Exception as error:
    errors.append(dict(operation='source-readback', error=str(error)))

# Record actual testcases FIRST, including failed/skipped/foreign cases and unexpected
# reports. Presence/PASS rejection must never turn partial execution into "zero tests".
expected_xml = {task.rsplit(':', 1)[0].lstrip(':').replace(':', '/') + '/build/test-results/' +
    task.rsplit(':', 1)[1] + '/TEST-' + klass + '.xml': (task, klass)
    for task, classes in EXPECTED.items() for klass in classes}
report_tasks = {task.rsplit(':', 1)[0].lstrip(':').replace(':', '/') + '/build/test-results/' +
    task.rsplit(':', 1)[1] + '/': task for task in EXPECTED}
xml_files = sorted((OUT / 'reports').rglob('*.xml'))
parsed_reports = []
report_observations = []
for path in xml_files:
    relative = str(path.relative_to(OUT / 'reports'))
    task = next((task for prefix, task in report_tasks.items() if relative.startswith(prefix)), None)
    suite = None
    children = []
    parse_error = None
    try:
        # Even an incomplete report can contain complete actual testcase elements.
        for event, element in ET.iterparse(path, events=('start', 'end')):
            if event == 'start' and suite is None:
                suite = element
            if event != 'end' or element.tag.rsplit('}', 1)[-1] != 'testcase':
                continue
            children.append(element)
            outcomes = [child.tag.rsplit('}', 1)[-1] for child in element
                if child.tag.rsplit('}', 1)[-1] in ('failure', 'error', 'skipped')]
            status = 'ERROR' if 'error' in outcomes else 'FAIL' if 'failure' in outcomes else \
                'SKIP' if 'skipped' in outcomes else 'PASS'
            observed.append(dict(report=relative, task=task, class_name=element.get('classname'),
                method=element.get('name'), status=status, outcomes=outcomes,
                selected_report=relative in expected_xml))
    except Exception as error:
        parse_error = str(error)
    parsed_reports.append((relative, suite, children, parse_error))
    report_observations.append(dict(report=relative, selected=relative in expected_xml,
        root_tag=suite.tag if suite is not None else None,
        declared=dict(suite.attrib) if suite is not None else {},
        observed_testcases=len(children), parse_error=parse_error))
write_json('observed-tests.json', observed)

# Only after all observations are retained, enforce exact whole-class nonempty PASS.
actual_xml = {relative for relative, _, _, _ in parsed_reports}
missing_xml = sorted(set(expected_xml) - actual_xml)
unexpected_xml = sorted(actual_xml - set(expected_xml))
xml_errors = []
if missing_xml or unexpected_xml:
    xml_errors.append(dict(operation='xml-evidence', error='Missing/extra selected class XML',
        missing=missing_xml, unexpected=unexpected_xml))
for relative, suite, children, parse_error in parsed_reports:
    try:
        require(parse_error is None, 'Malformed/partial XML: ' + str(parse_error))
        require(relative in expected_xml, 'Unexpected XML report; actual cases retained')
        _, klass = expected_xml[relative]
        require(suite is not None and suite.tag == 'testsuite' and children and
            suite.findall('testcase') == children and int(suite.get('tests', '-1')) == len(children) and
            all(int(suite.get(k, '-1')) == 0 for k in ('failures', 'errors', 'skipped')) and
            len({c.get('name') for c in children}) == len(children) and
            all(c.get('classname') == klass and c.get('name') and
                not any(child.tag.rsplit('}', 1)[-1] in ('failure', 'error', 'skipped')
                    for child in c) for c in children),
            'Selected class has zero/partial/failing/skipped/foreign/duplicate cases')
    except Exception as error:
        xml_errors.append(dict(operation='xml-evidence', report=relative, error=str(error)))
errors.extend(xml_errors)
xml_exact = not xml_errors

lines = []
try:
    lines = (OUT / 'gradle.log').read_text(errors='replace').splitlines()
except Exception as error:
    errors.append(dict(operation='task-evidence', error=str(error)))


def rows(label):
    marker = 'KIRA_SEVEN_' + label + ' '
    result = []
    for number, line in enumerate(lines, 1):
        if line.startswith(marker):
            try:
                row = json.loads(line[len(marker):])
                require(isinstance(row, dict), 'Expected an actual observation object')
                result.append(row)
            except Exception as error:
                errors.append(dict(operation='task-evidence', label=label, line=number, error=str(error)))
    return result


# Preserve all actual task/runtime observations before checking their success.
completions = rows('TASK_COMPLETE')
image = rows('IMAGE_RUNTIME_SELECTION')
write_json('task-completions.json', completions)
write_json('image-runtime-selection.json', image)
try:
    indexed = {(c['build'], c['path']): c for c in completions}
    required = [(build, task) for build, tasks in SCOPE['required_compilers'].items() for task in tasks] + \
        [('app', task) for task in EXPECTED]
    completions_exact = len(indexed) == len(completions) and all(key in indexed and
        indexed[key]['executed'] and indexed[key]['didWork'] and not indexed[key]['skipped'] and
        not indexed[key]['upToDate'] and not indexed[key]['noSource'] and indexed[key]['failure'] is None
        for key in required)
    require(completions_exact, 'Missing/nonexecuted/failed ordinary compiler or Test completion')
except Exception as error:
    errors.append(dict(operation='task-evidence', error=str(error)))
try:
    image_runtime_exact = len(image) == 1 and image[0]['build'] == 'app' and \
        image[0]['task'] == ':platform:desktopTest' and len(image[0]['artifacts']) == 4
    require(image_runtime_exact, 'Missing actual consumed Coil/Skiko observation')
except Exception as error:
    errors.append(dict(operation='task-evidence', error=str(error)))

normal_exits = False
try:
    normal_exits = all((OUT / name).read_text().split() == ['0', '0']
        for name in ('command-exits.txt', 'stop-exits.txt'))
    require(normal_exits, 'Gradle/timeout/stop/log-copy failure is not success')
except Exception as error:
    errors.append(dict(operation='command-exits', error=str(error)))

eligible = normal_exits and source_unchanged and xml_exact and completions_exact and image_runtime_exact and not errors
result = dict(status='RESULT_REVIEW_REQUIRED' if eligible else 'FAIL', normal_exits=normal_exits,
    source_unchanged=source_unchanged, xml_exact=xml_exact,
    selected_class_count=sum(map(len, EXPECTED.values())), selected_test_task_count=len(EXPECTED),
    observed_test_count=len(observed), observed_status_counts=dict(Counter(case['status'] for case in observed)),
    observed_class_count=len({case['class_name'] for case in observed if case['class_name']}),
    xml_reports=report_observations, missing_selected_xml=missing_xml, unexpected_xml=unexpected_xml,
    compiler_and_test_completions_exact=completions_exact,
    producer_inputs_status='NOT_EXECUTED_OUTSIDE_ORDINARY_FOLLOWUP_GRAPH', image_runtime_exact=image_runtime_exact,
    cleanup_boundary='Job-private Gradle stop; final scratch/process disposal belongs to the ephemeral hosted runner, not this verifier.',
    coverage='Only four Desktop Test tasks / 21 whole classes. Prior successful classes and omitted Engine/Compose compilers '
        'are not current execution. No static, Android/JNI/device, Apple, packaging or shipping qualification.', errors=errors)
write_json('result.json', result)
print(json.dumps(result))
sys.exit(0 if eligible else 1)
