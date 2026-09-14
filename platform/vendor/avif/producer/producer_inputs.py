"""Source, compiler, tool, and resolved public dependency evidence; never dump the environment."""

import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import sys

from artifact_checks import command, sha256_file


def require_hash(path, expected):
    actual = sha256_file(path)
    if actual != expected:
        raise RuntimeError(f"Producer input digest changed: {path.name}")
    return actual


def source_evidence(recipe, work, ndk, manifest):
    prepared = json.loads((work / "source-preparation.json").read_text())
    require_hash(recipe / "source-manifest.json", prepared["source_manifest_sha256"])
    modified = prepared["modified_producer_input_sha256"]
    for name, expected in modified.items():
        require_hash(work / name, expected)
    sources = {}
    for entry in manifest["sources"]:
        sources[entry["id"]] = check_source(work, entry, modified)
    for name, expected in manifest["ndk_source_basis"]["files"].items():
        require_hash(ndk / name, expected)
    return {"sources": sources, "modified_input_sha256": modified,
            "preparation_report_sha256": sha256_file(work / "source-preparation.json"),
            "ndk_compiled_cpufeatures_sha256": manifest["ndk_source_basis"]["files"]}


def check_source(work, entry, modified):
    source = work / entry["path"]
    head = command("git", "-C", source, "rev-parse", "HEAD")
    if head != entry["commit"] or command("git", "-C", source, "diff", "--cached", "--name-only"):
        raise RuntimeError("Producer source revision/index no longer matches its reviewed input")
    expected_changes = {
        str((work / name).relative_to(source)) for name in modified
        if (work / name).is_relative_to(source)
    }
    actual_changes = set(command("git", "-C", source, "diff", "--name-only").splitlines())
    if actual_changes != expected_changes:
        raise RuntimeError("Producer unexpectedly rewrote a tracked source input")
    files = {}
    for name, original in entry["files"].items():
        expected = modified.get(str((source / name).relative_to(work)), original)
        files[name] = require_hash(source / name, expected)
    return {"repository": entry["repository"], "commit": head,
            "tree": command("git", "-C", source, "rev-parse", "HEAD^{tree}"),
            "selected_input_sha256": files}


def recipe_evidence(recipe):
    root = Path(command("git", "-C", recipe, "rev-parse", "--show-toplevel"))
    relative = str(recipe.relative_to(root))
    head = command("git", "-C", recipe, "rev-parse", "HEAD")
    if head != os.environ["EXPECTED_RECIPE_COMMIT"] or head != os.environ["GITHUB_SHA"]:
        raise RuntimeError("Producer recipe revision changed")
    if command("git", "-C", root, "status", "--porcelain", "--untracked-files=all", "--", relative):
        raise RuntimeError("Producer recipe files are dirty or contain unreviewed inputs")
    names = command("git", "-C", root, "ls-files", "--", relative).splitlines()
    files = {}
    for name in names:
        path = root / name
        if path.is_symlink() or not path.resolve().is_relative_to(recipe):
            raise RuntimeError("Producer recipe contains an escaping or linked input")
        files[str(path.relative_to(recipe))] = sha256_file(path)
    if not files:
        raise RuntimeError("No tracked producer recipe inputs")
    active_workflow = root / ".github/workflows/avif-native-producer.yml"
    workflow_hash = require_hash(active_workflow, sha256_file(recipe / "producer/avif-native-producer.yml.in"))
    return {"commit": head, "tree": command("git", "-C", root, "rev-parse", "HEAD^{tree}"),
            "file_sha256": files, "active_workflow_sha256": workflow_hash}


def tool(path, *version_args):
    path = Path(path)
    return {"executable_name": path.name, "executable_sha256": sha256_file(path),
            "version": command(path, *version_args)}


