#!/usr/bin/env python3
"""One ordinary App32 Linux/Android compile and selected-test invocation. No schema replay."""
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import xml.etree.ElementTree as ET
import app32_linux_owned as owned

ROOT, WORK, OUT = owned.ROOT, owned.WORK, owned.OUT
require, record = owned.require, owned.record
CONTROLS = ('.github/workflows/app32-linux-batch.yml', 'scripts/ci/app32-linux-batch.init.gradle',
            'scripts/ci/app32-linux-batch.py', 'scripts/ci/app32_linux_owned.py')
COMPILES = [':composeApp:compileKotlinDesktop', ':composeApp:compileAndroidMain', ':app:compileDebugKotlin']
# NEXT-TARGETED-VALIDATION.md plus the frozen artifact3 class. No UI or unchanged schema tests.
TESTS = {
    ':platform:desktopTest': [
        'me.manga.kira.platform.download.DownloadOperationExclusionTest',
        'me.manga.kira.platform.download.DownloadOperationChildLifetimeTest'],
    ':sources:config:desktopTest': [
        'me.manga.kira.sources.config.SourceSelectionBootstrapTest',
        'me.manga.kira.sources.config.RetainedSourceSelectionRecoveryTest',
        'me.manga.kira.sources.config.IncrementalSourceCatalogManagerTest.accepted_document_publishes_verified_cache_before_remote_finishes'],
    ':domain:desktopTest': [
        'me.manga.kira.domain.usecase.reader.ListChaptersUseCaseTest'],
    ':presentation:desktopTest': [
        'me.manga.kira.presentation.details.DetailsJoinOwnershipRegressionTest',
        'me.manga.kira.presentation.reader.ReaderPageProgressOwnershipTest',
        'me.manga.kira.presentation.reader.ReaderViewModelProgressSessionTest'],
    ':data:desktopTest': [
        'me.manga.kira.data.repository.DownloadLibraryRemovalGuardTest',
        'me.manga.kira.data.repository.DownloadsOperationExclusionTest',
        'me.manga.kira.data.repository.ChapterPagesOperationExclusionTest',
        'me.manga.kira.data.repository.DownloadedChapterConversionOperationExclusionTest',
        'me.manga.kira.data.backup.BackupOperationExclusionTest',
        'me.manga.kira.data.repository.DownloadCleanupIoTest',
        'me.manga.kira.data.repository.CompressExistingDownloadsSizeRefreshTest',
        'me.manga.kira.data.repository.LibraryArtifactRemovalJoinTest',
        'me.manga.kira.data.backup.BackupOwnershipAdmissionJoinTest',
        'me.manga.kira.data.repository.RefreshDiscoveryOwnershipTest',
        'me.manga.kira.data.repository.selection.StrictSelectionArtifactMigrationTest',
        'me.manga.kira.data.repository.LibraryRepositoryRemoveTest.removal_preserves_other_source_url_only_history_and_notifications'],
    ':composeApp:desktopTest': [
        'me.manga.kira.sources.runtime.RoomDownloadCatalogAdmissionTest',
        'me.manga.kira.sources.runtime.DownloadSourceCatalogSelectionMigrationTest',
        'me.manga.kira.sources.runtime.EffectiveSourceSelectionRoomTest.original_caller_cancellation_inside_selection_writer_rolls_back_before_publication',
        'me.manga.kira.sources.runtime.EffectiveSourceSelectionRoomTest.cancellation_after_the_real_commit_reconciles_the_durable_candidate_without_old_state_restoration',
        'me.manga.kira.sources.runtime.EffectiveSourceSelectionReadinessRecoveryTest',
        'me.manga.kira.details.DetailsUrlOnlyRoomTest'],
    ':data:download:testAndroidHostTest': [
        'me.manga.kira.presentation.features.download.ui.test2.DownloadWorkerCancellationTest',
        'me.manga.kira.presentation.features.download.ui.test2.AndroidDownloadChallengeTest'],
    ':composeApp:testAndroidHostTest': [
        'me.manga.kira.details.AndroidDownloadChallengeRecoveryTest'],
    ':app:testDebugUnitTest': [
        'me.manga.kira.di.KoinGraphRegistrationTest',
        'me.manga.kira.di.KoinGraphResolutionTest'],
}
TASKS = COMPILES + list(TESTS)


def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args], text=True,
                                   env={**os.environ, 'GIT_OPTIONAL_LOCKS': '0'}).strip()


def source():
    return dict(head=git('rev-parse', 'HEAD'), tree=git('rev-parse', 'HEAD^{tree}'),
                clean=not git('status', '--porcelain', '--untracked-files=all'))


