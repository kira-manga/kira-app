# frozen_string_literal: true

require "minitest/autorun"
require "open3"
require "rbconfig"
require_relative "libwebp-notice-fixtures"

class IosLibwebpArtifactsTest < Minitest::Test
  include LibwebpNoticeFixtures
  ENTRYPOINT = File.join(ROOT, "release/ios/validate_artifacts.rb")
  FIXTURE_UUID = "00000000-0000-0000-0000-000000000065"
  APPLICATION_ID = "7CGZ2343AA.me.manga.kira"
  COMMAND_PORTS = %w[codesign lipo otool security dwarfdump ditto].freeze
  SUCCESSOR = 'load ARGV.fetch(0); File.write(ENV.fetch("UPLOAD_SENTINEL"), "fixture successor only\n")'

  # These narrow command-port doubles provide no signing/export evidence. In particular ditto
  # copies an owned directory fixture, not a real IPA. Real ZIP notice coverage lives separately.
  APPLE_COMMAND_DOUBLE = <<~'SH'
    #!/bin/sh
    set -eu
    port="${0##*/}"
    printf '%s\n' "$port" >> "$FAKE_COMMAND_LOG"
    case "$port" in
      codesign)
        [ "$#" -eq 4 ]
        case "$1" in
          --verify) [ "$2" = --deep ]; [ "$3" = --strict ]; [ -d "$4" ] ;;
          -d) [ "$2" = --entitlements ]; [ "$3" = ':-' ]; cat "$FAKE_ENTITLEMENTS" ;;
          *) exit 91 ;;
        esac ;;
      lipo) [ "$#" -eq 2 ]; [ "$1" = -archs ]; printf 'arm64\n' ;;
      otool) [ "$#" -eq 2 ]; [ "$1" = -L ]; printf 'unsigned test fixture:\n' ;;
      security)
        [ "$#" -eq 6 ]; [ "$1" = cms ]; [ "$2" = -D ]; [ "$3" = -i ]
        [ -f "$4" ]; [ "$5" = -o ]; cp "$FAKE_PROFILE" "$6" ;;
      dwarfdump)
        [ "$#" -eq 2 ]; [ "$1" = --uuid ]
        printf 'UUID: 00000000-0000-0000-0000-000000000065 (arm64) fixture\n' ;;
      ditto)
        [ "$#" -eq 4 ]; [ "$1" = -x ]; [ "$2" = -k ]; [ -f "$3" ]
        cp -R "$FAKE_IPA_ROOT/Payload" "$4/" ;;
      *) exit 92 ;;
    esac
  SH

  def test_actual_entrypoint_reaches_successor_only_after_both_notice_bundles_pass
    with_artifact_fixture do |environment|
      output, error, status = run_entrypoint(environment)
      assert status.success?, output + error
      assert File.file?(environment.fetch("UPLOAD_SENTINEL"))
      result = JSON.parse(File.read(environment.fetch("KIRA_ARTIFACT_STATUS_FILE")))
      assert_equal true, result.fetch("libwebp_notices_valid")
      assert_equal 4, command_calls(environment).count("codesign")
      assert_equal 1, command_calls(environment).count("ditto")
      assert_empty Dir.children(environment.fetch("TMPDIR"))
    end
  end

  def test_actual_entrypoint_refuses_missing_archive_notice_before_successor
    with_artifact_fixture do |environment|
      File.unlink(File.join(archive_app(environment), "Settings.bundle/Acknowledgements.plist"))
      assert_entrypoint_refuses(environment)
      assert_empty command_calls(environment), "archive notice refusal must precede signature commands"
    end
  end

  def test_actual_entrypoint_refuses_changed_ipa_notice_even_when_archive_passes
    with_artifact_fixture do |environment|
      path = File.join(environment.fetch("FAKE_IPA_ROOT"), "Payload/Kira.app/Settings.bundle/Acknowledgements.plist")
      rewrite_plist(path) { |child| child.fetch("PreferenceSpecifiers").first["FooterText"] = "incomplete notice" }
      assert_entrypoint_refuses(environment)
      assert_equal 2, command_calls(environment).count("codesign"), "only the archive may reach signature inspection"
      assert_equal 1, command_calls(environment).count("ditto")
    end
  end

  def test_actual_entrypoint_refuses_unreachable_split_archive_link_before_successor
    with_artifact_fixture do |environment|
      path = File.join(archive_app(environment), "Settings.bundle/Root.plist")
      File.binwrite(path, replace_root_link_scalar(File.binread(path), "Acknowledgements<![CDATA[Missing]]>"))
      assert_entrypoint_refuses(environment)
      assert_empty command_calls(environment), "archive notice refusal must precede signature commands"
    end
  end

  def test_actual_entrypoint_refuses_debug_archive_identity_before_signature_or_successor
    with_artifact_fixture do |environment|
      rewrite_plist(File.join(environment.fetch("KIRA_ARCHIVE_PATH"), "Info.plist")) do |info|
        info.fetch("ApplicationProperties")["CFBundleIdentifier"] = "me.manga.kira.debug"
      end
      assert_entrypoint_refuses(environment, reason: "Archive bundle ID is incorrect")
      assert_empty command_calls(environment)
    end
  end

  def test_actual_entrypoint_refuses_debug_ipa_identity_even_when_store_archive_passes
    with_artifact_fixture do |environment|
      path = File.join(environment.fetch("FAKE_IPA_ROOT"), "Payload/Kira.app/Info.plist")
      rewrite_plist(path) { |info| info["CFBundleIdentifier"] = "me.manga.kira.debug" }
      assert_entrypoint_refuses(environment, reason: "Artifact bundle ID is incorrect")
      assert_equal 2, command_calls(environment).count("codesign")
    end
  end

  def test_canonical_bundle_id_does_not_allow_debug_name_or_disabled_services
    {"CFBundleDisplayName" => "Kira Manga Debug", "KiraFirebaseServicesEnabled" => false}.each do |key, value|
      with_artifact_fixture do |environment|
        rewrite_plist(File.join(archive_app(environment), "Info.plist")) { |info| info[key] = value }
        reason = key == "CFBundleDisplayName" ? "Artifact display name" : "Artifact has development service configuration"
        assert_entrypoint_refuses(environment, reason: reason)
        assert_empty command_calls(environment)
      end
    end
  end

  private

  def assert_entrypoint_refuses(environment, reason: "libwebp notices:")
    output, error, status = run_entrypoint(environment)
    refute status.success?, "the actual validator entrypoint must exit nonzero"
    assert_includes output + error, reason
    refute File.exist?(environment.fetch("UPLOAD_SENTINEL"))
    refute File.exist?(environment.fetch("KIRA_ARTIFACT_STATUS_FILE"))
    assert_empty Dir.children(environment.fetch("TMPDIR"))
  end

  def run_entrypoint(environment)
    Open3.capture3(environment, RbConfig.ruby, "-e", SUCCESSOR, ENTRYPOINT, chdir: ROOT)
  end

  def command_calls(environment)
    path = environment.fetch("FAKE_COMMAND_LOG")
    File.file?(path) ? File.readlines(path, chomp: true) : []
  end

  def with_artifact_fixture
    Dir.mktmpdir("kira-libwebp-artifact-test") do |directory|
      environment = artifact_environment(directory)
      FileUtils.mkdir_p(environment.fetch("TMPDIR"))
      install_command_doubles(File.join(directory, "bin"))
      install_app_fixture(archive_app(environment))
      install_app_fixture(File.join(environment.fetch("FAKE_IPA_ROOT"), "Payload/Kira.app"))
      archive = environment.fetch("KIRA_ARCHIVE_PATH")
      write_plist(File.join(archive, "Info.plist"), {"ApplicationProperties" => app_info})
      FileUtils.mkdir_p(File.join(archive, "dSYMs/Kira.app.dSYM"))
      File.write(environment.fetch("KIRA_IPA_PATH"), "not an IPA: ditto uses an owned directory fixture\n")
      write_signing_fixtures(environment)
      File.write(environment.fetch("CRASHLYTICS_DSYM_UPLOAD_MARKER"), "fixture marker only\n")
      yield environment
    end
  end

  def artifact_environment(directory)
    {
      "PATH" => "#{File.join(directory, "bin")}:/usr/bin:/bin", "TMPDIR" => File.join(directory, "tmp"),
      "KIRA_ARCHIVE_PATH" => File.join(directory, "Kira.xcarchive"),
      "KIRA_IPA_PATH" => File.join(directory, "directory-fixture.ipa"), "KIRA_BUILD_NUMBER" => "7",
      "KIRA_PROFILE_UUID_FILE" => File.join(directory, "profile-uuid"),
      "KIRA_APPLICATION_IDENTIFIER_FILE" => File.join(directory, "application-id"),
      "KIRA_ARTIFACT_STATUS_FILE" => File.join(directory, "artifact-status.json"),
      "CRASHLYTICS_DSYM_UPLOAD_MARKER" => File.join(directory, "crashlytics-marker"),
      "UPLOAD_SENTINEL" => File.join(directory, "successor-reached"),
      "FAKE_COMMAND_LOG" => File.join(directory, "command-ports.log"),
      "FAKE_PROFILE" => File.join(directory, "unsigned-profile.plist"),
      "FAKE_ENTITLEMENTS" => File.join(directory, "unsigned-entitlements.plist"),
      "FAKE_IPA_ROOT" => File.join(directory, "prepared-ipa")
    }
  end

  def archive_app(environment)
    File.join(environment.fetch("KIRA_ARCHIVE_PATH"), "Products/Applications/Kira.app")
  end

  def install_command_doubles(directory)
    FileUtils.mkdir_p(directory)
    COMMAND_PORTS.each do |name|
      path = File.join(directory, name)
      File.write(path, APPLE_COMMAND_DOUBLE)
      File.chmod(0o700, path)
    end
  end

  def install_app_fixture(app)
    install_settings(app)
    write_plist(File.join(app, "Info.plist"), app_info)
    write_plist(File.join(app, "GoogleService-Info.plist"), {
      "BUNDLE_ID" => "me.manga.kira", "API_KEY" => "fixture-not-a-key",
      "GOOGLE_APP_ID" => "1:000000000:ios:fixture", "PROJECT_ID" => "notice-test-only"
    })
    write_plist(File.join(app, "PrivacyInfo.xcprivacy"), {"NSPrivacyTracking" => false})
    File.write(File.join(app, "embedded.mobileprovision"), "unsigned fixture profile\n")
    File.write(File.join(app, "Kira"), "notice validator fixture, not executable device code\n")
  end

  def app_info
    {
      "CFBundleIdentifier" => "me.manga.kira", "CFBundleShortVersionString" => "1.0.5",
      "CFBundleDisplayName" => "Kira Manga", "KiraFirebaseServicesEnabled" => true,
      "CFBundleVersion" => "7", "CFBundleExecutable" => "Kira", "KiraAppStoreID" => "6792232678",
      "KiraCrashDiagnosticsEnabled" => false, "ITSAppUsesNonExemptEncryption" => false
    }
  end

  def write_signing_fixtures(environment)
    entitlements = {
      "application-identifier" => APPLICATION_ID, "com.apple.developer.team-identifier" => "7CGZ2343AA",
      "aps-environment" => "production", "com.apple.developer.associated-domains" => ["applinks:kiramanga.me"],
      "get-task-allow" => false, "beta-reports-active" => true
    }
    write_plist(environment.fetch("FAKE_ENTITLEMENTS"), entitlements)
    write_plist(environment.fetch("FAKE_PROFILE"), {
      "UUID" => FIXTURE_UUID, "ExpirationDate" => Time.utc(2099, 1, 1),
      "TeamIdentifier" => ["7CGZ2343AA"], "Entitlements" => entitlements
    })
    File.write(environment.fetch("KIRA_PROFILE_UUID_FILE"), FIXTURE_UUID + "\n")
    File.write(environment.fetch("KIRA_APPLICATION_IDENTIFIER_FILE"), APPLICATION_ID + "\n")
  end
end
