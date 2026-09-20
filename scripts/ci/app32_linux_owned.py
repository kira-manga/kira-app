"""Owned runtime for the one App32 Linux batch; never reclaim shared runner state."""
import hashlib
import json
import os
from pathlib import Path
import select
import shutil
import signal
import subprocess
import time

ROOT = Path.cwd().resolve()
PREFIX = 'app32-linux-batch-01-' + os.environ['GITHUB_RUN_ID'] + '-' + os.environ['GITHUB_RUN_ATTEMPT']
WORK = Path(os.environ['RUNNER_TEMP']) / (PREFIX + '-work')
OUT = WORK.parent / (PREFIX + '-evidence')
MODULES = ('app', 'composeApp', 'desktopApp', 'core', 'domain', 'data', 'data/local', 'data/remote',
           'data/download', 'platform', 'presentation', 'ui', 'sources/contracts', 'sources/engine',
           'sources/config', 'sources/legacy')
OUTPUTS = [ROOT / p for p in ('build', '.gradle', '.kotlin')] + [ROOT / m / 'build' for m in MODULES]
MARKER = '-Dapp32.owned.root=' + str(WORK)
ENV_MARKER = ('APP32_OWNED_ROOT=' + str(WORK)).encode()
OWNER = dict(run=os.environ['GITHUB_RUN_ID'], sha=os.environ['GITHUB_SHA'], root=str(ROOT))
TOKEN = os.environ.pop('KIRA_PACKAGES_READ_TOKEN', '').encode()
RESOURCES = []
OUT_OWNED = False
os.umask(0o077)


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def redact(value):
    return value.replace(TOKEN, b'[REDACTED]') if TOKEN else value


def record(name, value):
    data = redact((json.dumps(value, indent=2) + '\n').encode())
    require(len(data) <= 1024 * 1024, 'JSON evidence exceeds 1MiB')
    (OUT / name).write_bytes(data)


def identity(path):
    require(not path.is_symlink() and path.resolve() == path and not path.is_mount(), 'Ambiguous owned path')
    info = path.stat()
    require(path.is_dir() and info.st_uid == os.getuid(), 'Not an owned directory')
    return dict(owner=OWNER, device=info.st_dev, inode=info.st_ino)


def begin_evidence():
    global OUT_OWNED
    require(OUT.resolve() == OUT and not OUT.is_symlink(), 'Canonical evidence path required')
    OUT.mkdir(exist_ok=False)
    record('evidence-owned.json', identity(OUT))
    OUT_OWNED = True


def resources(admission=False):
    memory = int(next(line.split()[1] for line in Path('/proc/meminfo').read_text().splitlines()
                      if line.startswith('MemAvailable:'))) * 1024
    disk = min(shutil.disk_usage(p).free for p in (ROOT, WORK.parent))
    RESOURCES.append(dict(time_utc=time.time(), memory_available_bytes=memory, minimum_disk_free_bytes=disk))
    record('resources.json', RESOURCES)
    require(memory >= (6 if admission else 1) * 2**30 and disk >= (8 if admission else 3) * 2**30,
            'Resource floor reached; stop without deleting shared caches/images')


def prepare_work():
    require(all(not p.exists() and not p.is_symlink() for p in OUTPUTS), 'Preserve preexisting outputs/caches')
    require(WORK.resolve() == WORK and not WORK.is_symlink(), 'Canonical scratch path required')
    resources(admission=True)
    WORK.mkdir(exist_ok=False)
    record('owned.json', identity(WORK))
    for name in ('home', 'tmp', 'gradle', 'project-cache', 'konan'):
        (WORK / name).mkdir()


def copy_installed_sdk(installed):
    sdk = WORK / 'sdk'
    sdk.mkdir()
    copied = []
    platform = next((p for p in ('platforms/android-37.0', 'platforms/android-37')
                     if (installed / p / 'android.jar').is_file()), 'platforms/android-37.0')
    for relative in (platform, 'build-tools/36.0.0', 'licenses'):
        source = installed / relative
        if source.is_dir():
            require(source.resolve().is_relative_to(installed), 'SDK component escapes installed root')
            (sdk / relative).parent.mkdir(parents=True, exist_ok=True)
            shutil.copytree(source, sdk / relative)
            copied.append(relative)
    return copied


def sdk_properties(directory):
    file = directory / 'source.properties'
    require(file.is_file() and not file.is_symlink() and file.stat().st_size <= 65536, 'Missing/bounded SDK metadata')
    return dict(tuple(part.strip() for part in line.split('=', 1))
                for line in file.read_text().splitlines() if '=' in line and not line.lstrip().startswith('#'))


