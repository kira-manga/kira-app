"""PRIVATE / UNBOUND / NOT_RUN. Pure App10 shared evidence; no subprocess/lifecycle/entry point.

Engine/Room physical joins adapt the existing App13 Android8 bodies. Whole-class XML
assertions adapt preparation11. All paths are the sole App10 lane's owned outputs.
"""
from collections import Counter
import hashlib
import io
import json
from pathlib import Path
import struct
import time
import xml.etree.ElementTree as ET
import zipfile

from app10_cold_root_support import MIB, regular, require, sha

ENGINE_MODULES = (":source-contract", ":source-engine")
PLATFORMS = {"androidJvm": ("compileAndroidMain", "compileAndroidMain")}
MODULES = ("core", "domain", "presentation", "ui", "platform", "data/local", "data/remote", "sources/legacy",
           "sources/contracts", "data/download", "data", "sources/engine", "sources/config", "composeApp")
TEST_PATHS = ()  # APK-only first phase; all previously executed host methods are carried separately.
ROOMS = (":data:local:kspAndroidMain",)
COMPILERS = [("app", ":" + m.replace("/", ":") + ":compileAndroidMain") for m in MODULES]
COMPILERS += [("app", p) for p in (":app:compileDebugKotlin", *ROOMS)]
COMPILERS += [("engine", m + ":compileAndroidMain") for m in ENGINE_MODULES]
RESOURCES = [f":{m}:{t}" for m in ("ui", "composeApp") for t in (
    "convertXmlValueResourcesForCommonMain", "generateResourceAccessorsForCommonMain", "generateComposeResClass")]
RESOURCES += [":composeApp:generateSourceRemoteConfig", ":app:packageDebug"]
LABELS = ("SETTINGS", "GRAPH", "PRODUCER_MODEL", "COMPILER_INPUT", "COMPILE_COMPLETE", "TASK_COMPLETE", "CLASSPATH",
          "RESOLUTION", "OUTGOING_CANDIDATE", "OUTGOING", "ARTIFACT", "HOST_RUNTIME", "TEST_RUNTIME",
          "ANDROID_CLASS_ORIGINS", "AVIF_RESOLUTION", "AVIF_CLASS_ORIGINS", "DEVICE_RUNTIME")
ROOT = ENGINE = REPORTS = PORTFOLIO = None


def configure(app, engine, reports, portfolio):
    global ROOT, ENGINE, REPORTS, PORTFOLIO
    ROOT, ENGINE, REPORTS, PORTFOLIO = app, engine, reports, portfolio


def record(name, value, cap=8*MIB):
    data = (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()
    require(len(data) <= cap, "Bounded evidence JSON exceeded")
    with (REPORTS / name).open("xb") as target:
        target.write(data)


def records(data):
    rows = {name: [] for name in LABELS}
    require(len(data) <= 16*MIB, "Bounded ordinary Gradle log required")
    for line in data.decode(errors="strict").splitlines():
        for label in LABELS:
            marker = "KIRA_ORIGINAL_ENGINE_" + label + " "
            if line.startswith(marker):
                rows[label].append(json.loads(line[len(marker):]))
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
        require(classes, "Fresh Android compiler classes required for both original modules")
        compiled[key] = classes
    require(set(compiled) == {(p, m) for p in PLATFORMS for m in ENGINE_MODULES}, "No overwritten/missing platform provenance")
    return compiled

def check_artifacts(rows):
    outgoing = rows["OUTGOING"]
    require(len(outgoing) == 1 and outgoing[0]["build"] == "engine", "One actual Android outgoing model required")
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
                                   ("engine", ":source-engine:" + names[1], {":source-contract"})):
            require({r["project"] for r in joins if (r["build"], r["task"], r["platform"]) == (build, task, platform)} == wanted,
                    "Original suppliers must reach the ordinary consumer compilers; prior host runtime joins are carried")
    return joins

