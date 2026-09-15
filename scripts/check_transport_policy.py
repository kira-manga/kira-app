#!/usr/bin/env python3
"""Check the zero-exception source policy, not merged APK/IPA or socket enforcement.

Only the owned app's standard app/src manifests/resources and shipping iOS plist
are checked. New manifest/resource overrides require review; this is not a Gradle
resource merger. --self-test also runs small isolated restored-policy controls.
"""
import argparse
from pathlib import Path
import plistlib
import sys
import tempfile
import unittest
from xml.etree import ElementTree as ET
from xml.parsers.expat import ExpatError

ANDROID = "{http://schemas.android.com/apk/res/android}"
MANIFEST = Path("app/src/main/AndroidManifest.xml")
POLICY = Path("app/src/main/res/xml/network_security_config.xml")
INFO = Path("iosApp/iosApp/Info.plist")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def xml(path):
    try:
        return ET.parse(path).getroot()
    except (OSError, ET.ParseError) as error:
        raise ValueError(f"Cannot parse XML: {path}") from error


def check(root):
    source = root / "app/src"
    require(not source.parent.is_symlink() and not source.is_symlink()
            and not any(p.is_symlink() for p in source.rglob("*")),
            "Symlinked app inputs require transport-policy review")
    require(sorted(source.rglob("AndroidManifest.xml")) == [root / MANIFEST],
            "Missing main manifest or additional app manifest requires policy review")
    manifest = xml(root / MANIFEST)
    applications = manifest.findall("application")
    require(manifest.tag == "manifest" and len(applications) == 1,
            "Expected exactly one Android application")
    application = applications[0]
    require(application.get(ANDROID + "networkSecurityConfig") == "@xml/network_security_config",
            "Android application must select @xml/network_security_config")
    require(ANDROID + "usesCleartextTraffic" not in application.attrib,
            "Do not replace or override the explicit network security policy")

    require(sorted(source.rglob("network_security_config.xml")) == [root / POLICY],
            "Missing policy or variant/qualified policy replacement requires review")
    policy = xml(root / POLICY)
    require(policy.tag == "network-security-config" and not policy.attrib and len(policy) == 1,
            "Expected only the explicit Android deny baseline")
    base = policy[0]
    require(base.tag == "base-config" and base.attrib == {"cleartextTrafficPermitted": "false"}
            and len(base) == 0 and all(not (text or "").strip()
                                      for text in (policy.text, base.text, base.tail)),
            "Android policy must deny cleartext without domain/debug/trust overrides")
    for path in source.rglob("*.xml"):
        if path.parent.name.startswith("values") and path.parent.parent.name == "res":
            require(not any(element.get("name") == "network_security_config"
                            for element in xml(path).iter()),
                    f"Network policy resource alias requires review: {path}")

    require(not (root / INFO).is_symlink(), "Symlinked iOS plist requires policy review")
    try:
        info = plistlib.loads((root / INFO).read_bytes())
    except (OSError, ValueError, plistlib.InvalidFileException, ExpatError) as error:
        raise ValueError("Cannot parse shipping iOS Info.plist") from error
    require(isinstance(info, dict) and all(isinstance(key, str) for key in info),
            "Expected an iOS plist dictionary")
    require(not any(key.startswith("NSAppTransportSecurity") for key in info),
            "iOS ATS overrides, including platform/device-qualified keys, require review")


class PolicyControls(unittest.TestCase):
    manifest = (f'<manifest xmlns:android="{ANDROID[1:-1]}">'
                '<application android:networkSecurityConfig="@xml/network_security_config" />'
                '</manifest>')
    policy = ('<network-security-config>'
              '<base-config cleartextTrafficPermitted="false" />'
              '</network-security-config>')

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="kira-transport-policy-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.put(MANIFEST, self.manifest)
        self.put(POLICY, self.policy)
        self.put(INFO, plistlib.dumps({"CFBundleIdentifier": "policy.control"}))

    def put(self, path, data):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data.encode() if isinstance(data, str) else data)

    def test_explicit_deny_without_overrides(self):
        check(self.root)

    def test_restored_android_allow_and_extra_rules(self):
        rules = [
            '<base-config cleartextTrafficPermitted="true" />',
            '<domain-config cleartextTrafficPermitted="true">'
            '<domain includeSubdomains="true">raijinscan.co</domain></domain-config>',
            '<base-config cleartextTrafficPermitted="false" />'
            '<domain-config cleartextTrafficPermitted="true"><domain>raijinscan.co</domain></domain-config>',
            '<base-config cleartextTrafficPermitted="false"><trust-anchors /></base-config>',
            '<base-config cleartextTrafficPermitted="false" /><debug-overrides />',
        ]
        for rule in rules:
            with self.subTest(rule=rule), self.assertRaises(ValueError):
                self.put(POLICY, f"<network-security-config>{rule}</network-security-config>")
                check(self.root)

    def test_changed_or_missing_manifest_association(self):
        for association in ("", 'android:networkSecurityConfig="@xml/other"',
                            'android:usesCleartextTraffic="false"'):
            with self.subTest(association=association), self.assertRaises(ValueError):
                self.put(MANIFEST, self.manifest.replace(
                    'android:networkSecurityConfig="@xml/network_security_config"', association))
                check(self.root)

    def test_variant_and_qualified_replacements(self):
        for path, data in [
            ("app/src/release/AndroidManifest.xml", self.manifest),
            ("app/src/release/res/xml/network_security_config.xml", self.policy),
            ("app/src/main/res/xml-v26/network_security_config.xml", self.policy),
        ]:
            with self.subTest(path=path):
                self.put(path, data)
                try:
                    with self.assertRaises(ValueError):
                        check(self.root)
                finally:
                    (self.root / path).unlink()

    def test_resource_alias(self):
        self.put("app/src/main/res/values-v26/policy.xml",
                 '<resources><item name="network_security_config" type="xml">@xml/allow</item></resources>')
        with self.assertRaises(ValueError):
            check(self.root)

    def test_restored_or_qualified_ios_overrides(self):
        for key, value in [
            ("NSAppTransportSecurity", {"NSExceptionDomains": {"raijinscan.co": {
                "NSExceptionAllowsInsecureHTTPLoads": True, "NSIncludesSubdomains": True}}}),
            ("NSAppTransportSecurity", {"NSAllowsArbitraryLoads": True}),
            ("NSAppTransportSecurity", {}),
            ("NSAppTransportSecurity~ipad", {"NSAllowsArbitraryLoads": True}),
        ]:
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                self.put(INFO, plistlib.dumps({key: value}))
                check(self.root)

    def test_malformed_inputs(self):
        for path in (MANIFEST, POLICY, INFO):
            with self.subTest(path=path):
                original = (self.root / path).read_bytes()
                try:
                    self.put(path, "<broken>")
                    with self.assertRaises(ValueError):
                        check(self.root)
                finally:
                    self.put(path, original)
        self.put(INFO, plistlib.dumps([]))
        with self.assertRaises(ValueError):
            check(self.root)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true", help="also exercise isolated regression controls")
    options = parser.parse_args()
    if options.self_test:
        result = unittest.TextTestRunner().run(unittest.defaultTestLoader.loadTestsFromTestCase(PolicyControls))
        if not result.wasSuccessful():
            return 1
    try:
        check(Path(__file__).resolve().parents[1])
    except ValueError as error:
        print(f"FAIL transport source policy: {error}", file=sys.stderr)
        return 1
    print("PASS transport source policy: Android explicit deny, iOS no ATS override (not packaged/runtime proof)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
