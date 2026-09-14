"""Bind actual per-ABI CMake/Ninja inputs and the prebuilt static libraries. NOT_RUN."""

import json
import os
from pathlib import Path
import re

from artifact_checks import ABI_ELF, sha256_bytes, sha256_file


CMAKE_GENERATED_VERSION = "3.22.1-g37088a8"
CXX_ID_TARGETS = {
    "armeabi-v7a": "armv7-none-linux-androideabi21",
    "arm64-v8a": "aarch64-none-linux-android21",
    "x86": "i686-none-linux-android21",
    "x86_64": "x86_64-none-linux-android21",
}
MAX_COMPILER_EVIDENCE_BYTES = 1024 * 1024


def compiler_evidence_text(path):
    if path.is_symlink() or path.resolve(strict=True) != path or not path.is_file():
        raise RuntimeError("Expected an unlinked generated compiler evidence file")
    with path.open("rb") as source:
        payload = source.read(MAX_COMPILER_EVIDENCE_BYTES + 1)
    if not payload or len(payload) > MAX_COMPILER_EVIDENCE_BYTES or b"\0" in payload:
        raise RuntimeError("Generated compiler evidence is empty, oversized, or non-text")
    return payload.decode("utf-8"), sha256_bytes(payload)


def cmake_compiler_literal(text, key):
    # Read only CMake's literal generated assignments; never execute/evaluate CMake text.
    lines = [line for line in text.splitlines()
             if re.match(rf"\s*set\s*\(\s*{re.escape(key)}(?:\s|\))", line, re.IGNORECASE)]
    match = re.fullmatch(rf'set\({re.escape(key)} "([^"\\$;\r\n]*)"\)[ \t]*', lines[0]) if len(lines) == 1 else None
    if match is None:
        raise RuntimeError(f"Missing, ambiguous, or nonliteral generated compiler field: {key}")
    return match.group(1)


def inspect_cxx_compiler(directory, ndk, abi, cache):
    # Android's toolchain can set CMAKE_CXX_COMPILER as a normal variable, not a cache entry.
    # Its generated language-compiler file is mandatory; a known NDK path is not a fallback.
    candidates = list(directory.glob("CMakeFiles/*/CMakeCXXCompiler.cmake"))
    expected_metadata = directory / f"CMakeFiles/{CMAKE_GENERATED_VERSION}/CMakeCXXCompiler.cmake"
    if candidates != [expected_metadata]:
        raise RuntimeError(f"Expected one compiler metadata file from the pinned CMake: {abi}")
    text, metadata_hash = compiler_evidence_text(expected_metadata)
    keys = ("CMAKE_CXX_COMPILER", "CMAKE_CXX_COMPILER_ID", "CMAKE_CXX_COMPILER_VERSION", "CMAKE_CXX_COMPILER_ARG1")
    values = {key: cmake_compiler_literal(text, key) for key in keys}
    expected_compiler = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/clang++"
    compiler = Path(values["CMAKE_CXX_COMPILER"])
    if (not compiler.is_absolute() or compiler.name != expected_compiler.name
            or compiler.parent.resolve(strict=True) != expected_compiler.parent.resolve(strict=True)
            or values["CMAKE_CXX_COMPILER_ID"] != "Clang"
            or values["CMAKE_CXX_COMPILER_VERSION"] != "14.0.7"
            or values["CMAKE_CXX_COMPILER_ARG1"] != ""):
        raise RuntimeError(f"Generated CXX compiler differs from the reviewed NDK compiler: {abi}")
    resolved = compiler.resolve(strict=True)
    if (resolved != expected_compiler.resolve(strict=True)
            or not resolved.is_relative_to(ndk.resolve(strict=True)) or not resolved.is_file()):
        raise RuntimeError("Generated CXX compiler resolves outside the pinned NDK")
    if "CMAKE_CXX_COMPILER" in cache and cache["CMAKE_CXX_COMPILER"] != str(compiler):
        raise RuntimeError("CMake cache contradicts generated CXX compiler metadata")
    log, log_hash = compiler_evidence_text(directory / "CMakeFiles/CMakeOutput.log")
    heading = 'Compiling the CXX compiler identification source file "CMakeCXXCompilerId.cpp" succeeded.'
    pattern = (rf"(?m)^{re.escape(heading)}\nCompiler: ([^\r\n]+)\n"
               r"Build flags: [^\r\n]*\nId flags: -c;--target=([A-Za-z0-9_+-]+)[ \t]*$")
    identities = re.findall(pattern, log)
    if (log.count(heading) != 1 or len(identities) != 1
            or identities[0][0].rstrip() != str(compiler)
            or identities[0][1] != CXX_ID_TARGETS[abi]):
        raise RuntimeError(f"Generated CXX identification does not bind the reviewed compiler/ABI/API: {abi}")
    return {"compiler_sha256": sha256_file(resolved), "compiler_path": str(compiler),
            "resolved_compiler_path": str(resolved), "compiler_metadata_sha256": metadata_hash,
            "compiler_metadata_values": values, "compiler_identification_log_sha256": log_hash,
            "compiler_identification_target": identities[0][1]}


def cache_values(path):
    values = {}
    for line in path.read_text().splitlines():
        if line.startswith(("#", "//")) or ":" not in line or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.split(":", 1)[0]] = value
    return values


