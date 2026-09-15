#!/usr/bin/env python3
"""Install named, hash-verified runtime archives; the hosted OS remains the bootstrap trust root."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import posixpath
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[2]
ROLES = ("java", "ruby", "native")
MAX_ARCHIVE = 1024 ** 3
MAX_EXPANDED = 4 * 1024 ** 3
RUBY_PREFIXES = {
    "linux-x64": Path("/opt/hostedtoolcache/Ruby/3.3.12/x64"),
    "macos-arm64": Path("/Users/runner/hostedtoolcache/Ruby/3.3.12/arm64"),
}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON key")
        result[key] = value
    return result


def read_json(path):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 1048576, "invalid JSON input")
    return json.loads(path.read_text(), object_pairs_hook=unique)


def digest(path):
    with path.open("rb") as stream:
        return stream_digest(stream)


def stream_digest(stream):
    value = hashlib.sha256()
    for data in iter(lambda: stream.read(1048576), b""):
        value.update(data)
    return value.hexdigest()


def identity(path):
    info = path.lstat()
    require(stat.S_ISDIR(info.st_mode) and path.resolve() == path, "directory is missing or aliased")
    return [info.st_dev, info.st_ino, info.st_uid]


def host():
    if sys.platform == "linux" and platform.machine() == "x86_64":
        values = dict(line.split("=", 1) for line in Path("/etc/os-release").read_text().splitlines() if "=" in line)
        require(values.get("ID", "").strip('"') == "ubuntu" and values.get("VERSION_ID", "").strip('"') == "24.04",
                "only the reviewed Ubuntu24.04 host is supported")
        return "linux-x64"
    require(sys.platform == "darwin" and platform.machine() == "arm64" and platform.mac_ver()[0].startswith("26."),
            "only the reviewed macOS26 ARM64 host is supported")
    return "macos-arm64"


def archive_name(name):
    path = PurePosixPath(name)
    require(not path.is_absolute() and ".." not in path.parts and not any(c in name for c in "\\\x00\r\n"),
            "unsafe archive path")
    return str(path)


def tree_snapshot(root):
    require(root.is_dir() and root.resolve() == root, "invalid installed root")
    result = {}
    for path in root.rglob("*"):
        name = path.relative_to(root).as_posix()
        info = path.lstat()
        if stat.S_ISLNK(info.st_mode):
            # Lexical containment alone misses '..' after an intermediate symlink.
            target = path.resolve()
            require(target == root or root in target.parents, "installed symlink escapes authenticated tree")
            result[name] = ["link", os.readlink(path)]
        elif stat.S_ISDIR(info.st_mode):
            result[name] = ["directory"]
        else:
            require(stat.S_ISREG(info.st_mode), "unexpected installed file type")
            result[name] = ["file", info.st_size, info.st_mode & 0o777, digest(path)]
    return result


def extract_verified(archive, destination, pin):
    # Both checks precede archive parsing, extraction, runtime execution or cache acceptance.
    require(archive.is_file() and not archive.is_symlink() and 0 < archive.stat().st_size <= MAX_ARCHIVE,
            "invalid runtime archive")
    require(not pin.get("size") or archive.stat().st_size == pin["size"], "runtime archive size mismatch")
    require(digest(archive) == pin["sha256"], "runtime archive checksum mismatch")
    require(destination.is_dir() and destination.resolve() == destination and not any(destination.iterdir()),
            "runtime extraction requires a fresh private directory")
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        require(0 < len(members) <= 100000 and sum(m.size for m in members) <= MAX_EXPANDED, "runtime archive expansion cap")
        roots = {archive_name(member.name).split("/", 1)[0] for member in members}
        require(len(roots) == 1, "runtime archive must have exactly one root")
        root_name = roots.pop()
        require(re.fullmatch(r"[A-Za-z0-9_+-][A-Za-z0-9_.+-]*", root_name) and
                ("directory" not in pin or pin["directory"] == root_name), "invalid archive root")
        by_name, expected, links = {}, {}, set()
        for member in members:
            name = archive_name(member.name)
            require(name not in by_name and (name == root_name or name.startswith(root_name + "/")),
                    "duplicate or foreign archive root")
            require(member.isdir() or member.isfile() or member.issym() or member.islnk(), "unsupported archive member")
            require(not member.mode & 0o7000, "privileged runtime archive mode")
            by_name[name] = member
            if member.issym():
                require(not PurePosixPath(member.linkname).is_absolute() and
                        not any(c in member.linkname for c in "\\\x00\r\n"), "unsafe archive symlink")
                target = posixpath.normpath(str(PurePosixPath(name).parent / member.linkname))
                require(target == root_name or target.startswith(root_name + "/"), "escaping archive symlink")
                links.add(name)
            if member.islnk():
                target = archive_name(member.linkname)
                require(target.startswith(root_name + "/"), "escaping archive hardlink")
        require(root_name not in by_name or by_name[root_name].isdir(), "archive root is not a directory")
        for name, member in by_name.items():
            require(not any(str(parent) in links for parent in PurePosixPath(name).parents), "archive descends through a symlink")
            require(all(str(parent) not in by_name or by_name[str(parent)].isdir()
                        for parent in PurePosixPath(name).parents), "archive descends through a non-directory")
            if member.islnk():
                target = by_name.get(archive_name(member.linkname))
                require(target is not None and target.isfile(), "archive hardlink must target a regular member")
            if name == root_name:
                continue
            relative = name[len(root_name) + 1:]
            for parent in PurePosixPath(relative).parents:
                if str(parent) != ".":
                    expected.setdefault(str(parent), ["directory"])
            if member.isdir():
                expected[relative] = ["directory"]
            elif member.issym():
                expected[relative] = ["link", member.linkname]
            else:
                content = by_name[archive_name(member.linkname)] if member.islnk() else member
                with source.extractfile(content) as stream:
                    expected[relative] = ["file", content.size, content.mode & 0o777, stream_digest(stream)]
        # The complete member/link inventory was checked above; no downloaded installer is run.
        if hasattr(tarfile, "fully_trusted_filter"):
            source.extractall(destination, filter="fully_trusted")
        else:  # Apple's system Python also supports the reviewed pre-filter tarfile API.
            source.extractall(destination)
    installed = destination / root_name
    require(tree_snapshot(installed) == expected, "extracted runtime tree differs from authenticated archive")
    return installed, expected


def bootstrap_environment():
    return {"PATH": "/usr/bin:/bin:/usr/sbin:/sbin", "HOME": os.environ["HOME"], "LANG": "C", "LC_ALL": "C"}


def download(pin, target):
    require(re.fullmatch(r"https://(?:github\.com|download\.jetbrains\.com)/[^\s?#]+", pin["url"]), "unreviewed runtime URL")
    require(re.fullmatch(r"[0-9a-f]{64}", pin["sha256"]), "missing runtime archive digest")
    subprocess.run(["/usr/bin/curl", "--fail", "--silent", "--show-error", "--location",
        "--proto", "=https", "--proto-redir", "=https", "--tlsv1.2", "--connect-timeout", "15",
        "--max-time", "180", "--max-filesize", str(pin.get("size", MAX_ARCHIVE)),
        "--output", str(target), pin["url"]], check=True, timeout=190, env=bootstrap_environment())


def publish(values, paths=()):
    for filename, data in (("GITHUB_ENV", "".join(key + "=" + str(value) + "\n" for key, value in values.items())),
                           ("GITHUB_PATH", "".join(str(path) + "\n" for path in paths))):
        if not data:
            continue
        path = Path(os.environ[filename])
        require(path.is_file() and not path.is_symlink() and all("\n" not in str(v) and "\r" not in str(v)
                for v in (*values.values(), *paths)), "invalid environment output")
        with path.open("a") as stream:
            stream.write(data)


def save(area, receipt):
    path = area / "owned.json"
    require(not path.is_symlink(), "aliased runtime receipt")
    path.write_text(json.dumps(receipt, sort_keys=True, indent=2) + "\n")


def cleanup_area(area, receipt, temporary):
    role = receipt.get("role")
    require(role in ROLES and area.parent == temporary and re.fullmatch("kira-verified-" + role + r"-[a-z0-9_]{8}", area.name)
            and identity(area) == receipt["identity"] and receipt["identity"][2] == os.getuid(), "unowned runtime cleanup path")
    if receipt.get("createdRuby"):
        prefix = RUBY_PREFIXES[receipt["host"]]
        require(str(prefix) == receipt["rubyPrefix"] and identity(prefix) == receipt["rubyIdentity"], "Ruby prefix ownership changed")
        marker = Path(str(prefix) + ".complete")
        if receipt.get("createdRubyMarker"):
            info = marker.lstat()
            require(stat.S_ISREG(info.st_mode) and info.st_size == 0 and
                    [info.st_dev, info.st_ino, info.st_uid] == receipt["rubyMarkerIdentity"], "Ruby cache marker changed")
            marker.unlink()
        shutil.rmtree(prefix)
    shutil.rmtree(area)


def install(role, current_host, temporary, pins):
    require(role != "native" or current_host == "macos-arm64", "Native archive installation is Apple-only")
    require(not os.environ.get("KIRA_VERIFIED_" + role.upper() + "_AREA"), "runtime role is already installed")
    area = Path(tempfile.mkdtemp(prefix="kira-verified-" + role + "-", dir=temporary))
    receipt = {"role": role, "host": current_host, "identity": identity(area), "archives": [], "createdRuby": False}
    save(area, receipt)
    area_key = "KIRA_VERIFIED_" + role.upper() + "_AREA"
    published_area = False
    try:
        # Publish ownership, not a usable runtime, so an interrupted install can still be cleaned.
        publish({area_key: str(area)})
        published_area = True
        entries = pins[role][current_host]
        if not isinstance(entries, list):
            entries = [entries]
        installed, inventories = [], []
        for index, pin in enumerate(entries):
            archive = area / (str(index) + ".tar.gz")
            unpack = area / str(index)
            unpack.mkdir(mode=0o700)
            download(pin, archive)
            directory, inventory = extract_verified(archive, unpack, pin)
            installed.append(directory)
            inventories.append(inventory)
            receipt["archives"].append({"url": pin["url"], "sha256": pin["sha256"], "bytes": archive.stat().st_size,
                                        "directory": str(directory), "files": len(inventory)})
            archive.unlink()
        values, paths = {}, []
        if role == "java":
            # Discover the layout from authenticated bytes, not a guessed provider tar prefix.
            candidates = list(installed[0].rglob("bin/java"))
            require(len(candidates) == 1 and stat.S_ISREG(candidates[0].lstat().st_mode), "ambiguous verified Java executable")
            home = candidates[0].parents[1]
            require(home.resolve() == home and (home / "release").is_file(), "missing verified Java home")
            version = subprocess.run([str(home / "bin/java"), "-version"], check=True, timeout=15,
                env=bootstrap_environment(), stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout.decode("utf-8")
            require('openjdk version "21.0.12.1"' in version and "Temurin-21.0.12.1+1" in version, "wrong verified Java runtime")
            values["JAVA_HOME"], paths = str(home), [home / "bin"]
        elif role == "ruby":
            prefix = RUBY_PREFIXES[current_host]
            require(Path(os.environ["RUNNER_TOOL_CACHE"]).resolve() == prefix.parents[2], "wrong embedded Ruby tool-cache prefix")
            marker = Path(str(prefix) + ".complete")
            if prefix.exists() or prefix.is_symlink():
                require(prefix.resolve() == prefix and tree_snapshot(prefix) == inventories[0], "preinstalled Ruby differs from authenticated archive")
                require(marker.is_file() and not marker.is_symlink() and marker.stat().st_size == 0, "preinstalled Ruby has no valid cache marker")
            else:
                require(not marker.exists() and not marker.is_symlink(), "preexisting incomplete Ruby marker")
                prefix.parent.mkdir(parents=True, exist_ok=True)
                require(prefix.parent.resolve() == prefix.parent, "aliased Ruby prefix parent")
                prefix.mkdir(mode=0o700)
                receipt.update(createdRuby=True, rubyPrefix=str(prefix), rubyIdentity=identity(prefix))
                save(area, receipt)
                shutil.copytree(installed[0], prefix, symlinks=True, dirs_exist_ok=True)
                require(tree_snapshot(prefix) == inventories[0], "installed Ruby differs from authenticated archive")
                with marker.open("x") as stream:
                    info = os.fstat(stream.fileno())
                    receipt.update(createdRubyMarker=True, rubyMarkerIdentity=[info.st_dev, info.st_ino, info.st_uid])
                save(area, receipt)
            receipt["rubyPrefix"] = str(prefix)
            values["KIRA_VERIFIED_RUBY_PREFIX"] = str(prefix)
        else:
            native_home = installed[0]
            properties = native_home / "konan/konan.properties"
            before = properties.read_bytes()
            active_false = re.compile(rb"(?m)^airplaneMode = false\n")
            require(len(active_false.findall(before)) == 1 and len(re.findall(rb"(?m)^\s*airplaneMode\s*[:=]", before)) == 1 and
                    not properties.is_symlink(), "unsupported Native offline-policy input")
            properties.write_bytes(active_false.sub(b"airplaneMode = true\n", before, count=1))
            receipt["nativePolicy"] = {"before": hashlib.sha256(before).hexdigest(), "after": digest(properties), "airplaneMode": True}
            data = area / "konan-data"
            dependencies = data / "dependencies"
            dependencies.mkdir(parents=True, mode=0o700)
            for directory in installed[1:]:
                shutil.move(str(directory), str(dependencies / directory.name))
            (dependencies / ".extracted").write_text("".join(pin["directory"] + "\n" for pin in entries[1:]))
            values.update(KONAN_DATA_DIR=str(data), KONAN_USE_INTERNAL_SERVER="0")
            values["ORG_GRADLE_PROJECT_kotlin.native.home"] = str(native_home)
            values["ORG_GRADLE_PROJECT_konan.data.dir"] = str(data)
        save(area, receipt)
        publish(values, paths)
        print("Verified " + role + " archive inputs: " + ", ".join(row["sha256"] for row in receipt["archives"]))
    except BaseException:
        try:
            cleanup_area(area, receipt, temporary)
            if published_area:
                publish({area_key: ""})
        except (KeyError, OSError, RuntimeError) as cleanup_error:
            print("Runtime cleanup also failed: " + str(cleanup_error), file=sys.stderr)
        raise


def main():
    require(len(sys.argv) == 2 and sys.argv[1] in (*ROLES, "cleanup"), "one fixed runtime role or cleanup is required")
    os.umask(0o077)
    temporary = Path(os.environ["RUNNER_TEMP"]).resolve()
    require(temporary.is_dir() and "\n" not in str(temporary) and "\r" not in str(temporary), "invalid runner temporary root")
    if sys.argv[1] == "cleanup":
        errors = []
        for role in reversed(ROLES):
            value = os.environ.get("KIRA_VERIFIED_" + role.upper() + "_AREA")
            if value:
                area = Path(value)
                try:
                    cleanup_area(area, read_json(area / "owned.json"), temporary)
                except (KeyError, OSError, RuntimeError, ValueError) as error:
                    errors.append(role + ": " + str(error))
        require(not errors, "; ".join(errors))
        return
    require(os.environ.get("GITHUB_ACTIONS") == "true" and os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted",
            "only an explicitly guarded hosted job may install runtime inputs")
    subprocess.run(["/usr/bin/ruby", str(ROOT / "scripts/release/verify-toolchain-inputs.rb")],
        check=True, timeout=60, env=bootstrap_environment())
    pins = read_json(ROOT / "release/verified-tools.json")["runtime_archives"]
    install(sys.argv[1], host(), temporary, pins)


if __name__ == "__main__":
    try:
        main()
    except (KeyError, OSError, RuntimeError, ValueError, subprocess.SubprocessError, tarfile.TarError) as error:
        sys.exit("Runtime input bootstrap failed: " + str(error))
