#!/usr/bin/env python3
"""One production XcodeGen / strict SwiftPM recipe; never a build or installer retry."""
import hashlib, importlib.util, json, os, pwd
from pathlib import Path
import re, shutil, signal, stat, sys, tempfile, time

def require(ok, message):
    if not ok:
        raise RuntimeError(message)

def identity(path):
    info = path.lstat()
    require(stat.S_ISDIR(info.st_mode) and path.resolve() == path, "Aliased/non-directory ownership root")
    return [info.st_dev, info.st_ino, info.st_uid]

def load(path, expected, name):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 131072, "Invalid helper")
    require(hashlib.sha256(path.read_bytes()).hexdigest() == expected, "Changed accepted helper")
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

class Qualification:
    def __init__(self, control, binding, owner, adapter):
        self.control, self.b, self.o = control, binding, owner
        self.app = control.parent / "app"
        self.app_id, self.ios_id = identity(self.app), identity(self.app / "iosApp")
        self.project = self.app / "iosApp/iosApp.xcodeproj"
        self.pbx = self.project / "project.pbxproj"
        self.canonical = self.app / "iosApp/Package.resolved"
        self.resolved = self.project / "project.xcworkspace/xcshareddata/swiftpm/Package.resolved"
        username = pwd.getpwuid(os.getuid()).pw_name
        require(os.getuid() == os.geteuid() and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.-]{0,63}", username), "Invalid current account identity")
        self.temporary = Path(os.environ["RUNNER_TEMP"]).resolve()
        identity(self.temporary)
        os.umask(0o077)
        self.public = control.parent / "app61-swiftpm-reports"
        self.public.mkdir(mode=0o700)
        self.run = Path(tempfile.mkdtemp(prefix="app61-swiftpm-", dir=self.temporary))
        self.run_id = identity(self.run)
        for name in ("home", "inputs", "work", "reports"):
            (self.run / name).mkdir(mode=0o700)
        (self.run / "env").touch(mode=0o600)
        self.env = {"PATH": "/usr/bin:/bin:/usr/sbin:/sbin", "HOME": str(self.run / "home"), "USER": username, "LOGNAME": username,
                    "CFFIXED_USER_HOME": str(self.run / "home"), "TMPDIR": str(self.run / "inputs"),
                    "RUNNER_TEMP": str(self.run / "inputs"), "GITHUB_ENV": str(self.run / "env"),
                    "DEVELOPER_DIR": binding["developerDir"], "LANG": "C", "LC_ALL": "C", "GIT_TERMINAL_PROMPT": "0"}
        self.limits, self.started = binding["deadlinesSeconds"], time.monotonic()
        self.end, self.work_end = self.started + self.limits["controller"], self.started + self.limits["work"]
        self.c = adapter.commands(owner, self.run, self.env, self.end)
        self.project_owned, self.project_id, self.original, self.negative_task = False, None, None, None
        self.source_verified, self.install_attempted = False, False
        self.result = {"binding": binding, "errors": [], "cleanup": {}, "locks": {}, "passed": False,
                       "controllerPython": {"executable": sys.executable, "version": sys.version}}
    def argv(self, command, cwd=None):
        return ["/bin/bash", "-c", 'cd -- "$1" && shift && exec "$@"', "app61-cwd", str(cwd or self.app), *map(str, command)]
    def call(self, command, label, seconds=30, cwd=None, cleaning=False, end=None):
        return self.c.call(self.argv(command, cwd), label, seconds, cleaning=cleaning,
                           end=min(self.c.end if cleaning else self.work_end, end or self.end))
    def source(self, label, cleaning=False):
        require(identity(self.app) == self.app_id and identity(self.app / "iosApp") == self.ios_id, "App ownership changed")
        for root, expected, name in ((self.app, [self.b["issueCommit"], self.b["issueTree"]], "app"),
                                     (self.control, [os.environ["GITHUB_SHA"]], "control")):
            revs = ["HEAD", "HEAD^{tree}"] if name == "app" else ["HEAD"]
            require(self.call(["/usr/bin/git", "-C", root, "rev-parse", *revs], label + "-" + name, cleaning=cleaning).splitlines() == expected,
                    "Wrong exact checkout binding")
            require(not self.call(["/usr/bin/git", "-C", root, "status", "--porcelain=v1", "--untracked-files=all"],
                                  label + "-" + name + "-status", cleaning=cleaning).strip(), "Checkout changed")
        hashes = {path: self.o.digest(self.app / path) for path in self.b["candidateFiles"]}
        require(hashes == self.b["candidateFiles"], "Production input bytes changed")
        require(not any((self.app / p).exists() or (self.app / p).is_symlink() for p in
                        ("app/google-services.json", "iosApp/iosApp/GoogleService-Info.plist", ".gradle", "composeApp/build")), "Unexpected protected/build input")
        self.result[label] = hashes
    def published(self):
        publication = self.run / "env"
        require(publication.is_file() and publication.stat().st_size <= 4096 and not publication.is_symlink(), "Invalid XcodeGen publication")
        if not publication.stat().st_size:
            return None
        match = re.fullmatch(r"KIRA_XCODEGEN=([^\r\n]+)\n", publication.read_text())
        require(match is not None, "Missing/ambiguous XcodeGen publication")
        self.c.env["KIRA_XCODEGEN"] = match[1]
        return Path(match[1])
    def cleanup_tool(self, label, cleaning=False):
        if not self.install_attempted:
            self.result["cleanup"]["productionXcodegenCleanupSkipped"] = "INSTALLER_NOT_ATTEMPTED"
            return False
        require(self.source_verified, "Unverified source cannot authorize production cleanup")
        script = self.app / "scripts/release/cleanup-xcodegen.sh"
        require(identity(self.app) == self.app_id and identity(self.app / "iosApp") == self.ios_id and
                script.resolve() == script and script.is_file() and not script.is_symlink() and
                script.stat().st_size <= 65536 and
                self.o.digest(script) == self.b["candidateFiles"]["scripts/release/cleanup-xcodegen.sh"],
                "Unbound current production cleanup script")
        self.published()
        self.call(["/bin/bash", "scripts/release/cleanup-xcodegen.sh"], label, cleaning=cleaning)
        return True
    def tool(self):
        require(self.source_verified, "Unverified source cannot authorize the installer")
        self.install_attempted = True
        self.call(["/bin/bash", "scripts/release/install-xcodegen.sh"], "xcodegen-install", self.limits["install"])
        tool = self.published()
        require(tool is not None, "Installer did not publish its tool")
        area = tool.parents[2]
        require(area.parent == self.run / "inputs" and re.fullmatch(r"kira-xcodegen\.[a-zA-Z0-9]{6}", area.name), "Wrong tool owner")
        area_id = identity(area)
        pins = self.o.read_json(self.app / "release/verified-tools.json")["xcodegen"]
        require(tool == area / pins["binary_path"] and self.o.digest(tool) == pins["binary_sha256"], "Wrong installed executable")
        paths = list(area.rglob("*"))
        require(0 < len(paths) <= 512 and all(not p.is_symlink() and (p.is_file() or p.is_dir()) for p in paths), "Unsafe installed layout")
        require((area / ".kira-xcodegen-owned").read_text() == str(tool) + "\n" and
                (area / "xcodegen/LICENSE").is_file() and (area / "xcodegen/share/xcodegen/SettingPresets").is_dir(), "Incomplete tool layout")
        self.result["xcodegen"] = {"pins": pins, "binarySha256": self.o.digest(tool), "area": str(area), "identity": area_id,
                                   "selectedLayout": sorted(str(p.relative_to(area)) for p in paths),
                                   "archiveEvidence": "Unmodified installer enforced SHA before extraction/execution; deleted archive not independently observed"}
        self.o.save(self.run / "reports/xcodegen.json", self.result["xcodegen"])
        self.call([tool, "generate"], "xcodegen-generate", self.limits["generate"], cwd=self.app / "iosApp")
        self.cleanup_tool("xcodegen-immediate-cleanup")
        require(not area.exists() and not area.is_symlink(), "XcodeGen cleanup left its owned directory")
    def locks(self, label):
        hashes = {"canonical": self.o.digest(self.canonical), "generated": self.o.digest(self.resolved)}
        self.result["locks"][label] = hashes
        require(set(hashes.values()) == {self.b["candidateFiles"]["iosApp/Package.resolved"]} and
                self.canonical.stat().st_size == self.resolved.stat().st_size == 3770 and
                self.canonical.read_bytes() == self.resolved.read_bytes(), "Canonical/generated raw lock changed")
    def checkouts(self):
        root = self.run / "inputs/DerivedData/SourcePackages/checkouts"
        identity(root)
        pins = {pin["identity"]: pin for pin in self.o.read_json(self.canonical)["pins"]}
        children = list(root.iterdir())
        require(len(pins) == len(children) == 13 and {p.name.lower() for p in children} == set(pins), "Not the exact 13 checkouts")
        deadline = min(self.work_end, time.monotonic() + self.limits["metadata"])
        rows = self.result["checkouts"] = []
        for child in sorted(children):
            identity(child)
            pin = pins[child.name.lower()]
            actual = self.call(["/usr/bin/git", "-C", child, "rev-parse", "HEAD"], "checkout-" + child.name, end=deadline).strip()
            rows.append({"identity": pin["identity"], "directory": child.name, "expectedRevision": pin["state"]["revision"], "actualRevision": actual})
            require(actual == pin["state"]["revision"], "Actual checkout revision differs from canonical lock")
    def mutate(self):
        require(identity(self.project) == self.project_id and self.o.digest(self.pbx) == hashlib.sha256(self.original).hexdigest() and
                self.original.count(b"version = 12.15.0;") == 1 and re.search(
            rb'isa = XCRemoteSwiftPackageReference;\s+repositoryURL = "https://github.com/firebase/firebase-ios-sdk";\s+requirement = \{\s+kind = exactVersion;\s+version = 12\.15\.0;',
            self.original), "Missing unique generated Firebase exact requirement")
        changed = self.original.replace(b"version = 12.15.0;", b"version = 12.14.0;", 1)
        self.pbx.write_bytes(changed)
        self.result["mutation"] = {"before": hashlib.sha256(self.original).hexdigest(), "negative": self.o.digest(self.pbx)}
        self.locks("before-negative")
    def negative(self):
        self.mutate()
        task = self.negative_task = self.c.start(self.argv(self.strict), "swiftpm-negative", self.limits["negative"], end=self.work_end)
        try:
            info = self.c.await_exit(task)
            limit = min(self.c.end, task["receipt"]["deadline"])
            while time.monotonic() < limit and not self.o.CANCELLED:
                if self.c.group_quiet(task, self.c.observe(end=limit)):
                    self.c.reap(task, info, end=limit)
                    break
                time.sleep(0.025)
            row = task["receipt"]
            require(row["leaderReaped"] and row["groupQuiet"] and row["actualExit"] > 0 and row["ended"] < limit and
                    not self.o.CANCELLED and not row["forced"] and not row["timedOut"] and not row["errors"] and self.c.within_cap(), "Negative did not finish naturally")
            raw = task["log"].read_text(errors="replace")
            refusal = sorted({line.strip() for line in raw.splitlines() if "out-of-date resolved file" in line and
                              str(self.resolved) in line and "not allowed when automatic dependency resolution is disabled" in line})
            require(len(refusal) == 1 and not re.search(r"(?i)failed to (?:clone|fetch|download)|could not resolve host|network is unreachable|no versions .*match|no available versions|connection timed out", raw),
                    "No unambiguous stale-lock refusal; network/unavailable-version failure is not evidence")
            require(self.o.digest(self.pbx) == self.result["mutation"]["negative"], "Resolver changed the injected project")
            self.result["negative"] = {"accepted": True, "actualExit": row["actualExit"], "diagnostic": refusal[0][:2048], "normalJoin": row["normalJoin"]}
            self.locks("after-negative")
        except Exception as error:
            self.c.failed(task, error)
            raise
        finally:
            self.c.checkpoint()  # Expected nonzero remains raw: normalJoin is never rewritten.
    def work(self):
        self.source("source-before")
        self.source_verified = True
        require(self.call(["/usr/bin/git", "-C", self.control, "rev-parse", "HEAD^"], "carrier-parent").strip() == self.b["carrierBase"], "Wrong carrier base")
        delta = self.call(["/usr/bin/git", "-C", self.control, "diff", "--name-only", "HEAD^", "HEAD"], "carrier-files").splitlines()
        require(sorted(delta) == self.b["carrierFiles"], "Carrier changed more than the five reviewed files")
        self.result["carrierFiles"] = {path: self.o.digest(self.control / path) for path in delta}
        self.call(["/usr/bin/ruby", "scripts/release/verify-toolchain-inputs.rb"], "bootstrap", self.limits["bootstrap"])
        self.result["xcode"] = self.call(["/usr/bin/xcodebuild", "-version"], "xcode-identity").strip()
        require(self.result["xcode"] == self.b["xcodeVersion"], "Wrong selected Xcode version/build")
        self.result["macOS"] = self.call(["/usr/bin/sw_vers", "-productVersion"], "macos-identity").strip()
        require(self.result["macOS"].startswith("26."), "Wrong actual macOS major")
        require(not self.project.exists() and not self.project.is_symlink(), "Preexisting generated project")
        self.project_owned = True
        self.tool()
        self.project_id = identity(self.project)
        require(self.pbx.is_file() and not self.pbx.is_symlink() and self.pbx.stat().st_size <= 1048576, "Invalid generated project")
        self.original = self.pbx.read_bytes()
        self.strict = ["/usr/bin/xcodebuild", "-resolvePackageDependencies", "-project", "iosApp/iosApp.xcodeproj", "-scheme", "iosApp",
                       "-configuration", "Release", "-destination", "generic/platform=iOS", "-derivedDataPath", self.run / "inputs/DerivedData",
                       "-clonedSourcePackagesDirPath", self.run / "inputs/DerivedData/SourcePackages", "-packageCachePath", self.run / "inputs/kira-swiftpm-cache",
                       "-onlyUsePackageVersionsFromResolvedFile", "-disableAutomaticPackageResolution", "-skipPackageUpdates", "CODE_SIGNING_ALLOWED=NO", "CODE_SIGNING_REQUIRED=NO"]
        self.call(["/usr/bin/ruby", "scripts/release/verify-toolchain-inputs.rb", "--restore-swiftpm"], "restore-lock", self.limits["bootstrap"])
        self.locks("restored")
        self.call(self.strict, "swiftpm-positive", self.limits["positive"])
        self.call(["/usr/bin/ruby", "scripts/release/verify-toolchain-inputs.rb", "--check-swiftpm"], "check-lock", self.limits["bootstrap"])
        self.locks("after-positive")
        self.checkouts()
        self.negative()

    def cleanup(self):
        signal.setitimer(signal.ITIMER_REAL, 0)
        self.c.begin_cleanup(min(self.end, time.monotonic() + self.limits["cleanup"]))
        cleanup_end, cleanup = self.c.end, self.result["cleanup"]
        try:
            self.c.end = min(cleanup_end, time.monotonic() + self.limits["stop"])
            cleanup["groupsQuietBeforeCleanup"] = self.c.drain()
            require(cleanup["groupsQuietBeforeCleanup"], "Retain owned paths while a command remains")
            self.c.end = cleanup_end
            cleanup["productionXcodegenCleanup"] = self.cleanup_tool("xcodegen-final-cleanup", cleaning=True)
            if self.original is not None:
                require(identity(self.project) == self.project_id and not self.pbx.is_symlink(), "Generated project changed ownership")
                self.result["projectBeforeRestore"] = self.o.digest(self.pbx)
                self.pbx.write_bytes(self.original)
                cleanup["restoredProjectSha256"] = self.o.digest(self.pbx)
                require(cleanup["restoredProjectSha256"] == hashlib.sha256(self.original).hexdigest(), "Generated project restoration failed")
            self.source("source-after", cleaning=True)
        except Exception as error:
            self.result["errors"].append("cleanup: " + str(error)[:500])
        finally:
            self.c.end = min(cleanup_end, time.monotonic() + self.limits["stop"])
            try:
                cleanup["groupsQuietAfterCleanup"] = self.c.drain()
            except Exception as error:
                self.result["errors"].append("final settlement: " + str(error)[:500])
            self.c.end = cleanup_end
            self.finish(cleanup_end)

    def retain(self):
        files = [self.run / "reports/commands.json", *[t["log"] for t in self.c.tasks if not t["leaf"]]]
        files += list((self.run / "reports").glob("xcodegen.json"))
        retained = self.result["retained"] = []
        for path in files:
            if path.exists():
                require(path.is_file() and not path.is_symlink(), "Nonregular retained output")
                with path.open("rb") as stream:
                    data = stream.read(1048576)
                (self.public / path.name).write_bytes(data)
                retained.append({"name": path.name, "bytes": len(data), "observedBytes": path.stat().st_size, "truncated": path.stat().st_size > len(data), "sha256": hashlib.sha256(data).hexdigest()})
        require(sum(row["bytes"] for row in retained) <= 6291456 - 131072, "Oversized bounded artifact")
    def finish(self, cleanup_end):
        self.c.within_cap()
        self.result["commandsNormalIncludingNegative"], self.result["logBudget"] = self.c.normal(), self.c.log_budget
        self.result["ordinaryCommandsNormal"] = not self.c.audit_failed and not self.c.log_budget_failed and all(
            t["receipt"]["normalJoin"] and not t["receipt"]["forced"] and not t["receipt"]["timedOut"] and not t["receipt"]["errors"]
            for t in self.c.tasks if t is not self.negative_task)
        try:
            self.retain()  # No census: bounded owned-command outputs and explicit receipts precede removal.
            if self.result["cleanup"].get("groupsQuietAfterCleanup") and (
                    not self.install_attempted or self.result["cleanup"].get("productionXcodegenCleanup")):
                require(identity(self.run) == self.run_id and identity(self.app / "iosApp") == self.ios_id, "Cleanup root identity changed")
                if self.project_owned and self.project.exists():
                    require(self.project_id is None or identity(self.project) == self.project_id, "Generated project owner changed")
                    shutil.rmtree(self.project)
                shutil.rmtree(self.run)
                self.result["cleanup"]["ownedPathsAbsent"] = not self.run.exists() and not self.run.is_symlink() and (not self.project_owned or not self.project.exists() and not self.project.is_symlink())
            require({p: self.o.digest(self.app / p) for p in self.b["candidateFiles"]} == self.b["candidateFiles"], "Source changed during removal")
            self.result["boundInputsUnchangedAfterRemoval"] = True
        except Exception as error:
            self.result["errors"].append("retention/removal: " + str(error)[:500])
        self.result["elapsedSeconds"] = round(time.monotonic() - self.started, 3)
        self.result["passed"] = bool(not self.result["errors"] and self.result.get("negative", {}).get("accepted") and
                                    self.result["ordinaryCommandsNormal"] and not self.o.CANCELLED and
                                    self.result["cleanup"].get("ownedPathsAbsent") and time.monotonic() < cleanup_end and not any(r["truncated"] for r in self.result["retained"]))
        self.publish()

    def publish(self):
        paths = list(self.public.iterdir())
        ready = all(p.is_file() and not p.is_symlink() and p.suffix in (".log", ".json") and p.stat().st_size <= 1048576 for p in paths) and sum(p.stat().st_size for p in paths) <= 6291456 - 131072
        self.result["retentionReady"] = ready
        self.result["passed"] &= ready
        self.o.save(self.public / "result.json", self.result)
        output = Path(os.environ["GITHUB_OUTPUT"])
        require(output.is_file() and not output.is_symlink(), "Invalid workflow output")
        with output.open("a") as stream:
            stream.write("retention_ready=" + str(ready).lower() + "\n")
        print("APP61_SWIFTPM", "PASS" if self.result["passed"] else "FAIL", "ownedPathsAbsent=" + str(self.result["cleanup"].get("ownedPathsAbsent", False)))

