"""Bounded source identities and Engine02 physical evidence; never launches Gradle."""
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

SOURCE = "7f9e49067acfd5c7f6eeb1a08550b98562d68c63"
SOURCE_TREE = "ff103c613e95aae853e929507f540c167edd3df7"
PREVIOUS_CARRIER = "2a02218c29f90fb6d66904353438739cf7df03b5"
FIRST_CARRIER = "2e1677025b0e4b55aa2a3f50237fc80a00f715d1"
ENGINE_COMMIT = "ed184165ebd3ee7f0d1db533cc40ca5a0868fdda"
ENGINE_TREE = "14e46a1ead24b5757d612fd55031e440f5304661"
CONTROLS = [".github/workflows/app72569-public-linux.yml", "scripts/app72569-observer.gradle",
            "scripts/app72569-original-engine.init.gradle", "scripts/app72569_evidence.py",
            "scripts/validate_app72569_linux.py"]
MODULES = ("core", "domain", "presentation", "ui", "platform", "data/local", "data/remote",
           "sources/legacy", "sources/contracts", "data/download", "data", "sources/engine", "sources/config", "composeApp")
ENGINE_MODULES = (":source-contract", ":source-engine")
TEST_PATH = ":app:testDebugUnitTest"
COMPILERS = [("app", ":" + m.replace("/", ":") + ":compileAndroidMain") for m in MODULES] + [
    ("app", ":app:compileDebugKotlin"), ("app", ":app:compileDebugUnitTestKotlin"),
    ("app", ":data:local:kspAndroidMain")] + [("engine", m + ":compileAndroidMain") for m in ENGINE_MODULES]
OBSERVED = [f":{m}:{t}" for m in ("ui", "composeApp") for t in (
    "convertXmlValueResourcesForCommonMain", "generateResourceAccessorsForCommonMain", "generateComposeResClass")]
OBSERVED += [":composeApp:generateSourceRemoteConfig", ":app:prepareNotificationAndroidHostRuntime", TEST_PATH]
ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT.parent / "engine"
WORK = Path(os.environ["RUNNER_TEMP"]) / "app72569-public-work"
REPORTS = WORK.parent / "app72569-public-reports"
GOOGLE = ROOT / "app/google-services.json"
OUTPUTS = [ROOT / m / "build" for m in (*MODULES, "app", "desktopApp", "")] + [ROOT / ".gradle", ROOT / ".kotlin"]
OUTPUTS += [ENGINE / p for p in ("build", ".gradle", ".kotlin", "source-contract/build", "source-engine/build", "source-testkit/build")]
MIB = 1024 * 1024

def require(condition, message):
    if not condition:
        raise RuntimeError(message)

def bounded(path, limit):
    require(path.is_file() and path.resolve() == path, "Missing/aliased bounded input")
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    return data

def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def record(name, value):
    limit = 8 * MIB if name == "android-provenance.json" else 65536
    data = (json.dumps(value, indent=2) + "\n").encode()
    (REPORTS / name).write_bytes(data[:limit])
    require(len(data) <= limit, "JSON cap exceeded; retained bounded prefix: " + name)

def git(root, *args):
    return subprocess.check_output(["git", "--no-optional-locks", "-C", str(root), *args],
                                   timeout=30).decode().rstrip("\n")

