"""Only App44's accepted pinned intake methods; no simulator/test lane is reused."""
import hashlib
import io
import json
import os
import re
from pathlib import Path, PurePosixPath
import stat
import time
import zipfile

XCODEGEN = {
    'version': '2.46.0',
    'url': 'https://github.com/yonaskolb/XcodeGen/releases/download/2.46.0/xcodegen.artifactbundle.zip',
    'archiveBytes': 4286070,
    'archiveSha256': 'ef6d0a23bfb7393387f98e321ffd78a487231172e2e78c48d3c26275c263fd0c',
    'entryCount': 47,
    'expandedBytes': 14237480,
    'layoutSha256': 'c1083f8d7cb229bf4628f1bc141756901a4f65d2328361d1cd551305da89c42d',
    'executable': 'xcodegen.artifactbundle/xcodegen-2.46.0-macosx/bin/xcodegen',
    'executableBytes': 14229032,
    'executableSha256': '8774da746668bc18fe74e54cbaf10f2631a1fb05947cd374179aa912f14f99db',
}


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def read_json(path):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 131072, 'Bounded intake JSON required')
    return json.loads(path.read_text())


class Intake:
    def __init__(self, c):
        self.c, self.owner, self.run, self.work_end = c, c['owner'], c['run'], c['workEnd']

    def save(self, name, value):
        self.c['e'].save(self.c['reports'] / name, value)

    def call(self, argv, label, end):
        return self.c['commands'].call(argv, label, end=end)


    def xcodegen_bytes(self, path, limit, end):
        require(not self.owner.CANCELLED and time.monotonic() < end and path.resolve() == path,
                'XCODEGEN_INTAKE_LATE_OR_UNSAFE_PATH')
        with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), 'rb') as stream:
            before = os.fstat(stream.fileno())
            require(stat.S_ISREG(before.st_mode) and 0 <= before.st_size <= limit <= 33554432,
                    'XCODEGEN_INTAKE_NONREGULAR_OR_OVERSIZED_FILE')
            data = stream.read(before.st_size + 1)
        after = path.lstat()
        require(not self.owner.CANCELLED and time.monotonic() < end and len(data) == before.st_size
                and (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_mode)
                == (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_mode),
                'XCODEGEN_INTAKE_LATE_OR_CHANGED_FILE')
        return data

    def intake_xcodegen(self, prerequisites):
        end = min(self.work_end, time.monotonic() + 30)
        receipt = {'status': 'STARTED', 'stage': 'curl-identity', 'pin': XCODEGEN, 'curlPath': '/usr/bin/curl', 'secondsCap': 30,
                   'expandedBytesCap': 33554432, 'entryCap': 64, 'retryCount': 0}
        prerequisites['fallback'] = receipt
        self.save('prerequisites.json', prerequisites)
        try:
            curl = Path('/usr/bin/curl')
            receipt['curlSha256'] = hashlib.sha256(self.xcodegen_bytes(curl, 33554432, end)).hexdigest()
            curl_version = self.call([curl, '-q', '--version'], 'xcodegen-curl-version', end=end).splitlines()[0]
            require(len(curl_version) <= 512, 'Curl version header oversized; raw log retained')
            receipt['curlVersion'] = curl_version
            version = re.match(r'curl (\d+)\.(\d+)\.(\d+)\b', receipt['curlVersion'])
            require(version is not None and tuple(map(int, version.groups())) >= (8, 4, 0),
                    'Curl 8.4.0+ required for a streaming byte cap; no installation')
            root = self.run / 'work/xcodegen-intake'
            root.mkdir(mode=0o700)
            archive = root / 'xcodegen.artifactbundle.zip'
            receipt['stage'] = 'download'
            self.save('prerequisites.json', prerequisites)
            downloaded = self.call([curl, '-q', '--proto', '=https', '--proto-redir', '=https', '--tlsv1.2',
                '--connect-timeout', '5', '--max-time', '30', '--max-filesize', str(XCODEGEN['archiveBytes']),
                '--max-redirs', '3', '--retry', '0', '--fail', '--silent', '--show-error', '--location',
                '--output', archive, '--write-out', '%{http_code} %{size_download}\n', XCODEGEN['url']],
                'xcodegen-download', end=end).strip()
            require(len(downloaded) <= 128, 'Unexpected download diagnostic; raw log retained')
            receipt['downloadOutput'] = downloaded
            require(downloaded == '200 ' + str(XCODEGEN['archiveBytes']), 'Unexpected upstream HTTP/byte receipt')
            receipt['stage'] = 'archive-verification'
            self.save('prerequisites.json', prerequisites)
            data = self.xcodegen_bytes(archive, XCODEGEN['archiveBytes'], end)
            receipt.update(archiveBytes=len(data), archiveSha256=hashlib.sha256(data).hexdigest())
            require(len(data) == XCODEGEN['archiveBytes'] and receipt['archiveSha256'] == XCODEGEN['archiveSha256'],
                    'Pinned upstream archive size/digest mismatch')
            receipt['stage'] = 'safe-extraction'
            self.save('prerequisites.json', prerequisites)
            layout, names = [], set()
            with zipfile.ZipFile(io.BytesIO(data)) as bundle:
                members = bundle.infolist()
                require(len(members) == XCODEGEN['entryCount'] <= 64
                        and sum(item.file_size for item in members) == XCODEGEN['expandedBytes'] <= 33554432,
                        'Unexpected/over-budget archive entry set')
                for member in members:
                    require(not self.owner.CANCELLED and time.monotonic() < end, 'Extraction cancelled/expired')
                    name, mode = PurePosixPath(member.filename), member.external_attr >> 16
                    require(member.orig_filename == member.filename and member.filename not in names
                            and not name.is_absolute() and '..' not in name.parts and '\\' not in member.filename
                            and str(name) + ('/' if member.is_dir() else '') == member.filename
                            and member.compress_type in (0, 8) and not member.flag_bits & 1
                            and stat.S_IFMT(mode) == (stat.S_IFDIR if member.is_dir() else stat.S_IFREG)
                            and not mode & (stat.S_ISUID | stat.S_ISGID | stat.S_ISVTX), 'Unsafe ZIP member')
                    names.add(member.filename)
                    target = root / str(name)
                    require(target.resolve() == target and target.is_relative_to(root), 'Unsafe extraction target')
                    with bundle.open(member) as stream:
                        content = stream.read(member.file_size + 1)
                    require(len(content) == member.file_size and time.monotonic() < end, 'Late/oversized ZIP content')
                    if member.is_dir():
                        require(not content, 'Nonempty ZIP directory')
                        target.mkdir(mode=0o700)
                    else:
                        with target.open('xb') as stream:
                            stream.write(content)  # No archive permissions, extractall, installer or executable invocation.
                        require(hashlib.sha256(self.xcodegen_bytes(target, member.file_size, end)).digest()
                                == hashlib.sha256(content).digest(), 'Extracted content changed')
                    layout.append({'path': member.filename, 'type': 'directory' if member.is_dir() else 'file',
                                   'bytes': len(content), 'sha256': hashlib.sha256(content).hexdigest()})
            layout_bytes = (json.dumps(sorted(layout, key=lambda row: row['path']), sort_keys=True, separators=(',', ':')) + '\n').encode()
            receipt['layoutSha256'] = hashlib.sha256(layout_bytes).hexdigest()
            require(receipt['layoutSha256'] == XCODEGEN['layoutSha256'], 'Reviewed archive content layout mismatch')
            info = read_json(root / 'xcodegen.artifactbundle/info.json')
            expected = {'version': XCODEGEN['version'], 'type': 'executable', 'variants': [{
                'path': 'xcodegen-2.46.0-macosx/bin/xcodegen', 'supportedTriples': ['x86_64-apple-macosx', 'arm64-apple-macosx']}]}
            require(info == {'schemaVersion': '1.0', 'artifacts': {'xcodegen': expected}}, 'Executable/version/ARM64 mapping changed')
            executable = root / XCODEGEN['executable']
            receipt['executableSha256'] = hashlib.sha256(self.xcodegen_bytes(executable, XCODEGEN['executableBytes'], end)).hexdigest()
            require(receipt['executableSha256'] == XCODEGEN['executableSha256'], 'Extracted executable digest mismatch')
            executable.chmod(0o700)  # Private owned scratch only; sibling resource bundle remains beside the binary.
            archive.unlink()
            require(not self.owner.CANCELLED and time.monotonic() < end, 'XcodeGen intake cancelled/expired')
            receipt.update(status='READY', stage='complete', archiveRemoved=True)
            return executable
        except Exception as error:
            receipt.update(status='FAILED', error=str(error))
            raise RuntimeError('XCODEGEN_INTAKE_' + receipt['stage'].upper().replace('-', '_') + '_FAILED: ' + str(error)) from error
        finally:
            self.save('prerequisites.json', prerequisites)


def acquire(c):
    require(c['request']['xcodegenIntakeAuthorization'] == 'PINNED_XCODEGEN_2_46_0_ONE_INTAKE_AUTHORIZED',
            'No author/unbound XcodeGen intake')
    intake, receipt = Intake(c), {'selection': 'one pinned upstream intake; no ambient fallback'}
    executable = intake.intake_xcodegen(receipt)
    version = c['commands'].call([str(executable), '--version'], 'xcodegen-version', end=c['workEnd']).strip()
    require(version == 'Version: ' + XCODEGEN['version'], 'Wrong pinned XcodeGen version')
    receipt['versionOutput'] = version
    intake.save('prerequisites.json', receipt)
    return executable
