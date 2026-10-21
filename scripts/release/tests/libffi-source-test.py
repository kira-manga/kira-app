#!/usr/bin/env python3
"""Small offline boundary tests; no compiler downloads or native builds."""

import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest import mock

sys.dont_write_bytecode = True
REPO = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("libffi_source", REPO / "scripts/release/prepare-libffi-source.py")
libffi = importlib.util.module_from_spec(spec)
spec.loader.exec_module(libffi)


class SourceBootstrapTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="kira-libffi-test-")
        self.addCleanup(temporary.cleanup)
        self.temp = Path(temporary.name)
        for name in ("runner", "home", "repo"):
            (self.temp / name).mkdir(mode=0o700)
        self.repo = self.temp / "repo"
        self.env = {"RUNNER_TEMP": str(self.temp / "runner"), "HOME": str(self.temp / "home"),
                    "GITHUB_REPOSITORY": "test/fixture", "GITHUB_RUN_ID": "17", "GITHUB_RUN_ATTEMPT": "1"}
        self.root = libffi.owned_root(self.env)

    def archive(self, name, entries):
        path = self.temp / (name + ".tar.gz")
        with tarfile.open(path, "w:gz") as archive:
            for entry_name, kind, payload in entries:
                item = tarfile.TarInfo(entry_name)
                item.mode = 0o755 if kind == "directory" else 0o644
                item.type = {"file": tarfile.REGTYPE, "directory": tarfile.DIRTYPE,
                             "symlink": tarfile.SYMTYPE, "hardlink": tarfile.LNKTYPE,
                             "fifo": tarfile.FIFOTYPE}[kind]
                if kind == "file":
                    data = payload.encode()
                    item.size = len(data)
                    archive.addfile(item, io.BytesIO(data))
                else:
                    item.linkname = payload
                    archive.addfile(item)
        return path, {"sha256": libffi.digest(path), "bytes": path.stat().st_size, "archive_root": "sample"}

    def installed(self):
        install = self.root / "install"
        for name in ("include/ffi.h", "include/ffitarget.h", "lib/libffi.a", "lib/pkgconfig/libffi.pc"):
            path = install / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("small fixture: " + name)
        return install

    def upstream_config(self):
        # Kotlin 2.4.0 PrepareKonanProperties selects host ESSENTIALS and appends
        # LLVMDistributionSource's generated aliases/default remote profile.
        return (b"dependencyProfiles = default alt\nairplaneMode = false\n"
                b"libffiDir.macos_arm64 = libffi-3.3-1-macos-arm64\n"
                b"llvmHome.macos_arm64 = $llvm.macos_arm64.user\n"
                b"llvm.macos_arm64.user=llvm-21-aarch64-macos-essentials-97\n"
                b"llvmVersion.macos_arm64=21\n"
                b"llvm-21-aarch64-macos-essentials-97.default=remote:public:resources/llvm/21-aarch64-macos\n"
                b"other = $distribution_version\n")

    def installed_llvm(self):
        llvm = self.root / "llvm" / libffi.LLVM_DEPENDENCY
        (llvm / "bin").mkdir(parents=True)
        clang = llvm / "bin/clang-21"
        clang.write_text("small authenticated LLVM fixture, never executed")
        clang.chmod(0o700)
        (llvm / "bin/clang").symlink_to("clang-21")
        return llvm

    def prepared(self):
        libffi.create_owned_root(self.root, self.env)
        pin = libffi.pins()
        home = self.root / "compiler" / pin["compiler"]["archive_root"]
        (home / "konan").mkdir(parents=True)
        (home / "tools").mkdir()
        (self.root / "data").mkdir()
        install = self.installed()
        llvm = self.installed_llvm()
        original = self.upstream_config()
        config = home / "konan/konan.properties"
        config.write_bytes(libffi.profile_bytes(original, install, llvm))
        state = {"owner": libffi.owner(self.root, self.env), "status": "prepared",
                 "inputs": libffi.state_inputs(REPO, pin), "native_home": str(home),
                 "konan_data_dir": str(self.root / "data"), "verification_count": 0,
                 "original_konan_properties_sha256": hashlib.sha256(original).hexdigest(),
                 "configured_konan_properties_sha256": libffi.digest(config),
                 "env_blacklist_sha256": None, "installed_files": libffi.install_hashes(install),
                 "llvm_tree_sha256": libffi.llvm_tree_hash(llvm)}
        libffi.write_json(self.root / libffi.STATE, state)
        self.env.update(GRADLE_OPTS=f"-Xmx128m {libffi.NATIVE_HOME}{home} {libffi.CACHE_OFF}",
                        KONAN_DATA_DIR=str(self.root / "data"))
        return home

    def test_reviewed_manifest_and_host_gate(self):
        pin = libffi.pins()
        self.assertEqual(pin["source"]["sha256"], "72fba7922703ddfa7a028d513ac15a85c8d54c8d67f55fa5a4802885dc652056")
        self.assertEqual(pin["compiler"]["bytes"], 259007716)
        self.assertEqual(pin["llvm"]["bytes"], 151150049)
        self.assertEqual(pin["llvm"]["sha256"], "62fd78f090353aa7b2c6601ad6f95bd7a0b0558b18f86978043cc878321b408a")
        with mock.patch.object(libffi.platform, "system", return_value="Linux"), \
                mock.patch.object(libffi, "download") as download:
            with self.assertRaises(libffi.BootstrapError):
                libffi.prepare(self.env)
            download.assert_not_called()
        self.assertFalse(self.root.exists())

    def test_download_bounds_hash_and_https_before_use(self):
        class Response(io.BytesIO):
            status = 200

        pin = {"url": "https://example.invalid/input", "sha256": hashlib.sha256(b"abc").hexdigest(), "bytes": 3}
        path = self.temp / "download"
        for body in (b"abcd", b"bad", b"ab"):
            with self.subTest(body=body), mock.patch.object(libffi.urllib.request, "build_opener") as opener:
                opener.return_value.open.return_value = Response(body)
                with self.assertRaises(libffi.BootstrapError):
                    libffi.download(pin, path)
                self.assertFalse(path.exists())
        with mock.patch.object(libffi.urllib.request, "build_opener") as opener:
            opener.return_value.open.return_value = Response(b"abc")
            libffi.download(pin, path)
        self.assertEqual(path.read_bytes(), b"abc")
        with mock.patch.object(libffi.urllib.request, "build_opener") as opener:
            opener.return_value.open.return_value = Response(b"abc")
            with self.assertRaises(FileExistsError):
                libffi.download(pin, path)
        self.assertEqual(path.read_bytes(), b"abc", "must not remove someone else's existing file")
        with self.assertRaises(libffi.BootstrapError):
            libffi.HTTPSRedirect().redirect_request(None, None, 302, "redirect", {}, "http://example.invalid/")

    def test_cfi_backport_authenticates_authority_patch_input_and_output(self):
        source = self.temp / "cfi-source"
        assembly = source / "src/aarch64/sysv.S"
        assembly.parent.mkdir(parents=True)
        before, after = b"reviewed input\n", b"reviewed corrected output\n"
        upstream = self.temp / "upstream.patch"
        upstream_bytes = b"authenticated upstream authority fixture\n"
        upstream.write_bytes(upstream_bytes)
        reviewed = libffi.pins()["cfi_backport"]
        local_patch = self.repo / reviewed["path"]
        local_patch.parent.mkdir(parents=True)
        patch_bytes = (REPO / reviewed["path"]).read_bytes()
        local_patch.write_bytes(patch_bytes)
        pin = {"cfi_upstream": {"bytes": len(upstream_bytes), "sha256": libffi.digest(upstream)},
               "cfi_backport": dict(reviewed, input_sha256=hashlib.sha256(before).hexdigest(),
                                    output_sha256=hashlib.sha256(after).hexdigest())}

        def apply(args, **kwargs):
            self.assertIn("--fuzz=0", args)
            self.assertEqual(kwargs["cwd"], source)
            if "--dry-run" not in args:
                assembly.write_bytes(after)

        assembly.write_bytes(before)
        with mock.patch.object(libffi, "run", side_effect=apply) as command:
            self.assertEqual(libffi.apply_cfi_backport(source, upstream, pin, self.repo, {}),
                             hashlib.sha256(after).hexdigest())
            self.assertEqual(command.call_count, 2)
            self.assertIn("--dry-run", command.call_args_list[0].args[0])
        for path, original in ((upstream, upstream_bytes), (local_patch, patch_bytes), (assembly, before)):
            with self.subTest(path=path.name):
                assembly.write_bytes(before)
                path.write_bytes(b"changed")
                with mock.patch.object(libffi, "run") as command:
                    with self.assertRaises(libffi.BootstrapError):
                        libffi.apply_cfi_backport(source, upstream, pin, self.repo, {})
                    command.assert_not_called()
                path.write_bytes(original)
        with mock.patch.object(libffi, "run") as command:
            with self.assertRaisesRegex(libffi.BootstrapError, "output assembly"):
                libffi.apply_cfi_backport(source, upstream, pin, self.repo, {})
            self.assertEqual(command.call_count, 2)

    def test_archive_inventory_rejects_unsafe_entries_before_writes(self):
        cases = [
            [("sample/../escape", "file", "bad")],
            [("/sample/absolute", "file", "bad")],
            [("other/file", "file", "bad")],
            [("sample/fifo", "fifo", "")],
            [("sample/file", "file", "one"), ("sample/file", "file", "two")],
            [("sample/D", "file", "one"), ("sample/d/child", "file", "two")],
            [("sample/link", "symlink", "../../escape")],
            [("sample/link", "symlink", "/tmp/escape")],
            [("sample/link", "symlink", "target"), ("sample/link/child", "file", "bad")],
            [("sample/a", "symlink", "b"), ("sample/b", "symlink", "a")],
            [("sample/link", "hardlink", "../escape")],
            [("sample/a", "symlink", "a")],
            [("sample/a", "symlink", "b"), ("sample/b", "symlink", "missing")],
            [("sample/a", "symlink", "b"), ("sample/b", "symlink", "../../escape")],
            [("sample/real/file", "file", "safe"), ("sample/alias", "symlink", "real"),
             ("sample/link", "symlink", "alias/file")],
            [("sample/file", "file", "safe"), ("sample/link", "symlink", "file"),
             ("sample/hard", "hardlink", "sample/link")],
            [("sample/file", "file", "safe"), ("sample/hard", "hardlink", "sample/file"),
             ("sample/link", "symlink", "hard")],
            [("sample/file", "file", "safe"), ("sample/hard", "hardlink", "sample/file"),
             ("sample/other", "hardlink", "sample/hard")],
        ]
        for index, entries in enumerate(cases):
            with self.subTest(index=index):
                path, pin = self.archive(str(index), entries)
                output = self.temp / ("output-" + str(index))
                with self.assertRaises(libffi.BootstrapError):
                    libffi.extract_verified(path, pin, output)
                self.assertFalse(output.exists())

    def test_verified_extraction_preserves_direct_links_and_copies_hardlinks(self):
        path, pin = self.archive("good", [("sample/dir/target", "file", "verified"),
                                          ("sample/link", "symlink", "dir/target"),
                                          ("sample/hard", "hardlink", "sample/dir/target")])
        with self.assertRaises(libffi.BootstrapError):
            libffi.extract_verified(path, dict(pin, sha256="0" * 64), self.temp / "bad-hash")
        self.assertFalse((self.temp / "bad-hash").exists())
        home = libffi.extract_verified(path, pin, self.temp / "good-extraction")
        self.assertEqual((home / "link").read_text(), "verified")
        self.assertEqual((home / "hard").stat().st_nlink, 1)
        with self.assertRaises(libffi.BootstrapError):
            libffi.extract_verified(path, pin, self.temp / "source", source=True)
        self.assertFalse((self.temp / "source").exists())

    def test_verified_extraction_preserves_llvm_internal_symlink_chains(self):
        # Exact four link names/targets/order from the pinned LLVM essentials-97 archive;
        # executable payloads are replaced with tiny inert text and are never executed.
        path, pin = self.archive("llvm-links", [
            ("sample/bin", "directory", ""),
            ("sample/bin/clang", "symlink", "clang-21"),
            ("sample/bin/clang++", "symlink", "clang"),
            ("sample/bin/clang-21", "file", "inert clang"),
            ("sample/bin/clang-cache", "symlink", "clang"),
            ("sample/bin/ld.lld", "symlink", "lld"),
            ("sample/bin/lld", "file", "inert lld"),
        ])
        home = libffi.extract_verified(path, pin, self.temp / "llvm-links")
        for name, immediate, content in (("clang", "clang-21", "inert clang"),
                                          ("clang++", "clang", "inert clang"),
                                          ("clang-cache", "clang", "inert clang"),
                                          ("ld.lld", "lld", "inert lld")):
            link = home / "bin" / name
            self.assertEqual(os.readlink(link), immediate)
            self.assertEqual(link.read_text(), content)
        (home / "bin/clang-21").chmod(0o700)
        libffi.llvm_tree_hash(home)  # Complete installed-tree validation accepts the internal chains.

    def test_archive_symlink_chain_depth_is_bounded_before_writes(self):
        for count in (32, 33):
            entries = [("sample/terminal", "file", "verified")]
            entries += [(f"sample/link-{index}", "symlink",
                         f"link-{index + 1}" if index + 1 < count else "terminal")
                        for index in range(count)]
            path, pin = self.archive(f"chain-{count}", entries)
            output = self.temp / f"chain-{count}"
            if count == 32:
                home = libffi.extract_verified(path, pin, output)
                self.assertEqual(os.readlink(home / "link-0"), "link-1")
            else:
                with self.assertRaisesRegex(libffi.BootstrapError, "excessive archive symlink chain"):
                    libffi.extract_verified(path, pin, output)
                self.assertFalse(output.exists())

    def test_profiles_are_exact_local_edits_with_offline_policy_and_no_extra_candidates(self):
        original = self.upstream_config().replace(b"default alt", b"default \\\n alt")
        path = self.root / "install"
        llvm = self.root / "llvm" / libffi.LLVM_DEPENDENCY
        modified = libffi.profile_bytes(original, path, llvm)
        expected = original.replace(b"airplaneMode = false\n", b"airplaneMode = true\n")
        expected = expected.replace(libffi.LLVM_REMOTE.encode(), str(llvm).encode())
        self.assertEqual(modified, expected + f"\n{libffi.PROFILE}={path}\n".encode())
        values = libffi.properties(modified)
        self.assertEqual([value for key, value in values if key == "airplaneMode"], ["true"])
        self.assertEqual([value for key, value in values if key == libffi.LLVM_PROFILE], [str(llvm)])
        for bad in (original.replace(b"default \\\n alt", b"alt default"),
                    original.replace(b"libffi-3.3-1-macos-arm64", b"unreviewed"),
                    original + b"libffiDir.macos_arm64=libffi-3.3-1-macos-arm64\n",
                    original + b"libffi-3.3-1-macos-arm64.alt=remote:public\n",
                    original + b"libffi-3.3-1-macos-arm64.\\u0064efault=/bad\n",
                    original.replace(b"$llvm.macos_arm64.user", b"$llvm.macos_arm64.dev"),
                    original.replace(b"llvmVersion.macos_arm64=21", b"llvmVersion.macos_arm64=22"),
                    original.replace(b"llvm-21-aarch64-macos-essentials-97", b"llvm-unreviewed"),
                    original.replace(libffi.LLVM_REMOTE.encode(), b"/unreviewed/cache"),
                    original + f"{libffi.LLVM_DEPENDENCY}.alt=remote:public\n".encode(),
                    original + f"{libffi.LLVM_DEPENDENCY}.\\u0064efault=/bad\n".encode(),
                    original.replace(b"airplaneMode = false", b"airplaneMode = true"),
                    original.replace(b"airplaneMode = false", b"airplaneMode=false"),
                    original + b"airplaneMode = false\n",
                    original + b"airplane\\u004dode=false\n"):
            with self.subTest(bad=bad), self.assertRaises(libffi.BootstrapError):
                libffi.profile_bytes(bad, path, llvm)
        with self.assertRaises(libffi.BootstrapError):
            libffi.profile_bytes(original, Path("/tmp/unsafe path"), llvm)
        with self.assertRaises(libffi.BootstrapError):
            libffi.profile_bytes(original, path, Path("/tmp/unsafe path"))

    def test_property_and_environment_overrides_fail_closed(self):
        (self.repo / "gradle.properties").write_text("org.gradle.caching=true\n")
        good = dict(self.env, GRADLE_OPTS="-Xmx128m -Dorg.gradle.daemon=false")
        libffi.override_guard(self.repo, good)
        for addition in ({"KONAN_DATA_DIR": "/other"}, {"KONAN_HOME": "/other"},
                         {"JAVA_OPTS": "-Dorg.gradle.project.konan.data.dir=/other"},
                         {"JAVA_TOOL_OPTIONS": "-Dorg.jetbrains.kotlin.native.home=/other"},
                         {"ORG_GRADLE_PROJECT_kotlin.native.home": "/other"},
                         {"GRADLE_OPTS": "-Dorg.gradle.caching=true"}, {"KONAN_USE_INTERNAL_SERVER": "1"}):
            with self.subTest(addition=addition), self.assertRaises(libffi.BootstrapError):
                libffi.override_guard(self.repo, dict(good, **addition))
        for filename, setting in (("gradle.properties", "systemProp.org.gradle.project.konan.data.dir=/other\n"),
                                  ("local.properties", "konan.data.dir=/other\n"),
                                  ("local.properties", "\\u006botlin.native.home=/other\n")):
            path = self.repo / filename
            path.write_text(setting)
            with self.subTest(setting=setting), self.assertRaises(libffi.BootstrapError):
                libffi.override_guard(self.repo, good)
            path.unlink()
        home, data = self.root / "compiler", self.root / "data"
        prepared = dict(good, GRADLE_OPTS=good["GRADLE_OPTS"] + f" {libffi.NATIVE_HOME}{home} {libffi.CACHE_OFF}",
                        KONAN_DATA_DIR=str(data))
        libffi.override_guard(self.repo, prepared, home, data)
        for options in (good["GRADLE_OPTS"], f"{libffi.NATIVE_HOME}{home}",
                        prepared["GRADLE_OPTS"] + f" {libffi.NATIVE_HOME}{home}"):
            with self.subTest(options=options), self.assertRaises(libffi.BootstrapError):
                libffi.override_guard(self.repo, dict(prepared, GRADLE_OPTS=options), home, data)

    def test_verify_detects_library_config_and_environment_drift(self):
        home = self.prepared()
        with mock.patch.object(libffi, "host_guard"):
            libffi.verify(self.env)
            self.assertEqual(libffi.read_json(self.root / libffi.STATE)["verification_count"], 1)
            library = self.root / "install/lib/libffi.a"
            original = library.read_bytes()
            library.write_text("modified")
            with self.assertRaisesRegex(libffi.BootstrapError, "installed libffi"):
                libffi.verify(self.env)
            library.write_bytes(original)
            config = home / "konan/konan.properties"
            original_config = config.read_bytes()
            config.write_bytes(original_config.replace(b"$distribution_version", b"unexpected"))
            with self.assertRaisesRegex(libffi.BootstrapError, "config changed"):
                libffi.verify(self.env)
            config.write_bytes(original_config)
            for before, after in ((b"airplaneMode = true", b"airplaneMode = false"),
                                  (str(self.root / "llvm").encode(), b"/unverified/llvm")):
                config.write_bytes(original_config.replace(before, after))
                with self.assertRaisesRegex(libffi.BootstrapError, "LLVM/offline profile"):
                    libffi.verify(self.env)
                config.write_bytes(original_config)
            clang = self.root / "llvm" / libffi.LLVM_DEPENDENCY / "bin/clang-21"
            original_clang = clang.read_bytes()
            clang.write_bytes(b"modified")
            with self.assertRaisesRegex(libffi.BootstrapError, "installed LLVM tree changed"):
                libffi.verify(self.env)
            clang.write_bytes(original_clang)
            libffi.verify(self.env)
            with self.assertRaises(libffi.BootstrapError):
                libffi.verify(dict(self.env, KONAN_DATA_DIR="/other"))
        library.unlink()
        library.symlink_to(home / "konan/konan.properties")
        with self.assertRaises(libffi.BootstrapError):
            libffi.install_hashes(self.root / "install")

    def test_llvm_tree_inventory_detects_extra_paths_modes_and_external_links(self):
        llvm = self.installed_llvm()
        expected = libffi.llvm_tree_hash(llvm)
        extra = llvm / "unexpected"
        extra.write_text("not in the authenticated tree")
        self.assertNotEqual(libffi.llvm_tree_hash(llvm), expected)
        extra.unlink()
        clang = llvm / "bin/clang-21"
        clang.chmod(0o500)
        self.assertNotEqual(libffi.llvm_tree_hash(llvm), expected)
        clang.chmod(0o700)
        self.assertEqual(libffi.llvm_tree_hash(llvm), expected)
        link = llvm / "bin/clang"
        link.unlink()
        outside = self.temp / "outside"
        outside.write_bytes(clang.read_bytes())
        outside.chmod(0o700)
        link.symlink_to(outside)
        with self.assertRaisesRegex(libffi.BootstrapError, "LLVM link escapes"):
            libffi.llvm_tree_hash(llvm)

    def test_blacklist_and_download_receipts_cannot_bypass_isolation(self):
        home = self.prepared()
        blacklist = home / "tools/env_blacklist"
        blacklist.write_text("SDKROOT\n")
        self.assertEqual(libffi.blacklist_hash(home), libffi.digest(blacklist))
        blacklist.write_text("SDKROOT\nKONAN_DATA_DIR\n")
        with self.assertRaises(libffi.BootstrapError):
            libffi.blacklist_hash(home)
        dependencies = self.root / "data/dependencies"
        (dependencies / "cache").mkdir(parents=True)
        libffi.no_prebuilt(self.root / "data")
        for relative, body in ((libffi.DEPENDENCY, "partial extraction"),
                               ("cache/" + libffi.DEPENDENCY + ".tar.gz.part", "download"),
                               (".extracted", libffi.DEPENDENCY + "\n")):
            path = dependencies / relative
            path.write_text(body)
            with self.subTest(relative=relative), self.assertRaises(libffi.BootstrapError):
                libffi.no_prebuilt(self.root / "data")
            path.unlink()

    def test_cleanup_requires_exact_root_marker_and_preserves_external_files(self):
        libffi.create_owned_root(self.root, self.env)
        outside = self.temp / "unrelated"
        outside.mkdir()
        (outside / "keep").write_text("must survive")
        (self.root / "link").symlink_to(outside, target_is_directory=True)
        marker = self.root / libffi.MARKER
        actual = marker.read_bytes()
        marker.write_text("{}")
        with self.assertRaises(libffi.BootstrapError):
            libffi.cleanup(self.env)
        self.assertTrue(self.root.exists())
        marker.write_bytes(actual)
        libffi.cleanup(self.env)
        self.assertFalse(self.root.exists())
        self.assertEqual((outside / "keep").read_text(), "must survive")
        evidence = libffi.read_json(self.root.parent / libffi.PROVENANCE)
        self.assertEqual(evidence["cleanup"], "complete")
        self.assertEqual(evidence["owner"]["root"], str(self.root))
        libffi.cleanup(self.env)  # A second always-cleanup is harmless.
        with self.assertRaises(libffi.BootstrapError):
            libffi.create_owned_root(self.root, self.env)  # Never overwrite prior evidence.

    def test_cleanup_rejects_symlink_root_and_unrelated_provenance(self):
        outside = self.temp / "unrelated-root"
        outside.mkdir()
        self.root.symlink_to(outside, target_is_directory=True)
        with self.assertRaises(libffi.BootstrapError):
            libffi.cleanup(self.env)
        self.root.unlink()
        self.assertTrue(outside.is_dir())
        libffi.create_owned_root(self.root, self.env)
        evidence = self.root.parent / libffi.PROVENANCE
        evidence.write_text('{"owner": "unrelated"}')
        with self.assertRaises(libffi.BootstrapError):
            libffi.cleanup(self.env)
        self.assertTrue(self.root.exists())
        self.assertEqual(json.loads(evidence.read_text()), {"owner": "unrelated"})

    def test_xcode_tool_preserves_multicall_alias_but_rejects_external_target(self):
        developer = self.temp / "Developer"
        developer.mkdir()
        target = developer / "libtool"
        target.write_text("trusted fixture")
        target.chmod(0o700)
        alias = developer / "ranlib"
        alias.symlink_to(target)
        with mock.patch.object(libffi, "run", return_value=str(alias)):
            self.assertEqual(libffi.xcode_tool("ranlib", developer, {}), alias)
            self.assertNotEqual(alias, target)
            alias.unlink()
            external = self.temp / "outside-libtool"
            external.write_text("outside selected Xcode")
            external.chmod(0o700)
            alias.symlink_to(external)
            with self.assertRaises(libffi.BootstrapError):
                libffi.xcode_tool("ranlib", developer, {})

    def test_command_timeout_reaps_its_owned_process_group(self):
        # Tiny Python processes only. The child writes proof on TERM; its parent
        # reaps it, avoiding even a leftover zombie from this negative test.
        stopped = self.temp / "child-stopped"
        child = ("import pathlib,signal,sys,time\n"
                 "def stop(*args):\n pathlib.Path(sys.argv[1]).write_text('stopped'); sys.exit(0)\n"
                 "signal.signal(signal.SIGTERM,stop)\ntime.sleep(30)\n")
        parent = ("import signal,subprocess,sys,time\n"
                  "child=subprocess.Popen([sys.executable,'-c',sys.argv[1],sys.argv[2]])\n"
                  "def stop(*args):\n child.wait(timeout=3); sys.exit(0)\n"
                  "signal.signal(signal.SIGTERM,stop)\ntime.sleep(30)\n")
        with self.assertRaises(subprocess.TimeoutExpired):
            libffi.run([sys.executable, "-c", parent, child, stopped], env=dict(os.environ), timeout=1)
        self.assertEqual(stopped.read_text(), "stopped")


if __name__ == "__main__":
    unittest.main()
