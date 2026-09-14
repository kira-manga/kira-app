"""Bounded App13 source and per-platform physical evidence; never launches Gradle."""
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ENGINE_COMMIT = "ed184165ebd3ee7f0d1db533cc40ca5a0868fdda"
ENGINE_TREE = "14e46a1ead24b5757d612fd55031e440f5304661"
CONTROLS = [".github/workflows/app13-public-linux.yml", "scripts/app13-observer.gradle",
            "scripts/app13-original-engine.init.gradle", "scripts/app13-portfolio.json",
            "scripts/app13_evidence.py", "scripts/validate_app13_linux.py"]
MODULES = ("core", "domain", "presentation", "ui", "platform", "data/local", "data/remote", "sources/legacy",
           "sources/contracts", "data/download", "data", "sources/engine", "sources/config", "composeApp")
ENGINE_MODULES = (":source-contract", ":source-engine")
PLATFORMS = {"jvm": ("compileKotlinDesktop", "compileKotlinJvm")}
TEST_PATHS = (":composeApp:desktopTest",)
ROOMS = (":data:local:kspKotlinDesktop",)
COMPILERS = [("app", ":" + m.replace("/", ":") + ":" + names[0]) for m in MODULES for names in PLATFORMS.values()]
COMPILERS += [("app", ":composeApp:compileTestKotlinDesktop")]
COMPILERS += [("app", p) for p in ROOMS] + [("engine", m + ":" + names[1]) for m in ENGINE_MODULES for names in PLATFORMS.values()]
OBSERVED = [f":{m}:{t}" for m in ("ui", "composeApp") for t in (
    "convertXmlValueResourcesForCommonMain", "generateResourceAccessorsForCommonMain", "generateComposeResClass")]
OBSERVED += [":composeApp:generateSourceRemoteConfig", *TEST_PATHS, ":composeApp:checkLocaleKeyParity"]
ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT.parent / "engine"
WORK = Path(os.environ["RUNNER_TEMP"]) / "app13-public-work"
REPORTS = WORK.parent / "app13-public-reports"
PORTFOLIO_PATH = ROOT / "scripts/app13-portfolio.json"
OUTPUTS = [ROOT / m / "build" for m in (*MODULES, "app", "desktopApp", "")] + [ROOT / ".gradle", ROOT / ".kotlin"]
OUTPUTS += [ENGINE / p for p in ("build", ".gradle", ".kotlin", "source-contract/build", "source-engine/build", "source-testkit/build")]
MIB = 1024 * 1024

def require(condition, message):
    if not condition:
        raise RuntimeError(message)

def bounded(path, limit):
    require(path.is_file() and path.resolve() == path, "Missing/aliased bounded input")
    with path.open("rb") as stream:
        return stream.read(limit + 1)

def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def record(name, value):
    limit = 8 * MIB if name == "linux-provenance.json" else 65536
    data = (json.dumps(value, indent=2) + "\n").encode()
    (REPORTS / name).write_bytes(data[:limit])
    require(len(data) <= limit, "JSON cap exceeded; retained bounded prefix: " + name)

def git(root, *args):
    return subprocess.check_output(["git", "--no-optional-locks", "-C", str(root), *args], timeout=30).decode().rstrip("\n")

