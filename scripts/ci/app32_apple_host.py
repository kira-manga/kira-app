"""Installed Apple tools and one disposable simulator; no shared cache/device cleanup."""
import json
import os
from pathlib import Path
import pwd
import re
import shutil
import time
import app32_apple_owned as o

XCODE = '/Applications/Xcode_26.4.1.app/Contents/Developer'
RUNTIME = 'com.apple.CoreSimulator.SimRuntime.iOS-26-4'
DEVICE_TYPE = 'com.apple.CoreSimulator.SimDeviceType.iPhone-17'
HOME = Path(pwd.getpwuid(os.getuid()).pw_dir).resolve()
MODULES = ('app', 'composeApp', 'desktopApp', 'core', 'domain', 'data', 'data/local', 'data/remote',
           'data/download', 'platform', 'presentation', 'ui', 'sources/contracts', 'sources/engine',
           'sources/config', 'sources/legacy')
OUTPUTS = [o.ROOT / p for p in ('build', '.gradle', '.kotlin')] + [o.ROOT / m / 'build' for m in MODULES]
RESOURCES = []


def environment(java=None, packages=False, simulator=False):
    home = HOME if simulator else o.WORK / 'home'
    env = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', HOME=str(home), CFFIXED_USER_HOME=str(home),
               DEVELOPER_DIR=XCODE, LANG='en_US.UTF-8', LC_ALL='en_US.UTF-8', CI='true',
               GRADLE_USER_HOME=str(o.WORK / 'gradle'), KONAN_DATA_DIR=str(o.WORK / 'konan'),
               TMPDIR=str(o.WORK / 'tmp') + '/', TMP=str(o.WORK / 'tmp'), TEMP=str(o.WORK / 'tmp'),
               XDG_CACHE_HOME=str(o.WORK / 'home/.cache'), XDG_CONFIG_HOME=str(o.WORK / 'home/.config'),
               APP32_OWNED_ROOT=str(o.WORK), APP32_APP_ROOT=str(o.ROOT), APP32_SIMULATOR_HOME=str(HOME),
               GIT_TERMINAL_PROMPT='0', GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL='/dev/null',
               JAVA_OPTS='-Xmx256m -XX:ActiveProcessorCount=2 ' + o.MARKER +
               f' -Duser.home={o.WORK / "home"} -Djava.io.tmpdir={o.WORK / "tmp"}')
    for key in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        if os.environ.get(key):
            env[key] = os.environ[key]  # Installed SDK only; no Android task/provisioning.
    if java:
        env.update(JAVA_HOME=str(java), PATH=str(Path(java) / 'bin') + ':' + env['PATH'])
    if packages:
        o.require(0 < len(o.TOKEN) <= 512, 'Normal KIRA_PACKAGES_READ_TOKEN is required privately')
        env.update(KIRA_PACKAGES_READ_TOKEN=o.TOKEN.decode(), KIRA_PACKAGES_USER=os.environ['GITHUB_ACTOR'])
    return env


def prepare_work():
    o.require(HOME == Path(os.environ['HOME']).resolve() and HOME.is_dir(), 'Use this runner account simulator HOME')
    o.require(all(not p.exists() and not p.is_symlink() for p in OUTPUTS), 'Preserve preexisting build/cache outputs')
    o.require(o.WORK.resolve() == o.WORK and not o.WORK.is_symlink(), 'Canonical private scratch required')
    o.WORK.mkdir(exist_ok=False)
    o.record('owned.json', o.identity(o.WORK))
    for name in ('home', 'tmp', 'gradle', 'konan', 'project-cache', 'kotlin'):
        (o.WORK / name).mkdir()


def resources(commands, admission=False):
    physical = int(commands.call(['/usr/sbin/sysctl', '-n', 'hw.memsize'], 'memory-size').strip())
    raw = commands.call(['/usr/bin/vm_stat'], 'memory-pages')
    size = re.search(r'page size of (\d+) bytes', raw)
    fields = dict(re.findall(r'^(Pages [^:]+):\s+(\d+)\.', raw, re.M))
    o.require(size and all(key in fields for key in ('Pages free', 'Pages inactive', 'Pages speculative')),
              'Unknown vm_stat format; no invented memory measurement')
    available = int(size.group(1)) * sum(int(fields[k]) for k in ('Pages free', 'Pages inactive', 'Pages speculative'))
    disk = min(shutil.disk_usage(p).free for p in (o.ROOT, o.WORK.parent))
    RESOURCES.append(dict(time_utc=time.time(), physical_bytes=physical, reclaimable_estimate_bytes=available,
                          minimum_disk_free_bytes=disk))
    o.record('resources.json', RESOURCES)
    o.require(physical >= 6 * 2**30 and available >= (2 * 2**30 if admission else 512 * 2**20)
              and disk >= (12 if admission else 3) * 2**30, 'Resource floor reached; never reclaim shared state')


