"""Bind actual per-ABI CMake/Ninja inputs and the prebuilt static libraries. NOT_RUN."""

import json
import os
from pathlib import Path
import re

from artifact_checks import ABI_ELF, sha256_file


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
    compiler = Path(values["CMAKE_CXX_COMPILER"]).resolve()
    if not compiler.is_relative_to(ndk.resolve()):
        raise RuntimeError("Native CMake compiler is outside the pinned NDK")
    static_inputs = check_static_link_inputs(work, cache.parent, abi)
    return {"cmake_cache_sha256": sha256_file(cache), "reviewed_cache_values": expected,
            "compiler_sha256": sha256_file(compiler), "ninja_sha256": sha256_file(ninja),
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
