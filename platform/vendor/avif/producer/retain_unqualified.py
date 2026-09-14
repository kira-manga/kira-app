"""Bounded diagnostic custody after native provenance failure, never candidate acceptance. NOT_RUN."""

import json
import os
from pathlib import Path
import stat

from artifact_checks import ABI_ELF, MAX_AAR_BYTES, sha256_bytes, sha256_file
from owned_resources import OWNER_ENV, context, process_origin, require_owned


MAX_METADATA_BYTES = 1024 * 1024
MAX_RETAINED_BYTES = 96 * 1024 * 1024
UNQUALIFIED_AAR = "UNQUALIFIED-avif-1.3.0.841110fd-kira-limits1.aar"
RAW_INPUTS = {
    "CMakeCache.txt": "CMakeCache.txt",
    "CMakeOutput.log": "CMakeFiles/CMakeOutput.log",
    "build.ninja": "build.ninja",
    "rules.ninja": "CMakeFiles/rules.ninja",
    "android_gradle_build.json": "android_gradle_build.json",
}


def identity(metadata):
    return (metadata.st_dev, metadata.st_ino, metadata.st_mode, metadata.st_uid,
            metadata.st_nlink, metadata.st_size, metadata.st_mtime_ns, metadata.st_ctime_ns)


def bounded_file(source, root, limit, destination=None):
    if not source.is_relative_to(root) or source.resolve() != source:
        raise RuntimeError("Refusing linked or escaping unqualified evidence")
    try:
        descriptor = os.open(source, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except FileNotFoundError:
        return None  # Missing raw evidence is recorded as missing, not treated as proof.
    with os.fdopen(descriptor, "rb") as original:
        before = os.fstat(original.fileno())
        if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.getuid()
                or before.st_nlink != 1 or not 0 <= before.st_size <= limit):
            raise RuntimeError("Unqualified evidence is not a bounded same-UID regular file")
        payload = original.read(limit + 1)
        after = os.fstat(original.fileno())
    if (len(payload) != before.st_size or len(payload) > limit
            or identity(before) != identity(after) or identity(before) != identity(source.lstat())
            or source.resolve(strict=True) != source):
        raise RuntimeError("Unqualified evidence changed while being read")
    if destination is not None:
        destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        with destination.open("xb") as output:
            output.write(payload)
    return {"bytes": len(payload), "sha256": sha256_bytes(payload)}