def tools(commands):
    o.require(Path(XCODE).is_dir(), 'Exact installed Xcode26.4.1 required; no installation/fallback')
    version = commands.call(['/usr/bin/xcodebuild', '-version'], 'xcode-version').strip()
    o.require(version == 'Xcode 26.4.1\nBuild version 17E202', 'Wrong installed Xcode build')
    o.require(commands.call(['/usr/bin/uname', '-m'], 'machine').strip() == 'arm64', 'Native ARM64 runner required')
    macos = commands.call(['/usr/bin/sw_vers', '-productVersion'], 'macos-version').strip()
    o.require(macos.startswith('26.'), 'macos-26 runner required')
    sdks = {sdk: commands.call(['/usr/bin/xcrun', '--sdk', sdk, '--show-sdk-version'], sdk + '-sdk').strip()
            for sdk in ('iphoneos', 'iphonesimulator')}
    o.require(set(sdks.values()) == {'26.4'}, 'Installed 26.4 device and simulator SDKs required')
    java = Path(commands.call(['/usr/libexec/java_home', '-v', '21'], 'java-home').strip()).resolve()
    release = (java / 'release').read_text()
    o.require(re.search(r'^JAVA_VERSION="21(?:\.|\")', release, re.M)
              and re.search(r'^OS_ARCH="(?:aarch64|arm64)"', release, re.M), 'Installed ARM JDK21 required')
    commands.call([str(java / 'bin/java'), '-version'], 'java-version')
    o.record('runtime.json', dict(xcode=version, macos=macos, sdks=sdks, java_home=str(java),
              gradle_heap='3g', native_heap='3g', metaspace='768m', workers=1, native_threads=1,
              acquisition='ordinary app Gradle; no Native/libffi repin', app61_provenance_hold=True))
    commands.env = environment(java)
    return java


def simctl(commands, args, label, seconds=20, cleaning=False):
    return commands.call(['/usr/bin/xcrun', 'simctl', *args], label, seconds=seconds,
                         env=environment(simulator=True), cleaning=cleaning)


def devices(commands, cleaning=False):
    value = json.loads(simctl(commands, ['list', 'devices', '--json'], 'device-list', cleaning=cleaning))
    return [dict(row, runtime=runtime) for runtime, rows in value['devices'].items() for row in rows]


def simulator_identity(state, row):
    udid = state['udid']
    o.require(re.fullmatch(r'[0-9A-F]{8}(?:-[0-9A-F]{4}){3}-[0-9A-F]{12}', udid), 'Invalid owned simulator UUID')
    root = HOME / 'Library/Developer/CoreSimulator/Devices' / udid
    o.require((row['udid'], row['name'], row['runtime'], row['deviceTypeIdentifier'], row['dataPath'])
              == (udid, state['name'], RUNTIME, DEVICE_TYPE, str(root / 'data')),
              'Owned simulator UUID/name/runtime/type/data path changed')
    o.require((root / 'data').resolve() == root / 'data' and (root / 'data').is_dir(), 'Aliased simulator data')
    return dict(root=str(root), directory=o.identity(root), runner_home=str(HOME))