def check_actions(rows):
    completions = rows["COMPILE_COMPLETE"]
    require(Counter((r["build"], r["path"]) for r in completions) == Counter(COMPILERS) and all(worked(r) for r in completions),
            "Every ordinary APK Android compiler and original Engine/Room action must work")
    tasks = rows["TASK_COMPLETE"]
    states = {(r["build"], r["path"]): r for r in tasks}
    require(len(states) == len(tasks) and all(r["failure"] is None for r in tasks), "No duplicate/failed actual task completion")
    require(all(("app", p) in states and worked(states[("app", p)]) for p in RESOURCES), "Required resources/host/package actions did not work")
    root = states.get(("app", ":app:assembleDebug"), {})
    require(root.get("executed") and root.get("failure") is None, "Ordinary aggregate assemble root not completed")
    require(all(states.get(("app", p), {}).get("emptyProcessorClasspath") for p in (":app:kspDebugKotlin",)),
            "Existing normal empty-processor KSP skips must be observed, not relabeled compile work")
    wanted = [pair for pair in COMPILERS if pair not in [("app", task) for task in ROOMS]]
    for label in ("COMPILER_INPUT", "CLASSPATH", "RESOLUTION"):
        extra = [] if label == "COMPILER_INPUT" else [("app", p) for p in TEST_PATHS]
        if label == "RESOLUTION":
            extra += [("app", ":app:packageDebug")]
        require(Counter((r["build"], r["task"]) for r in rows[label]) == Counter(wanted + extra), "Missing/extra compiler/input evidence")
    for label in ("SETTINGS", "GRAPH"):
        require(Counter(r["build"] for r in rows[label]) == Counter(["app", "engine"]), "Both actual builds required")
    require(Counter((r["build"], r["project"]) for r in rows["PRODUCER_MODEL"]) ==
            Counter(("engine", m) for m in (*ENGINE_MODULES, ":source-testkit")), "Keep original three-module model")
    for label in ("HOST_RUNTIME", "TEST_RUNTIME"):
        require(Counter(r["task"] for r in rows[label]) == Counter(TEST_PATHS), "No unrequested host Test runtime may execute")
    require(Counter(r["task"] for r in rows["ANDROID_CLASS_ORIGINS"]) == Counter({p: 2 for p in TEST_PATHS}), "No unrequested host Room/SQLite class observations")
    for room in (r for r in completions if r["build"] == "app" and r["path"] in ROOMS):
        require({"MangaDatabase_Impl", "ChapterDownloadDao_Impl"} <= {Path(r["path"]).stem for r in room["roomSources"]},
                "Real Android Room generated implementations required")
        for row in room["roomSources"]:
            path = Path(row["path"])
            require(path.resolve() == path and ROOT / "data/local/build" in path.parents and sha(path) == row["sha256"],
                    "Generated Android Room bytes changed before last consumer")
    for task in (":platform:compileAndroidMain", ":composeApp:compileAndroidMain", ":app:compileDebugKotlin", ":app:packageDebug"):
        require(any(r["task"] == task for r in rows["AVIF_RESOLUTION"]), "Genuine candidate must reach each production consumer")
    for task in (":platform:compileAndroidMain", ":composeApp:compileAndroidMain", ":app:compileDebugKotlin"):
        require(any(r["task"] == task and r["entry"] == "org/aomedia/avif/android/AvifDecoder.class" for r in rows["AVIF_CLASS_ORIGINS"]),
                "Actual production AVIF class supplier missing")


def retain_xml(rows, device=False):
    observed, inventory = [], []
    valid = True
    if not device:
        hosts = {r["task"]: r for r in rows["TEST_RUNTIME"]}
        require(len(hosts) == len(rows["TEST_RUNTIME"]) and set(hosts) <= set(TEST_PATHS), "Duplicate/foreign XML owner")
        valid &= set(hosts) == set(TEST_PATHS)
        sources = []
        index = 0
        for spec in PORTFOLIO["tests"]:
            if spec["task"] not in hosts:
                continue
            directory = Path(hosts[spec["task"]]["xmlDirectory"])
            build = ROOT / spec["task"].rsplit(":", 1)[0].lstrip(":").replace(":", "/") / "build"
            require(directory.resolve() == directory and build / "test-results" in directory.parents, "Foreign host XML directory")
            expected = {"TEST-" + c + ".xml": c for c in spec["classes"]}
            valid &= {p.name for p in directory.glob("*.xml")} == set(expected)
            for name, klass in expected.items():
                index += 1
                sources.append((directory / name, f"host-{index:02}.xml", spec["task"], {klass}))
    else:
        require(len(rows["DEVICE_RUNTIME"]) <= 1, "Duplicate connected task observation")
        sources = []
        valid &= len(rows["DEVICE_RUNTIME"]) == 1
        if rows["DEVICE_RUNTIME"]:
            row = rows["DEVICE_RUNTIME"][0]
            require(row["task"] == PORTFOLIO["device"]["task"], "Foreign connected XML owner")
            directory = Path(row["resultsDirectory"])
            require(directory.resolve() == directory and ROOT / "platform/build" in directory.parents, "Foreign device XML directory")
            files = sorted(directory.rglob("*.xml"))
            require(len(files) <= 32, "Bounded raw connected XML inventory required")
            valid &= bool(files)
            sources = [(p, f"device-{i:02}.xml", row["task"], set(PORTFOLIO["device"]["classes"])) for i, p in enumerate(files, 1)]
    for path, name, task, classes in sources:
        if not path.is_file():
            valid = False
            continue
        data = regular(path, MIB)
        with (REPORTS / name).open("xb") as out:
            out.write(data)
        inventory.append(dict(file=name, source=str(path.relative_to(ROOT)), sha256=hashlib.sha256(data).hexdigest(), bytes=len(data)))
        try:
            root = ET.fromstring(data)
            suites = [root] if root.tag == "testsuite" else list(root) if root.tag == "testsuites" else []
            valid &= bool(suites) and not any(root.findall(".//" + tag) for tag in ("failure", "error", "skipped"))
            for suite in suites:
                cases = suite.findall("testcase")
                valid &= suite.tag == "testsuite" and bool(cases) and int(suite.get("tests", "-1")) == len(cases)
                valid &= all(int(suite.get(k, "-1")) == 0 for k in ("failures", "errors", "skipped"))
                valid &= all(c.get("classname") in classes and c.get("name") and
                             not any(c.find(k) is not None for k in ("failure", "error", "skipped")) for c in cases)
                observed.extend(dict(task=task, class_name=c.get("classname"), method=c.get("name")) for c in cases)
        except (ET.ParseError, ValueError):
            valid = False
    keys = [(r["task"], r["class_name"], r["method"]) for r in observed]
    valid &= len(keys) == len(set(keys))
    expected = ({(PORTFOLIO["device"]["task"], c) for c in PORTFOLIO["device"]["classes"]} if device else
                {(s["task"], c) for s in PORTFOLIO["tests"] for c in s["classes"]})
    valid &= {(r["task"], r["class_name"]) for r in observed} == expected
    return dict(exact_nonzero_no_failures_no_skips=bool(valid and expected),
                requested_class_count=len(expected), inventory=inventory, observed=observed)


