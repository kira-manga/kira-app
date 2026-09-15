"""Candidate-byte checks used only by the separately admitted hosted producer. NOT_RUN."""

import hashlib
from pathlib import PurePosixPath
import re
import stat
import struct
import subprocess
import zipfile


MAX_AAR_BYTES = 64 * 1024 * 1024
MAX_EXPANDED_BYTES = 128 * 1024 * 1024
MAX_MEMBERS = 256
MAX_TOOL_OUTPUT = 2 * 1024 * 1024
ABI_ELF = {"armeabi-v7a": (1, 40), "arm64-v8a": (2, 183), "x86": (1, 3), "x86_64": (2, 62)}
JNI_PREFIX = "Java_org_aomedia_avif_android_AvifDecoder_"
JNI_METHODS = {
    "isAvifImage", "getInfo", "decode", "createDecoder", "destroyDecoder", "nextFrame",
    "nextFrameIndex", "nthFrame", "resultToString", "versionString",
    "getInfoWithLimitsNative", "decodeWithLimitsNative",
}
DECODER = "org.aomedia.avif.android.AvifDecoder"
INFO = DECODER + "$Info"
BUFFER = "java.nio.ByteBuffer"
BITMAP = "android.graphics.Bitmap"
DECODER_PUBLIC_API = {
    f"public static boolean isAvifImage({BUFFER});",
    f"public static native boolean getInfo({BUFFER}, int, {INFO});",
    f"public static boolean getInfoWithLimits({BUFFER}, int, {INFO}, int, int);",
    f"public static boolean decode({BUFFER}, int, {BITMAP});",
    f"public static native boolean decode({BUFFER}, int, {BITMAP}, int);",
    f"public static boolean decodeWithLimits({BUFFER}, int, {BITMAP}, int, int, int);",
    "public int getWidth();", "public int getHeight();", "public int getDepth();",
    "public boolean getAlphaPresent();", "public int getFrameCount();",
    "public int getRepetitionCount();", "public double[] getFrameDurations();",
    "public void release();", f"public static {DECODER} create({BUFFER});",
    f"public static {DECODER} create({BUFFER}, int);", f"public int nextFrame({BITMAP});",
    "public int nextFrameIndex();", f"public int nthFrame(int, {BITMAP});",
    "public static native java.lang.String resultToString(int);",
    "public static native java.lang.String versionString();",
}
INFO_PUBLIC_API = {"public int width;", "public int height;", "public int depth;", "public boolean alphaPresent;"}
BOUNDED_BRIDGES = {
    f"private static native boolean getInfoWithLimitsNative({BUFFER}, int, {INFO}, int, int);",
    f"private static native boolean decodeWithLimitsNative({BUFFER}, int, {BITMAP}, int, int, int);",
}


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def command(*args):
    result = subprocess.run(
        [str(arg) for arg in args], check=False, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, timeout=60,
    )
    if result.returncode != 0 or len(result.stdout) > MAX_TOOL_OUTPUT:
        raise RuntimeError(f"Producer inspection command failed: {PurePosixPath(str(args[0])).name}")
    return result.stdout.decode("utf-8", errors="strict").strip()


def safe_members(archive):
    members = archive.infolist()
    if len(members) > MAX_MEMBERS or sum(item.file_size for item in members) > MAX_EXPANDED_BYTES:
        raise RuntimeError("AAR member count or expanded size exceeds the inspection budget")
    names = set()
    for item in members:
        name = item.filename
        parts = name.rstrip("/").split("/")
        unsafe = not name or len(name) > 512 or any(part in ("", ".", "..") for part in parts)
        unsafe |= not re.fullmatch(r"[A-Za-z0-9_.$/+-]+", name) or PurePosixPath(name).is_absolute()
        unsafe |= name in names or bool(item.flag_bits & 1) or stat.S_ISLNK(item.external_attr >> 16)
        unsafe |= item.file_size < 0 or item.file_size > MAX_AAR_BYTES
        if unsafe:
            raise RuntimeError("Unsafe, duplicate, encrypted, or oversized AAR member")
        names.add(name)
    return members


def elf_loads(data, abi):
    elf_class, machine = ABI_ELF[abi]
    if len(data) < 64 or data[:7] != b"\x7fELF" + bytes((elf_class, 1, 1)):
        raise RuntimeError(f"Unexpected ELF identity: {abi}")
    if struct.unpack_from("<HH", data, 16) != (3, machine):
        raise RuntimeError(f"Unexpected ELF type/machine: {abi}")
    if elf_class == 1:
        offset = struct.unpack_from("<I", data, 28)[0]
        entry_size, count = struct.unpack_from("<HH", data, 42)
        layout, minimum_size = "<IIIIIIII", 32
    else:
        offset = struct.unpack_from("<Q", data, 32)[0]
        entry_size, count = struct.unpack_from("<HH", data, 54)
        layout, minimum_size = "<IIQQQQQQ", 56
    if not 0 < count <= 512 or entry_size < minimum_size or offset + count * entry_size > len(data):
        raise RuntimeError(f"Invalid ELF program-header table: {abi}")
    loads = []
    for index in range(count):
        entry = struct.unpack_from(layout, data, offset + index * entry_size)
        if entry[0] == 1:
            loads.append(check_load(entry, elf_class, len(data), abi))
    if not loads:
        raise RuntimeError(f"ELF has no LOAD segments: {abi}")
    return {"class": elf_class, "machine": machine, "load_segments": loads}


