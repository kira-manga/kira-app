"""UNBOUND common-platform controls; App75 Apple02 recipe, unchanged owned-process helper."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import signal
import sys
import time

CONTROL = Path(__file__).resolve().parents[1]
BRANCH = 'remediation/app-4-7-25-69-public-platform-01'
REQUEST = 'ci/app472569-platform.request.json'
SOURCE = {'repository': 'kira-manga/kira-app', 'sha': '91b172a7e2bdb08ac42fcaec1de845a6d70709e5',
          'tree': 'd2b737ac64232fd2bc9d22e89db459ef53af1bc8'}
TASKS = [':data:compileKotlinIosSimulatorArm64', ':presentation:compileKotlinIosSimulatorArm64']
DESKTOP_TASKS = [':platform:compileKotlinDesktop', ':presentation:compileKotlinDesktop']
MODULES = (':core', ':domain', ':platform', ':data:local', ':data:remote', ':sources:legacy',
           ':data:download', ':sources:contracts', ':data', ':presentation')
MAINS = [module + ':compileKotlinIosSimulatorArm64' for module in MODULES]
KSP = ':data:local:kspKotlinIosSimulatorArm64'
CINTEROP = ':platform:cinteropLibwebpIosSimulatorArm64'
REQUIRED = MAINS + [KSP, CINTEROP]
OWNER_HASH = '9f69c87182eba29030fcd65c49b4e8459a41749440dab9767f3fbfe0c02db6cf'
INPUTS_HASH = 'e350754cbacc71b5d5e9656302a4994b9ca66f14c21685895cc6b4d44e75a16e'
INPUTS = 'ci/app472569-platform-source-inputs.json'
XCODE = '/Applications/Xcode_26.4.1.app/Contents/Developer'
SCRATCH = ('gradle-home', 'konan', 'home', 'tmp', 'project-cache', 'kotlin', 'work')
OUTPUTS = ('build', *(module[1:].replace(':', '/') + '/build' for module in MODULES), '.gradle', '.kotlin')
FILES = ('.github/workflows/app472569-platform-compile.yml', INPUTS, 'ci/app472569_apple.py',
         'ci/app472569-apple.init.gradle', 'ci/app472569_linux.py', 'ci/app472569-linux.init.gradle',
         'ci/app8-apple.py')


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def digest(path):
    require(path.is_file() and path.resolve() == path and not path.is_symlink(), 'Missing/aliased bound file')
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            result.update(block)
    return result.hexdigest()


def read_json(path):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 65536, 'Invalid JSON input')
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'Duplicate JSON key')
            result[key] = value
        return result
    def constant(_value):
        raise RuntimeError('Non-finite JSON')
    return json.loads(path.read_text(), object_pairs_hook=unique, parse_constant=constant)


def validate_request(request, context, target):
    require(target in ('desktop', 'ios') and 'UNBOUND' not in BRANCH, 'Preparation remains UNBOUND')
    fixed = {'schema': 'app472569-platform-v1', 'status': 'PRIMARY_BOUND_FOR_REVIEW',
             'authorization': 'APP472569_PLATFORM_ONE_ATTEMPT_AUTHORIZED',
             'sourceRepository': SOURCE['repository'], 'sourceSha': SOURCE['sha'], 'sourceTree': SOURCE['tree'],
             'tasks': {'desktop': DESKTOP_TASKS, 'ios': TASKS}, 'expectedRunAttempt': 1,
             'sourceInputsSha256': INPUTS_HASH, 'ownerHelperSha256': OWNER_HASH,
             'developerDir': XCODE, 'coldPublicAcquisitionAuthorized': True}
    require(isinstance(request, dict) and set(request) == set(fixed) | {'controls'}, 'Unknown/missing request field')
    require(type(request['expectedRunAttempt']) is int and type(request['coldPublicAcquisitionAuthorized']) is bool,
            'Incorrect authorization field types')
    require(all(request[key] == value for key, value in fixed.items()), 'UNBOUND/unauthorized request')
    expected = {'GITHUB_ACTIONS': 'true', 'GITHUB_REPOSITORY': SOURCE['repository'],
                'GITHUB_REF': 'refs/heads/' + BRANCH, 'GITHUB_EVENT_NAME': 'push', 'GITHUB_RUN_ATTEMPT': '1',
                'RUNNER_OS': 'macOS' if target == 'ios' else 'Linux',
                'RUNNER_ARCH': 'ARM64' if target == 'ios' else 'X64', 'RUNNER_ENVIRONMENT': 'github-hosted',
                'GITHUB_WORKFLOW_REF': SOURCE['repository'] + '/.github/workflows/app472569-platform-compile.yml@refs/heads/' + BRANCH}
    require(all(context.get(key) == value for key, value in expected.items()), 'Wrong public hosted invocation')
    require(re.fullmatch('[1-9][0-9]*', context.get('GITHUB_RUN_ID', '')), 'Invalid run identity')
    require(re.fullmatch('[0-9a-f]{40}', context.get('GITHUB_SHA', '')) and
            context.get('GITHUB_WORKFLOW_SHA') == context['GITHUB_SHA'], 'Wrong event/workflow carrier')
    return SOURCE['sha']


def checked_request(target='ios'):
    request = read_json(CONTROL / REQUEST)
    validate_request(request, os.environ, target)
    require(CONTROL == Path(os.environ['GITHUB_WORKSPACE']).resolve() / 'control', 'Wrong control checkout')
    event = read_json(Path(os.environ['GITHUB_EVENT_PATH']))
    require(event['repository']['private'] is False and event['repository']['full_name'] == SOURCE['repository'] and
            event['after'] == event['head_commit']['id'] == os.environ['GITHUB_SHA'] and
            event['ref'] == os.environ['GITHUB_REF'] and event['deleted'] is False and event['forced'] is False,
            'Not the bound ordinary public push')
    require(isinstance(request['controls'], dict) and set(request['controls']) == set(FILES), 'Exact seven control pins required')
    require(all(re.fullmatch('[0-9a-f]{64}', value or '') and digest(CONTROL / name) == value
                for name, value in request['controls'].items()), 'Unbound/changed controls')
    require(request['controls'][INPUTS] == INPUTS_HASH and request['controls']['ci/app8-apple.py'] == OWNER_HASH,
            'Different input map or owned-process implementation')
    return request


def carrier(readgit, request):
    sha = os.environ['GITHUB_SHA']
    require(readgit(CONTROL, 'carrier-sha', ['rev-parse', 'HEAD']) == sha, 'Different control carrier')
    require(readgit(CONTROL, 'carrier-parent', ['rev-parse', 'HEAD^@']) == SOURCE['sha'] and
            readgit(CONTROL, 'carrier-parent-tree', ['rev-parse', 'HEAD^1^{tree}']) == SOURCE['tree'],
            'Carrier must directly extend the frozen union source')
    changed = readgit(CONTROL, 'carrier-delta', ['diff-tree', '--no-commit-id', '--name-status', '--no-renames',
                                               '-r', 'HEAD^1', 'HEAD']).splitlines()
    require(sorted(changed) == sorted('A\t' + name for name in (*FILES, REQUEST)), 'Carrier must add only eight reviewed controls')
    require(not readgit(CONTROL, 'carrier-clean', ['status', '--porcelain', '--untracked-files=all']) and
            not readgit(CONTROL, 'carrier-ignored', ['ls-files', '--others', '--ignored', '--exclude-standard']),
            'Dirty/private carrier inputs')
    return {'sha': sha, 'parent': SOURCE, 'addedControls': sorted((*FILES, REQUEST)), 'controls': request['controls']}


def source_snapshot(readgit, source, pins, outputs, label, cleaning=False):
    require(source.is_dir() and source.resolve() == source and not source.is_symlink(), 'Aliased/missing public source')
    require(readgit(source, label + '-sha', ['rev-parse', 'HEAD'], cleaning) == SOURCE['sha'] and
            readgit(source, label + '-tree', ['rev-parse', 'HEAD^{tree}'], cleaning) == SOURCE['tree'], 'Different source commit/tree')
    require(not readgit(source, label + '-clean', ['status', '--porcelain', '--untracked-files=all'], cleaning), 'Source changed')
    ignored = readgit(source, label + '-ignored', ['ls-files', '--others', '--ignored', '--exclude-standard', '-z'], cleaning)
    require(all(any(name == root or name.startswith(root + '/') for root in outputs)
                for name in ignored.split('\0') if name), 'Ignored/private input outside owned output roots')
    require(all(not Path(name).is_absolute() and '..' not in Path(name).parts for name in pins), 'Unsafe input path')
    actual = {name: digest(source / name) for name in pins}
    require(actual == pins, 'Reviewed source/build inputs changed')
    return {'sha': SOURCE['sha'], 'tree': SOURCE['tree'], 'inputCount': len(pins),
            'inputMapSha256': INPUTS_HASH, 'trackedAndUntrackedClean': True, 'noIgnoredInputsOutsideOwnedOutputs': True}


def child_environment(run, original):
    marker = 'app472569.apple.owner=' + run.name
    java = Path(original['JAVA_HOME_21_arm64']).resolve()
    require((java / 'bin/java').is_file() and (java / 'release').is_file() and
            (java / 'release').stat().st_size <= 65536 and 'JAVA_VERSION="21.' in (java / 'release').read_text(),
            'Installed JDK21 ARM64 required; no tool installation')
    result = {'PATH': str(java / 'bin') + ':/usr/bin:/bin:/usr/sbin:/sbin', 'JAVA_HOME': str(java), 'HOME': str(run / 'home'),
              'GRADLE_USER_HOME': str(run / 'gradle-home'), 'KONAN_DATA_DIR': str(run / 'konan'),
              'TMPDIR': str(run / 'tmp') + '/', 'TMP': str(run / 'tmp'), 'TEMP': str(run / 'tmp'),
              'DEVELOPER_DIR': XCODE, 'CI': 'true', 'LANG': 'en_US.UTF-8', 'LC_ALL': 'en_US.UTF-8',
              'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': '/dev/null', 'GIT_OPTIONAL_LOCKS': '0',
              'KIRA_SOURCE_CONFIG_BASE_URL': '', 'KIRA_SOURCE_CONFIG_PINNED_KEYS': '', 'KIRA_APP_VERSION': '1.0.5',
              'APP472569_RUN': str(run), 'APP472569_PROOF': str(run / 'reports/task-proof.json'),
              'JAVA_OPTS': f'-Xmx128m -D{marker} -Duser.home={run / "home"} -Djava.io.tmpdir={run / "tmp"}'}
    for key in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        if original.get(key):
            result[key] = original[key]
    return result


def compilation_argv(source, run):
    return [str(source / 'gradlew'), '-p', str(source), *TASKS, '--no-daemon', '--no-parallel', '--max-workers=1',
            '--no-build-cache', '--no-configuration-cache', '--console=plain', '--stacktrace',
            '--project-cache-dir', str(run / 'project-cache'), '-I', str(CONTROL / 'ci/app472569-apple.init.gradle'),
            '-Pkotlin.compiler.execution.strategy=in-process', '-Pkotlin.native.disableCompilerDaemon=false',
            '-Pkotlin.native.parallelThreads=1',
            '-Pkotlin.incremental=false', '-PkiraUseMavenLocal=false',
            '-Porg.gradle.java.installations.auto-download=false', '-Pandroid.builder.sdkDownload=false', '-Dorg.gradle.vfs.watch=false',
            '-Pkotlin.project.persistent.dir=' + str(run / 'kotlin'),
            f'-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=768m -Dapp472569.apple.owner={run.name} '
            f'-Duser.home={run / "home"} -Djava.io.tmpdir={run / "tmp"}']


def absence(commands, run, source):
    settled = commands.drain()
    rows = commands.census(cleaning=True)
    workers = [row for row in rows if row['pid'] != os.getpid() and not row['state'].startswith('Z')
               and any(value in row['command'] for value in (str(run), str(source), 'app472569.apple.owner=' + run.name))]
    return {'absent': settled and not workers, 'groupsSettled': settled, 'remainingOwned': [{k: row[k] for k in ('pid', 'uid', 'ppid', 'pgid', 'state')} for row in workers]}


def remove_scoped(root, names, end):
    require(root.is_dir() and root.resolve() == root and not root.is_symlink(), 'Unsafe cleanup owner')
    removed = []
    for name in names:
        require(time.monotonic() < end, 'Owned cleanup deadline exceeded')
        path = root / name
        require(not path.is_symlink() and path.resolve().is_relative_to(root), 'Unsafe cleanup root')
        if path.exists():
            require(path.is_dir(), 'Expected owned generated directory')
            shutil.rmtree(path)
        require(not path.exists() and time.monotonic() < end, 'Owned scratch remains or cleanup deadline exceeded')
        removed.append(name)
    return removed


def output_proof(source, reports, request, end):
    proof = read_json(reports / 'task-proof.json')
    require(proof['schema'] == 'app472569-apple-task-v1' and proof['requestedTasks'] == TASKS
            and proof['requiredTasks'] == REQUIRED and proof['sourceSha'] == request['sourceSha']
            and proof['gradleVersion'] == '9.6.1', 'Missing real task proof')
    work = proof['work']
    require(set(work) == set(REQUIRED), 'Missing/extra required action receipt')
    entries, files, task_files, total = set(), {}, {}, 0
    for task in REQUIRED:
        state = proof['states'][task]
        require(all(state[name] is True for name in ('executed', 'didWork', 'actionsReachedEnd'))
                and all(state[name] is False for name in ('skipped', 'upToDate', 'noSource'))
                and state['failure'] is None, 'Required action did not execute normally: ' + task)
        row = work[task]
        require(isinstance(row['outputs'], list) and len(row['outputs']) == 1, 'Invalid declared output')
        if task in MAINS:
            require(row['target'] == 'ios_simulator_arm64' and row['sourceCount'] > 0, 'Wrong/empty main target')
        module = task.rsplit(':', 1)[0][1:].replace(':', '/')
        root = source / module / 'build'
        path = source / row['outputs'][0]
        require(path.exists() and not path.is_symlink() and path.resolve().is_relative_to(root),
                'Unexpected output path')
        task_files[task] = []
        for item in ([path] if path.is_file() else path.rglob('*')):
            require(time.monotonic() < end, 'Output evidence deadline exceeded')
            require(not item.is_symlink() and item.resolve().is_relative_to(root), 'Unsafe compiler output')
            entries.add(item)
            # Retain the successful donor bounds for ten main klibs plus KSP/cinterop.
            require(len(entries) <= 4096, 'Oversized output inventory')
            if item.is_file():
                require(item not in files, 'Overlapping output ownership')
                size = item.stat().st_size
                total += size
                require(total <= 67108864, 'Oversized compiler output')
                files[item] = {'task': task, 'path': str(item.relative_to(source)), 'bytes': size, 'sha256': digest(item)}
                task_files[task].append(item)
        require(task_files[task], 'Missing required output: ' + task)
        if task != KSP:
            require(any(item.suffix == '.klib' or item.name == 'manifest' for item in task_files[task]),
                    'No compiled klib output: ' + task)
    generated = work[KSP]['generatedSources']
    require(len(generated) == 3 and {Path(name).name for name in generated} ==
            {'MangaDatabase_Impl.kt', 'ChapterDownloadDao_Impl.kt', 'ChapterDao_Impl.kt'}, 'Missing/ambiguous Room source receipts')
    room = []
    for name in generated:
        path = source / name
        require(path in task_files[KSP] and path.stat().st_size <= 131072, 'Missing/oversized generated Room source')
        room.append(dict(files[path], utf8=path.read_bytes().decode('utf-8')))
    invalidation = []
    expected = {'ChapterDao_Impl.kt': ('getChapterByUrl', {'saved_chapters', 'saved_manga'}),
                'ChapterDownloadDao_Impl.kt': ('observeDownloadsForMangaUrl', {'chapter_downloads', 'saved_manga'})}
    for row in room:
        if Path(row['path']).name not in expected:
            continue
        method, tables = expected[Path(row['path']).name]
        text = row['utf8']
        matches = list(re.finditer(r'\bpublic\s+override\s+fun\s+' + method + r'\s*\(', text))
        require(len(matches) == 1, 'Missing/ambiguous generated joined query: ' + method)
        tail = text[matches[0].start():]
        following = re.search(r'\n\s*public\s+override\b', tail[matches[0].end() - matches[0].start():])
        if following:
            tail = tail[:matches[0].end() - matches[0].start() + following.start()]
        flows = re.findall(r'createFlow\(__db,\s*false,\s*arrayOf\(([^)]*)\)\)', tail)
        require(len(flows) == 1, 'Missing/ambiguous generated invalidation table list')
        actual = re.findall(r'"([a-z_]+)"', flows[0])
        require(len(actual) == 2 and set(actual) == tables and
                not re.sub(r'"[a-z_]+"|[,\s]', '', flows[0]), 'Different joined-query invalidation tables')
        invalidation.append({'path': row['path'], 'method': method, 'tables': sorted(actual)})
    require(len(invalidation) == 2, 'Both joined-query witnesses are required')
    result = {'sourceSha': request['sourceSha'], 'entryCount': len(entries), 'fileCount': len(files), 'bytes': total,
              'files': [files[path] for path in sorted(files)], 'roomGenerated': room, 'joinedQueryInvalidation': invalidation}
    require(len((json.dumps(result, sort_keys=True, indent=2) + '\n').encode('utf-8')) <= 2097152,
            'Oversized output receipt')
    return result


def native_markers(run):
    # File evidence only; never invoke another compiler or hash/copy the whole SDK/distribution.
    roots = list((run / 'konan').glob('kotlin-native*'))
    require(len(roots) == 1 and 'macos-aarch64' in roots[0].name and roots[0].name.endswith('-2.4.0'),
            'Missing/ambiguous Kotlin Native 2.4.0 host distribution')
    root = roots[0]
    require(root.is_dir() and not root.is_symlink(), 'Invalid native distribution directory')
    names = ('konan/konan.properties', 'bin/konanc')
    return {'distribution': root.name, 'kotlinVersionFromBoundCatalog': '2.4.0',
            'markers': {name: digest(root / name) for name in names}}


def retain_public(save, reports, result, logs):
    # Fixed public copies, not wildcard uploads of the donor's numbered/private-census logs.
    caps = {'request.json': 65536, 'source.json': 65536, 'identity.json': 65536, 'tools.json': 65536,
            'task-proof.json': 65536, 'outputs.json': 2097152, 'native-markers.json': 65536, 'commands.json': 1048576}
    public = reports / 'public'
    public.mkdir(mode=0o700)
    retained = []
    for name, cap in caps.items():
        path = reports / name
        if not path.exists():
            continue
        try:
            require(path.is_file() and path.resolve() == path and path.stat().st_size <= cap, 'Unsafe/oversized report: ' + name)
            with path.open('rb') as stream:
                raw = stream.read(cap + 1)
            require(len(raw) <= cap, 'Report grew past cap: ' + name)
            json.loads(raw)  # Data only; stable bounded snapshot, including failed runs.
            with (public / name).open('xb') as stream:
                stream.write(raw)
            retained.append(name)
        except Exception as error:
            result['errors'].append('retention: ' + str(error)[:500])
    for name, path in logs.items():
        try:
            require(name in ('compile.log', 'stop-immediate.log', 'stop-final.log') and path.is_file() and
                    path.resolve() == path, 'Unexpected/aliased public log')
            with path.open('rb') as stream:
                raw = stream.read(1048577)
            if len(raw) > 1048576 or path.stat().st_size > 1048576:
                result['errors'].append('retained bounded log prefix: ' + name)
            with (public / name).open('xb') as stream:
                stream.write(raw[:1048576])
            retained.append(name)
        except Exception as error:
            result['errors'].append('retention: ' + str(error)[:500])
    expected = set(caps) | {'compile.log', 'stop-immediate.log', 'stop-final.log'}
    if result['target'] == 'desktop':
        expected.remove('native-markers.json')
    if result.get('passed') and set(retained) != expected:
        result['errors'].append('Missing required current compile/status/source/cleanup reports')
    result['passed'] = bool(result.get('passed') and not result['errors'])
    result['status'] = 'RESULT_REVIEW_REQUIRED' if result['passed'] else 'FAIL'
    result['retention'] = {'files': sorted([*retained, 'result.json']), 'maximumFiles': 12,
                           'maximumBytes': sum(caps.values()) + 3 * 1048576 + 131072,
                           'sourceText': ('Three bounded newly generated Room witnesses only; no source snapshots/private evidence'
                                          if result['target'] == 'ios' else 'No source text; compile-output hashes only')}
    save(reports / 'result.json', result)
    save(public / 'result.json', result)
    with Path(os.environ['GITHUB_OUTPUT']).open('a') as stream:
        stream.write('retention_ready=true\n')


def run_recipe(owner, source, run, request, system):
    """App75 Apple02 fixed recipe; the unchanged helper owns launch/wait/census/signals."""
    pins = read_json(CONTROL / INPUTS)
    end = time.monotonic() + 1440
    work_end = end - 240
    env = child_environment(run, os.environ)
    env['APP472569_SOURCE_SHA'] = SOURCE['sha']
    commands = None
    reports, errors, started, source_ready = run / 'reports', [], False, False
    identity = {}
    result = {'schema': 'app472569-platform-result-v1', 'target': 'ios', 'passed': False, 'source': SOURCE,
              'carrierSha': os.environ['GITHUB_SHA'], 'tasks': TASKS, 'run': run.name, 'system': system, 'errors': errors,
              'scope': 'Ten ordinary simulator mains, Room generation and libwebp cinterop; zero tests. DI is allocated to Android45.',
              'limits': {'workSeconds': 1200, 'cleanupReserveSeconds': 240, 'stopSecondsEach': 40, 'workers': 1,
                         'sampledCommandLogBytes': 1048576, 'sampledTotalCommandLogBytes': 4194304,
                         'outputInventoryEntries': 4096, 'outputInventoryBytes': 67108864,
                         'roomSourceBytesEach': 131072, 'outputReceiptBytes': 2097152}}
    owner.save(reports / 'request.json', request)
    owner.save(reports / 'result.json', result)
    def readgit(root, label, args, cleaning=False):
        return commands.call(['/usr/bin/git', '--no-optional-locks', '-C', str(root), *args], label, seconds=15,
                             end=end if cleaning else work_end, cleaning=cleaning).rstrip('\n')
    def stop(label):
        try:
            commands.call([str(source / 'gradlew'), '--stop'], label, seconds=40, cleaning=True)
        except Exception as error:
            errors.append(label + ': ' + str(error)[:500])
    try:
        owner.deadline_capabilities()
        commands = owner.Commands(run, env, end)
        identity['carrier'] = carrier(readgit, request)
        require(all(not (source / name).exists() and not (source / name).is_symlink() for name in OUTPUTS),
                'Preexisting outputs; no compilation or source cleanup authority')
        identity['before'] = source_snapshot(readgit, source, pins, OUTPUTS, 'before')
        source_ready = True
        owner.save(reports / 'source.json', {'sha': SOURCE['sha'], 'tree': SOURCE['tree'], 'inputs': pins})
        owner.save(reports / 'identity.json', identity)
        tool_info = {'python': sys.version, 'developerDir': XCODE,
                     'javaReleaseSha256': digest(Path(env['JAVA_HOME']) / 'release')}
        tool_info['java'] = commands.call([env['JAVA_HOME'] + '/bin/java', '-version'], 'java-version', end=work_end)
        tool_info['xcode'] = commands.call(['/usr/bin/xcodebuild', '-version'], 'xcode-version', end=work_end)
        tool_info['sdk'] = commands.call(['/usr/bin/xcrun', '--sdk', 'iphonesimulator', '--show-sdk-version'], 'sdk-version', end=work_end)
        require(tool_info['xcode'].strip() == 'Xcode 26.4.1\nBuild version 17E202' and tool_info['sdk'].strip() == '26.4',
                'Different installed Xcode/SDK')
        owner.save(reports / 'tools.json', tool_info)
        require(shutil.disk_usage(run).free >= 8 * 1024**3, '8GiB free space required before Gradle')
        started = True
        commands.call(compilation_argv(source, run), 'compile', seconds=1200, end=work_end)
        result['compileSucceeded'] = True
    except Exception as error:
        errors.append('validation: ' + str(error)[:500])
    finally:
        if started:
            stop('gradle-stop-immediate')
        try:
            result['afterImmediateStop'] = (absence(commands, run, source) if commands else
                                            {'absent': True, 'noChildOwnerCreated': True})
            require(result['afterImmediateStop']['absent'], 'Owned workers/ownership uncertainty; preserve outputs and scratch')
            if source_ready:
                if result.get('compileSucceeded'):
                    try:
                        owner.save(reports / 'outputs.json', output_proof(source, reports, request, end), limit=2097152)
                        owner.save(reports / 'native-markers.json', native_markers(run))
                        result['outputProofPreserved'] = True
                    except Exception as error:
                        errors.append('evidence: ' + str(error)[:500])
                identity['atCapture'] = source_snapshot(readgit, source, pins, OUTPUTS, 'capture', True)
                checked_request()
                require(identity['atCapture'] == identity['before'], 'Source changed; preserve outputs')
                owner.save(reports / 'identity.json', identity)
                result['outputsRemoved'] = remove_scoped(source, OUTPUTS, end)
        except Exception as error:
            errors.append('preserve/clean: ' + str(error)[:500])
        if started:
            stop('gradle-stop-final')
        try:
            result['afterFinalStop'] = (absence(commands, run, source) if commands else
                                        {'absent': True, 'noChildOwnerCreated': True})
            require(result['afterFinalStop']['absent'], 'Owned workers/ownership uncertainty; retain scratch')
            if source_ready:
                require(result.get('outputsRemoved') == list(OUTPUTS), 'Incomplete source-output cleanup; retain scratch')
                identity['after'] = source_snapshot(readgit, source, pins, (), 'after', True)
                checked_request()
                require(identity['after'] == identity['before'], 'Final source mismatch; retain scratch')
                owner.save(reports / 'identity.json', identity)
                result['sourcePreserved'] = True
            result['scratchRemoved'] = remove_scoped(run, SCRATCH, end)
        except Exception as error:
            errors.append('final ownership/clean: ' + str(error)[:500])
            if commands is not None:
                try:
                    commands.retire_observer()  # Existing observer only; never retry a failed final census.
                except Exception as retirement_error:
                    errors.append('final observer retirement: ' + str(retirement_error)[:500])
                try:
                    commands.checkpoint()
                except Exception as checkpoint_error:
                    errors.append('final observer receipt: ' + str(checkpoint_error)[:500])
        result['sourceBaselineVerified'], result['compileAttempted'] = source_ready, started
        result['normalOwnedCompletion'] = commands.normal() if commands else False
        result['cancelled'] = owner.CANCELLED
        result['passed'] = bool(result.get('compileSucceeded') and result.get('outputProofPreserved') and
                                result.get('outputsRemoved') == list(OUTPUTS) and result.get('sourcePreserved') and
                                result.get('scratchRemoved') == list(SCRATCH) and not errors and
                                result['normalOwnedCompletion'] and not owner.CANCELLED)
        logs = {}
        for label, name in [('compile', 'compile.log'), ('gradle-stop-immediate', 'stop-immediate.log'),
                            ('gradle-stop-final', 'stop-final.log')]:
            matches = [task for task in commands.tasks if task['receipt']['label'] == label] if commands else []
            require(len(matches) <= 1, 'Repeated compile/stop command')
            if matches and matches[0]['log'].is_file():
                logs[name] = matches[0]['log']
        retain_public(owner.save, reports, result, logs)
    return 0 if result['passed'] else 1


def compile_once(request):
    require(os.uname().sysname == 'Darwin' and os.uname().machine == 'arm64', 'macOS ARM required')
    with Path('/System/Library/CoreServices/SystemVersion.plist').open('rb') as stream:
        system = plistlib.load(stream)
    require(system['ProductVersion'].startswith('26.') and Path(XCODE).is_dir(), 'Required macOS26/Xcode unavailable')
    workspace, temporary = Path(os.environ['GITHUB_WORKSPACE']).resolve(), Path(os.environ['RUNNER_TEMP']).resolve()
    source, run = workspace / 'app', Path(os.environ['APP472569_RUN'])
    require(CONTROL == workspace / 'control' and source.is_dir() and not source.is_symlink(), 'Wrong checkout paths')
    require(run == temporary / ('app472569-apple-' + os.environ['GITHUB_RUN_ID'] + '-1')
            and run.resolve() == run and not run.exists() and not any(c.isspace() for c in str(run)), 'Unsafe/nonfresh run root')
    require(shutil.disk_usage(temporary).free >= 8 * 1024**3, 'Fresh8GiB disk floor required')
    spec = importlib.util.spec_from_file_location('app472569_existing_darwin_owner', CONTROL / 'ci/app8-apple.py')
    owner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(owner)  # Definitions only; never App8 main/PF/Simulator. No scratch if import fails.
    run.mkdir(mode=0o700)
    for name in (*SCRATCH, 'reports'):
        (run / name).mkdir()
    for number in (signal.SIGINT, signal.SIGTERM):
        signal.signal(number, owner.interrupted)
    return run_recipe(owner, source, run, request, system)


def main():
    os.umask(0o077)
    require(sys.argv[1:] in (['request'], ['compile']), 'Expected one fixed phase')
    request = checked_request()
    if sys.argv[1] == 'request':
        with Path(os.environ['GITHUB_OUTPUT']).open('a') as stream:
            stream.write('source_sha=' + request['sourceSha'] + '\n')
        return 0
    return compile_once(request)


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        print('APP472569 APPLE INCOMPLETE: ' + str(error), file=sys.stderr)
        raise SystemExit(1)