def admit(portfolio):
    for key, expected in {"GITHUB_ACTIONS": "true", "GITHUB_EVENT_NAME": "push", "GITHUB_RUN_ATTEMPT": "1",
                          "GITHUB_REPOSITORY": "kira-manga/kira-app", "RUNNER_OS": "Linux", "RUNNER_ARCH": "X64",
                          "GITHUB_REF": "refs/heads/remediation/app-13-public-validation-06"}.items():
        require(os.environ.get(key) == expected, "Unexpected " + key)
    require(ROOT == Path.cwd() == Path(os.environ["GITHUB_WORKSPACE"]) / "app" and
            WORK.parent.resolve() == WORK.parent and WORK.parent not in ROOT.parents and ROOT.parent not in WORK.parents,
            "Unexpected checkout/scratch paths")
    event = bounded(Path(os.environ["GITHUB_EVENT_PATH"]), MIB)
    require(len(event) <= MIB and json.loads(event)["repository"]["private"] is False, "Public repository required")
    source = portfolio["source"]
    require(git(ROOT, "rev-parse", "HEAD") == os.environ["GITHUB_SHA"] and git(ROOT, "rev-parse", "HEAD^@") == source["head"] and
            git(ROOT, "rev-parse", source["head"] + "^{tree}") == source["tree"], "Carrier must directly extend the frozen source")
    require(git(ROOT, "diff", "--name-status", source["head"], "HEAD").splitlines() == ["A\t" + p for p in CONTROLS],
            "Carrier must add exactly the six reviewed controls")
    require(git(ENGINE, "rev-parse", "HEAD") == ENGINE_COMMIT and git(ENGINE, "rev-parse", "HEAD^{tree}") == ENGINE_TREE,
            "Exact original public Engine checkout required")
    require(all(not p.exists() and not p.is_symlink() for p in OUTPUTS + [WORK, REPORTS]), "Preserve preexisting outputs/scratch")

def write_binding(portfolio):
    common = [{"path": name, "sha256": row["sha256"]} for name, row in source_map(ENGINE).items()
              if "/src/commonMain/" in name and name.endswith(".kt")]
    binding = dict(status="PRIMARY_BOUND_FOR_REVIEW", contract=portfolio["contract"], portfolio_sha256=sha(PORTFOLIO_PATH),
        app=dict(repo="app", **portfolio["source"], carrier=os.environ["GITHUB_SHA"]),
        engine=dict(source_path="engine", commit=ENGINE_COMMIT, tree=ENGINE_TREE, common_main=common),
        tests=portfolio["tests"], locale_task=portfolio["locale_task"], statics=portfolio["statics"], max_heap="1g")
    data = (json.dumps(binding) + "\n").encode()
    require(len(data) <= 65536, "Runtime binding exceeds64KiB")
    with (WORK / "binding.json").open("xb") as stream:
        stream.write(data)
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
        require(all(any(root / name == p or p in (root / name).parents for p in OUTPUTS) for name in extras if name),
                "Extra untracked/ignored source or private input; no Google example copy")
        require(role != "engine" or len(rows) == 51, "Original Engine must contain exactly51 tracked files")
        result[role] = dict(head=git(root, "rev-parse", "HEAD"), tree=git(root, "rev-parse", "HEAD^{tree}"),
                            file_count=len(rows), map_sha256=hashlib.sha256(json.dumps(rows, sort_keys=True).encode()).hexdigest())
        if role == "app":
            result[role]["controls"] = {name: rows[name] for name in CONTROLS}
    return result

def xml_hosts(rows):
    hosts = {r["task"]: r for r in rows["TEST_RUNTIME"]}
    require(len(rows["TEST_RUNTIME"]) == len(hosts) and set(hosts) <= set(TEST_PATHS) and
            all(r["build"] == "app" for r in hosts.values()), "Duplicate/foreign XML owner")
    return hosts

def retain_xml(portfolio, rows):
    hosts = xml_hosts(rows)
    valid = set(hosts) == set(TEST_PATHS) and len({r["xmlDirectory"] for r in hosts.values()}) == len(hosts)
    for test in (t for t in portfolio["tests"] if t["task"] in hosts):
        directory = Path(hosts[test["task"]]["xmlDirectory"])
        require(directory.resolve() == directory and ROOT / "composeApp/build/test-results" in directory.parents, "Aliased/foreign XML directory")
        tests = [case["selector"] for case in test["cases"]]
        names, roster = {f"TEST-{t.rsplit('.', 1)[0]}.xml" for t in tests}, []
        valid &= {p.name for p in directory.glob("*.xml")} == names
        for name in sorted(names):
            path = directory / name
            if not path.is_file() or path.resolve() != path:
                valid = False
                continue
            data = bounded(path, MIB)
            valid &= len(data) <= MIB
            (REPORTS / name).write_bytes(data[:MIB])
            try:
                suite = ET.fromstring(data[:MIB])
                cases = suite.findall("testcase")
                valid &= suite.tag == "testsuite" and int(suite.get("tests", "-1")) == len(cases)
                valid &= all(int(suite.get(key, "-1")) == 0 for key in ("failures", "errors", "skipped"))
            except (ET.ParseError, ValueError):
                valid = False
                continue
            valid &= all(case.get("classname") == name[5:-4] for case in cases)
            valid &= not any(suite.findall(f".//{tag}") for tag in ("failure", "error", "skipped"))
            roster.extend(f"{case.get('classname')}.{case.get('name')}" for case in cases)
        valid &= Counter(roster) == Counter(tests)
    return valid

