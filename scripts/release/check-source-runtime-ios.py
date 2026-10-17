#!/usr/bin/env python3
"""Focused hosted Apple gate; no UI app, signing, publication, or Store access."""
import json
import os
from pathlib import Path
import platform
import re
import shutil
import signal
import subprocess
import time
import xml.etree.ElementTree as ET


def run(args, timeout=30):
    return subprocess.run(args, check=True, timeout=timeout)


def capture(args, timeout=30):
    return subprocess.check_output(args, text=True, timeout=timeout).strip()


def output(value):
    with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
        stream.write(value + "\n")


class NativeGate:
    def __init__(self):
        self.repo = Path(__file__).resolve().parents[2]
        assert os.environ.get("GITHUB_ACTIONS") == "true" and Path.cwd() == self.repo
        assert platform.system() == "Darwin" and platform.machine() == "arm64"
        assert os.environ["GITHUB_REPOSITORY"] == "kira-manga/kira-app"
        assert os.environ["GITHUB_REF"] == "refs/heads/remediation/app-priority-sources-20261017"
        assert capture(["git", "rev-parse", "HEAD"]) == os.environ["GITHUB_SHA"]
        self.root = Path(os.environ["RUNNER_TEMP"]).resolve(strict=True) / "source-runtime-native"
        self.home = self.root / "gradle"
        self.evidence = self.root.with_name("source-runtime-ios-evidence")
        assert os.environ["GRADLE_USER_HOME"] == str(self.home)
        self.owner = {key: os.environ[key] for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_SHA")}
        assert all(re.fullmatch(r"[1-9][0-9]*", self.owner[k]) for k in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"))
        self.name = "kira-source-runtime-" + self.owner["GITHUB_RUN_ID"] + "-" + self.owner["GITHUB_RUN_ATTEMPT"]
        self.marker = "-Dkira.source.runtime.owner=" + self.name
        self.reservation = None
        self.failures = []
        self.root.mkdir(mode=0o700)
        (self.root / "owner.json").write_text(json.dumps(self.owner))
        self.evidence.mkdir(mode=0o700)
        (self.evidence / "source.json").write_text(json.dumps(self.owner, indent=2) + "\n")

    def attempt(self, label, action):
        try:
            action()
        except Exception as error:
            self.failures.append(label + ": " + type(error).__name__)
            print(label + " failed", flush=True)

    def require_inputs(self):
        run(["python3", "-B", "scripts/release/prepare-libffi-source.py", "verify"], timeout=120)
        run(["/usr/bin/xcodebuild", "-version"])
        sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
        assert sdk.is_absolute(), "Installed SDK37 required"
        platforms = [sdk / "platforms" / name for name in ("android-37", "android-37.0")]
        installed = [p for p in platforms if p.exists()]
        assert installed, "Installed SDK37 required; no SDK install or alias"
        for directory in installed:
            assert (directory / "android.jar").stat().st_size > 0
            source = directory / "source.properties"
            assert source.stat().st_size <= 65536
            properties = {}
            for line in source.read_text().splitlines():
                if not line.strip() or line.lstrip().startswith("#"):
                    continue
                key, value = (part.strip() for part in line.split("=", 1))
                assert key not in properties
                properties[key] = value
            assert properties.get("AndroidVersion.ApiLevel") in {"37", "37.0"}
            assert properties.get("AndroidVersion.CodeName", "") in {"", "REL"}
            assert properties.get("AndroidVersion.PreviewSdkInt", "0") == "0"

    def create_simulator(self):
        devices = json.loads(capture(["/usr/bin/xcrun", "simctl", "list", "devices", "available", "--json"]))["devices"]
        choices = [(runtime, d) for runtime, group in devices.items()
                   if re.fullmatch(r"com\.apple\.CoreSimulator\.SimRuntime\.iOS-[0-9-]+", runtime)
                   for d in group if d.get("isAvailable") and d.get("deviceTypeIdentifier", "").startswith("com.apple.CoreSimulator.SimDeviceType.iPhone-")]
        assert choices, "No installed iOS iPhone simulator; no download or platform substitution"
        assert all(d["name"] != self.name for group in devices.values() for d in group)
        runtime, device = max(choices, key=lambda pair: tuple(map(int, pair[0].split(".iOS-")[1].split("-"))))
        self.reservation = {"name": self.name, "runtime": runtime, "device_type": device["deviceTypeIdentifier"]}
        self.save_reservation()
        udid = capture(["/usr/bin/xcrun", "simctl", "create", self.name, self.reservation["device_type"], runtime])
        assert re.fullmatch(r"[0-9A-Fa-f-]{36}", udid)
        self.reservation["udid"] = udid
        self.save_reservation()
        run(["/usr/bin/xcrun", "simctl", "boot", udid])
        run(["/usr/bin/xcrun", "simctl", "bootstatus", udid, "-b"], timeout=420)

    def save_reservation(self):
        (self.evidence / "simulator.json").write_text(json.dumps(self.reservation, indent=2) + "\n")

    def compile_swift(self):
        sdk = capture(["/usr/bin/xcrun", "--sdk", "iphonesimulator", "--show-sdk-path"], timeout=120)
        run(["/usr/bin/xcrun", "--sdk", "iphonesimulator", "swiftc", "-swift-version", "5",
             "-target", "arm64-apple-ios15.0-simulator", "-sdk", sdk, "-parse-as-library",
             "-module-cache-path", str(self.root / "swift-cache"), "-emit-object",
             "iosApp/iosApp/NativeReader/ReaderPerfLog.swift", "-o", str(self.root / "ReaderPerfLog.o")], timeout=120)
        (self.evidence / "swift-result.txt").write_text("ReaderPerfLog.swift iOS simulator object compile PASS\n")

    def test(self):
        output("workers_stopped=false")
        run(["./gradlew", "--no-daemon", "--no-parallel", "--no-build-cache", "--no-configuration-cache",
             "--max-workers=1", "--console=plain", "-Dorg.gradle.jvmargs=-Xmx3g " + self.marker,
             "-Pkotlin.compiler.execution.strategy=in-process", ":data:iosSimulatorArm64Test",
             "--device", self.reservation["udid"], "--tests", "me.manga.kira.data.repository.IosBackgroundHeaderPolicyTest",
             "--stacktrace"], timeout=1500)

    def owned_pids(self):
        lines = capture(["/bin/ps", "-axo", "pid=,command="]).splitlines()
        return [int(line.split()[0]) for line in lines if self.marker in line.split()[1:]]

    def reap_workers(self):
        # Only this run's uniquely marked daemon, never all Java processes.
        for sig in (signal.SIGTERM, signal.SIGKILL):
            for pid in self.owned_pids():
                try:
                    os.kill(pid, sig)
                except ProcessLookupError:
                    pass
            if not self.owned_pids():
                break
            time.sleep(3)
        assert not self.owned_pids(), "Owned Native worker remains"

    def stop_workers(self):
        launchers = list(self.home.glob("wrapper/dists/gradle-9.6.1-bin/*/gradle-9.6.1/bin/gradle"))
        assert len(launchers) <= 1
        try:
            if launchers:
                assert not launchers[0].is_symlink()
                run([str(launchers[0]), "--offline", "--stop"], timeout=60)
            else:
                assert not (self.home / "daemon").exists()
        finally:
            self.reap_workers()
        output("workers_stopped=true")

    def remove_simulator(self):
        if self.reservation is None:
            return
        devices = json.loads(capture(["/usr/bin/xcrun", "simctl", "list", "devices", "--json"]))["devices"]
        matches = [d for d in devices.get(self.reservation["runtime"], []) if d["name"] == self.name]
        assert len(matches) <= 1
        for device in matches:
            assert device["deviceTypeIdentifier"] == self.reservation["device_type"]
            assert "udid" not in self.reservation or device["udid"] == self.reservation["udid"]
            if device["state"] != "Shutdown":
                run(["/usr/bin/xcrun", "simctl", "shutdown", device["udid"]], timeout=60)
            run(["/usr/bin/xcrun", "simctl", "delete", device["udid"]], timeout=60)

    def preserve_xml(self):
        directory = self.repo / "data/build/test-results/iosSimulatorArm64Test"
        files = list(directory.glob("*.xml"))
        assert len(files) <= 10
        for path in files:
            assert not path.is_symlink() and path.stat().st_size <= 2 * 1024 * 1024
            shutil.copyfile(path, self.evidence / path.name)

    def verify_xml(self):
        cases = [case for path in self.evidence.glob("*.xml") for case in ET.parse(path).iter("testcase")]
        expected = {"retainedRetryAndPageRetryHonorOptOutWithoutResolvingOrWideningFrozenHeaders",
                    "policyIsRecheckedAfterOwnershipAdmissionForBothHandoffsAndOptInStillRefreshes"}
        assert len(cases) == 2 and {c.attrib["name"].split("[")[0] for c in cases} == expected
        expected_class = "me.manga.kira.data.repository.IosBackgroundHeaderPolicyTest"
        assert all(c.attrib["classname"] in {expected_class, "iosSimulatorArm64Test." + expected_class} for c in cases)
        assert not any(c.find(tag) is not None for c in cases for tag in ("failure", "error", "skipped"))
        (self.evidence / "test-result.txt").write_text("2/2 actual repository Native tests PASS\n")

    def execute(self):
        try:
            self.require_inputs()
            self.attempt("Swift object compilation", self.compile_swift)
            self.create_simulator()
            self.test()
        finally:
            self.attempt("Gradle shutdown", self.stop_workers)
            self.attempt("Simulator shutdown and delete", self.remove_simulator)
            self.attempt("XML preservation", self.preserve_xml)
            (self.evidence / "cleanup.json").write_text(json.dumps({"failures": self.failures}, indent=2) + "\n")
            if self.failures:
                raise RuntimeError("; ".join(self.failures))
        self.verify_xml()


if __name__ == "__main__":
    output("workers_stopped=true")  # No workers until test() explicitly marks them live.
    NativeGate().execute()
