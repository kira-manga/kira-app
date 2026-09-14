# Vendored libwebp 1.5.0

The iOS device and arm64-simulator archives and seven public headers are existing native inputs.
Their source/version attribution is upstream [libwebp v1.5.0](https://github.com/webmproject/libwebp/tree/v1.5.0).
`COPYING` and `PATENTS` retain the complete original public upstream text, without translation or
rewriting. `provenance.json` records that tag, public URLs, the previously recorded source-archive
SHA-256, and the exact hashes of both notices and all nine native files.

These hashes identify observed bytes. Prior inspection of the two recorded archives reported
`WebPGetEncoderVersion=0x010500`; the original build has not been reproduced. This record is not a
publisher attestation, exhaustive dependency/license audit, or blanket legal-compliance claim.

## Recipient notice surface

The host resource is `iosApp/iosApp/Settings.bundle`, at the final app bundle's root. The intended
offline path is **iOS Settings → Apps → Kira Manga → Third-Party Notices** (older iOS omits Apps).
`Root.plist` links to `Acknowledgements` without a path or `.plist` suffix. That child contains two
`PSGroupSpecifier.FooterText` values: the complete COPYING and PATENTS texts, including final
newlines, with libwebp 1.5.0 titles. There is no legal-child `StringsTable`, localized child
override, or iPhone/iPad visibility filter.

Only the navigation label is localized, through native Settings resources for
`en/ar/de/es/fr/id/in/it/ja/pt/ru/tr`; both Indonesian tags are intentional. `en.lproj` supplies the
English default. These labels follow iOS Settings localization, not the app's live Compose
language override. The legal text always remains original English.

`iosApp/project.yml` excludes the bundle from recursive source discovery and adds one explicit
whole-file resource. Repository preflight checks that parsed configuration. Actual XcodeGen
resource-phase membership, built resource conversion, and installed Settings readability still
require Apple-side inspection; source configuration alone does not establish delivery.

## Validation and boundaries

- `release/ios/lib/libwebp_notices.rb` pins the provenance record and checks all native/notice
  hashes at repository preflight. The same helper verifies parsed Settings structure, legal text,
  version, and source-matching navigation labels in both archive and extracted-IPA apps through
  `validate_artifacts.rb`. XML file hashes are not used for compiled plist resources.
- Both local-IPA Fastlane lanes (`upload_only`, `upload_external`) perform notice-only inspection
  before their API, app/group, or upload calls. The adapter lazily uses the existing locked
  RubyZip **2.4.1**, reads only fixed allowlisted notice files with bounded sizes, and cleans its
  owned staging directory. It does not execute archive contents or replace signature checks.
- This is not general ZIP security, candidate binding, or upload authorization (App108).
  `finalize_external` has no local IPA and cannot retrospectively prove notices in remote builds.
  Mobile Release Kit remains separate identity-blocked shadow configuration; its artifact hooks
  are not enabled or validated by this change. Existing workflow secret preparation and
  Crashlytics upload precede the final artifact gate; it is not a before-all-external-effects gate.

## Updating

1. Establish the new upstream version and exact native-input attribution before changing pins;
   never relabel changed archives. Obtain the corresponding complete COPYING/PATENTS and record
   public origin/tag/source-archive digest. Do not rebuild unchanged native inputs merely for a
   notice-only edit.
2. Review `provenance.json`, the helper's version/provenance pin, and the Settings child titles
   and legal footers together. Preserve exact legal text, localization, and whole-bundle wiring.
   Do not automatically regenerate acceptance pins from whatever files happen to be present.
3. In a prepared Ruby/Minitest environment, run `release/ios/validate_project_configuration.rb`,
   the four `scripts/release/tests/ios-libwebp-*-test.rb` files, and
   `scripts/release/tests/ios-upload-only-workflow-test.rb`. ZIP/lane tests require the exact locked
   RubyZip, not a substitute or stub. Binary-plist conversion coverage explicitly requires macOS.
4. Before distribution, inspect actual generated resources, built/archive apps and the exact
   exported IPA, then open both full notices on the installed build (including localization,
   large text and iPad). Follow `docs/release/INTERNAL_RELEASE_QA.md`; fixture command doubles are
   not real signing, export, device, TestFlight or Store verification.
