#!/usr/bin/env python3
"""PRIVATE / UNBOUND / NOT_RUN: one admitted, externally owned API35 real-app traversal.

No emulator/server lifecycle, build, prefs writes, permission resets or fake app root.
The outer executor owns fresh AVD provenance, child supervision and final cleanup.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "me.manga.kira"
PINS = {
    "source_commit": "3fac0a7ba2c98001396e45857a7f6ab9ce1a5eb2",
    "source_tree": "770300c991be84e679a69a766a4d6ad16a801862",
    "engine_commit": "ed184165ebd3ee7f0d1db533cc40ca5a0868fdda",
    "engine_tree": "14e46a1ead24b5757d612fd55031e440f5304661",
}
DEFAULTS = {"first_launch": True, "notif_permission_asked": False}
UI_TEMP = "/data/local/tmp/app10-cold-root.xml"
WELCOME, THEME, START, LIBRARY = (
    "Welcome to Kira Manga", "Choose Your Theme", "Start Reading", "Your Library is empty"
)


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("adb", "apk", "binding", "out"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    binding = json.loads(args.binding.read_text())
    require(binding.get("status") == "PRIMARY_ADMITTED" and
            all(binding.get(k) == v for k, v in PINS.items()), "UNBOUND or wrong source binding")
    with args.apk.open("rb") as stream:
        apk_sha = hashlib.file_digest(stream, "sha256").hexdigest()
    require(apk_sha == binding.get("apk_sha256"), "APK hash differs from primary binding")
    require(args.adb.is_file() and args.apk.is_file() and not args.out.exists(), "Exact fresh output required")
    args.out.mkdir(mode=0o700)
    deadline = time.monotonic() + 420
    stage = "initial"
    report = {"status": "INCOMPLETE", **PINS, "apk_sha256": apk_sha,
              "serial": "emulator-5580", "adb_port": 5038, "events": [], "screenshots": []}

    def adb(*words, accepted=(0,), limit=20):
        remaining = deadline - time.monotonic()
        require(remaining > 0, "Traversal deadline exceeded")
        result = subprocess.run(
            [str(args.adb), "-H", "127.0.0.1", "-P", "5038", "-s", "emulator-5580", *words],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=min(limit, remaining), check=False,
        )
        require(result.returncode in accepted, "ADB command failed in " + stage)
        require(len(result.stdout) <= 8 * 1024 * 1024 and len(result.stderr) <= 65536,
                "ADB output exceeded evidence bound")
        return result

    def shell(*words, **kwargs):
        return adb("shell", *words, **kwargs).stdout.decode("utf-8", errors="strict").strip()

    def nodes():
        # Ephemeral XML on the fresh owned device; never an evidence artifact.
        try:
            shell("uiautomator", "dump", "--compressed", UI_TEMP)
            raw = adb("exec-out", "cat", UI_TEMP).stdout
            require(len(raw) <= 1024 * 1024, "UI XML exceeded bound")
            return list(ET.fromstring(raw).iter("node"))
        finally:
            shell("rm", "-f", UI_TEMP)

    def matches(items, label):
        return [n for n in items if label in (n.get("text"), n.get("content-desc"))]

    def wait_for(label, seconds=35):
        end = min(deadline, time.monotonic() + seconds)
        while time.monotonic() < end:
            items = nodes()
            found = matches(items, label)
            if found:
                return items, found[0]
            time.sleep(0.5)
        raise RuntimeError("Expected UI not observed: " + label)

    def tap(node):
        require(node.get("enabled") == "true", "Expected enabled UI control")
        box = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        require(box is not None, "UI bounds unavailable")
        x1, y1, x2, y2 = map(int, box.groups())
        require(x2 > x1 and y2 > y1, "Empty UI bounds")
        shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def click(label):
        _, node = wait_for(label)
        tap(node)
        report["events"].append("tap:" + label)

    def screenshot(name):
        require(len(report["screenshots"]) < 7, "Screenshot count exceeded")
        raw = adb("exec-out", "screencap", "-p").stdout
        require(raw.startswith(b"\x89PNG\r\n\x1a\n"), "Invalid screenshot")
        with (args.out / name).open("xb") as stream:
            stream.write(raw)
        report["screenshots"].append({"file": name, "sha256": hashlib.sha256(raw).hexdigest()})

    def permission_denied():
        raw = shell("dumpsys", "package", PKG)
        flags = re.findall(r"android\.permission\.POST_NOTIFICATIONS: granted=(true|false)", raw)
        require(flags == ["false"], "Expected one denied POST_NOTIFICATIONS runtime permission")
        return True

    def cells():
        # Full XML is parsed transiently in memory, never printed or retained.
        result = adb("exec-out", "run-as", PKG, "cat", "shared_prefs/kira_settings.xml", accepted=(0, 1))
        if result.returncode:
            require(b"No such file or directory" in result.stderr + result.stdout,
                    "Cannot inspect debug production settings")
            entries = []
        else:
            require(len(result.stdout) <= 262144, "Settings XML exceeded bound")
            entries = list(ET.fromstring(result.stdout))
        filtered = {}
        for key, default in DEFAULTS.items():
            found = [e for e in entries if e.get("name") == key]
            require(len(found) <= 1 and all(e.tag == "boolean" and e.get("value") in ("true", "false")
                                          for e in found), "Unexpected relevant settings cell")
            physical = found[0].get("value") == "true" if found else None
            filtered[key] = {"present": bool(found), "value": physical,
                             "effective": physical if found else default}
        return filtered

    def persist(expected_asked):
        shell("input", "keyevent", "KEYCODE_HOME")
        end = time.monotonic() + 10
        while time.monotonic() < end:
            observed = cells()
            first, asked = observed["first_launch"], observed["notif_permission_asked"]
            if first["present"] and first["value"] is False and asked["effective"] is expected_asked:
                return observed
            time.sleep(0.5)
        raise RuntimeError("Expected production cells not persisted after normal background")

    def deny_if_present(items, filename):
        controls = [n for n in items if n.get("package", "").endswith(".permissioncontroller")]
        denial = [n for n in controls if n.get("resource-id", "").endswith(":id/permission_deny_button")]
        if not controls:
            return False
        require(len(denial) == 1 and any("notifications" in n.get("text", "").lower() for n in controls),
                "Unexpected system permission UI; do not guess")
        screenshot(filename)
        tap(denial[0])
        report["events"].append("deny:POST_NOTIFICATIONS:" + stage)
        return True

    def library(cold):
        end, stable = time.monotonic() + 40, None
        backfill = False
        while time.monotonic() < end:
            items = nodes()
            if cold and deny_if_present(items, "06-cold-backfill.png"):
                require(not backfill, "Unexpected repeated backfill prompt")
                backfill, stable = True, None
                continue
            if matches(items, "What's New"):
                close = [n for n in items if n.get("package") == PKG and n.get("content-desc") == "Close"]
                require(len(close) == 1, "What's New close control unavailable")
                tap(close[0])
                report["events"].append("dismiss:What's New:" + stage)
                stable = None
                continue
            if cold:
                require(not any(matches(items, label) for label in (WELCOME, THEME, START)),
                        "Cold fresh task selected onboarding instead of Library")
            if matches(items, LIBRARY):
                stable = stable or time.monotonic()
                if time.monotonic() - stable >= 2:
                    return "observed_and_denied" if backfill else "not_observed_may_be_auto_denied"
            else:
                stable = None
            time.sleep(0.5)
        raise RuntimeError("Actual empty Library not observed")

    def pid():
        value = shell("pidof", PKG, accepted=(0, 1))
        require(not value or re.fullmatch(r"\d+", value), "Unexpected main process roster")
        return int(value) if value else None

    def lifecycle():
        process = pid()
        require(process is not None, "Main process absent")
        tasks = set()
        for line in shell("dumpsys", "activity", "activities").splitlines():
            if ("mResumedActivity" in line or "topResumedActivity" in line) and PKG + "/" in line:
                match = re.search(r"me\.manga\.kira/(?:me\.manga\.kira)?\.MainActivity\b.*?\bt(\d+)\b", line)
                if match:
                    tasks.add(int(match.group(1)))
        require(len(tasks) == 1, "Actual resumed MainActivity task identity unavailable")
        shell("test", "-d", "/proc/" + str(process))
        return {"pid": process, "task_id": tasks.pop()}

    def launch(flags):
        shell("am", "start", "-W", "--user", "0", "-a", "android.intent.action.MAIN",
              "-c", "android.intent.category.LAUNCHER", "-n", PKG + "/.MainActivity", "-f", flags)
        report["events"].append("launcher:" + flags + ":no_extras_no_data")

    try:
        stage = "fresh_install"
        require(shell("getprop", "sys.boot_completed") == "1", "Owned emulator not booted")
        require(shell("getprop", "ro.build.version.sdk") == "35", "Only admitted API35 cohort")
        require(shell("am", "get-current-user") == "0", "Only fresh emulator primary user")
        locale = shell("getprop", "persist.sys.locale") or shell("getprop", "ro.product.locale")
        require(locale.lower().startswith("en"), "English UI required; do not guess labels")
        report["device"] = {"api": 35, "locale": locale,
                            "fingerprint": shell("getprop", "ro.build.fingerprint")}
        require(not shell("pm", "path", PKG, accepted=(0, 1)), "Package already installed; never clear another install")
        installed = adb("install", "--no-streaming", str(args.apk), limit=120).stdout
        require(b"Success" in installed, "Exact debug APK installation failed")
        initial = cells()
        require(all(not cell["present"] for cell in initial.values()) and pid() is None,
                "Fresh install unexpectedly has settings/process state")
        report["initial_cells"] = initial
        report["initial_permission_denied"] = permission_denied()

        stage = "welcome_theme"
        launch("0x10000000")
        wait_for(WELCOME)
        screenshot("01-welcome.png")
        click("Get Started")
        items, _ = wait_for(THEME)
        optional = "Notifications are optional. Enable them to receive new chapter alerts and download updates."
        require(matches(items, optional), "Real optional notification copy unavailable")
        continuation = matches(items, "Continue")
        require(len(continuation) == 1 and continuation[0].get("enabled") == "true",
                "Optional Theme Continue not enabled before request")
        require(not any(n.get("package", "").endswith(".permissioncontroller") for n in items),
                "Unexpected automatic permission dialog")
        time.sleep(2)
        items, _ = wait_for(THEME)
        require(not any(n.get("package", "").endswith(".permissioncontroller") for n in items),
                "Unexpected automatic permission dialog")
        screenshot("02-theme-optional.png")
        click("Grant Permission")
        request_end, denied = time.monotonic() + 8, False
        while time.monotonic() < request_end:
            if deny_if_present(nodes(), "03-theme-denial.png"):
                denied = True
                break
            time.sleep(0.5)
        report["theme_request"] = "observed_and_denied" if denied else "not_observed_may_be_auto_denied"
        wait_for(THEME)
        permission_denied()
        click("Continue")
        stage = "start_reading"
        wait_for(START)
        screenshot("04-start-reading.png")
        click("Continue to Library")

        stage = "first_library"
        library(cold=False)
        screenshot("05-first-library.png")
        old = lifecycle()
        report["before_stop"] = {**old, "permission_denied": permission_denied(), "cells": persist(False)}
        shell("am", "force-stop", "--user", "0", PKG)
        stop_end = time.monotonic() + 10
        while pid() is not None and time.monotonic() < stop_end:
            time.sleep(0.2)
        require(pid() is None, "Old main process still present after force-stop")
        shell("test", "!", "-d", "/proc/" + str(old["pid"]))
        report["old_main_process_absent"] = True

        stage = "cold_library"
        launch("0x18008000")
        report["cold_backfill"] = library(cold=True)
        screenshot("07-cold-library.png")
        new = lifecycle()
        require(new["pid"] != old["pid"] and new["task_id"] != old["task_id"],
                "Cold verification requires both new process and new launcher task")
        report["after_cold_launch"] = {**new, "permission_denied": permission_denied(), "cells": persist(True)}
        report["status"] = "OBSERVED_PASS"
    except Exception as error:
        report["status"] = "FAIL_OR_BLOCKED"
        report["failure"] = {"stage": stage, "type": type(error).__name__,
                             "detail": str(error) if isinstance(error, RuntimeError) else "No raw command output retained"}
    finally:
        with (args.out / "observations.json").open("x") as stream:
            json.dump(report, stream, indent=2, sort_keys=True)
            stream.write("\n")
    print(json.dumps({"status": report["status"], "outer_owned_cleanup_still_required": True}))
    return 0 if report["status"] == "OBSERVED_PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