def check_load(entry, elf_class, length, abi):
    if elf_class == 1:
        _, offset, address, _, file_size, memory_size, _, alignment = entry
    else:
        _, _, offset, address, _, file_size, memory_size, alignment = entry
    valid = alignment >= 16384 and alignment & (alignment - 1) == 0
    valid &= alignment > 0 and offset % alignment == address % alignment and offset + file_size <= length
    if not valid or memory_size < file_size:
        raise RuntimeError(f"ELF LOAD segment is invalid or not 16-KiB aligned: {abi}")
    return {"offset": offset, "virtual_address": address, "file_bytes": file_size,
            "memory_bytes": memory_size, "alignment": alignment}


def inspect_java(classes, javap):
    with zipfile.ZipFile(classes) as archive:
        entries = safe_members(archive)
        if sum(entry.file_size for entry in entries) > 4 * 1024 * 1024:
            raise RuntimeError("Produced classes.jar exceeds the class inspection budget")
        names = {entry.filename for entry in entries}
        required = {DECODER.replace(".", "/") + ".class", INFO.replace(".", "/") + ".class"}
        if not required.issubset(names):
            raise RuntimeError("Produced classes.jar is missing the original decoder classes")
    evidence = {}
    for owner, public, private in ((DECODER, DECODER_PUBLIC_API, BOUNDED_BRIDGES), (INFO, INFO_PUBLIC_API, set())):
        output = command(javap, "-classpath", classes, "-private", "-s", owner)
        declarations = {re.sub(r"\s+", " ", line.strip()) for line in output.splitlines()}
        if not (public | private).issubset(declarations):
            raise RuntimeError("Produced classes.jar is missing an exact old/new API on its original class")
        evidence[owner] = {
            "actual_public_declarations": sorted(line for line in declarations if line.startswith("public ")),
            "required_public_declarations": sorted(public),
            "bounded_private_jni_declarations": sorted(private),
            "javap_output_sha256": sha256_bytes(output.encode()),
        }
    return evidence


def inspect_jni(path, nm):
    output = command(nm, "--dynamic", "--defined-only", "--format=posix", path)
    exported = {line.split()[0] for line in output.splitlines() if line.strip()}
    expected = {JNI_PREFIX + method for method in JNI_METHODS}
    if {name for name in exported if name.startswith(JNI_PREFIX)} != expected:
        raise RuntimeError("A produced ABI is missing or changing an old/new JNI export")
    return {"required_jni_exports": sorted(expected), "nm_output_sha256": sha256_bytes(output.encode())}


def inspect_aar(aar, inspection, ndk, java_home, expected_assets):
    if not 0 < aar.stat().st_size <= MAX_AAR_BYTES:
        raise RuntimeError("Candidate AAR size is outside the admitted producer budget")
    inspection.mkdir(exist_ok=False)
    expected_jni = {f"jni/{abi}/libavif_android.so" for abi in ABI_ELF}
    nm = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm"
    members, abis = {}, {}
    with zipfile.ZipFile(aar) as archive:
        entries = safe_members(archive)
        native_names = {item.filename for item in entries if item.filename.endswith(".so")}
        if native_names != expected_jni:
            raise RuntimeError("Candidate AAR does not contain exactly the four reviewed JNI payloads")
        for entry in entries:
            if entry.is_dir():
                members[entry.filename] = {"directory": True, "bytes": 0, "sha256": sha256_bytes(b"")}
                continue
            data = archive.read(entry)
            members[entry.filename] = {"bytes": len(data), "sha256": sha256_bytes(data)}
            if entry.filename in expected_jni:
                abi = entry.filename.split("/")[1]
                path = inspection / f"{abi}.so"
                path.write_bytes(data)
                abis[abi] = elf_loads(data, abi) | inspect_jni(path, nm)
            elif entry.filename == "classes.jar":
                (inspection / "classes.jar").write_bytes(data)
    return finish_aar_report(aar, inspection, java_home, expected_assets, members, abis)


def finish_aar_report(aar, inspection, java_home, expected_assets, members, abis):
    for name, expected in expected_assets.items():
        if members.get(name, {}).get("sha256") != expected:
            raise RuntimeError("Candidate AAR is missing or changing a required source/license asset")
    actual_assets = {
        name for name, member in members.items()
        if name.startswith("assets/kira-avif-notices/") and not member.get("directory", False)
    }
    if actual_assets != set(expected_assets):
        raise RuntimeError("Candidate notice bundle contains unreviewed assets")
    classes = inspection / "classes.jar"
    if not classes.is_file():
        raise RuntimeError("Candidate AAR has no classes.jar")
    return {
        "sha256": sha256_file(aar), "bytes": aar.stat().st_size, "members": members,
        "java_api": inspect_java(classes, java_home / "bin/javap"), "abis": abis,
        "elf_load_alignment": "PASS_16_KIB_OR_GREATER",
        "final_apk_zip_alignment_and_device_loading": "NOT_RUN",
        "native_behavior_and_app_qualification": "NOT_RUN",
    }