def admit():
    for key, expected in {"GITHUB_ACTIONS": "true", "GITHUB_EVENT_NAME": "push", "GITHUB_RUN_ATTEMPT": "1",
                          "GITHUB_REPOSITORY": "kira-manga/kira-app", "RUNNER_OS": "Linux", "RUNNER_ARCH": "X64",
                          "GITHUB_REF": "refs/heads/remediation/app-7-25-69-public-validation"}.items():
        require(os.environ.get(key) == expected, "Unexpected " + key)
    require(ROOT == Path.cwd() == Path(os.environ["GITHUB_WORKSPACE"]) / "app" and
            WORK.parent.resolve() == WORK.parent and WORK.parent not in ROOT.parents and ROOT.parent not in WORK.parents,
            "Unexpected checkout/scratch paths")
    event = bounded(Path(os.environ["GITHUB_EVENT_PATH"]), MIB)
    require(len(event) <= MIB and json.loads(event)["repository"]["private"] is False, "Public repository required")
    require(git(ROOT, "rev-parse", "HEAD") == os.environ["GITHUB_SHA"] and
            git(ROOT, "rev-parse", "HEAD^@") == PREVIOUS_CARRIER and
            git(ROOT, "rev-parse", PREVIOUS_CARRIER + "^@") == FIRST_CARRIER and
            git(ROOT, "rev-parse", FIRST_CARRIER + "^@") == SOURCE and
            git(ROOT, "rev-parse", SOURCE + "^{tree}") == SOURCE_TREE,
            "Carrier must be the event commit and a single child of the pinned failed carrier/source chain")
    require(git(ROOT, "diff", "--name-status", SOURCE, "HEAD").splitlines() == ["A\t" + p for p in CONTROLS],
            "Carrier must add exactly the five reviewed controls")
    require(git(ENGINE, "rev-parse", "HEAD") == ENGINE_COMMIT and git(ENGINE, "rev-parse", "HEAD^{tree}") == ENGINE_TREE,
            "Exact original public Engine checkout required")
    require(all(not p.exists() and not p.is_symlink() for p in OUTPUTS + [GOOGLE, WORK, REPORTS]),
            "Preserve all preexisting outputs, Google configuration and scratch")

def write_binding(tests):
    common = [{"path": name, "sha256": row["sha256"]} for name, row in source_map(ENGINE).items()
              if "/src/commonMain/" in name and name.endswith(".kt")]
    binding = dict(status="PRIMARY_BOUND_FOR_REVIEW", contract="app72569-public-linux-original-engine02",
        app=dict(repo="app", head=SOURCE, tree=SOURCE_TREE, carrier=os.environ["GITHUB_SHA"]),
        engine=dict(source_path="engine", commit=ENGINE_COMMIT, tree=ENGINE_TREE, common_main=common),
        test=dict(task=TEST_PATH, max_heap="1g", cases=[dict(class_name=t.rsplit(".", 1)[0], method=t.rsplit(".", 1)[1]) for t in tests]),
        host_runtime=dict(stage_task=":app:prepareNotificationAndroidHostRuntime", relative_directory="notification-android-host-runtime",
                          sdk_configuration="notificationHostSdkInput", native_configuration="notificationHostNativeInput",
                          native_property="kira.notification.sqlite.native"))
    data = (json.dumps(binding) + "\n").encode()
    require(len(data) <= 65536, "Runtime binding exceeds64KiB")
    (WORK / "binding.json").write_bytes(data)
    return hashlib.sha256(data).hexdigest()

def source_map(root):
    rows = {}
    require(root.resolve() == root, "Aliased public checkout")
    for entry in git(root, "ls-tree", "-rz", "HEAD").rstrip("\0").split("\0"):
        metadata, name = entry.split("\t", 1)
        mode, kind, blob = metadata.split()
        path = root / name
        require(kind == "blob" and path.is_file() and path.resolve() == path, "Nonregular tracked source")
        require(mode == ("100755" if path.stat().st_mode & 0o111 else "100644"), "Tracked executable mode changed")
        content = hashlib.sha256()
        identity = hashlib.sha1(f"blob {path.stat().st_size}\0".encode())
        with path.open("rb") as stream:
            while chunk := stream.read(65536):
                content.update(chunk)
                identity.update(chunk)
        require(identity.hexdigest() == blob, "Tracked source bytes differ from public Git tree")
        rows[name] = {"sha256": content.hexdigest(), "git_mode": mode}
    return rows

def snapshot():
    result = {}
    for role, root in (("app", ROOT), ("engine", ENGINE)):
        rows = source_map(root)
        require(not git(root, "status", "--porcelain=v1", "--untracked-files=no"), "Tracked/index state is not clean")
        extras = git(root, "ls-files", "--others", "--exclude-standard", "-z").split("\0")
        extras += git(root, "ls-files", "--others", "--ignored", "--exclude-standard", "-z").split("\0")
        require(all(any(root / name == p or p in (root / name).parents for p in OUTPUTS + [GOOGLE])
                    for name in extras if name), "Extra untracked/ignored source or private input")
        require(role != "engine" or len(rows) == 51, "Original Engine must contain exactly51 tracked files")
        result[role] = dict(head=git(root, "rev-parse", "HEAD"), tree=git(root, "rev-parse", "HEAD^{tree}"),
                            file_count=len(rows), map_sha256=hashlib.sha256(json.dumps(rows, sort_keys=True).encode()).hexdigest())
        if role == "app":
            result[role]["controls"] = {name: rows[name] for name in CONTROLS}
    return result