def check_job_pools(directory):
    rules_path = directory / "CMakeFiles/rules.ninja"
    build_path = directory / "build.ninja"
    rules, build = rules_path.read_text(), build_path.read_text()
    for pool, depth in (("kira_compile", 2), ("kira_link", 1)):
        if not re.search(rf"(?m)^pool {pool}\n  depth = {depth}\s*$", rules):
            raise RuntimeError("Generated Ninja rules do not enforce the admitted job-pool depths")
    counts = {"compile": 0, "link": 0}
    for block in re.split(r"(?m)(?=^build )", build):
        header = block.splitlines()[0] if block else ""
        kind = "compile" if "_COMPILER_" in header else "link" if "_LINKER_" in header else None
        if kind:
            if not re.search(rf"(?m)^  pool = kira_{kind}\s*$", block):
                raise RuntimeError("A generated native compile/link edge bypasses its admitted pool")
            counts[kind] += 1
    if not all(counts.values()):
        raise RuntimeError("Generated Ninja compile/link pool evidence is empty")
    return {"compile_edges": counts["compile"], "link_edges": counts["link"],
            "rules_sha256": sha256_file(rules_path), "build_sha256": sha256_file(build_path)}


def inspect_abi_build(work, ndk, abi, ninja):
    # Upstream AGP's release variant retains symbols in RelWithDebInfo before packaging.
    native_root = work / "libavif/android_jni/avifandroidjni/.cxx/RelWithDebInfo"
    candidates = list(native_root.glob(f"*/{abi}/CMakeCache.txt"))
    if len(candidates) != 1:
        raise RuntimeError(f"Expected one fresh release CMake cache: {abi}")
    cache = candidates[0]
    values = cache_values(cache)
    expected = {
        "ANDROID_ABI": abi, "AVIF_CODEC_DAV1D": "LOCAL", "AVIF_LIBYUV": "LOCAL",
        "AVIF_CODEC_LIBGAV1": "OFF", "AVIF_CODEC_AOM": "OFF",
        "CMAKE_BUILD_TYPE": "RelWithDebInfo",
        "CMAKE_JOB_POOLS": "kira_compile=2;kira_link=1",
        "CMAKE_JOB_POOL_COMPILE": "kira_compile", "CMAKE_JOB_POOL_LINK": "kira_link",
    }
    if any(values.get(key) != value for key, value in expected.items()):
        raise RuntimeError(f"Unreviewed codec, ABI, or build concurrency inputs: {abi}")
    if Path(values["CMAKE_MAKE_PROGRAM"]).resolve() != ninja.resolve():
        raise RuntimeError("CMake used a Ninja outside the pinned SDK CMake package")
    compiler = inspect_cxx_compiler(cache.parent, ndk, abi, values)
    static_inputs = check_static_link_inputs(work, cache.parent, abi)
    return {"cmake_cache_sha256": sha256_file(cache), "reviewed_cache_values": expected,
            **compiler, "ninja_sha256": sha256_file(ninja),
            "agp_build_command": check_agp_ninja_command(cache.parent, ninja),
            "job_pools": check_job_pools(cache.parent), "static_inputs": static_inputs}


def check_agp_ninja_command(directory, ninja):
    # AGP's CmakeFileApiV1 writes this command; CxxRegularBuilder executes it. The
    # CMAKE_MAKE_PROGRAM cache entry alone does not select AGP's build executable.
    metadata = directory / "android_gradle_build.json"
    model = json.loads(metadata.read_text())
    command = model.get("buildTargetsCommandComponents")
    if not isinstance(command, list) or not command or not all(isinstance(arg, str) for arg in command):
        raise RuntimeError("AGP has no inspectable native build-target command")
    if Path(command[0]).resolve() != ninja.resolve() or command.count("-C") != 1:
        raise RuntimeError("AGP's actual build command selected a different Ninja executable")
    working = command.index("-C") + 1
    if working >= len(command) or Path(command[working]).resolve() != directory.resolve():
        raise RuntimeError("AGP's native build command points outside the reviewed ABI directory")
    return {"metadata_sha256": sha256_file(metadata), "build_targets_command_components": command}


def check_static_link_inputs(work, directory, abi):
    build = (directory / "build.ninja").read_text()
    link_edges = []
    for block in re.split(r"(?m)(?=^build )", build):
        header = block.splitlines()[0] if block else ""
        if "_SHARED_LIBRARY_LINKER_" in header and "libavif_android.so" in header.split(":", 1)[0]:
            link_edges.append(block.replace("$\n", ""))
    if len(link_edges) != 1:
        raise RuntimeError("Expected one actual JNI shared-library link edge")
    libraries = re.search(r"(?m)^  LINK_LIBRARIES = (.+)$", link_edges[0])
    if libraries is None:
        raise RuntimeError("The JNI link edge has no generated static-library input list")
    linked = libraries.group(1).split()
    names = (f"libavif/ext/dav1d/build/{abi}/src/libdav1d.a", f"libavif/ext/libyuv/build/{abi}/libyuv.a")
    evidence = {}
    for name in names:
        path = work / name
        if not path.is_file() or str(path) not in linked:
            raise RuntimeError("Native link omitted a reviewed prebuilt static library or used a fallback")
        evidence[name] = {"bytes": path.stat().st_size, "sha256": sha256_file(path)}
    return evidence


def native_build_evidence(work, ndk):
    ninja = Path(os.environ["ANDROID_HOME"]) / "cmake/3.22.1/bin/ninja"
    return {abi: inspect_abi_build(work, ndk, abi, ninja) for abi in ABI_ELF}