def sdk_identity():
    sdk = WORK / 'sdk'
    platform = next((sdk / p for p in ('platforms/android-37.0', 'platforms/android-37')
                     if (sdk / p / 'android.jar').is_file()), None)
    require(platform is not None, 'SDK37 platform is missing')
    properties = sdk_properties(platform)
    require(properties.get('AndroidVersion.ApiLevel') in ('37', '37.0')
            and properties.get('AndroidVersion.CodeName', 'REL') == 'REL', 'Stable API37 only; no aliases/preview')
    tools = sdk / 'build-tools/36.0.0'
    require(sdk_properties(tools).get('Pkg.Revision') == '36.0.0', 'Exact build-tools36.0.0 required')
    hashes = {}
    for path in (platform / 'android.jar', tools / 'aapt2', tools / 'd8'):
        require(path.is_file() and path.resolve() == path, 'Regular owned SDK input required')
        with path.open('rb') as file:
            hashes[str(path.relative_to(sdk))] = hashlib.file_digest(file, 'sha256').hexdigest()
    return dict(platform=platform.name, api=properties['AndroidVersion.ApiLevel'],
                platform_revision=properties.get('Pkg.Revision'), build_tools='36.0.0', sha256=hashes)


def prepare_sdk():
    supplied = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    require(supplied, 'Installed SDK root required to reuse tools/licenses')
    installed, sdk = Path(supplied).resolve(), WORK / 'sdk'
    copied = copy_installed_sdk(installed)
    missing = ([] if any((sdk / p / 'android.jar').is_file() for p in
                        ('platforms/android-37.0', 'platforms/android-37')) else ['platforms;android-37.0'])
    if not (sdk / 'build-tools/36.0.0/aapt2').is_file():
        require(not (sdk / 'build-tools/36.0.0').exists(), 'Partial installed build tools; preserve and fail')
        missing.append('build-tools;36.0.0')
    proof = dict(installed_root=str(installed), owned_root=str(sdk), copied=copied, requested_packages=missing)
    record('sdk-provision.json', proof)
    if missing:
        manager = (installed / 'cmdline-tools/latest/bin/sdkmanager').resolve()
        require(manager.is_file() and manager.is_relative_to(installed), 'Installed sdkmanager required; no bootstrap')
        command = [str(manager), '--sdk_root=' + str(sdk), '--install', *missing]
        proof['exit_code'] = capture(command, runtime_env(), 'sdk.log', 240, 2 * 1024 * 1024, monitor=True)
        record('sdk-provision.json', proof)
        require(proof['exit_code'] == 0, 'Exact SDK provision failed; inspect sdk.log, including license/account requirements')
    require(not owned_processes(), 'SDK provision left owned workers; stop before Gradle')
    proof['inputs'] = sdk_identity()
    record('sdk-provision.json', proof)
    resources()


def runtime_env(packages=False):
    java = Path(os.environ['JAVA_HOME_21_X64'])
    env = {k: os.environ[k] for k in ('PATH', 'LANG', 'LC_ALL', 'ANDROID_HOME', 'ANDROID_SDK_ROOT') if k in os.environ}
    env.update(JAVA_HOME=str(java), PATH=str(java / 'bin') + ':' + env['PATH'], HOME=str(WORK / 'home'),
               ANDROID_HOME=str(WORK / 'sdk'), ANDROID_SDK_ROOT=str(WORK / 'sdk'),
               TMPDIR=str(WORK / 'tmp'), TMP=str(WORK / 'tmp'), TEMP=str(WORK / 'tmp'),
               GRADLE_USER_HOME=str(WORK / 'gradle'), KONAN_DATA_DIR=str(WORK / 'konan'),
               XDG_CACHE_HOME=str(WORK / 'home/.cache'), XDG_CONFIG_HOME=str(WORK / 'home/.config'),
               APP32_OWNED_ROOT=str(WORK), APP32_APP_ROOT=str(ROOT), GIT_TERMINAL_PROMPT='0',
               JAVA_OPTS='-Xmx256m -XX:ActiveProcessorCount=2 ' + MARKER,
               JAVA_TOOL_OPTIONS='-Xmx512m -XX:MaxMetaspaceSize=512m -XX:ActiveProcessorCount=2 '
               + f'-Duser.home={WORK / "home"} -Djava.io.tmpdir={WORK / "tmp"}')
    if packages:
        require(0 < len(TOKEN) <= 512, 'KIRA_PACKAGES_READ_TOKEN must be supplied privately')
        env.update(KIRA_PACKAGES_READ_TOKEN=TOKEN.decode(), KIRA_PACKAGES_USER=os.environ['GITHUB_ACTOR'])
    return env


