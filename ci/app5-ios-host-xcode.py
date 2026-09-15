"""Finite shipping-project staging and one shell-phase argv augmentation; no build preflight."""
import difflib
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import sys

PROJECT = 'iosApp/iosApp.xcodeproj'
EXAMPLE = 'iosApp/iosApp/GoogleService-Info.plist.example'
STAGED = 'iosApp/iosApp/GoogleService-Info.plist'
PHASE = 'Build & embed Kotlin/Compose framework'
SCRIPT = 'cd "$SRCROOT/.."\n./gradlew :composeApp:embedAndSignAppleFrameworkForXcode\n'


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def bytes_of(path, cap):
    require(path.is_file() and not path.is_symlink() and path.resolve() == path and path.stat().st_size <= cap,
            'Missing/aliased/oversized host file')
    with path.open('rb') as stream:
        data = stream.read(cap + 1)
    require(len(data) <= cap, 'Host file grew beyond cap')
    return data


def retained(c, name, data):
    require(name in c['e'].PUBLIC_CAPS and len(data) <= c['e'].PUBLIC_CAPS[name], 'Unlisted/oversized host evidence')
    fd = os.open(c['reports'] / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(data)


def stage_example(c):
    root = c['roots']['app']
    data, target = bytes_of(root / EXAMPLE, 65536), root / STAGED
    require(not target.exists() and not target.is_symlink() and target.resolve() == target, 'Preexisting plist is not owned')
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    c['exampleCreated'] = True
    with os.fdopen(fd, 'wb') as stream:
        stream.write(data)
    require(bytes_of(target, 65536) == data, 'Example staging changed')


def generator_user(c):
    identity = c['commands'].owner
    require(os.getuid() == os.geteuid() == identity['realUid'] == identity['effectiveUid'] > 0,
            'XcodeGen needs the unchanged nonroot owner')
    raw = c['commands'].call(['/usr/bin/id', '-un'], 'xcodegen-user', end=c['workEnd'])
    name = raw.removesuffix('\n')
    require(raw == name + '\n' and re.fullmatch(r'[A-Za-z_][A-Za-z0-9_.-]{0,63}', name), 'Invalid actual XcodeGen USER')
    return name


def project_object(c, project, label):
    raw = c['commands'].call(['/usr/bin/plutil', '-convert', 'json', '-o', '-', str(project)], label, end=c['workEnd'])
    return json.loads(raw, object_pairs_hook=c['unique'])


def phase_id(model):
    rows = [(key, value) for key, value in model['objects'].items()
            if value.get('isa') == 'PBXShellScriptBuildPhase' and value.get('name') == PHASE]
    require(len(rows) == 1 and rows[0][1]['shellScript'] == SCRIPT, 'Expected original embed phase absent/ambiguous/changed')
    return rows[0][0]


def augment(c, gradle_argv):
    project = c['roots']['app'] / PROJECT / 'project.pbxproj'
    before = bytes_of(project, 1048576)
    model = project_object(c, project, 'generated-project-before')
    key = phase_id(model)
    require(gradle_argv[3] == ':composeApp:embedAndSignAppleFrameworkForXcode', 'Wrong embedded Gradle root')
    command = './gradlew ' + shlex.join([gradle_argv[3], *gradle_argv[1:3], *gradle_argv[4:]])
    script = 'cd "$SRCROOT/.."\n' + command + '\n'
    old, new = ('shellScript = ' + json.dumps(value, ensure_ascii=False) + ';' for value in (SCRIPT, script))
    require(before.count(old.encode()) == 1, 'Generated shell literal absent/ambiguous; do not guess a rewrite')
    after = before.replace(old.encode(), new.encode(), 1)
    fd = os.open(project, os.O_WRONLY | os.O_TRUNC | os.O_NOFOLLOW)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(after)
    changed = project_object(c, project, 'generated-project-after')
    require(changed['objects'][key]['shellScript'] == script, 'Generated phase augmentation did not parse exactly')
    changed['objects'][key]['shellScript'] = SCRIPT
    require(changed == model, 'Generated project changed outside the sole phase argv')
    delta = ''.join(difflib.unified_diff(before.decode().splitlines(True), after.decode().splitlines(True),
                                        fromfile='pristine/project.pbxproj', tofile='augmented/project.pbxproj')).encode()
    retained(c, 'generated-project.patch', delta)
    project_receipt(c, before, after, script, delta)


def project_receipt(c, before, after, script, delta):
    sha = lambda data: hashlib.sha256(data).hexdigest()
    root = c['roots']['app']
    scheme = root / PROJECT / 'xcshareddata/xcschemes/iosApp.xcscheme'
    c['e'].save(c['reports'] / 'project.json', {'pristineSha256': sha(before), 'augmentedSha256': sha(after),
        'deltaSha256': sha(delta), 'phaseName': PHASE, 'phaseBefore': SCRIPT, 'phaseAfter': script,
        'allOtherParsedProjectFieldsUnchanged': True, 'schemeSha256': c['e'].sha(scheme),
        'projectSpecSha256': c['e'].sha(root / 'iosApp/project.yml'),
        'exampleStagedBeforeGeneration': True, 'exampleSha256': c['e'].sha(root / STAGED)})


def prepare(c, gradle_argv):
    stage_example(c)
    executable = c['gen'].acquire(c)
    username = generator_user(c)
    before = c['e'].sha(executable)
    root = c['roots']['app']
    c['commands'].call([str(executable), 'generate', '--spec', str(root / 'iosApp/project.yml'),
        '--project', str(root / 'iosApp')], 'generate-project', seconds=60, end=c['workEnd'], extra={'USER': username})
    require(c['e'].sha(executable) == before == c['gen'].XCODEGEN['executableSha256'], 'XcodeGen changed during generation')
    augment(c, gradle_argv)


def argv(c):
    work = c['run'] / 'work'
    return ['/usr/bin/xcodebuild', '-project', str(c['roots']['app'] / PROJECT), '-scheme', 'iosApp',
        '-configuration', 'Debug', '-sdk', 'iphonesimulator', '-destination', 'generic/platform=iOS Simulator',
        '-derivedDataPath', str(work / 'DerivedData'), '-clonedSourcePackagesDirPath', str(work / 'SourcePackages'),
        '-packageCachePath', str(work / 'swiftpm-cache'), '-resultBundlePath', str(work / 'host-build.xcresult'),
        '-jobs', '1', 'ARCHS=arm64', 'ONLY_ACTIVE_ARCH=YES', 'CODE_SIGNING_ALLOWED=NO',
        'CODE_SIGNING_REQUIRED=NO', 'CODE_SIGN_IDENTITY=', 'DEVELOPMENT_TEAM=',
        'CLANG_MODULE_CACHE_PATH=' + str(work / 'clang-module-cache'),
        'SWIFT_MODULE_CACHE_PATH=' + str(work / 'swift-module-cache'), 'build']


def remove_example(c):
    if c.get('exampleCreated'):
        root = c['roots']['app']
        require(bytes_of(root / STAGED, 65536) == bytes_of(root / EXAMPLE, 65536), 'Owned example changed; retain evidence')
        (root / STAGED).unlink()
        require(not (root / STAGED).exists(), 'Owned example remains')


def tools(c):
    commands, env = c['commands'], c['env']
    java = commands.call([env['JAVA_HOME'] + '/bin/java', '-version'], 'java-version', end=c['workEnd'])
    xcode = commands.call(['/usr/bin/xcodebuild', '-version'], 'xcode-version', end=c['workEnd'])
    sdk = commands.call(['/usr/bin/xcrun', '--sdk', 'iphonesimulator', '--show-sdk-version'], 'sdk-version', end=c['workEnd'])
    require(xcode.strip() == 'Xcode 26.4.1\nBuild version 17E202' and sdk.strip() == '26.4', 'Wrong installed Xcode/SDK')
    c['e'].save(c['reports'] / 'tools.json', {'python': sys.version, 'java': java, 'xcode': xcode, 'sdk': sdk,
        'developerDir': env['DEVELOPER_DIR'], 'javaReleaseSha256': c['e'].sha(Path(env['JAVA_HOME']) / 'release')})


def verify_project(c):
    report = c['reports'] / 'project.json'
    if not report.exists():
        return
    row = c['e'].read(report)
    project = c['roots']['app'] / PROJECT
    actual = {'projectSha256': c['e'].sha(project / 'project.pbxproj'),
              'schemeSha256': c['e'].sha(project / 'xcshareddata/xcschemes/iosApp.xcscheme')}
    actual['unchanged'] = actual['projectSha256'] == row['augmentedSha256'] and actual['schemeSha256'] == row['schemeSha256']
    row['afterBuild'] = actual
    c['e'].save(report, row)
    require(actual['unchanged'], 'Generated project/scheme changed during the build')