def bind_source():
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    branch = os.environ['APP32_BRANCH']
    require(not branch.startswith('UNBOUND') and branch not in ('main', 'testing', 'release', 'internal-testing'),
            'Primary must bind a fresh isolated validation branch')
    require(os.environ['GITHUB_EVENT_NAME'] == 'push' and os.environ['GITHUB_REPOSITORY'] == 'kira-manga/kira-app'
            and os.environ['GITHUB_REF'] == 'refs/heads/' + branch and event.get('created') is True
            and os.environ['GITHUB_RUN_ATTEMPT'] == '1' and event['repository']['private'] is False,
            'Only the bound new public branch push, first attempt')
    require(os.environ['RUNNER_OS'] == 'Linux' and os.environ['RUNNER_ARCH'] == 'X64'
            and ROOT == Path(os.environ['GITHUB_WORKSPACE']).resolve(), 'Hosted Linux x64 app checkout required')
    sha, tree = os.environ['APP32_SOURCE_SHA'], os.environ['APP32_SOURCE_TREE']
    require(re.fullmatch('[0-9a-f]{40}', sha) and re.fullmatch('[0-9a-f]{40}', tree), 'Unbound source SHA/tree')
    before = source()
    record('source-before.json', dict(before, reviewed_source_sha=sha, reviewed_source_tree=tree, branch=branch))
    require(before['clean'] and before['head'] == os.environ['GITHUB_SHA']
            and git('rev-parse', 'HEAD^@') == sha and git('rev-parse', sha + '^{tree}') == tree,
            'Clean carrier must have exactly the reviewed source-only parent/tree')
    require(git('diff-tree', '--no-commit-id', '--no-renames', '--name-status', '-r', sha, 'HEAD').splitlines()
            == ['A\t' + p for p in CONTROLS], 'Carrier may add only these four reviewed controls')


def prepare():
    owned.begin_evidence()
    bind_source()
    java = Path(os.environ['JAVA_HOME_21_X64'])
    version = re.search(r'^JAVA_VERSION="(21(?:\.[^"]*)?)"', (java / 'release').read_text(), re.M)
    require(version, 'Installed JDK21 required; no JDK download')
    env = owned.runtime_env(packages=True)
    owned.prepare_work()
    record('runtime.json', dict(java_home=str(java), java_version=version.group(1), work=str(WORK),
                               gradle_heap='3g', gradle_metaspace='1g', test_heap='1g', test_metaspace='512m',
                               workers=1, test_forks=1, compiler='in-process', package_resolver='normal settings.gradle.kts'))
    owned.prepare_sdk()
    env.update(APP32_TEST_FILTERS=json.dumps(TESTS), APP32_REQUESTED_TASKS=json.dumps(TASKS))
    return env


def command():
    args = ['./gradlew', owned.MARKER, *COMPILES]
    for task, filters in TESTS.items():
        args.append(task)
        for test in filters:
            args += ['--tests', test]
    return args + ['--no-daemon', '--no-parallel', '--max-workers=1', '--no-build-cache', '--no-configuration-cache',
                   '--no-configure-on-demand', '--console=plain', '--stacktrace',
                   '-Pkotlin.compiler.execution.strategy=in-process', '-Porg.gradle.java.installations.auto-download=false',
                   '-Pandroid.builder.sdkDownload=false', '-PkiraUseMavenLocal=false', '-Dorg.gradle.vfs.watch=false',
                   '-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g -XX:ActiveProcessorCount=2 ' + owned.MARKER,
                   '--project-cache-dir', str(WORK / 'project-cache'), '-I', CONTROLS[1]]


def retain_reports():
    reports = []
    for task in TESTS:
        module, name = task[1:].rsplit(':', 1)
        directory = ROOT / module.replace(':', '/') / 'build/test-results' / name
        require(directory.resolve() == directory, 'Do not follow report directory links')
        for path in sorted(directory.glob('*.xml')):
            require(len(reports) < 64 and path.is_file() and not path.is_symlink()
                    and path.stat().st_size <= 1024 * 1024, 'XML must be regular, <=1MiB, <=64 files total')
            target = OUT / 'reports' / task[1:].replace(':', '/') / path.name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(owned.redact(path.read_bytes()))
            reports.append((task, target))
    return reports


