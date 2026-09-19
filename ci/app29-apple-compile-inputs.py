"""Bounded authenticated original4 intake plus exact original26; no rebuild or substitute route."""
import hashlib
import http.client
import io
import json
import os
from pathlib import Path
import re
import ssl
import stat
import sys
import time
from urllib.parse import urlsplit
import zipfile

MANIFEST = 'ci/app29-original-inputs/manifest.json'
MANIFEST_SHA = '7bde304b50eed0695336221a3ac4a4baca1c8a4ed60332b58fa4ef613943ac38'
API_PATH = '/repos/kira-manga/kira-source-engine/actions/artifacts/10575608342'
ZIP_SHA = 'fbf850636478135f5029bffbfc4b50e4b0e1ac64c206b9faa143f64a50d98184'
ZIP_CAP, META_CAP = 1048576, 65536
STAGE, HTTP_STATUS = 'entry', None


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def parse(data):
    def pairs(rows):
        result = {}
        for key, value in rows:
            require(key not in result, 'Duplicate dependency JSON key')
            result[key] = value
        return result
    def constant(_value):
        raise RuntimeError('Nonfinite dependency JSON')
    return json.loads(data, object_pairs_hook=pairs, parse_constant=constant)


def regular_bytes(path, cap):
    require(not path.is_symlink() and path.resolve() == path and stat.S_ISREG(path.stat().st_mode)
            and path.stat().st_size <= cap, 'Nonregular/aliased/oversized dependency file')
    with path.open('rb') as stream:
        data = stream.read(cap + 1)
    require(len(data) <= cap, 'Dependency file exceeded its cap')
    return data


def manifest(control):
    raw = regular_bytes(control / MANIFEST, META_CAP)
    require(hashlib.sha256(raw).hexdigest() == MANIFEST_SHA, 'Wrong sealed dependency inventory')
    value = parse(raw)
    require(len(value['files']) == 30 and sum(row['bytes'] for row in value['files']) == 921210,
            'Wrong original30 count/bytes')
    return value


def binaries(value):
    return [row for row in value['files'] if Path(row['path']).suffix in ('.aar', '.klib')]


def verify_tree(root, rows):
    require(root.is_dir() and root.resolve() == root and not root.is_symlink(), 'Unsafe dependency root')
    expected = {row['path']: row for row in rows}
    directories = {str(parent) for name in expected for parent in Path(name).parents if str(parent) != '.'}
    files, seen_dirs, content = {}, set(), {}
    for path in sorted(root.rglob('*')):
        name = str(path.relative_to(root))
        require(not path.is_symlink() and path.resolve() == path, 'Aliased dependency entry')
        if path.is_dir():
            require(name in directories, 'Unexpected dependency directory')
            seen_dirs.add(name)
            continue
        require(name in expected, 'Unexpected dependency file')
        data, pin = regular_bytes(path, expected[name]['bytes']), expected[name]
        digest = hashlib.sha256(data).hexdigest()
        require(len(data) == pin['bytes'] and digest == pin['sha256'], 'Changed exact-original bytes')
        files[name], content[name] = {'bytes': len(data), 'sha256': digest}, data
    require(set(files) == set(expected) and seen_dirs == directories, 'Missing dependency entries')
    return files, content


def snapshot(run, value):
    root = run / 'work/dependencies'
    require(root.is_dir() and root.resolve() == root and sorted(p.name for p in root.iterdir()) == ['repository'],
            'Unexpected dependency union contents')
    files, _ = verify_tree(root / 'repository', value['files'])
    return {'manifestSha256': MANIFEST_SHA, 'sealedUnionManifestSha256': value['sealedUnionManifestSha256'],
            'files': files, 'fileCount': len(files), 'totalBytes': sum(row['bytes'] for row in files.values())}