def records():
    labels = ("SETTINGS", "GRAPH", "PRODUCER_MODEL", "COMPILER_INPUT", "COMPILE_COMPLETE", "TASK_COMPLETE", "CLASSPATH",
              "RESOLUTION", "OUTGOING_CANDIDATE", "OUTGOING", "ARTIFACT", "HOST_RUNTIME", "TEST_RUNTIME", "ANDROID_CLASS_ORIGINS")
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
    record("linux-provenance.json", {"records": rows, "parse_exact": valid})
    require(valid, "Truncated/invalid observer record; raw prefix retained")
    return rows

def worked(row):
    return (row["executed"] and row["didWork"] and not row["skipped"] and not row["upToDate"] and
            not row["noSource"] and row["failure"] is None)

def physical(row):
    path = Path(row["path"])
    require(path.resolve() == path and any((ENGINE / m[1:] / "build") in path.parents for m in ENGINE_MODULES),
            "Foreign/aliased producer output")
    if row["kind"] == "file":
        require(path.is_file() and path.stat().st_size == row["bytes"] and sha(path) == row["sha256"], "Physical artifact bytes changed")
    else:
        require(row["kind"] == "directory" and path.is_dir(), "Missing compiler class directory")
        classes = {}
        for file in (path / "me/manga/kira/source").rglob("*.class"):
            require(file.is_file() and file.resolve() == file, "Aliased producer class")
            classes[str(file.relative_to(path))] = sha(file)
        require(classes == row["neutralClasses"], "Physical compiler class map changed before cleanup")

def check_actions(rows):
    completions = rows["COMPILE_COMPLETE"]
    wanted = [p for p in COMPILERS if p not in [("app", task) for task in ROOMS]]
    require(Counter((r["build"], r["path"]) for r in completions) == Counter(COMPILERS) and all(worked(r) for r in completions),
            "All17 normal JVM compiler actions plus the Room KSP action must work")
    tasks = rows["TASK_COMPLETE"]
    require(Counter(r["path"] for r in tasks if r["build"] == "app") == Counter(OBSERVED) and all(worked(r) for r in tasks),
            "Normal resources/Test/locale/producer actions must work")
    for label in ("COMPILER_INPUT", "CLASSPATH", "RESOLUTION"):
        expected = wanted + ([("app", p) for p in TEST_PATHS] if label != "COMPILER_INPUT" else [])
        require(Counter((r["build"], r["task"]) for r in rows[label]) == Counter(expected), "Missing/extra " + label)
    for label in ("SETTINGS", "GRAPH"):
        require(Counter(r["build"] for r in rows[label]) == Counter(["app", "engine"]), "Both actual builds required")
    require(Counter((r["build"], r["project"]) for r in rows["PRODUCER_MODEL"]) ==
            Counter(("engine", m) for m in (*ENGINE_MODULES, ":source-testkit")), "Keep original three-module model")
    require(not rows["HOST_RUNTIME"] and not rows["ANDROID_CLASS_ORIGINS"] and
            Counter(r["task"] for r in rows["TEST_RUNTIME"]) == Counter(TEST_PATHS),
            "Only the JVM before-fork Test observation; no fresh Android runtime rows")
    for room in (r for r in completions if r["build"] == "app" and r["path"] in ROOMS):
        require({"MangaDatabase_Impl", "ChapterDownloadDao_Impl"} <= {Path(r["path"]).stem for r in room["roomSources"]},
                "Real JVM Room generated implementations required")
        for row in room["roomSources"]:
            path = Path(row["path"])
            require(path.resolve() == path and ROOT / "data/local/build" in path.parents and sha(path) == row["sha256"],
                    "Generated Room bytes changed before cleanup")