def capture_phase(log, device=False):
    phase = "device" if device else "host"
    data = regular(log, 16*MIB)
    with (REPORTS / ("device-build.log" if device else "build.log")).open("xb") as out:
        out.write(data)
    rows, xml, joins, error = {}, None, [], None
    try:
        rows = records(data)
        xml = retain_xml(rows, device)
        if device or PORTFOLIO["tests"]:
            require(xml["exact_nonzero_no_failures_no_skips"], "Actual whole-class XML inventory failed")
        else:
            require(not rows["TEST_RUNTIME"] and not rows["HOST_RUNTIME"] and
                    xml["requested_class_count"] == 0 and not xml["inventory"] and not xml["observed"],
                    "APK-only phase must not execute or claim host tests")
        if device:
            task, compiler = PORTFOLIO["device"]["task"], PORTFOLIO["device"]["compiler"]
            completions = rows["COMPILE_COMPLETE"] + rows["TASK_COMPLETE"]
            require(len({(r["build"], r["path"]) for r in completions}) == len(completions) and
                    all(r["failure"] is None for r in completions), "Duplicate/failed device graph action")
            require(all(sum(r["build"] == "app" and r["path"] == p and worked(r) for r in completions) == 1 for p in (task, compiler)),
                    "New ordinary device compiler and connected action must really work")
            require(all(r["build"] == "app" and r["path"] in (compiler, ":core:compileAndroidMain", ":platform:compileAndroidMain")
                        for r in rows["COMPILE_COMPLETE"]), "No repeated Engine/host/app compiler batch")
            retained_mains = [r for r in rows["COMPILE_COMPLETE"] if r["path"] != compiler]
            require(Counter(r["path"] for r in retained_mains) ==
                    Counter((":core:compileAndroidMain", ":platform:compileAndroidMain")) and
                    all(r["executed"] and r["upToDate"] and not r["didWork"] and not r["noSource"] and
                        r["failure"] is None for r in retained_mains),
                    "Retained core/platform outputs must be reused, not rebuilt for the device phase")
            require(sum(r["task"] == compiler for r in rows["COMPILER_INPUT"]) == 1 and
                    any(r["task"] == compiler for r in rows["AVIF_CLASS_ORIGINS"]) and
                    all(any(r["task"] == p for r in rows["AVIF_RESOLUTION"]) for p in (compiler, task)),
                    "Real device compiler/class/AAR runtime observations required")
        else:
            check_actions(rows)
            compiled = producer_classes(rows)
            check_artifacts(rows)
            joins = lineage(rows, compiled)
    except Exception as failure:
        error = dict(type=type(failure).__name__, stage=phase + "-evidence")
    record(phase + "-provenance.json", dict(records=rows, lineage=joins, physical_joins_before_cleanup=error is None, error=error))
    record(phase + "-tests.json", xml or dict(exact_nonzero_no_failures_no_skips=False), cap=MIB)
    return error is None