def response_body(response, connection, cap, end):
    require(response.getheader('Content-Encoding') in (None, 'identity'), 'Unexpected HTTP content encoding')
    length = response.getheader('Content-Length')
    require(length is None or (length.isdecimal() and 0 <= int(length) <= cap), 'HTTP declared size cap')
    data = bytearray()
    while True:
        require(time.monotonic() < end, 'Artifact network deadline exceeded')
        if connection.sock is not None:
            connection.sock.settimeout(min(20, end - time.monotonic()))
        block = response.read(min(65536, cap + 1 - len(data)))
        data.extend(block)
        require(len(data) <= cap, 'Artifact response exceeded byte cap')
        if not block:
            break
    require(time.monotonic() < end and (length is None or len(data) == int(length)), 'Incomplete/late artifact response')
    return bytes(data)


def request(host, path, headers, status, cap, end):
    global HTTP_STATUS
    require(time.monotonic() < end, 'Artifact network deadline exceeded')
    # http.client never consults proxy variables, curlrc, netrc, cookies or credential helpers.
    connection = http.client.HTTPSConnection(host, timeout=min(20, end - time.monotonic()),
                                            context=ssl.create_default_context())
    try:
        connection.request('GET', path, headers=headers)
        response = connection.getresponse()
        HTTP_STATUS = response.status if 100 <= response.status <= 599 else None
        require(response.status == status, 'Artifact unavailable/unauthorized or unexpected HTTP status')
        if status == 302:
            location = response.getheader('Location', '')
            require(0 < len(location) <= 16384, 'Invalid artifact redirect')
            return location
        return response_body(response, connection, cap, end)
    finally:
        connection.close()


def download(token, value):
    global STAGE, HTTP_STATUS
    STAGE, HTTP_STATUS = 'credential', None
    require(0 < len(token) <= 4096 and token.isascii() and '\r' not in token and '\n' not in token,
            'Missing/invalid authenticated artifact credential')
    end = time.monotonic() + 90
    headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json',
               'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'Kira-original4-intake',
               'Accept-Encoding': 'identity'}
    STAGE = 'metadata'
    meta = parse(request('api.github.com', API_PATH, headers, 200, META_CAP, end))
    artifact = value['artifact']
    require(meta['id'] == artifact['id'] and meta['name'] == artifact['name'] and meta['expired'] is False
            and meta['workflow_run']['id'] == artifact['runId']
            and meta['workflow_run']['head_sha'] == artifact['headSha']
            and meta['archive_download_url'] == 'https://api.github.com' + API_PATH + '/zip'
            and type(meta['size_in_bytes']) is int and 0 < meta['size_in_bytes'] <= ZIP_CAP,
            'Artifact metadata provenance/availability/size mismatch')
    STAGE, HTTP_STATUS = 'redirect', None
    location = request('api.github.com', API_PATH + '/zip', headers, 302, 0, end)
    url = urlsplit(location)
    require(url.scheme == 'https' and url.username is None and url.password is None and url.port is None
            and not url.fragment and url.netloc == url.hostname and url.query and url.path.startswith('/')
            and re.fullmatch(r'productionresultssa[0-9]+\.blob\.core\.windows\.net', url.hostname or ''),
            'Unadmitted artifact redirect authority; no fallback')
    # Exactly one signed blob hop. Authorization is never forwarded to the redirect target.
    STAGE, HTTP_STATUS = 'archive', None
    raw = request(url.hostname, url.path + '?' + url.query,
                  {'User-Agent': 'Kira-original4-intake', 'Accept-Encoding': 'identity'}, 200, ZIP_CAP, end)
    require(hashlib.sha256(raw).hexdigest() == ZIP_SHA, 'Different original4 ZIP bytes')
    return raw, meta['size_in_bytes']


