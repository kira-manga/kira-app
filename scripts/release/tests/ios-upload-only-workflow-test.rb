require "minitest/autorun"
require "fileutils"
require "open3"
require "rbconfig"
require "tmpdir"

class IosUploadOnlyWorkflowTest < Minitest::Test
  ROOT = File.expand_path("../../..", __dir__)
  FASTFILE = File.read(File.join(ROOT, "fastlane/Fastfile"))
  TESTFLIGHT_WORKFLOW = File.read(File.join(ROOT, ".github/workflows/testflight.yml"))
  CI_WORKFLOW = File.read(File.join(ROOT, ".github/workflows/ci.yml"))
  RELEASE_CONFIGURATION = File.read(File.join(ROOT, "release/ios/write_release_configuration.rb"))
  ARTIFACT_VALIDATOR = File.read(File.join(ROOT, "release/ios/validate_artifacts.rb"))
  XCODE_PROJECT = File.read(File.join(ROOT, "iosApp/project.yml"))
  CRASHLYTICS_UPLOADER = File.join(ROOT, "release/ios/upload_crashlytics_dsyms.rb")

  def test_upload_only_lane_cannot_distribute_or_submit_review
    lane = lane_body("upload_only")

    assert_includes lane, "distribute_external: false"
    assert_includes lane, "submit_beta_review: false"
    assert_includes lane, "notify_external_testers: false"
    refute_includes lane, "distribute_external: true"
    refute_includes lane, "submit_beta_review: true"
  end

  def test_testflight_workflow_uses_upload_only_lane
    assert_includes TESTFLIGHT_WORKFLOW, "bundle exec fastlane ios upload_only"
    refute_includes TESTFLIGHT_WORKFLOW, "bundle exec fastlane ios upload_external"
    refute_includes TESTFLIGHT_WORKFLOW, "bundle exec fastlane ios finalize_external"
  end

  def test_preflight_does_not_create_external_groups
    refute_includes lane_body("prepare_testflight"), "ensure_testflight_groups"
  end

  def test_production_candidate_disables_internal_crash_diagnostics
    assert_includes RELEASE_CONFIGURATION, "KIRA_CRASH_DIAGNOSTICS_ENABLED = NO"
    refute_includes RELEASE_CONFIGURATION, "KIRA_CRASH_DIAGNOSTICS_ENABLED = YES"
    assert_includes ARTIFACT_VALIDATOR, "Production candidate exposes internal crash diagnostics"
    assert_includes ARTIFACT_VALIDATOR, "crash_diagnostics_disabled: true"
  end

  def test_release_version_is_consistent
    assert_includes FASTFILE, 'MARKETING_VERSION = "1.0.5"'
    assert_includes RELEASE_CONFIGURATION, "MARKETING_VERSION = 1.0.5"
    assert_includes ARTIFACT_VALIDATOR, 'EXPECTED_VERSION = "1.0.5"'
    assert_includes TESTFLIGHT_WORKFLOW, "name: kira-ios-1.0.5-"
  end

  def test_generated_release_xcconfig_disables_diagnostics
    Dir.mktmpdir("kira-ios-release-config") do |directory|
      build_number_file = File.join(directory, "build-number")
      profile_uuid_file = File.join(directory, "profile-uuid")
      xcconfig_path = File.join(directory, "release.xcconfig")
      export_options_path = File.join(directory, "ExportOptions.plist")
      File.write(build_number_file, "7\n")
      File.write(profile_uuid_file, "00000000-0000-0000-0000-000000000007\n")

      environment = {
        "KIRA_BUILD_NUMBER_FILE" => build_number_file,
        "KIRA_PROFILE_UUID_FILE" => profile_uuid_file,
        "KIRA_KEYCHAIN_PATH" => File.join(directory, "release.keychain-db"),
        "KIRA_XCCONFIG_PATH" => xcconfig_path,
        "KIRA_EXPORT_OPTIONS_PATH" => export_options_path
      }
      script = File.join(ROOT, "release/ios/write_release_configuration.rb")
      assert system(environment, RbConfig.ruby, script, out: File::NULL)

      generated = File.read(xcconfig_path)
      assert_includes generated, "MARKETING_VERSION = 1.0.5"
      assert_includes generated, "CURRENT_PROJECT_VERSION = 7"
      assert_includes generated, "KIRA_CRASH_DIAGNOSTICS_ENABLED = NO"
      refute_includes generated, "KIRA_CRASH_DIAGNOSTICS_ENABLED = YES"
    end
  end

  def test_ci_and_testflight_receive_source_authority_configuration
    [CI_WORKFLOW, TESTFLIGHT_WORKFLOW].each do |workflow|
      assert_includes workflow, "KIRA_SOURCE_CONFIG_BASE_URL: ${{ vars.KIRA_SOURCE_CONFIG_BASE_URL }}"
      assert_includes workflow, "KIRA_SOURCE_CONFIG_PINNED_KEYS: ${{ vars.KIRA_SOURCE_CONFIG_PINNED_KEYS }}"
    end


    assert_includes TESTFLIGHT_WORKFLOW, '"$base_url/api/v1/source-config/document"'
    assert_includes TESTFLIGHT_WORKFLOW, "x-config-signing-key-id"
  end

  def test_testflight_defers_crashlytics_only_to_a_mandatory_post_archive_gate
    assert_includes TESTFLIGHT_WORKFLOW, 'CRASHLYTICS_DSYM_UPLOAD_DEFERRED: "1"'
    assert_includes TESTFLIGHT_WORKFLOW, 'app_dsym="$KIRA_ARCHIVE_PATH/dSYMs/Kira.app.dSYM"'
    assert_includes TESTFLIGHT_WORKFLOW, '"$upload_tool" "$KIRA_FIREBASE_PLIST" "$app_dsym"'
    assert_includes TESTFLIGHT_WORKFLOW, 'if [ ! -f "$CRASHLYTICS_DSYM_UPLOAD_MARKER" ]'
    refute_includes TESTFLIGHT_WORKFLOW, "CRASHLYTICS_DSYM_UPLOAD_OPTIONAL=1"

    archive_position = TESTFLIGHT_WORKFLOW.index("Archive the signed App Store build")
    crashlytics_position = TESTFLIGHT_WORKFLOW.index("Upload and confirm the archived app dSYM")
    export_position = TESTFLIGHT_WORKFLOW.index("Export the signed IPA")
    assert_operator archive_position, :<, crashlytics_position
    assert_operator crashlytics_position, :<, export_position

    assert_includes XCODE_PROJECT, 'if [ "${CRASHLYTICS_DSYM_UPLOAD_DEFERRED:-0}" = "1" ]'
    assert_includes XCODE_PROJECT, 'if [ "${CI:-false}" != "true" ]'
    assert_includes XCODE_PROJECT, 'DSYM_PATH="${DWARF_DSYM_FOLDER_PATH}/${DWARF_DSYM_FILE_NAME}"'
    refute_includes XCODE_PROJECT, '"$UPLOAD" "$GSP" "$DSYM_ROOT"'
  end

  def test_crashlytics_uploader_accepts_one_explicit_app_dsym
    Dir.mktmpdir("kira-crashlytics-uploader") do |directory|
      bin_directory = File.join(directory, "bin")
      dsym_path = File.join(directory, "Kira.app.dSYM")
      plist_path = File.join(directory, "GoogleService-Info.plist")
      marker_path = File.join(directory, "uploaded")
      arguments_path = File.join(directory, "arguments")
      upload_tool = File.join(directory, "upload-symbols")
      FileUtils.mkdir_p(bin_directory)
      FileUtils.mkdir_p(dsym_path)
      File.write(plist_path, "fixture\n")
      File.write(
        File.join(bin_directory, "dwarfdump"),
        "#!/bin/sh\nprintf 'UUID: 00000000-0000-0000-0000-000000000001 (arm64) fixture\\n'\n"
      )
      File.write(
        upload_tool,
        "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$FAKE_ARGS_PATH\"\n" \
          "printf 'Successfully uploaded Crashlytics symbols\\n'\n"
      )
      FileUtils.chmod(0o700, File.join(bin_directory, "dwarfdump"))
      FileUtils.chmod(0o700, upload_tool)

      environment = {
        "PATH" => "#{bin_directory}:#{ENV.fetch("PATH")}",
        "CRASHLYTICS_DSYM_UPLOAD_MARKER" => marker_path,
        "FAKE_ARGS_PATH" => arguments_path
      }
      _output, error, status = Open3.capture3(
        environment,
        RbConfig.ruby,
        CRASHLYTICS_UPLOADER,
        upload_tool,
        plist_path,
        dsym_path
      )
      assert status.success?, error
      assert File.file?(marker_path)
      assert_includes File.readlines(arguments_path, chomp: true), dsym_path
    end
  end

  private

  def lane_body(name)
    match = FASTFILE.match(/lane :#{Regexp.escape(name)} do\n(?<body>.*?)(?=^  lane :|^end\s*$)/m)
    refute_nil match, "Fastlane lane #{name} is missing"
    match[:body]
  end
end