def write_metadata(target, name, value, files):
    payload = (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")
    if len(payload) > MAX_METADATA_BYTES:
        raise RuntimeError("Unqualified metadata exceeds its byte bound")
    with (target / name).open("xb") as output:
        output.write(payload)
    files[name] = {"bytes": len(payload), "sha256": sha256_bytes(payload)}


def retain_native_inputs(work, target, files):
    native = work / "libavif/android_jni/avifandroidjni/.cxx/RelWithDebInfo"
    index, static = {}, {}
    for abi in ABI_ELF:
        directories = list(native.glob(f"*/{abi}"))
        entry = {"directory_matches": len(directories), "files": {}}
        index[abi] = entry
        if len(directories) == 1:
            directory = directories[0]
            if directory.resolve(strict=True) != directory or not directory.is_dir():
                raise RuntimeError("Refusing a linked native evidence directory")
            entry["directory"] = str(directory.relative_to(work))
            paths = {name: directory / relative for name, relative in RAW_INPUTS.items()}
            compilers = list(directory.glob("CMakeFiles/*/CMakeCXXCompiler.cmake"))
            entry["compiler_metadata_matches"] = len(compilers)
            if len(compilers) == 1:
                paths["CMakeCXXCompiler.cmake"] = compilers[0]
            for name, source in paths.items():
                output = f"native-inputs/{abi}/{name}"
                report = bounded_file(source, work, MAX_METADATA_BYTES, target / output)
                entry["files"][name] = {"source": str(source.relative_to(work)), "snapshot": report}
                if report is not None:
                    files[output] = report
        for relative in (f"libavif/ext/dav1d/build/{abi}/src/libdav1d.a",
                         f"libavif/ext/libyuv/build/{abi}/libyuv.a"):
            # Hash at most eight 64-MiB files; do not retain archives or claim the link was checked.
            static[relative] = bounded_file(work / relative, work, MAX_AAR_BYTES)
    write_metadata(target, "native-input-index.json", index, files)
    write_metadata(target, "static-output-hashes.json", static, files)


def retain_unqualified(work, ndk, artifact, partial):
    run = context()
    if work != run.work or os.environ.get(OWNER_ENV) != run.owner:
        raise RuntimeError("Unqualified retention is not owned by this producer stage")
    for root in (run.work, run.evidence):
        require_owned(root, run.owner)
    process_origin(run)
    target = run.evidence / "unqualified-collector"
    target.mkdir(mode=0o700, exist_ok=False)
    files = {}
    aar = work / "libavif/android_jni/avifandroidjni/build/outputs/aar/avifandroidjni-release.aar"
    copied = bounded_file(aar, work, MAX_AAR_BYTES, target / UNQUALIFIED_AAR)
    if copied is None or copied != {key: artifact[key] for key in ("bytes", "sha256")}:
        raise RuntimeError("Unqualified AAR differs from the already inspected bytes")
    files[UNQUALIFIED_AAR] = copied
    preparation = bounded_file(work / "source-preparation.json", work, MAX_METADATA_BYTES,
                               target / "source-preparation.json")
    if preparation is None or preparation["sha256"] != partial["source_inputs"]["preparation_report_sha256"]:
        raise RuntimeError("Unqualified source preparation differs from checked provenance inputs")
    files["source-preparation.json"] = preparation
    write_metadata(target, "artifact-report.json", artifact, files)
    write_metadata(target, "partial-provenance.json", {
        **partial, "state": "UNQUALIFIED_NATIVE_PROVENANCE_COLLECTION_FAILED",
        "producer_structural_checks": "INCOMPLETE", "native_builds": "INCOMPLETE",
        "recorded_work_root": str(work), "recorded_ndk_root": str(ndk),
        "automatic_reuse_or_candidate_acceptance": "NOT_AUTHORIZED",
    }, files)
    retain_native_inputs(work, target, files)
    write_metadata(target, "UNQUALIFIED.json", {
        "state": "UNQUALIFIED_NATIVE_PROVENANCE_COLLECTION_FAILED",
        "native_and_app_qualification": "NOT_RUN",
        "missing_or_ambiguous_raw_inputs": "Recorded in index; not replaced with inferred values",
        "use": "Diagnostic custody only. Complete independently reviewed provenance is still required.",
    }, files)
    expected = set(files)
    actual = set()
    for path in target.rglob("*"):
        if path.is_symlink() or (not path.is_dir() and not path.is_file()):
            raise RuntimeError("Unexpected linked or nonregular unqualified output")
        if path.is_file():
            actual.add(str(path.relative_to(target)))
    if actual != expected or len(files) > 31:
        raise RuntimeError("Unqualified snapshot escaped its exact file allowlist")
    for name, report in files.items():
        if (target / name).stat().st_size != report["bytes"] or sha256_file(target / name) != report["sha256"]:
            raise RuntimeError("Unqualified output changed during snapshot assembly")
    checksums = "".join(f"{files[name]['sha256']}  {name}\n" for name in sorted(files)).encode("ascii")
    if len(checksums) > 65536 or sum(item["bytes"] for item in files.values()) + len(checksums) > MAX_RETAINED_BYTES:
        raise RuntimeError("Unqualified snapshot exceeds its total retained-byte bound")
    with (target / "SHA256SUMS").open("xb") as output:
        output.write(checksums)
    require_owned(run.evidence, run.owner)
    process_origin(run)
    # Only a complete, hashed snapshot can opt into the separate failure-only upload.
    with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
        output.write("unqualified_retention_ready=true\n")