def retain_xml(tests):
    directory = ROOT / "app/build/test-results/testDebugUnitTest"
    names = {f"TEST-{test.rsplit('.', 1)[0]}.xml" for test in tests}
    roster = []
    valid = {p.name for p in directory.glob("*.xml")} == names
    for name in sorted(names):
        path = directory / name
        if not path.is_file() or path.resolve() != path:
            valid = False
            continue
        data = bounded(path, MIB)
        valid &= len(data) <= MIB
        data = data[:MIB]
        (REPORTS / name).write_bytes(data)
        try:
            suite = ET.fromstring(data)
            cases = suite.findall("testcase")
            valid &= suite.tag == "testsuite" and int(suite.get("tests", "-1")) == len(cases)
            valid &= all(int(suite.get(key, "-1")) == 0 for key in ("failures", "errors", "skipped"))
        except (ET.ParseError, ValueError):
            valid = False
            continue
        valid &= all(case.get("classname") == name[5:-4] for case in cases)
        valid &= not any(suite.findall(f".//{tag}") for tag in ("failure", "error", "skipped"))
        roster.extend(f"{case.get('classname')}.{case.get('name')}" for case in cases)
    return valid and Counter(roster) == Counter(tests)

def records():
    labels = ("SETTINGS", "GRAPH", "PRODUCER_MODEL", "COMPILER_INPUT", "COMPILE_COMPLETE", "TASK_COMPLETE",
              "CLASSPATH", "RESOLUTION", "OUTGOING_CANDIDATE", "OUTGOING", "ARTIFACT", "HOST_RUNTIME", "ANDROID_CLASS_ORIGINS")
    rows = {name: [] for name in labels}
    data = bounded(REPORTS / "gradle.log", 16 * MIB)
    valid = len(data) <= 16 * MIB
    for line in data[:16 * MIB].decode(errors="replace").splitlines():
        for label in labels:
            marker = "KIRA_ORIGINAL_ENGINE_" + label + " "
            if line.startswith(marker):
                try:
                    rows[label].append(json.loads(line[len(marker):]))
                except ValueError:
                    valid = False
    record("android-provenance.json", {"records": rows, "parse_exact": valid})
    require(valid, "Truncated/invalid observer record; raw prefix retained")
    return rows

def worked(row):
    return (row["executed"] and row["didWork"] and not row["skipped"] and not row["upToDate"] and
            not row["noSource"] and row["failure"] is None)

def physical(row):
    path = Path(row["path"])
    require(path.resolve() == path and any((ENGINE / m[1:] / "build") in path.parents
                                          for m in ENGINE_MODULES), "Foreign/aliased producer output")
    if row["kind"] == "file":
        require(path.is_file() and path.stat().st_size == row["bytes"] and sha(path) == row["sha256"],
                "Produced physical artifact bytes changed before cleanup")
    else:
        require(row["kind"] == "directory" and path.is_dir(), "Missing compiler class directory")
        classes = {}
        for file in (path / "me/manga/kira/source").rglob("*.class"):
            require(file.is_file() and file.resolve() == file, "Aliased producer class")
            classes[str(file.relative_to(path))] = sha(file)
        require(classes == row["neutralClasses"], "Physical compiler class map changed before cleanup")