def producer_classes(rows):
    compiled = {}
    for row in rows["COMPILE_COMPLETE"]:
        if row["build"] != "engine":
            continue
        key, classes = (row["platform"], row["path"].rsplit(":", 1)[0]), {}
        require(key not in compiled, "Duplicate platform/module compiler completion")
        for output in row["outputs"]:
            physical(output)
            for name, digest in output["neutralClasses"].items():
                require(name not in classes or classes[name] == digest, "Inconsistent compiler output")
                classes[name] = digest
        require(classes, "Fresh compiler classes required for both original modules on the selected JVM platform")
        compiled[key] = classes
    require(set(compiled) == {(p, m) for p in PLATFORMS for m in ENGINE_MODULES}, "No overwritten/missing platform provenance")
    return compiled

def check_artifacts(rows):
    outgoing = rows["OUTGOING"]
    require(len(outgoing) == 1 and outgoing[0]["build"] == "engine", "One actual JVM outgoing model required")
    states = {(r["build"], r["path"]): r for r in rows["COMPILE_COMPLETE"] + rows["TASK_COMPLETE"]}
    for artifact in rows["ARTIFACT"]:
        require(artifact["build"] == "engine" and any(all(artifact[k] == a[k] for k in
                ("project", "platform", "variant", "attributes", "artifactType", "classifier", "extension", "path", "producers", "producerDependencyPaths"))
                for a in outgoing[0]["artifacts"]), "Artifact not in the admitted ordinary outgoing model")
        compiler = artifact["project"] + ":" + PLATFORMS[artifact["platform"]][1]
        require(artifact["producer"] in artifact["producers"] and all(worked(states[("engine", p)]) for p in artifact["producers"]) and
                compiler in artifact["producerDependencyPaths"], "Real outgoing actions must retain their platform compiler prerequisite")
        physical(artifact["file"])

def lineage(rows, compiled):
    joins = []
    for cp in rows["CLASSPATH"]:
        usage, suppliers = "java-runtime" if cp["kind"] == "test" else "java-api", {}
        for entry in cp["entries"]:
            if not entry["neutralClasses"]:
                continue
            for name in entry["neutralClasses"]:
                require(name not in suppliers or suppliers[name] == entry["path"], "Same-hash duplicates in different containers are forbidden")
                suppliers[name] = entry["path"]
            matches = [a for a in rows["ARTIFACT"] if a["file"] == entry and a["platform"] == cp["platform"] and
                       a["attributes"].get("org.gradle.usage", {}).get("value") == usage and
                       a["attributes"].get("org.jetbrains.kotlin.platform.type", {}).get("value") == cp["platform"]]
            require(matches, "No identical fresh normal-platform outgoing artifact on actual classpath")
            require(len({a["project"] for a in matches}) == 1, "Ambiguous module supplier")
            for match in matches:
                require(entry["neutralClasses"] == compiled[(cp["platform"], match["project"])], "Compiler/artifact/consumer class bytes differ")
                joins.append(dict(build=cp["build"], task=cp["task"], kind=cp["kind"], platform=cp["platform"], path=entry["path"],
                    sha256=entry["sha256"], project=match["project"], variant=match["variant"], producers=match["producers"],
                    producer_dependency_paths=match["producerDependencyPaths"], compiler_class_bytes_equal=True))
    for index, (platform, names) in enumerate(PLATFORMS.items()):
        for build, task, wanted in (("app", ":sources:engine:" + names[0], set(ENGINE_MODULES)),
                                   ("app", TEST_PATHS[index], set(ENGINE_MODULES)),
                                   ("engine", ":source-engine:" + names[1], {":source-contract"})):
            require({r["project"] for r in joins if (r["build"], r["task"], r["platform"]) == (build, task, platform)} == wanted,
                    "Each normal platform's original suppliers must reach its consumer compilers and Test")
    return joins

def prove_linux(rows):
    check_actions(rows)
    compiled = producer_classes(rows)
    check_artifacts(rows)
    joins = lineage(rows, compiled)
    record("linux-provenance.json", {"records": rows, "lineage": joins, "physical_joins_before_cleanup": True})
    return True
