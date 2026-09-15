#!/usr/bin/env python3
"""Package only a source-bound AAR candidate and public-safe evidence. No runtime qualification."""

import json
import os
from pathlib import Path
import re
import shutil
import sys

from artifact_checks import inspect_aar, sha256_file
from native_build_evidence import native_build_evidence
from producer_inputs import recipe_evidence, source_evidence, tool_evidence
from retain_unqualified import retain_unqualified


def require_admitted_context(recipe, work, result):
    expected = os.environ.get("EXPECTED_RECIPE_COMMIT", "")
    if os.environ.get("KIRA_AVIF_PRODUCER_ADMITTED") != "native15-reviewed-source-only":
        raise RuntimeError("Primary producer admission is required")
    if not re.fullmatch(r"[0-9a-f]{40}", expected) or expected != os.environ.get("GITHUB_SHA"):
        raise RuntimeError("Expected recipe revision does not match the dispatched producer")
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("GITHUB_REF_NAME") == "main":
        raise RuntimeError("Candidate collection is restricted to the admitted non-main hosted run")
    temporary = Path(os.environ["RUNNER_TEMP"]).resolve()
    if not work.is_relative_to(temporary) or not result.is_relative_to(temporary) or recipe.is_relative_to(work):
        raise RuntimeError("Producer paths are not isolated under the hosted temporary directory")
    if any(result.iterdir()):
        raise RuntimeError("Result directory is not fresh; no preexisting output may be published")


def expected_aar_assets(recipe, manifest):
    assets = {"assets/kira-avif-notices/source-manifest.json": sha256_file(recipe / "source-manifest.json")}
    for name, digest in manifest["notices"].items():
        if sha256_file(recipe / name) != digest:
            raise RuntimeError("A reviewed license/notice changed during production")
        assets["assets/kira-avif-notices/" + Path(name).name] = digest
    return assets


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def candidate_provenance(recipe, work, ndk, java_home, manifest, artifact):
    provenance = {
        "schema_version": 1,
        "state": "PRODUCED_CANDIDATE_NOT_RUNTIME_QUALIFIED",
        "candidate_version": manifest["candidate_version"],
        "candidate_aar_sha256": artifact["sha256"],
        "source_manifest_sha256": sha256_file(recipe / "source-manifest.json"),
        "limits_patch_sha256": sha256_file(recipe / "limits.patch"),
        "recipe": recipe_evidence(recipe),
        "source_inputs": source_evidence(recipe, work, ndk, manifest),
        "tools": tool_evidence(work, ndk, java_home, manifest),
        "hosted_run": {"run_id": os.environ["GITHUB_RUN_ID"], "attempt": os.environ["GITHUB_RUN_ATTEMPT"],
                       "image_os": os.environ.get("ImageOS"), "image_version": os.environ.get("ImageVersion")},
        "declared_resource_limits": manifest["producer"],
        "producer_structural_checks": "PASS",
        "native_qualification": "NOT_RUN",
        "app_compile_runtime_and_packaging_qualification": "NOT_RUN",
        "byte_identical_rebuild_or_baseline_maven_equivalence": "NOT_CLAIMED",
    }
    try:
        provenance["native_builds"] = native_build_evidence(work, ndk)
    except Exception:
        # AAR/recipe/source/tool checks above have passed. Retention is diagnostic, not fallback
        # provenance: it cannot change this failure, and no candidate result is published.
        try:
            retain_unqualified(work, ndk, artifact, provenance)
        except Exception as error:
            print(f"unqualified_retention_failed error_type={type(error).__name__}", file=sys.stderr)
        raise
    return provenance


def copy_public_inputs(recipe, work, result, manifest):
    names = ["source-manifest.json", "limits.patch", "producer/native-build.init.gradle"]
    for name in names:
        shutil.copyfile(recipe / name, result / Path(name).name)
    for name in manifest["notices"]:
        destination = result / "notices" / Path(name).name
        destination.parent.mkdir(exist_ok=True)
        shutil.copyfile(recipe / name, destination)
    shutil.copyfile(work / "source-preparation.json", result / "source-preparation.json")


def finish_bundle(recipe, work, result, manifest, aar, artifact, provenance):
    candidate_name = "avif-" + manifest["candidate_version"] + ".aar"
    shutil.copyfile(aar, result / candidate_name)
    if sha256_file(result / candidate_name) != artifact["sha256"]:
        raise RuntimeError("Candidate bytes changed during the output copy")
    copy_public_inputs(recipe, work, result, manifest)
    write_json(result / "artifact-report.json", artifact)
    provenance["artifact_report_sha256"] = sha256_file(result / "artifact-report.json")
    write_json(result / "provenance.json", provenance)
    allowlist = {
        candidate_name, "provenance.json", "artifact-report.json", "source-preparation.json",
        "source-manifest.json", "limits.patch", "native-build.init.gradle",
    }
    allowlist.update("notices/" + Path(name).name for name in manifest["notices"])
    files = {str(path.relative_to(result)): path for path in result.rglob("*") if path.is_file()}
    if set(files) != allowlist:
        raise RuntimeError("Result directory contains output outside the public-safe allowlist")
    checksums = "".join(f"{sha256_file(files[name])}  {name}\n" for name in sorted(files))
    (result / "SHA256SUMS").write_text(checksums)


def main():
    recipe, work, result, ndk = (Path(arg).resolve() for arg in sys.argv[1:])
    require_admitted_context(recipe, work, result)
    manifest = json.loads((recipe / "source-manifest.json").read_text())
    java_home = Path(os.environ["JAVA_HOME"]).resolve()
    aar = work / "libavif/android_jni/avifandroidjni/build/outputs/aar/avifandroidjni-release.aar"
    artifact = inspect_aar(aar, work / "artifact-inspection", ndk, java_home, expected_aar_assets(recipe, manifest))
    provenance = candidate_provenance(recipe, work, ndk, java_home, manifest, artifact)
    finish_bundle(recipe, work, result, manifest, aar, artifact, provenance)


if __name__ == "__main__":
    main()
