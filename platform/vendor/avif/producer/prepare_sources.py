#!/usr/bin/env python3
"""Hosted preparation, NOT_RUN: pinned native source fetches, scoped JNI patch, and notices."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


def run(*args):
    return subprocess.run(args, check=True, text=True, capture_output=True, timeout=180).stdout.strip()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_files(root, files):
    for name, expected in files.items():
        path = (root / name).resolve()
        if not path.is_relative_to(root.resolve()) or digest(path) != expected:
            raise RuntimeError(f"Pinned source/notice digest mismatch: {name}")


def fetch_source(work, source):
    target = (work / source["path"]).resolve()
    if not target.is_relative_to(work.resolve()):
        raise RuntimeError("Source path escapes the isolated producer directory")
    target.mkdir(parents=True, exist_ok=False)
    run("git", "-C", str(target), "init", "--quiet")
    run("git", "-C", str(target), "remote", "add", "origin", source["repository"])
    run("git", "-C", str(target), "fetch", "--depth=1", "--no-tags", "origin", source["commit"])
    run("git", "-C", str(target), "checkout", "--detach", "FETCH_HEAD")
    if run("git", "-C", str(target), "rev-parse", "HEAD") != source["commit"]:
        raise RuntimeError(f"Source revision mismatch: {source['id']}")
    verify_files(target, source["files"])


def apply_source_patch(recipe, source, specification):
    patch = recipe / specification["path"]
    run("git", "-C", str(source), "apply", "--check", str(patch))
    run("git", "-C", str(source), "apply", str(patch))
    changed = set(run("git", "-C", str(source), "diff", "--name-only").splitlines())
    if changed != set(specification["paths"]):
        raise RuntimeError("A source patch changed paths outside its reviewed scope")


def apply_recipe(recipe, work, manifest):
    source = work / "libavif"
    apply_source_patch(recipe, source, manifest["patch"])
    wrapper = source / "android_jni/gradle/wrapper/gradle-wrapper.properties"
    properties = wrapper.read_text().rstrip("\r\n")
    if "distributionSha256Sum=" in properties:
        raise RuntimeError("Unexpected preexisting Gradle distribution checksum")
    wrapper.write_text(properties + "\ndistributionSha256Sum=" + manifest["producer"]["gradle_distribution_sha256"] + "\n")
    assets = source / "android_jni/avifandroidjni/src/main/assets/kira-avif-notices"
    assets.mkdir(parents=True, exist_ok=False)
    for name in manifest["notices"]:
        shutil.copyfile(recipe / name, assets / Path(name).name)
    shutil.copyfile(recipe / "source-manifest.json", assets / "source-manifest.json")
    modified = [source / name for name in manifest["patch"]["paths"]] + [wrapper]
    return {str(path.relative_to(work)): digest(path) for path in modified}


def main():
    if os.environ.get("KIRA_AVIF_PRODUCER_ADMITTED") != "native15-reviewed-source-only":
        raise RuntimeError("Primary producer admission is required")
    recipe, work, ndk = (Path(arg).resolve() for arg in sys.argv[1:])
    manifest = json.loads((recipe / "source-manifest.json").read_text())
    patch = manifest["patch"]
    verify_files(recipe, {patch["path"]: patch["sha256"]})
    verify_files(recipe, manifest["notices"])
    verify_files(ndk, manifest["ndk_source_basis"]["files"])
    sources = manifest["sources"]
    for source in sources:
        fetch_source(work, source)
    modified = apply_recipe(recipe, work, manifest)
    result = {
        "source_commits": {s["id"]: s["commit"] for s in sources},
        "source_manifest_sha256": digest(recipe / "source-manifest.json"),
        "patch_sha256": digest(recipe / "limits.patch"),
        "modified_producer_input_sha256": modified,
        "ndk_compiled_cpufeatures_sha256": manifest["ndk_source_basis"]["files"],
        "native_tests": "NOT_RUN",
    }
    (work / "source-preparation.json").write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