def report_cases(task, path):
    suite = ET.parse(path).getroot()
    cases = suite.findall('testcase')
    good = suite.tag == 'testsuite' and int(suite.get('tests', '-1')) == len(cases) and bool(cases)
    good &= all(int(suite.get(key, '-1')) == 0 for key in ('errors', 'failures', 'skipped'))
    observed = []
    for case in cases:
        cls, name = case.get('classname', ''), case.get('name', '')
        test = cls + '.' + name.removesuffix('[desktop]')
        outcomes = [child.tag for child in case if child.tag in ('error', 'failure', 'skipped')]
        observed.append(dict(task=task, classname=cls, name=name, test=test, outcomes=outcomes))
        good &= bool(cls and name) and not outcomes and (cls in TESTS[task] or test in TESTS[task])
    return observed, good


def verify_tests(reports):
    observed, errors = [], []
    for task, path in reports:
        try:
            cases, good = report_cases(task, path)
            observed.extend(cases)
            if not good:
                errors.append(task + '/' + path.name + ': failing/skipped/empty/unselected suite')
        except Exception as error:
            errors.append(task + '/' + path.name + ': ' + type(error).__name__)
    record('observed-tests.json', dict(cases=observed, errors=errors))
    ids = [(row['task'], row['classname'], row['name']) for row in observed]
    covered = all(any(row['task'] == task and test in (row['classname'], row['test']) for row in observed)
                  for task, filters in TESTS.items() for test in filters)
    require(not errors and bool(ids) and len(ids) == len(set(ids)) and covered,
            'Require every exact selected filter, fresh nonzero XML, no extras/duplicates/failures/skips')


def verify_execution():
    require(not owned.owned_processes(), 'Stop owned workers before inspecting reports')
    reports = retain_reports()
    log = OUT / 'gradle.log'
    completions = [json.loads(line.removeprefix('APP32_COMPLETE '))
                   for line in (log.read_text(errors='replace').splitlines() if log.is_file() else [])
                   if line.startswith('APP32_COMPLETE ')]
    record('task-completions.json', completions)
    verify_tests(reports)
    require(sorted(row['path'] for row in completions) == sorted(TASKS) and all(
        row['executed'] and row['didWork'] and not row['skipped'] and not row['upToDate']
        and not row['noSource'] and row['failure'] is None for row in completions),
        'All requested ordinary compile/Test tasks must execute; no skipped/up-to-date/zero-work acceptance')


def verify_source():
    after = source()
    record('source-after.json', after)
    before = json.loads((OUT / 'source-before.json').read_text())
    require(after['clean'] and all(after[key] == before[key] for key in ('head', 'tree', 'clean')),
            'Source/tree changed; preserve changed source, never remove or regenerate committed schemas')


def finish(result):
    for label, action in (('stop', owned.stop_owned), ('evidence', verify_execution),
                          ('cleanup', owned.cleanup), ('source', verify_source)):
        try:
            action()
        except BaseException as error:
            result['errors'].append(label + ': ' + type(error).__name__ + ': ' + str(error)[:512])
    if (OUT / 'stop.json').is_file():
        stopped = json.loads((OUT / 'stop.json').read_text())
        if stopped['term_signals'] or stopped['kill_signals'] or stopped['error'] or (
                stopped['attempted'] and stopped['exit_code'] != 0):
            result['errors'].append('Forced/failed Gradle or owned-worker shutdown; inspect stop.json')
    result['status'] = 'PASS' if result['exit_code'] == 0 and not result['errors'] else 'FAIL'
    record('result.json', result)
    print('App32 Linux batch:', result['status'])
    return 0 if result['status'] == 'PASS' else 1


def main():
    if sys.argv[1:] == ['discard']:
        owned.discard()
        return 0
    if sys.argv[1:] == ['cleanup']:
        if OUT.exists():
            require(json.loads((OUT / 'evidence-owned.json').read_text()) == owned.identity(OUT), 'Evidence ownership changed')
            if (OUT / 'owned.json').is_file() and WORK.exists():
                return finish(dict(exit_code=None, errors=['Interrupted/incomplete run; fallback cleanup']))
            owned.cleanup()
        return 0
    require(sys.argv[1:] == ['run'], 'Use run, cleanup or discard only')
    result = dict(exit_code=None, errors=[])
    try:
        env = prepare()
        args = command()
        record('command.json', args)
        result['exit_code'] = owned.capture(args, env, 'gradle.log', 35 * 60, 16 * 1024 * 1024, monitor=True)
    except BaseException as error:
        result['errors'].append(type(error).__name__ + ': ' + str(error)[:512])
    if owned.OUT_OWNED:
        return finish(result)
    print(owned.redact(json.dumps(result).encode()).decode())
    return 1


if __name__ == '__main__':
    def interrupted(signum, frame):
        raise InterruptedError('Runner interrupted validation')
    signal.signal(signal.SIGTERM, interrupted)
    sys.exit(main())