def create_simulator(commands):
    runtimes = json.loads(simctl(commands, ['list', 'runtimes', '--json'], 'runtime-list'))['runtimes']
    matches = [row for row in runtimes if row['identifier'] == RUNTIME and row.get('isAvailable') is True]
    o.require(len(matches) == 1 and matches[0]['version'] == '26.4.1' and matches[0]['buildversion'] == '23E254a'
              and 'arm64' in matches[0]['supportedArchitectures'], 'Exact installed ARM simulator runtime required')
    state = dict(name=o.PREFIX, creating=True, udid=None, deleted=False)
    o.require(not any(row['name'] == state['name'] for row in devices(commands)), 'Preserve preexisting simulator name')
    o.record('simulator.json', state)  # Intent precedes create, including a cancelled/failed command join.
    state['udid'] = simctl(commands, ['create', state['name'], DEVICE_TYPE, RUNTIME], 'create-simulator').strip()
    rows = [row for row in devices(commands) if row['udid'] == state['udid'] or row['name'] == state['name']]
    o.require(len(rows) == 1 and rows[0]['state'] == 'Shutdown', 'Created simulator must be uniquely shut down')
    state.update(creating=False, identity=simulator_identity(state, rows[0]))
    o.record('simulator.json', state)
    o.require(not o.CANCELLED, 'Cancelled before simulator boot')
    simctl(commands, ['boot', state['udid']], 'boot-simulator')
    simctl(commands, ['bootstatus', state['udid'], '-b'], 'simulator-ready', seconds=180)
    rows = [row for row in devices(commands) if row['udid'] == state['udid']]
    o.require(len(rows) == 1 and rows[0]['state'] == 'Booted'
              and simulator_identity(state, rows[0]) == state['identity'], 'Owned simulator boot identity changed')
    return state


def dispose_simulator(commands):
    path = o.OUT / 'simulator.json'
    if not path.is_file():
        return ''
    state = json.loads(path.read_text())
    o.require(state['name'] == o.PREFIX, 'Simulator receipt belongs to another run')
    rows = [row for row in devices(commands, True) if row['name'] == state['name'] or row['udid'] == state['udid']]
    if state['deleted']:
        o.require(not rows, 'Deleted simulator unexpectedly returned')
        return state['udid'] or ''
    o.require(len(rows) <= 1 and (rows or state['creating']), 'Owned simulator missing/ambiguous; preserve scratch')
    if rows:
        state['udid'] = state['udid'] or rows[0]['udid']
        bound = simulator_identity(state, rows[0])
        o.require(state.get('identity', bound) == bound, 'Owned simulator root identity changed')
        if rows[0]['state'] != 'Shutdown':
            simctl(commands, ['shutdown', state['udid']], 'shutdown-simulator', seconds=60, cleaning=True)
        rows = [row for row in devices(commands, True) if row['udid'] == state['udid']]
        o.require(len(rows) == 1 and rows[0]['state'] == 'Shutdown'
                  and simulator_identity(state, rows[0]) == bound, 'Owned simulator shutdown unproven')
        simctl(commands, ['delete', state['udid']], 'delete-simulator', cleaning=True)
    o.require(not any(row['name'] == state['name'] or row['udid'] == state['udid'] for row in devices(commands, True)),
              'Owned simulator remains')
    state['deleted'] = True
    o.record('simulator.json', state)
    return state['udid'] or ''


def stop(commands):
    o.require(json.loads((o.OUT / 'owned.json').read_text()) == o.identity(o.WORK), 'Scratch ownership changed')
    proof = dict(attempted=False, exit_code=None)
    installed = list((o.WORK / 'gradle/wrapper/dists').glob('gradle-9.6.1-bin/*/gradle-9.6.1/bin/gradle'))
    try:
        if len(installed) == 1 and (installed[0].parents[2] / 'gradle-9.6.1-bin.zip.ok').is_file():
            proof['attempted'] = True
            proof['exit_code'], _ = commands.capture(['./gradlew', o.MARKER, '--offline', '--stop', '--console=plain'],
                                                     'gradle-stop', seconds=35, cap=128 * 1024, cleaning=True)
            time.sleep(3)  # Gradle may return immediately before its private daemon finishes exiting.
    finally:
        o.record('stop.json', proof)
    o.require(not proof['attempted'] or proof['exit_code'] == 0, 'Owned offline Gradle stop failed')


def cleanup():
    o.require(json.loads((o.OUT / 'owned.json').read_text()) == o.identity(o.WORK), 'Scratch ownership changed')
    for path in OUTPUTS + [o.WORK]:
        o.require(not path.is_symlink() and path.resolve() == path and not path.is_mount(), 'Ambiguous cleanup path')
        if path.exists():
            o.identity(path)
            shutil.rmtree(path)
    state = dict(fresh_outputs_absent=all(not p.exists() and not p.is_symlink() for p in OUTPUTS + [o.WORK]))
    o.record('cleanup.json', state)
    o.require(state['fresh_outputs_absent'], 'Owned fresh-output cleanup incomplete')