def candidate_input(app):
    path = app / "platform/vendor/avif/candidate/binding.json"
    value = json.loads(regular(path, 65536))
    require(value["coordinate"] == "org.aomedia.avif.android:avif:1.3.0.841110fd-kira-limits1" and
            value["aar"]["sha256"] == "3fb46514a771c9d8efc38192d80e69031021c3b6f7f5f09a154131ca083c5888" and
            value["aar"]["bytes"] == 3002110, "Only the primary adopted genuine AAR")
    aar = app / value["aar"]["path"]
    require(aar.resolve() == aar and aar.stat().st_size == value["aar"]["bytes"] and sha(aar) == value["aar"]["sha256"], "Candidate bytes changed")
    report_pin = value["producer"]["actual_evidence"]["artifact-report.json"]
    report_path = app / report_pin["path"]
    require(sha(report_path) == report_pin["sha256"] == "ca4117c1ba14b2bd16739b417ed968154221aac0500b957e44a21e4c9cd20903", "Original producer report changed")
    report = json.loads(regular(report_path, 65536))
    payloads = value["candidate_payload_pins"]
    with zipfile.ZipFile(aar) as archive:
        data = archive.read("classes.jar")
        require(len(data) == payloads["classes.jar"]["bytes"] and hashlib.sha256(data).hexdigest() == payloads["classes.jar"]["sha256"],
                "Genuine Java payload identity changed")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        classes = {n: hashlib.sha256(archive.read(n)).hexdigest() for n in archive.namelist() if n.endswith(".class")}
    return dict(coordinate=value["coordinate"], aar=value["aar"], payloads=payloads, class_sha256=classes,
                binding_sha256=sha(path), producer_report_sha256=report_pin["sha256"], abi_reports=report["abis"])


def native_apk(path, avif, device=False):
    require(path.is_file() and path.resolve() == path and path.stat().st_size <= 512*MIB, "Bounded exact ordinary APK required")
    rows = []
    with zipfile.ZipFile(path) as archive, path.open("rb") as source:
        entries = archive.infolist()
        require(len(entries) <= 100000 and len({e.filename for e in entries}) == len(entries), "Duplicate/excess APK ZIP inventory")
        selected = [e for e in entries if "avif" in e.filename.lower() and e.filename.endswith(".so")]
        require(1 <= len(selected) <= 4, "No old/new or foreign AVIF native packaging")
        for entry in selected:
            parts = entry.filename.split("/")
            require(len(parts) == 3 and parts[0] == "lib" and parts[2] == "libavif_android.so", "Foreign AVIF native path")
            abi = parts[1]
            pin = avif["payloads"]["jni/" + abi + "/libavif_android.so"]
            require(entry.file_size == pin["bytes"] <= 8*MIB, "Native payload size changed")
            digest = hashlib.sha256(archive.read(entry)).hexdigest()
            require(digest == pin["sha256"], "Packaged native bytes differ; no stripping/repacking waiver")
            source.seek(entry.header_offset)
            header = source.read(30)
            require(len(header) == 30 and header[:4] == b"PK\x03\x04", "Bad ZIP local header")
            name_length, extra_length = struct.unpack_from("<HH", header, 26)
            require(source.read(name_length).decode("utf-8") == entry.filename, "ZIP local/central entry identity differs")
            offset = entry.header_offset + 30 + name_length + extra_length
            require(entry.compress_type == zipfile.ZIP_STORED and offset % 16384 == 0,
                    "Packaged JNI must be uncompressed and 16KiB ZIP-aligned")
            segments = avif["abi_reports"][abi]["load_segments"]
            require(segments and all(s["alignment"] >= 16384 and (s["offset"] - s["virtual_address"]) % 16384 == 0 for s in segments),
                    "Hash-identical producer ELF must preserve its 16KiB LOAD alignment")
            rows.append(dict(abi=abi, entry=entry.filename, bytes=entry.file_size, sha256=digest,
                             zip_method=entry.compress_type, data_offset=offset, zip_alignment_16k=True,
                             producer_load_segments=segments, elf_identity_join=True))
    expected = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
    actual = {r["abi"] for r in rows}
    require(actual <= expected and "x86_64" in actual and (device or actual == expected), "Unexpected APK ABI inventory")
    value = dict(apk_sha256=sha(path), bytes=path.stat().st_size, candidate_aar_sha256=avif["aar"]["sha256"],
                 native=rows, role="library-instrumentation" if device else "production-debug",
                 limitation="Packaged-byte/alignment join, not 16KiB-device loading or other-ABI runtime qualification")
    record("device-native.json" if device else "app-native.json", value, cap=MIB)
    return value
