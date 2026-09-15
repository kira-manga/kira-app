"""Offline synthetic-archive tests; not provider-archive or hosted-runtime qualification."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location("runtime_inputs", Path(__file__).resolve().parents[1] / "install-toolchain-inputs.py")
runtime = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runtime)


class RuntimeInputsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.runner = self.root / "runner"
        self.runner.mkdir()
        self.env_file, self.path_file = self.root / "github-env", self.root / "github-path"
        self.env_file.touch()
        self.path_file.touch()
        self.ruby_prefix = self.root / "toolcache/Ruby/3.3.12/x64"
        environment = mock.patch.dict(os.environ, {
            "HOME": str(self.root), "RUNNER_TEMP": str(self.runner),
            "RUNNER_TOOL_CACHE": str(self.root / "toolcache"),
            "GITHUB_ENV": str(self.env_file), "GITHUB_PATH": str(self.path_file),
        }, clear=True)
        environment.start()
        self.addCleanup(environment.stop)
        prefixes = mock.patch.object(runtime, "RUBY_PREFIXES", {"linux-x64": self.ruby_prefix})
        prefixes.start()
        self.addCleanup(prefixes.stop)

    def archive(self, root, files, symlinks=(), hardlinks=(), pin_directory=True, directories=(), modes=None):
        modes = modes or {}
        path = self.root / ("archive-" + str(len(list(self.root.glob("archive-*")))) + ".tar.gz")
        with tarfile.open(path, "w:gz") as archive:
            for name in directories:
                member = tarfile.TarInfo(root + ("/" + name if name else ""))
                member.type, member.mode = tarfile.DIRTYPE, modes.get(name, 0o755)
                archive.addfile(member)
            for name, data in files:
                member = tarfile.TarInfo(root + "/" + name)
                member.size = len(data)
                member.mode = modes.get(name, 0o755 if "/bin/" in member.name else 0o644)
                archive.addfile(member, io.BytesIO(data))
            for kind, links in ((tarfile.SYMTYPE, symlinks), (tarfile.LNKTYPE, hardlinks)):
                for name, target in links:
                    member = tarfile.TarInfo(root + "/" + name)
                    member.type, member.linkname, member.mode = kind, target, modes.get(name, 0o755)
                    archive.addfile(member)
        pin = {"url": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "size": path.stat().st_size}
        if pin_directory:
            pin["directory"] = root
        return pin

    def extract(self, pin):
        destination = Path(tempfile.mkdtemp(dir=self.root)).resolve()
        return runtime.extract_verified(self.root / pin["url"], destination, pin)

    def install(self, role, entries, host="linux-x64", process=None):
        def download(pin, target):
            shutil.copyfile(self.root / pin["url"], target)

        with mock.patch.object(runtime, "download", side_effect=download), \
                mock.patch.object(runtime.subprocess, "run", side_effect=process) as executed, \
                contextlib.redirect_stdout(io.StringIO()):
            runtime.install(role, host, self.runner, {role: {host: entries}})
        return executed

    def values(self):
        return dict(line.split("=", 1) for line in self.env_file.read_text().splitlines())

    def cleanup(self, role):
        area = Path(self.values()["KIRA_VERIFIED_" + role.upper() + "_AREA"])
        runtime.cleanup_area(area, runtime.read_json(area / "owned.json"), self.runner)
        self.assertFalse(area.exists())

    def test_checksum_failure_precedes_archive_parsing(self):
        pin = self.archive("jdk", [("bin/java", b"fixture")])
        pin["sha256"] = "0" * 64
        with mock.patch.object(runtime.tarfile, "open") as parser, mock.patch.object(runtime.subprocess, "run") as executed:
            with self.assertRaisesRegex(RuntimeError, "checksum mismatch"):
                self.extract(pin)
            parser.assert_not_called()
            executed.assert_not_called()

    def test_authenticated_regular_files_and_internal_links_match_the_complete_tree(self):
        pin = self.archive("jdk", [("bin/java", b"fixture")], [("bin/java-link", "java")], [("bin/java-hard", "jdk/bin/java")])
        installed, inventory = self.extract(pin)
        self.assertEqual(inventory, runtime.tree_snapshot(installed))
        self.assertEqual(b"fixture", (installed / "bin/java-hard").read_bytes())
        (installed / "unexpected-gem").write_bytes(b"unreviewed")
        self.assertNotEqual(inventory, runtime.tree_snapshot(installed))

    def test_directory_sgid_is_stripped_before_extraction_without_changing_files(self):
        pin = self.archive("jdk", [("bin/java", b"fixture")], directories=("", "bin"),
                           modes={"": 0o2755, "bin": 0o2770, "bin/java": 0o751})
        extractall = tarfile.TarFile.extractall

        def checked(source, destination, **options):
            self.assertEqual([0o755, 0o770, 0o751], [member.mode for member in options["members"]])
            return extractall(source, destination, **options)

        with mock.patch.object(runtime.tarfile.TarFile, "extractall", checked):
            installed, inventory = self.extract(pin)
        self.assertEqual(0o755, installed.stat().st_mode & 0o7777)
        self.assertEqual(0o770, (installed / "bin").stat().st_mode & 0o7777)
        self.assertEqual(0o751, (installed / "bin/java").stat().st_mode & 0o7777)
        self.assertEqual(b"fixture", (installed / "bin/java").read_bytes())
        self.assertEqual(inventory, runtime.tree_snapshot(installed))

    def test_unsafe_authenticated_archive_layouts_are_rejected(self):
        fixtures = [
            ([("../outside", b"x")], (), (), "unsafe archive path"),
            ([("bin/java", b"x"), ("bin/java", b"y")], (), (), "duplicate"),
            ([("bin/java", b"x")], [("escape", "../../outside")], (), "escaping archive symlink"),
            ([("bin/java", b"x")], [("escape", "/tmp/outside")], (), "unsafe archive symlink"),
            ([("link/file", b"x")], [("link", "bin")], (), "descends through a symlink"),
            ([("file", b"x"), ("file/child", b"y")], (), (), "descends through a non-directory"),
            ([("bin/java", b"x")], (), [("bad", "../outside")], "unsafe archive path"),
        ]
        for files, symlinks, hardlinks, message in fixtures:
            with self.subTest(message=message):
                with self.assertRaisesRegex(RuntimeError, message):
                    self.extract(self.archive("jdk", files, symlinks, hardlinks))
        for kind in ("directory", "file", "symlink", "hardlink"):
            for special in (0o4000, 0o2000, 0o1000):
                if kind == "directory" and special == 0o2000:
                    continue  # This one bit is normalized, not installed.
                with self.subTest(kind=kind, special=special):
                    pin = self.archive("jdk", [("bin/java", b"x")] + ([("danger", b"x")] if kind == "file" else []),
                        symlinks=[("danger", "bin/java")] if kind == "symlink" else (),
                        hardlinks=[("danger", "jdk/bin/java")] if kind == "hardlink" else (),
                        directories=("danger",) if kind == "directory" else (),
                        modes={"danger": special | (0o2755 if kind == "directory" else 0o755)})
                    with self.assertRaisesRegex(RuntimeError, "privileged runtime archive mode"):
                        self.extract(pin)
        self.assertFalse((self.root / "outside").exists())

    def test_java_executes_only_after_authentication_and_publishes_its_discovered_home(self):
        pin = self.archive("provider-root", [("Contents/Home/bin/java", b"fixture-java"),
                                             ("Contents/Home/release", b"fixture-release")], pin_directory=False)

        def version(command, **options):
            self.assertEqual("-version", command[1])
            self.assertEqual(b"fixture-java", Path(command[0]).read_bytes())
            self.assertTrue(Path(command[0]).is_relative_to(self.runner))
            self.assertEqual("/usr/bin:/bin:/usr/sbin:/sbin", options["env"]["PATH"])
            return subprocess.CompletedProcess(command, 0, b'openjdk version "21.0.12.1"\nTemurin-21.0.12.1+1\n')

        executed = self.install("java", pin, process=version)
        self.assertEqual(1, executed.call_count)
        home = Path(self.values()["JAVA_HOME"])
        self.assertEqual("provider-root/Contents/Home", home.relative_to(home.parents[2]).as_posix())
        self.assertEqual(str(home / "bin") + "\n", self.path_file.read_text())
        self.cleanup("java")

    def test_symlink_chains_cannot_escape_the_authenticated_tree(self):
        outside = self.root / "outside"
        outside.write_bytes(b"owned fixture sentinel")
        pin = self.archive("jdk", [], [("dir/b", ".."), ("dir/a", "b/../../outside")])
        with self.assertRaisesRegex(RuntimeError, "installed symlink escapes authenticated tree"):
            self.extract(pin)
        self.assertEqual(b"owned fixture sentinel", outside.read_bytes())

    def test_bad_java_archive_never_executes_or_publishes_a_runtime(self):
        pin = self.archive("jdk", [("bin/java", b"fixture"), ("release", b"fixture")], pin_directory=False)
        pin["sha256"] = "0" * 64
        with self.assertRaisesRegex(RuntimeError, "checksum mismatch"):
            self.install("java", pin, process=lambda *a, **k: self.fail("unverified Java executed"))
        self.assertEqual({"KIRA_VERIFIED_JAVA_AREA": ""}, self.values())
        self.assertEqual("", self.path_file.read_text())
        self.assertEqual([], list(self.runner.iterdir()))

    def test_ruby_installs_only_into_an_absent_embedded_prefix_and_cleans_only_its_creation(self):
        pin = self.archive("x64", [("bin/ruby", b"fixture-ruby")])
        executed = self.install("ruby", pin)
        executed.assert_not_called()
        self.assertEqual(b"fixture-ruby", (self.ruby_prefix / "bin/ruby").read_bytes())
        self.assertTrue(Path(str(self.ruby_prefix) + ".complete").is_file())
        self.cleanup("ruby")
        self.assertFalse(self.ruby_prefix.exists())
        self.assertFalse(Path(str(self.ruby_prefix) + ".complete").exists())

    def test_ruby_reuses_only_an_exact_existing_tree_and_never_removes_it(self):
        pin = self.archive("x64", [("bin/ruby", b"fixture-ruby")])
        authenticated, _ = self.extract(pin)
        shutil.copytree(authenticated, self.ruby_prefix)
        Path(str(self.ruby_prefix) + ".complete").touch()
        self.install("ruby", pin).assert_not_called()
        self.cleanup("ruby")
        self.assertTrue(self.ruby_prefix.is_dir())
        for changed in (self.ruby_prefix / "bin/ruby", self.ruby_prefix / "extra-gem"):
            with self.subTest(changed=changed.name):
                old = changed.read_bytes() if changed.exists() else None
                changed.write_bytes(b"unreviewed")
                diagnostic = io.StringIO()
                with contextlib.redirect_stderr(diagnostic), self.assertRaisesRegex(RuntimeError, "preinstalled Ruby differs"):
                    self.install("ruby", pin)
                report = json.loads(diagnostic.getvalue().split(": ", 1)[1])
                self.assertEqual(True, report["canonical"])
                self.assertEqual("directory", report["prefix"])
                self.assertEqual("complete", report["comparison"])
                self.assertEqual({"missing": 0, "extra": int(old is None), "type": 0, "mode": 0,
                                  "size": int(old is not None), "hash": int(old is not None), "link": 0}, report["mismatches"])
                self.assertEqual(["bin/ruby"] if old is not None else [], report["names"])
                self.assertEqual(b"unreviewed", changed.read_bytes())
                changed.unlink() if old is None else changed.write_bytes(old)
        diagnostic = io.StringIO()
        with mock.patch.object(runtime, "tree_snapshot", side_effect=RuntimeError("snapshot unavailable")) as snapshot, \
                contextlib.redirect_stderr(diagnostic), self.assertRaisesRegex(RuntimeError, "snapshot unavailable"):
            runtime.verify_existing_ruby(self.ruby_prefix, {})
        snapshot.assert_called_once_with(self.ruby_prefix)
        report = json.loads(diagnostic.getvalue().split(": ", 1)[1])
        self.assertEqual("unavailable", report["comparison"])
        self.assertTrue(all(count is None for count in report["mismatches"].values()))
        self.assertIsNone(report["namesOmitted"])
        self.assertTrue(self.ruby_prefix.is_dir())

    def test_ruby_cache_diagnostic_counts_all_differences_without_exposing_extra_names(self):
        expected = {"bin/ruby": ["file", 3, 0o755, "before"], "lib/link": ["link", "before"],
                    "lib/type": ["directory"], **{"lib/missing" + str(i): ["directory"] for i in range(12)}}
        for name in ("lib/unsafe\nname", "lib/nonascii-\u00e9", "lib/" + "x" * 161, ".hidden"):
            expected[name] = ["directory"]
        actual = {"bin/ruby": ["file", 4, 0o644, "after"], "lib/link": ["link", "after"],
                  "lib/type": ["file", 0, 0o644, "after"], "extra-private-name": ["directory"]}
        report = runtime.ruby_cache_difference(expected, actual, True, "directory")
        self.assertEqual({"missing": 16, "extra": 1, "type": 1, "mode": 1, "size": 1, "hash": 1, "link": 1},
                         report["mismatches"])
        self.assertEqual(["bin/ruby", "lib/link", "lib/missing0", "lib/missing1", "lib/missing10",
                          "lib/missing11", "lib/missing2", "lib/missing3"], report["names"])
        self.assertEqual(12, report["namesOmitted"])
        self.assertNotIn("extra-private-name", json.dumps(report))

    def test_ruby_rejects_a_changed_cache_root_before_installation(self):
        pin = self.archive("x64", [("bin/ruby", b"fixture-ruby")])
        os.environ["RUNNER_TOOL_CACHE"] = str(self.root / "different-toolcache")
        with self.assertRaisesRegex(RuntimeError, "wrong embedded Ruby tool-cache prefix"):
            self.install("ruby", pin)
        self.assertFalse(self.ruby_prefix.exists())

    def test_native_uses_only_fresh_authenticated_dependencies_and_explicit_offline_policy(self):
        before = b"dependenciesUrl = https://example.invalid\nairplaneMode = false\n"
        pins = [self.archive("native", [("konan/konan.properties", before)]),
                self.archive("llvm-reviewed", [("bin/clang", b"fixture-clang")]),
                self.archive("libffi-reviewed", [("lib/libffi.dylib", b"fixture-libffi")])]
        self.install("native", pins, host="macos-arm64").assert_not_called()
        values = self.values()
        data = Path(values["KONAN_DATA_DIR"])
        home = Path(values["ORG_GRADLE_PROJECT_kotlin.native.home"])
        self.assertEqual(str(data), values["ORG_GRADLE_PROJECT_konan.data.dir"])
        self.assertEqual("0", values["KONAN_USE_INTERNAL_SERVER"])
        self.assertEqual(before.replace(b"false", b"true"), (home / "konan/konan.properties").read_bytes())
        self.assertEqual({".extracted", "llvm-reviewed", "libffi-reviewed"}, {p.name for p in (data / "dependencies").iterdir()})
        self.assertEqual("llvm-reviewed\nlibffi-reviewed\n", (data / "dependencies/.extracted").read_text())
        receipt = json.loads((Path(values["KIRA_VERIFIED_NATIVE_AREA"]) / "owned.json").read_text())
        self.assertEqual(hashlib.sha256(before).hexdigest(), receipt["nativePolicy"]["before"])
        self.assertEqual(3, len(receipt["archives"]))
        self.cleanup("native")

    def test_native_policy_change_is_not_silently_rewritten(self):
        for before in (b"airplaneMode = false\nairplaneMode = false\n",
                       b"# airplaneMode = false\nairplaneMode=false\n"):
            with self.subTest(properties=before):
                pins = [self.archive("native", [("konan/konan.properties", before)])]
                with self.assertRaisesRegex(RuntimeError, "unsupported Native offline-policy input"):
                    self.install("native", pins, host="macos-arm64")
                self.assertEqual({"KIRA_VERIFIED_NATIVE_AREA": ""}, self.values())
                self.assertEqual([], list(self.runner.iterdir()))

    def test_cleanup_refuses_changed_directory_identity(self):
        area = Path(tempfile.mkdtemp(prefix="kira-verified-java-", dir=self.runner)).resolve()
        receipt = {"role": "java", "identity": runtime.identity(area)}
        retained = self.root / "retained"
        area.rename(retained)
        area.mkdir()
        with self.assertRaisesRegex(RuntimeError, "unowned runtime cleanup path"):
            runtime.cleanup_area(area, receipt, self.runner)
        self.assertTrue(retained.is_dir())
        self.assertTrue(area.is_dir())


if __name__ == "__main__":
    unittest.main()
