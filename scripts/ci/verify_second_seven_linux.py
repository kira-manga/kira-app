#!/usr/bin/env python3
"""Inspect only this fixed gate's real XML/logs; never resolve or run Gradle."""
import json
import os
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
source_unchanged = xml_exact = completions_exact = image_runtime_exact = producer_exact = False


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def write_json(name, value):
    (OUT / name).write_text(json.dumps(value, indent=2) + '\n')


# Copy every XML under the five real task report directories, including unexpected XML.
# A missing report is not replaced with a synthetic successful suite.
try:
    for task in EXPECTED:
        module, name = task.rsplit(':', 1)
        report_root = APP / module.lstrip(':').replace(':', '/') / 'build/test-results' / name
        for source in report_root.rglob('*.xml'):
            target = OUT / 'reports' / source.relative_to(APP)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
except Exception as error:
    errors.append(dict(operation='preserve-xml', error=str(error)))

try:
    after = {}
    for role in ('APP', 'ENGINE'):
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

# Reuse the ordinary gate's whole-class, nonempty, zero-failure/error/skip XML contract.
try:
    xml_files = list((OUT / 'reports').rglob('*.xml'))
    expected_xml = {task.rsplit(':', 1)[0].lstrip(':').replace(':', '/') + '/build/test-results/' +
        task.rsplit(':', 1)[1] + '/TEST-' + klass + '.xml': (task, klass)
        for task, classes in EXPECTED.items() for klass in classes}
    require({str(p.relative_to(OUT / 'reports')) for p in xml_files} == set(expected_xml), 'Missing/extra selected class XML')
    for path in xml_files:
        task, klass = expected_xml[str(path.relative_to(OUT / 'reports'))]
        suite = ET.parse(path).getroot()
        children = suite.findall('testcase')
        require(suite.tag == 'testsuite' and children and int(suite.get('tests', '-1')) == len(children) and
            all(int(suite.get(k, '-1')) == 0 for k in ('failures', 'errors', 'skipped')) and
            all(c.get('classname') == klass and c.get('name') and
                not any(c.find(k) is not None for k in ('failure', 'error', 'skipped')) for c in children),
            'Selected class has zero/partial/failing/skipped/foreign cases')
        observed.extend(dict(task=task, class_name=klass, method=c.get('name')) for c in children)
    xml_exact = True
except Exception as error:
    errors.append(dict(operation='xml-evidence', error=str(error)))
write_json('observed-tests.json', observed)

try:
    lines = (OUT / 'gradle.log').read_text(errors='replace').splitlines()
    def rows(label):
        marker = 'KIRA_SEVEN_' + label + ' '
        return [json.loads(line[len(marker):]) for line in lines if line.startswith(marker)]
    completions = rows('TASK_COMPLETE')
    write_json('task-completions.json', completions)
    indexed = {(c['build'], c['path']): c for c in completions}
    required = [(build, task) for build, tasks in SCOPE['required_compilers'].items() for task in tasks] + \
        [('app', task) for task in EXPECTED]
    completions_exact = len(indexed) == len(completions) and all(key in indexed and
        indexed[key]['executed'] and indexed[key]['didWork'] and not indexed[key]['skipped'] and
        not indexed[key]['upToDate'] and not indexed[key]['noSource'] and indexed[key]['failure'] is None
        for key in required)
    require(completions_exact, 'Missing/nonexecuted/failed ordinary compiler or Test completion')
    producer = rows('PRODUCER_INPUTS')
    write_json('producer-inputs.json', producer)
    producer_exact = len(producer) == 1 and producer[0]['build'] == 'app' and \
        producer[0]['task'] == ':sources:engine:compileKotlinDesktop' and \
        {edge['project'] for edge in producer[0]['edges']} == {':source-contract', ':source-engine'}
    require(producer_exact, 'Missing actual original Engine compiler-input observation')
    image = rows('IMAGE_RUNTIME_SELECTION')
    write_json('image-runtime-selection.json', image)
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

eligible = normal_exits and source_unchanged and xml_exact and completions_exact and producer_exact and image_runtime_exact and not errors
result = dict(status='RESULT_REVIEW_REQUIRED' if eligible else 'FAIL', normal_exits=normal_exits,
    source_unchanged=source_unchanged, xml_exact=xml_exact, selected_class_count=42,
    observed_test_count=len(observed), compiler_and_test_completions_exact=completions_exact,
    producer_inputs_exact=producer_exact, image_runtime_exact=image_runtime_exact,
    cleanup_boundary='Job-private Gradle stop; final scratch/process disposal belongs to the ephemeral hosted runner, not this verifier.',
    coverage='Only five Desktop Test tasks; no static, Android/JNI/device, Apple, packaging or shipping qualification.', errors=errors)
write_json('result.json', result)
print(json.dumps(result))
sys.exit(0 if eligible else 1)