def members(raw, value):
    pins = {Path(row['path']).name: row for row in binaries(value)}
    receipt = value['artifact']['receipt']
    expected = {**pins, receipt['file']: receipt}
    require(len(pins) == 4, 'Wrong original binary count')
    content = {}
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        infos = archive.infolist()
        require(len(infos) == 5 and {item.filename for item in infos} == set(expected), 'Unexpected ZIP member set')
        for item in infos:
            pin = expected[item.filename]
            mode = item.external_attr >> 16
            require(not item.is_dir() and stat.S_IFMT(mode) in (0, stat.S_IFREG)
                    and not item.flag_bits & 1 and item.file_size == pin['bytes']
                    and item.compress_size <= ZIP_CAP, 'Nonregular/encrypted/oversized ZIP member')
            with archive.open(item) as stream:
                data = stream.read(pin['bytes'] + 1)
            require(len(data) == pin['bytes'] and hashlib.sha256(data).hexdigest() == pin['sha256'],
                    'Original4 ZIP member byte mismatch')
            content[item.filename] = data
    rows = parse(content.pop('receipt.json'))
    actual = sorted(rows, key=lambda row: row['file'])
    wanted = sorted([{'file': name, 'bytes': pin['bytes'], 'sha256': pin['sha256']}
                     for name, pin in pins.items()], key=lambda row: row['file'])
    require(actual == wanted, 'Original4 receipt content mismatch')
    return {pin['path']: content[name] for name, pin in pins.items()}


def assemble(run, content, value):
    root = run / 'work/dependencies'
    require(not root.exists() and not root.is_symlink() and root.parent.resolve() == root.parent,
            'Fresh owned dependency output required')
    root.mkdir(mode=0o700)
    for name, data in sorted(content.items()):
        path = root / 'repository' / name
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        with path.open('xb') as stream:
            stream.write(data)
    snapshot(run, value)


def main():
    global STAGE, HTTP_STATUS
    os.umask(0o077)
    token = os.environ.pop('APP29_ARTIFACT_TOKEN', '')
    require(sys.argv[1:] == [], 'No alternate artifact command accepted')
    control, run = Path(os.environ['KIRA_APP5_CONTROL']), Path(os.environ['KIRA_APP5_RUN'])
    require(control.resolve() == control and run.resolve() == run and run.is_dir(), 'Unsafe owned paths')
    STAGE = 'manifest'
    value = manifest(control)
    carrier_rows = [row for row in value['files'] if row not in binaries(value)]
    STAGE = 'carrier26'
    files, content = verify_tree(control / 'ci/app29-original-inputs/repository', carrier_rows)
    require(len(files) == 26 and sum(row['bytes'] for row in files.values()) == 429133, 'Wrong original26')
    raw, metadata_size = download(token, value)
    token = None
    STAGE, HTTP_STATUS = 'zip-members', None
    content.update(members(raw, value))
    STAGE = 'assembly'
    assemble(run, content, value)
    STAGE = 'receipt'
    report = {'status': 'EXACT_ORIGINAL30_REASSEMBLED', 'manifestSha256': MANIFEST_SHA,
              'artifact': value['artifact'], 'archiveBytes': len(raw), 'archiveSha256': ZIP_SHA,
              'metadataSizeInBytes': metadata_size, 'files': 30, 'bytes': 921210,
              'httpRequests': 3, 'redirects': 1, 'retries': 0, 'credentialForwardedToBlob': False,
              'fallback': False, 'rebuilt': False}
    data = (json.dumps(report, sort_keys=True, indent=2) + '\n').encode()
    require(len(data) <= META_CAP, 'Intake receipt cap')
    with (run / 'reports/intake.json').open('xb') as stream:
        stream.write(data)
    print('ORIGINAL4_INTAKE_VERIFIED; ORIGINAL30_REASSEMBLED', flush=True)


if __name__ == '__main__':
    try:
        main()
    except Exception:
        # Never print HTTP bodies, exceptions, signed URLs, headers, tokens or tracebacks.
        print('ORIGINAL4_INTAKE_FAILED ' + json.dumps({'stage': STAGE, 'httpStatus': HTTP_STATUS,
                                                     'gradleFallback': False}, sort_keys=True), flush=True)
        raise SystemExit(1)
