"""Exact one-attempt hosted admission and unmodified source custody; never self-admits."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import stat

CONTROL = Path(__file__).resolve().parents[1]
BRANCH = 'candidate/app29-mobile-edit-apple-compile-20260918-01'
WORKFLOW = '.github/workflows/app29-apple-compile.yml'
REQUEST = 'ci/app29-apple-compile.request.json'
PINS = 'ci/app29-apple-compile.source-pins.json'
MANIFEST = 'ci/app29-original-inputs/manifest.json'
MANIFEST_SHA = '60b23eee4ab4c620a9e353f0ddbb02377013129b2f068a60ebd28278223d29bb'
FILES = (WORKFLOW, 'ci/app29-apple-compile-admission.py', 'ci/app29-apple-compile-evidence.py',
         'ci/app29-apple-compile-inputs.py', 'ci/app29-apple-compile-proof.py',
         'ci/app29-apple-compile.init.gradle', 'ci/app29-apple-compile.py', PINS, MANIFEST,
         'ci/app5-ios-host-commands.py', 'ci/app8-apple.py')
APP = {'repository': 'kira-manga/kira-app', 'sha': '448a014dacbff124347706cd3944a0aa473465d1',
       'tree': 'c7027e371f2b5672f3ea9d27dec2685f8b6a6050'}
TASK = ':composeApp:compileKotlinIosSimulatorArm64'
OWNER_SHA = '9f69c87182eba29030fcd65c49b4e8459a41749440dab9767f3fbfe0c02db6cf'
ADAPTER_SHA = 'bad29587b8b5a5095fb5674d3c0c1ceddac517e11d9940182876ec5f1b689a90'
XCODE = '/Applications/Xcode_26.4.1.app/Contents/Developer'
LIMITS = {'compileSeconds': 1200, 'artifactSeconds': 120, 'artifactNetworkSeconds': 90,
          'workSeconds': 1440, 'controllerSeconds': 1680, 'cleanupSeconds': 240,
          'stopSeconds': 40, 'jobMinutes': 35, 'gradleWorkers': 1, 'nativeThreads': 1,
          'jvmHeapGiB': 3, 'metaspaceMiB': 768, 'diskFloorGiB': 8,
          'compileCommandLogBytes': 8388608, 'otherCommandLogBytes': 1048576,
          'aggregateCommandLogBytes': 12582912, 'otherAggregateCommandLogBytes': 4194304,
          'cleanupCommandLogBytes': 1048576, 'klibFingerprintBytes': 1073741824,
          'artifactZipBytes': 1048576, 'artifactMetadataBytes': 65536,
          'artifactHttpRequests': 3, 'artifactRedirects': 1, 'artifactRetries': 0}


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'Duplicate JSON key')
        result[key] = value
    return result


def constant(_value):
    raise RuntimeError('Nonfinite JSON')


def read(path):
    require(path.resolve() == path and not path.is_symlink() and stat.S_ISREG(path.stat().st_mode)
            and path.stat().st_size <= 65536, 'Invalid bounded input')
    return json.loads(path.read_text(), object_pairs_hook=unique, parse_constant=constant)


def digest(path):
    require(path.resolve() == path and not path.is_symlink() and stat.S_ISREG(path.stat().st_mode)
            and path.stat().st_size <= 1048576, 'Invalid bounded carrier file')
    return hashlib.sha256(path.read_bytes()).hexdigest()


def input_files():
    require(digest(CONTROL / MANIFEST) == MANIFEST_SHA, 'Different dependency inventory')
    rows = read(CONTROL / MANIFEST)['files']
    files = {'ci/app29-original-inputs/repository/' + row['path']: row['sha256'] for row in rows
             if Path(row['path']).suffix not in ('.aar', '.klib')}
    require(len(rows) == 30 and len(files) == 26, 'Wrong exact-original input count')
    return files


def controls(request):
    require(set(request['controls']) == set(FILES), 'Wrong control hash inventory')
    for name, expected in {**request['controls'], **input_files()}.items():
        require(re.fullmatch('[0-9a-f]{64}', expected or '')
                and digest(CONTROL / name) == expected, 'Unbound/changed carrier control or input')
    require(request['controls']['ci/app8-apple.py'] == OWNER_SHA
            and request['controls']['ci/app5-ios-host-commands.py'] == ADAPTER_SHA,
            'Different accepted ownership implementation or finite-log adapter')


def invocation():
    expected = {'GITHUB_ACTIONS': 'true', 'GITHUB_REPOSITORY': APP['repository'],
                'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': 'refs/heads/' + BRANCH,
                'GITHUB_RUN_ATTEMPT': '1', 'RUNNER_OS': 'macOS', 'RUNNER_ARCH': 'ARM64',
                'RUNNER_ENVIRONMENT': 'github-hosted',
                'GITHUB_WORKFLOW_REF': APP['repository'] + '/' + WORKFLOW + '@refs/heads/' + BRANCH}
    require(all(os.environ.get(k) == v for k, v in expected.items()), 'Wrong public hosted invocation')
    require(re.fullmatch('[1-9][0-9]*', os.environ.get('GITHUB_RUN_ID', '')), 'Invalid run identity')
    require(re.fullmatch('[0-9a-f]{40}', os.environ.get('GITHUB_SHA', ''))
            and os.environ.get('GITHUB_WORKFLOW_SHA') == os.environ['GITHUB_SHA'], 'Wrong event carrier')
    event = read(Path(os.environ['GITHUB_EVENT_PATH']))
    require(event['repository']['private'] is False and event['repository']['full_name'] == APP['repository']
            and event['after'] == event['head_commit']['id'] == os.environ['GITHUB_SHA']
            and event['ref'] == os.environ['GITHUB_REF'] and event['deleted'] is False
            and event['forced'] is False, 'Not the bound ordinary public push')


def admit():
    request = read(CONTROL / REQUEST)
    fixed = dict(schema='app-29-apple-compile-v1', status='PRIMARY_BOUND_FOR_REVIEW',
                 authorization='APP_29_APPLE_MAIN_COMPILE_ONE_ATTEMPT_AUTHORIZED',
                 acquisitionAuthorization='ORIGINAL4_AUTHENTICATED_AND_PUBLIC_DECLARED_GRADLE_ONLY',
                 mode='COMPILE_ONLY', ref=BRANCH, limits=LIMITS, app=APP, tasks=[TASK],
                 inputManifestSha256=MANIFEST_SHA, expectedRunAttempt=1)
    require(set(request) == set(fixed) | {'controls'} and type(request['expectedRunAttempt']) is int
            and all(request.get(k) == v for k, v in fixed.items()), 'UNBOUND/unauthorized request')
    invocation()
    controls(request)
    return request


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)  # Definitions only; no old App8 entry point or input intake.
    return module


def git(c, root, label, args, cleaning=False):
    return c['commands'].call(['/usr/bin/git', '-C', str(root), *args], label, seconds=15,
                             end=c['end'] if cleaning else c['workEnd'], cleaning=cleaning).strip()


def checkout(c, root, expected, label, cleaning=False):
    require(git(c, root, label + '-sha', ['rev-parse', 'HEAD'], cleaning) == expected['sha'], 'Wrong source SHA')
    require(git(c, root, label + '-tree', ['rev-parse', 'HEAD^{tree}'], cleaning) == expected['tree'], 'Wrong source tree')
    require(not git(c, root, label + '-clean', ['status', '--porcelain', '--untracked-files=all'], cleaning), 'Dirty checkout')
    require(not git(c, root, label + '-ignored', ['ls-files', '--others', '--ignored', '--exclude-standard'], cleaning),
            'Ignored/private inputs or unexpected output remain')
    return dict(expected, root=str(root), clean=True)


def carrier(c):
    sha = os.environ['GITHUB_SHA']
    require(git(c, CONTROL, 'carrier-parent', ['rev-list', '--parents', '-n', '1', 'HEAD']) == sha + ' ' + APP['sha'],
            'Carrier must directly extend the exact source')
    require(git(c, CONTROL, 'carrier-parent-tree', ['rev-parse', 'HEAD^1^{tree}']) == APP['tree'], 'Wrong parent tree')
    changed = git(c, CONTROL, 'carrier-delta', ['diff-tree', '--no-commit-id', '--name-status', '--no-renames',
                                             '-r', 'HEAD^1', 'HEAD']).splitlines()
    added = sorted((*FILES, REQUEST, *input_files()))
    require(sorted(changed) == sorted('A\t' + name for name in added), 'Carrier may add only controls and original26')
    require(not git(c, CONTROL, 'carrier-clean', ['status', '--porcelain', '--untracked-files=all']), 'Dirty carrier')
    return {'sha': sha, 'parent': APP, 'addedFiles': added, 'hashes': c['request']['controls']}


def prepare(c):
    e, root = c['e'], c['roots']['app']
    pins = e.read(CONTROL / PINS)
    require(pins['status'] == 'PRIMARY_BOUND_FOR_REVIEW' and pins['schema'] == 'app-29-apple-compile-source-pins-v1',
            'Source pins remain UNBOUND')
    require(pins['app_modules'] == list(e.MODULES)
            and all(pins['app'][key] == value for key, value in APP.items()), 'Different source pins')
    c['identity'] = {'carrier': carrier(c), 'before': {'app': checkout(c, root, APP, 'app')}}
    require(all(not (root / p).exists() and not (root / p).is_symlink()
                for p in (*e.OUTPUTS['app'], '.swiftpm-locks')), 'Preexisting outputs are not owned')
    c['pins'], c['before'] = pins, {'app': e.inputs(root, 'app', pins)}
    e.save(c['reports'] / 'source.json', c['before'])
    e.save(c['reports'] / 'identity.json', c['identity'])
    c['sourceReady'] = True


def unchanged_inputs(c):
    after = {'app': c['e'].inputs(c['roots']['app'], 'app', c['pins'])}
    c['identity']['sourceInputsUnchanged'] = after == c['before']
    c['e'].save(c['reports'] / 'identity.json', c['identity'])
    require(c['identity']['sourceInputsUnchanged'], 'Source inputs changed; retain outputs')
    controls(c['request'])


def final_source(c):
    if not c['sourceReady'] or not c['result'].get('outputsRemoved'):
        return False
    c['identity']['after'] = {'app': checkout(c, c['roots']['app'], APP, 'app-final', True)}
    require(not git(c, CONTROL, 'carrier-final-clean', ['status', '--porcelain', '--untracked-files=all'], True),
            'Carrier changed')
    controls(c['request'])
    c['e'].save(c['reports'] / 'identity.json', c['identity'])
    return True


def diagnostic(stage, error):
    # Fixed tokens only: no exception text, source excerpts, environment, locals or URLs.
    kinds = {'RuntimeError', 'ValueError', 'TypeError', 'KeyError', 'IndexError', 'AssertionError',
             'OSError', 'FileNotFoundError', 'PermissionError', 'TimeoutError', 'JSONDecodeError', 'UnicodeDecodeError'}
    label = stage if stage in ('entry', 'preparation', 'compile', 'cleanup') else 'unknown'
    kind = type(error).__name__ if type(error).__name__ in kinds else 'OtherException'
    print('APP29_COMPILE_ERROR ' + json.dumps({'stage': label, 'type': kind}), flush=True)
    return label + ': ' + kind
