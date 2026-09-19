"""Real ended main-task/KLIB proof and ordinary exact-original ARM dependency observations."""
from pathlib import Path
import stat
import time
import zipfile

MANIFEST_SHA = '7bde304b50eed0695336221a3ac4a4baca1c8a4ed60332b58fa4ef613943ac38'
TASK = ':composeApp:compileKotlinIosSimulatorArm64'


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def klib_manifest(path):
    if path.is_dir():
        manifest, linkdata = path / 'default/manifest', path / 'default/linkdata'
        require(manifest.resolve() == manifest and not manifest.is_symlink()
                and stat.S_ISREG(manifest.stat().st_mode) and manifest.stat().st_size <= 65536,
                'Invalid unpacked KLIB manifest')
        require(linkdata.resolve() == linkdata and linkdata.is_dir() and not linkdata.is_symlink()
                and any(p.is_file() for p in linkdata.rglob('*')), 'Missing KLIB linkdata')
        data = manifest.read_bytes()
    else:
        with zipfile.ZipFile(path) as archive:
            infos = archive.infolist()
            names = [item.filename for item in infos]
            require(0 < len(infos) <= 4096 and len(set(names)) == len(names)
                    and 'default/manifest' in names
                    and any(name.startswith('default/linkdata/') and not name.endswith('/') for name in names),
                    'Missing/ambiguous packed KLIB metadata')
            info = archive.getinfo('default/manifest')
            require(info.file_size <= 65536 and not info.is_dir(), 'Oversized packed KLIB manifest')
            with archive.open(info) as stream:
                data = stream.read(65537)
    require(len(data) <= 65536 and 'native_targets=ios_simulator_arm64' in data.decode('utf-8').splitlines(),
            'Not an actual ARM simulator-target KLIB')


def graph(c, observations):
    required = {':' + module.replace('/', ':') + ':compileKotlinIosSimulatorArm64' for module in c['e'].MODULES}
    require(set(observations) == {'inputManifestSha256', 'requested', 'dryRun', 'excluded', 'graph', 'states', 'resolved'}
            and observations['inputManifestSha256'] == MANIFEST_SHA and observations['requested'] == [TASK]
            and observations['dryRun'] is False and observations['excluded'] == [], 'Wrong compile invocation observation')
    paths, states = observations['graph'], observations['states']
    require(0 < len(paths) <= 512 and len(set(paths)) == len(paths) and set(states) == set(paths)
            and required <= set(paths), 'Missing/duplicate ordinary main compile task')
    require(all(row['ended'] is True and row['task'] == name and row.get('failure') is None
                for name, row in states.items()), 'Incomplete/failed compile graph')
    return [(name, states[name]) for name in sorted(required)]


def main_klibs(c, observations, result):
    total = 0
    for name, row in graph(c, observations):
        require(time.monotonic() < c['end'] and c['e'].completed(row)
                and row['target'] == 'ios_simulator_arm64' and 'KotlinNativeCompile' in row['type'],
                'Fresh executed main KotlinNativeCompile required')
        module = name.rsplit(':', 1)[0].strip(':').replace(':', '/')
        base = c['roots']['app'] / module / 'build/classes/kotlin/iosSimulatorArm64/main'
        path = Path(row['klib'])
        require(path.resolve() == path and not path.is_symlink() and path.is_relative_to(base)
                and path.exists(), 'Unowned/missing main KLIB output')
        mark = c['e'].fingerprint(path, c['end'], maximum=268435456)
        total += mark['bytes']
        require(total <= 1073741824, 'Aggregate KLIB fingerprint cap exceeded')
        klib_manifest(path)
        result['mainKlibs'].append({'task': name, 'state': row, 'fingerprint': mark,
                                   'nativeTarget': 'ios_simulator_arm64', 'linkdataPresent': True})
        result['mainKlibsProven'] = len(result['mainKlibs'])
    result['fingerprintedBytes'] = total


def dependencies(c, observations, result):
    value = c['inputs'].manifest(Path(c['env']['KIRA_APP5_CONTROL']))
    pins = {row['path']: row for row in value['files']}
    rows = observations['resolved']
    require(0 < len(rows) <= 256, 'Missing/oversized ordinary ARM dependency observations')
    projects = {':' + module.replace('/', ':') for module in c['e'].MODULES}
    seen = {}
    for row in rows:
        pin = pins[row['unionPath']]
        path = Path(row['path'])
        family, configuration = row['family'], row['configuration'].lower()
        require(time.monotonic() < c['end'] and row['project'] in projects
                and family in ('source-engine', 'source-contract') and row['group'] == 'me.manga.kira.source'
                and row['module'] in (family, family + '-iossimulatorarm64') and row['version'] == '0.1.0'
                and 'iossimulatorarm64' in configuration and configuration.endswith('compileklibraries')
                and 'test' not in configuration and pin['path'].endswith(family + '-iossimulatorarm64-0.1.0.klib')
                and row['sha256'] == pin['sha256'] and row['bytes'] == pin['bytes'], 'Wrong observed ARM original')
        require(path.resolve() == path and (path.is_relative_to(c['run'] / 'gradle-home')
                or path.is_relative_to(c['run'] / 'work/dependencies/repository'))
                and path.stat().st_size == pin['bytes'] and c['e'].sha(path) == pin['sha256'],
                'Changed/unowned resolved exact ARM original')
        seen.setdefault(row['project'], set()).add(family)
    require(all(seen.get(project) == {'source-engine', 'source-contract'}
                for project in (':composeApp', ':sources:engine')),
            'Both originals must resolve in ordinary engine and full composition main closures')
    result['resolvedArmInputs'] = rows


def collect(c):
    result = {'passed': False, 'mainKlibsProven': 0, 'mainKlibs': [], 'errors': [],
              'scope': 'FULL_ORDINARY_COMPOSITION_ARM_MAIN_KLIB_ONLY',
              'testsExecuted': 0, 'linkFrameworkHostRuntimeSigningPhysicalCredit': False}
    try:
        require(c['result'].get('afterWorkStop', {}).get('absent') is True, 'No compile evidence before owned absence')
        observations = c['e'].read(c['reports'] / 'compile-tasks.json')
        main_klibs(c, observations, result)
        dependencies(c, observations, result)
        require(result['mainKlibsProven'] == len(c['e'].MODULES) and time.monotonic() < c['end'],
                'Incomplete or late main compile proof')
        result['passed'] = True
    except Exception:
        result['errors'].append('Actual ended main-task/KLIB/original-dependency proof incomplete')
    c['e'].save(c['reports'] / 'compile-proof.json', result)
    return result