def main():
    require(sys.argv[1:] == ["--qualify"], "Fixed qualification recipe only")
    control = Path(__file__).resolve().parents[2]
    require(control == Path(os.environ["GITHUB_WORKSPACE"]).resolve() / "control", "Wrong control checkout")
    path = control / "ci/app61-swiftpm/binding.json"
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 16384, "Invalid binding")
    binding = json.loads(path.read_text())
    expected = {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted", "RUNNER_OS": "macOS", "RUNNER_ARCH": "ARM64",
                "GITHUB_REPOSITORY": binding["repository"], "GITHUB_EVENT_NAME": "push", "GITHUB_RUN_ATTEMPT": "1", "GITHUB_REF": "refs/heads/" + binding["branch"]}
    require(binding["schema"] == "app61-xcodegen-strict-swiftpm-v1" and all(os.environ.get(k) == v for k, v in expected.items()) and
            re.fullmatch(r"[0-9a-f]{40}", os.environ.get("GITHUB_SHA", "")) and os.uname().sysname == "Darwin" and os.uname().machine == "arm64", "Wrong hosted qualification context")
    owner = load(path.parent / "app8-owner.py", binding["ownerSha256"], "app61_owner")
    adapter = load(path.parent / "app5-commands.py", binding["commandsSha256"], "app61_commands")
    owner.deadline_capabilities()
    run = Qualification(control, binding, owner, adapter)
    for signum in (signal.SIGTERM, signal.SIGINT, signal.SIGALRM):
        signal.signal(signum, owner.interrupted)
    signal.setitimer(signal.ITIMER_REAL, binding["deadlinesSeconds"]["work"])
    try:
        run.work()
    except Exception as error:
        run.result["errors"].append(str(error)[:500])
    finally:
        try:
            run.cleanup()
        except Exception as error:
            run.result["errors"].append("incomplete cleanup: " + str(error)[:500])
            run.result["passed"] = False
            run.publish()
    return 0 if run.result["passed"] else 1

if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        sys.exit("APP61_SWIFTPM INCOMPLETE: " + str(error))