def tool_evidence(work, ndk, java_home, manifest):
    sdk = Path(os.environ["ANDROID_HOME"])
    llvm = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    tools = {
        "java": tool(java_home / "bin/java", "-version"),
        "javac": tool(java_home / "bin/javac", "-version"),
        "javap": tool(java_home / "bin/javap", "-version"),
        "python": tool(sys.executable, "--version"),
        "meson": tool(shutil.which("meson"), "--version"),
        "ninja": tool(sdk / "cmake/3.22.1/bin/ninja", "--version"),
        "nasm": tool(shutil.which("nasm"), "-v"),
        "cmake": tool(sdk / "cmake/3.22.1/bin/cmake", "--version"),
    }
    for name in ("clang", "clang++", "llvm-ar", "ld.lld", "llvm-strip", "llvm-nm"):
        tools["ndk_" + name] = tool(llvm / name, "--version")
    return {"executables": tools, "packages": package_evidence(sdk, ndk, java_home, manifest),
            "gradle": gradle_evidence(work), "host_kernel": command("uname", "-srvm")}


def package_evidence(sdk, ndk, java_home, manifest):
    wheel = manifest["producer"]["meson_wheel"]
    wheel_path = Path(os.environ["RUNNER_TEMP"]) / "avif-native-wheels" / wheel["filename"]
    packages = {
        "meson_wheel_sha256": require_hash(wheel_path, wheel["sha256"]),
        "meson_installed_version": importlib.metadata.version("meson"),
        "pip_installed_version": importlib.metadata.version("pip"),
        "nasm_ubuntu_package": command("dpkg-query", "-W", "-f=${Version}", "nasm"),
        "jdk_release_sha256": sha256_file(java_home / "release"),
        "jdk_modules_sha256": sha256_file(java_home / "lib/modules"),
        "ndk_source_properties_sha256": sha256_file(ndk / "source.properties"),
        "cmake_source_properties_sha256": sha256_file(sdk / "cmake/3.22.1/source.properties"),
        "cmake_package_metadata_sha256": sha256_file(sdk / "cmake/3.22.1/package.xml"),
        "ninja_origin": "Unmodified bin/ninja supplied by the installed Android SDK cmake;3.22.1 package",
        "android_jar_sha256": sha256_file(sdk / "platforms/android-31/android.jar"),
        "build_tools_source_properties_sha256": sha256_file(sdk / "build-tools/30.0.3/source.properties"),
    }
    if packages["meson_installed_version"] != "1.7.0" or packages["nasm_ubuntu_package"] != "2.15.05-1":
        raise RuntimeError("An installed producer package changed from its reviewed version")
    return packages


def gradle_evidence(work):
    home = work / "gradle-home"
    candidates = list((home / "wrapper/dists/gradle-7.5-bin").glob("*/gradle-7.5/bin/gradle"))
    if len(candidates) != 1:
        raise RuntimeError("Expected the single checksum-verified Gradle 7.5 distribution")
    executable = candidates[0]
    version = command(executable, "--version", "--no-daemon")
    if "Gradle 7.5\n" not in version:
        raise RuntimeError("Actual Gradle version differs from the source recipe")
    libraries = {str(path.relative_to(executable.parents[1])): sha256_file(path)
                 for path in sorted((executable.parents[1] / "lib").rglob("*.jar"))}
    modules = home / "caches/modules-2/files-2.1"
    dependencies = {str(path.relative_to(modules)): sha256_file(path) for path in sorted(modules.rglob("*"))
                    if path.is_file() and path.suffix in (".jar", ".pom", ".module")}
    agp = [name for name in dependencies if name.startswith("com.android.tools.build/gradle/7.4.1/")
           and name.endswith("/gradle-7.4.1.jar")]
    if len(agp) != 1:
        raise RuntimeError("Missing actual AGP 7.4.1 implementation in the isolated dependency cache")
    return {"version": version, "launcher_sha256": sha256_file(executable),
            "distribution_jars_sha256": libraries, "resolved_public_dependency_sha256": dependencies}