def check_actions(rows):
    completions = rows["COMPILE_COMPLETE"]
    wanted = [p for p in COMPILERS if p != ("app", ":data:local:kspAndroidMain")]
    require(Counter((r["build"], r["path"]) for r in completions) == Counter(COMPILERS) and
            all(worked(r) for r in completions), "Actual18 compiler actions plus Room KSP must all work")
    tasks = rows["TASK_COMPLETE"]
    require(Counter(r["path"] for r in tasks if r["build"] == "app") == Counter(OBSERVED) and
            all(worked(r) for r in tasks), "Normal resources/host/producer actions must all work")
    for label, expected in (("COMPILER_INPUT", wanted), ("CLASSPATH", wanted + [("app", TEST_PATH)]),
                            ("RESOLUTION", wanted + [("app", TEST_PATH)])):
        require(Counter((r["build"], r["task"]) for r in rows[label]) == Counter(expected), "Missing/extra " + label)
    for label in ("SETTINGS", "GRAPH"):
        require(Counter(r["build"] for r in rows[label]) == Counter(["app", "engine"]), "Both actual builds required")
    require(Counter((r["build"], r["project"]) for r in rows["PRODUCER_MODEL"]) ==
            Counter(("engine", m) for m in (*ENGINE_MODULES, ":source-testkit")), "Keep original three-module model")
    require(len(rows["HOST_RUNTIME"]) == 1 and len(rows["ANDROID_CLASS_ORIGINS"]) == 2, "Before-fork Android inputs required")
    room = next(r for r in completions if (r["build"], r["path"]) == ("app", ":data:local:kspAndroidMain"))
    require({"MangaDatabase_Impl", "ChapterDownloadDao_Impl"} <= {Path(r["path"]).stem for r in room["roomSources"]},
            "Real Android Room generated implementations required")
    for row in room["roomSources"]:
        path = Path(row["path"])
        require(path.resolve() == path and ROOT / "data/local/build" in path.parents and sha(path) == row["sha256"],
                "Generated Room bytes changed before cleanup")

def producer_classes(rows):
    compiled = {}
    for row in rows["COMPILE_COMPLETE"]:
        if row["build"] != "engine":
            continue
        classes = {}
        for output in row["outputs"]:
            physical(output)
            for name, digest in output["neutralClasses"].items():
                require(name not in classes or classes[name] == digest, "Inconsistent producer compiler output")
                classes[name] = digest
        require(classes, "Fresh Android compiler classes required for both producer modules")
        compiled[row["path"].rsplit(":", 1)[0]] = classes
    return compiled

def check_artifacts(rows):
    outgoing = rows["OUTGOING"]
    require(len(outgoing) == 1 and outgoing[0]["build"] == "engine", "One actual Android outgoing model required")
    states = {(r["build"], r["path"]): r for r in rows["COMPILE_COMPLETE"] + rows["TASK_COMPLETE"]}
    for artifact in rows["ARTIFACT"]:
        require(artifact["build"] == "engine" and any(all(artifact[k] == a[k] for k in
                ("project", "variant", "attributes", "path", "producers", "producerDependencyPaths"))
                for a in outgoing[0]["artifacts"]), "Artifact is not from the observed ordinary Android model")
        require(artifact["producer"] in artifact["producers"] and
                all(worked(states[("engine", p)]) for p in artifact["producers"]) and
                artifact["project"] + ":compileAndroidMain" in artifact["producerDependencyPaths"],
                "Real outgoing actions must retain their ordinary main compiler prerequisite")
        physical(artifact["file"])

def lineage(rows, compiled):
    joins = []
    for cp in rows["CLASSPATH"]:
        usage = "java-runtime" if cp["kind"] == "test" else "java-api"
        for entry in cp["entries"]:
            if not entry["neutralClasses"]:
                continue
            matches = [a for a in rows["ARTIFACT"] if a["file"] == entry and
                       a["attributes"].get("org.gradle.usage", {}).get("value") == usage and
                       a["attributes"].get("org.jetbrains.kotlin.platform.type", {}).get("value") == "androidJvm"]
            require(matches, "No path/hash-identical fresh outgoing Android artifact on actual classpath")
            for match in matches:
                require(entry["neutralClasses"] == compiled[match["project"]], "Compiler/artifact/consumer class bytes differ")
                joins.append(dict(build=cp["build"], task=cp["task"], kind=cp["kind"], path=entry["path"],
                                  sha256=entry["sha256"], project=match["project"], variant=match["variant"],
                                  producers=match["producers"], producer_dependency_paths=match["producerDependencyPaths"],
                                  compiler_class_bytes_equal=True))
    for build, task, wanted in (("app", ":sources:engine:compileAndroidMain", set(ENGINE_MODULES)),
                                ("app", TEST_PATH, set(ENGINE_MODULES)),
                                ("engine", ":source-engine:compileAndroidMain", {":source-contract"})):
        require({r["project"] for r in joins if (r["build"], r["task"]) == (build, task)} == wanted,
                "Fresh original Android suppliers must reach both actual consumer compilers and Test")
    return joins

def prove_android():
    rows = records()
    check_actions(rows)
    compiled = producer_classes(rows)
    check_artifacts(rows)
    joins = lineage(rows, compiled)
    record("android-provenance.json", {"records": rows, "lineage": joins, "physical_joins_before_cleanup": True})
    return True