def capture(command, env, name, seconds, limit, monitor=False):
    deadline, next_check = time.monotonic() + seconds, time.monotonic() + 60
    process = subprocess.Popen(command, cwd=ROOT, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, start_new_session=True)
    pending = b''
    try:
        with (OUT / name).open('wb') as log:
            while True:
                require(time.monotonic() < deadline, name + ': command deadline exceeded')
                if monitor and time.monotonic() >= next_check:
                    resources()
                    next_check = time.monotonic() + 60
                if not select.select([process.stdout], [], [], 0.2)[0]:
                    continue
                chunk = os.read(process.stdout.fileno(), 65536)
                if not chunk:
                    log.write(redact(pending))
                    break
                limit -= len(chunk)
                require(limit >= 0, name + ': log limit exceeded')
                pending = redact(pending + chunk)
                keep = min(max(0, len(TOKEN) - 1), len(pending))
                cut = len(pending) - keep
                log.write(pending[:cut])
                pending = pending[cut:]
        return process.wait(timeout=max(1, deadline - time.monotonic()))
    finally:
        process.stdout.close()


def marked(entry):
    if entry.stat().st_uid != os.getuid():
        return False
    return (MARKER.encode() in (entry / 'cmdline').read_bytes().split(b'\0')
            or ENV_MARKER in (entry / 'environ').read_bytes().split(b'\0'))


def owned_processes(sig=None):
    # Exact marker also covers inherited-environment Android native/Gradle worker children.
    count = 0
    for entry in Path('/proc').iterdir():
        if not entry.name.isdigit() or int(entry.name) == os.getpid():
            continue
        fd = None
        try:
            if not marked(entry):
                continue
            fd = os.pidfd_open(int(entry.name))
            if not marked(entry):
                continue
            if sig is not None:
                signal.pidfd_send_signal(fd, sig)
            count += 1
        except (FileNotFoundError, ProcessLookupError):
            pass
        finally:
            if fd is not None:
                os.close(fd)
    return count


def await_owned_exit(seconds):
    deadline = time.monotonic() + seconds
    while owned_processes() and time.monotonic() < deadline:
        time.sleep(0.2)


def stop_owned():
    if not (OUT / 'owned.json').is_file():
        return
    receipt = json.loads((OUT / 'owned.json').read_text())
    require(receipt['owner'] == OWNER, 'Scratch receipt belongs to another run/source')
    if WORK.exists():
        require(receipt == identity(WORK), 'Scratch ownership changed')
    stopped = dict(attempted=False, exit_code=None, error=None, term_signals=0, kill_signals=0)
    if (OUT / 'stop.json').is_file():
        stopped = json.loads((OUT / 'stop.json').read_text())
    else:
        installed = list((WORK / 'gradle/wrapper/dists').glob('gradle-9.6.1-bin/*/gradle-9.6.1/bin/gradle'))
        if len(installed) == 1 and (installed[0].parents[2] / 'gradle-9.6.1-bin.zip.ok').is_file():
            stopped['attempted'] = True
            try:
                stopped['exit_code'] = capture(['./gradlew', MARKER, '--offline', '--stop', '--console=plain'],
                                              runtime_env(), 'stop.log', 35, 128 * 1024)
            except BaseException as error:
                stopped['error'] = type(error).__name__ + ': ' + str(error)[:512]
    try:
        await_owned_exit(3)  # A successful --stop may return just before its daemon exits.
        for sig, seconds, key in ((signal.SIGTERM, 8, 'term_signals'), (signal.SIGKILL, 4, 'kill_signals')):
            stopped[key] += owned_processes(sig)
            await_owned_exit(seconds)
        stopped['remaining'] = owned_processes()
        require(stopped['remaining'] == 0, 'Owned runtime remains; preserve files for runner disposal')
    finally:
        record('stop.json', stopped)


def cleanup():
    if not (OUT / 'owned.json').is_file():
        return
    stop_owned()
    for path in OUTPUTS + [WORK]:
        require(not path.is_symlink() and path.resolve() == path and not path.is_mount(), 'Ambiguous cleanup path')
        if path.exists():
            identity(path)
            shutil.rmtree(path)
    state = dict(owned_processes_absent=not owned_processes(),
                 fresh_outputs_absent=all(not p.exists() and not p.is_symlink() for p in OUTPUTS + [WORK]))
    record('cleanup.json', state)
    require(all(state.values()), 'Owned cleanup incomplete')


def discard():
    if OUT.exists():
        require(json.loads((OUT / 'evidence-owned.json').read_text()) == identity(OUT), 'Evidence ownership changed')
        require(not owned_processes() and all(not p.exists() and not p.is_symlink() for p in OUTPUTS + [WORK]),
                'Keep evidence if runtime/output cleanup is incomplete')
        shutil.rmtree(OUT)
