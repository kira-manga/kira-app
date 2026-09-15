#!/usr/bin/env python3
"""PRIVATE / UNBOUND / NOT_RUN: one admitted, externally owned API35 real-app traversal.

No emulator/server lifecycle, build, prefs writes, permission resets or fake app root.
The outer executor owns fresh AVD provenance, child supervision and final cleanup.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

PKG = "me.manga.kira"
PINS = {
    "source_commit": "826d7d2eecbcc8c2f834cc202d188898e41fd64f",
    "source_tree": "e0e9400ace05d8e608e6f7765cd81f64cc3719e4",
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


def parse_adb_features(raw):
    # Linux `adb features` emits one name plus LF, not the comma-separated wire codec.
    # AOSP client/commandline.cpp at 1cf2f017d312f73b3dc53bda85ef2610e35a80e9.
    require(len(raw) <= 65536 and
            re.fullmatch(rb"(?:[a-z][a-z0-9_]*\n)+", raw) is not None,
            "ADB features output must be bounded LF-delimited ASCII feature names")
    names = raw.decode("ascii", errors="strict").splitlines()
    require(len(names) == len(set(names)), "Duplicate ADB feature names")
    return names


def permission_diagnostic(controls, denial):
    # Retain only bounded system identifiers/booleans, never UI text or a full hierarchy.
    def identifier(value, grammar):
        return value if len(value) <= 160 and re.fullmatch(grammar, value) else None

    return {
        "controller_count": len(controls),
        "expected_denial_count": len(denial),
        "notification_text_count": sum("notifications" in n.get("text", "").lower() for n in controls),
        "nodes_truncated": len(controls) > 32,
        "nodes": [{
            "package": identifier(n.get("package", ""), r"[A-Za-z0-9_.]+"),
            "resource_id": identifier(n.get("resource-id", ""), r"[A-Za-z0-9_.]+:id/[A-Za-z0-9_]+"),
            "enabled": n.get("enabled") == "true",
            "clickable": n.get("clickable") == "true",
            "notification_text": "notifications" in n.get("text", "").lower(),
        } for n in controls[:32]],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("adb", "apk", "binding", "out"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    binding = json.loads(args.binding.read_text())
    require(all(re.fullmatch(r"[0-9a-f]{40}", v) for v in PINS.values()) and
            binding.get("status") == "PRIMARY_ADMITTED" and
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
        shot = {"file": name, "sha256": hashlib.sha256(raw).hexdigest()}
        # Stage outside ui: failed writes/closes must not poison its exact PNG roster.
        with tempfile.TemporaryDirectory(prefix=".app10-screenshot-", dir=args.out.parent) as scratch:
            staged = Path(scratch) / "image"
            with staged.open("xb") as stream:
                require(stream.write(raw) == len(raw), "Incomplete screenshot write")
            # Publish only a closed image, without replacing any existing admitted path.
            os.link(staged, args.out / name, follow_symlinks=False)
            # A fallible staging cleanup must not leave this complete image unrostered.
            report["screenshots"].append(shot)

    def permission_denied():
        raw = shell("dumpsys", "package", PKG)
        flags = re.findall(r"android\.permission\.POST_NOTIFICATIONS: granted=(true|false)", raw)
        require(flags == ["false"], "Expected one denied POST_NOTIFICATIONS runtime permission")
        return True

    def cells():
        # Full XML is parsed transiently in memory, never printed or retained.
        # shell_v2 propagates the remote cat status; exec-out does not establish it.
        result = adb("shell", "-T", "run-as", PKG, "cat", "shared_prefs/kira_settings.xml", accepted=(0, 1))
        diagnostic = {"transport": "shell_v2_no_pty", "exit": result.returncode,
                      "stdout_bytes": len(result.stdout), "stderr_bytes": len(result.stderr)}
        report["settings_read"] = diagnostic  # Counts/status only, never XML or unrelated preference values.
        if result.returncode:
            missing = b"cat: shared_prefs/kira_settings.xml: No such file or directory"
            require((result.stdout + result.stderr).strip() == missing,
                    "Settings read failed without exact missing-file evidence")
            diagnostic["status"] = "absent"
            entries = []
        else:
            require(len(result.stdout) <= 262144, "Settings XML exceeded bound")
            try:
                settings = ET.fromstring(result.stdout)
            except ET.ParseError as error:
                diagnostic.update(status="invalid_xml", xml_error_code=error.code,
                                  xml_error_position=list(error.position))
                raise RuntimeError("Settings read succeeded remotely but XML parsing failed; only numeric diagnostics retained") from None
            require(settings.tag == "map", "Unexpected settings XML root")
            diagnostic["status"] = "parsed_map"
            entries = list(settings)
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
        recognized = len(denial) == 1 and any("notifications" in n.get("text", "").lower() for n in controls)
        if not recognized:
            report["unexpected_permission_ui"] = permission_diagnostic(controls, denial)
            try:
                screenshot(filename)
                report["unexpected_permission_ui"]["screenshot_file"] = filename
            except Exception as error:
                # Diagnostic capture cannot replace or hide the same fail-closed UI refusal.
                report["unexpected_permission_ui"]["screenshot_error"] = type(error).__name__
        require(recognized, "Unexpected system permission UI; do not guess")
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
        feature_diagnostic = {"format": "lf_delimited_ascii_names", "status": "command_failed_or_incomplete"}
        report["adb_features"] = feature_diagnostic  # No raw stdout/stderr or feature-name payload retained.
        feature_result = adb("features")
        feature_diagnostic.update(exit=feature_result.returncode, stdout_bytes=len(feature_result.stdout),
                                  stderr_bytes=len(feature_result.stderr), status="invalid_cli_output")
        features = parse_adb_features(feature_result.stdout)
        feature_diagnostic.update(feature_count=len(features), shell_v2="shell_v2" in features,
                                  status="shell_v2_present" if "shell_v2" in features else "shell_v2_absent")
        require("shell_v2" in features, "Remote shell exit-status protocol required for settings reads")
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
