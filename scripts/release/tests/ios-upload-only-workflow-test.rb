require "minitest/autorun"
require "fileutils"
require "open3"
require "rbconfig"
require "tmpdir"
require "yaml"
require_relative "../../../release/version"

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

  def test_archive_and_ipa_validation_is_a_mandatory_pre_upload_gate
    workflow = YAML.safe_load(TESTFLIGHT_WORKFLOW, aliases: false)
    job = workflow.fetch("jobs").fetch("build-and-upload")
    steps = job.fetch("steps")
    validators = steps.select { |step| step["id"] == "artifact-validation" }
    uploads = steps.select { |step| step["id"] == "testflight-upload" }
    assert_equal 1, validators.length
    assert_equal 1, uploads.length
    validation, upload = validators.first, uploads.first
    assert_equal "bundle exec ruby release/ios/validate_artifacts.rb", validation.fetch("run").strip
    assert_equal "set +x\nbundle exec fastlane ios upload_only", upload.fetch("run").strip
    assert_operator steps.index(validation), :<, steps.index(upload)
    [job, validation, upload].each { |node| refute node.key?("continue-on-error") }
    [validation, upload].each do |step|
      refute step.key?("if"), "validation/upload must retain default success-only step execution"
      refute step.key?("shell"), "validation/upload must use the job's fail-fast shell"
    end
    assert_equal "bash", job.dig("defaults", "run", "shell")
  end

  def test_ios_plist_helpers_use_the_locked_bundle_and_validate_source_before_build_or_secrets
    steps = YAML.safe_load(TESTFLIGHT_WORKFLOW, aliases: false).fetch("jobs").fetch("build-and-upload").fetch("steps")
    validations = steps.select { |step| step["name"] == "Validate committed release configuration" }
    assert_equal 1, validations.length
    validation = validations.first
    assert_equal %w[name run], validation.keys.sort
    assert_equal "bundle exec ruby release/ios/validate_project_configuration.rb", validation.fetch("run")
    ruby_setup = steps.find { |step| step["name"] == "Set up verified Ruby and pinned Fastlane" }
    assert_equal steps.index(ruby_setup) + 1, steps.index(validation)
    [
      "Install verified XcodeGen",
      "Resolve the reviewed SwiftPM lock before protected inputs",
      "Build verified libffi source and select the private Kotlin distribution",
      "Consume the verified Apple Gradle graph before protected inputs",
      "Decode protected files without logging contents"
    ].each do |name|
      boundary = steps.find { |step| step["name"] == name }
      refute_nil boundary, name
      assert_operator steps.index(validation), :<, steps.index(boundary)
    end

    signing = steps.find { |step| step["name"] == "Validate and install signing assets" }
    assert_includes signing.fetch("run").lines.map(&:strip), "bundle exec ruby release/ios/validate_signing_assets.rb"
    assert_includes signing.fetch("run").lines.map(&:strip), "env -u GEM_HOME -u GEM_PATH /usr/bin/ruby release/ios/extract_signing_identity.rb"
    %w[if continue-on-error shell].each { |key| refute signing.key?(key) }
    %w[validate_project_configuration validate_signing_assets validate_artifacts].each do |helper|
      assert_equal 1, TESTFLIGHT_WORKFLOW.scan("bundle exec ruby release/ios/#{helper}.rb").length
      refute_match(/^\s*(?:run: )?ruby release\/ios\/#{helper}\.rb\s*$/, TESTFLIGHT_WORKFLOW)
    end
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
    version = KiraRelease::Version.selected_name(environment: {})
    settings = YAML.safe_load(XCODE_PROJECT, aliases: false).fetch("targets").fetch("iosApp").fetch("settings")
    assert_equal version, settings.fetch("base").fetch("MARKETING_VERSION")
    assert_includes FASTFILE, "MARKETING_VERSION = KiraRelease::Version.selected_name"
    assert_includes RELEASE_CONFIGURATION, 'MARKETING_VERSION = #{version_name}'
    assert_includes RELEASE_CONFIGURATION, 'KIRA_APP_VERSION = #{version_name}'
    assert_includes ARTIFACT_VALIDATOR, "EXPECTED_VERSION = KiraRelease::Version.selected_name"
    assert_includes TESTFLIGHT_WORKFLOW, 'name: kira-ios-${{ env.KIRA_VERSION_NAME }}-${{ env.KIRA_BUILD_NUMBER }}'
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
      version = KiraRelease::Version.selected_name(environment: {})
      assert_includes generated, "MARKETING_VERSION = #{version}"
      assert_includes generated, "KIRA_APP_VERSION = #{version}"
      assert_includes generated, "CURRENT_PROJECT_VERSION = 7"
      assert_includes generated, "KIRA_CRASH_DIAGNOSTICS_ENABLED = NO"
      refute_includes generated, "KIRA_CRASH_DIAGNOSTICS_ENABLED = YES"
    end
  end

  def test_committed_version_requires_one_bounded_canonical_value
    with_version_fixture do |root|
      assert_equal "9.0.1", KiraRelease::Version.selected_name(root: root, environment: {})
      invalid = ["", "VERSION_CODE=1\n", "VERSION_NAME=9.0.1\nVERSION_NAME=9.0.1\n",
                 "VERSION_NAME=09.0.1\n", "VERSION_NAME=9.0.1-beta\n", "VERSION_NAME=9.0.1 \n",
                 "VERSION_NAME=#{'9' * 65}.0.1\n", "#" * (16 * 1024 + 1)]
      invalid.each do |contents|
        File.write(File.join(root, "release/version.properties"), contents)
        assert_raises(KiraRelease::Version::Error) { KiraRelease::Version.selected_name(root: root, environment: {}) }
      end
    end
  end

  def test_explicit_release_inputs_must_agree_without_trimming_or_precedence_bypass
    with_version_fixture do |root|
      selected = KiraRelease::Version::ENVIRONMENT_KEYS.to_h { |key| [key, "9.0.1"] }
      assert_equal "9.0.1", KiraRelease::Version.selected_name(root: root, environment: selected)
      KiraRelease::Version::ENVIRONMENT_KEYS.product(["", " 9.0.1", "9.0.2", "9.0.1+build"]).each do |key, value|
        failure = assert_raises(KiraRelease::Version::Error) do
          KiraRelease::Version.selected_name(root: root, environment: selected.merge(key => value))
        end
        assert_equal "Explicit release version conflicts with the committed version", failure.message
      end
    end
  end

  def test_generated_verifier_requires_exact_current_single_declaration
    with_version_fixture do |root|
      verify = -> { KiraRelease::Version.verify_generated(root: root, environment: {}) }
      assert_raises(KiraRelease::Version::Error, &verify)
      path = File.join(root, KiraRelease::Version::GENERATED_PATH)
      FileUtils.mkdir_p(File.dirname(path))
      current = "  const val APP_VERSION: String = \"9.0.1\"\n"
      File.write(path, current)
      assert_equal "9.0.1", verify.call
      ["", current * 2, current.sub("9.0.1", "9.0.2"), "#" * (64 * 1024 + 1)].each do |contents|
        File.write(path, contents)
        assert_raises(KiraRelease::Version::Error, &verify)
      end
    end
  end

  def test_both_approved_workflows_select_wire_and_marketing_before_build_or_store_preflight
    %w[android-internal-testing testflight].each do |name|
      text = File.read(File.join(ROOT, ".github/workflows/#{name}.yml"))
      steps = YAML.safe_load(text, aliases: false).fetch("jobs").fetch("build-and-upload").fetch("steps")
      selections = steps.select { |step| step["name"] == "Select committed release and source-catalog version" }
      assert_equal 1, selections.length
      selection = selections.first
      assert_equal %w[name run], selection.keys.sort
      assert_includes selection.fetch("run"), 'version_name="$(/usr/bin/ruby release/version.rb)"'
      %w[KIRA_VERSION_NAME KIRA_APP_VERSION].each do |key|
        assert_includes selection.fetch("run"), "printf '#{key}=%s\\n' \"$version_name\""
      end
      assert_includes selection.fetch("run"), '>> "$GITHUB_ENV"'
      boundaries = steps.select { |step| step["run"].to_s.match?(/\.\/gradlew |select-version-code|prepare_testflight/) }
      refute_empty boundaries
      boundaries.each { |step| assert_operator steps.index(selection), :<, steps.index(step) }
      assert_includes text, "ruby release/version.rb verify-generated"
    end
  end

  def test_ios_verifies_generation_before_archive_and_again_after_xcode
    steps = YAML.safe_load(TESTFLIGHT_WORKFLOW, aliases: false).fetch("jobs").fetch("build-and-upload").fetch("steps")
    compilation = steps.find { |step| step["name"] == "Consume the verified Apple Gradle graph before protected inputs" }
    archive = steps.find { |step| step["id"] == "archive" }
    verification = steps.find { |step| step["name"] == "Verify the Xcode-generated source-catalog version" }
    assert_includes compilation.fetch("run"), "ruby release/version.rb verify-generated"
    assert_equal %w[name run], verification.keys.sort
    assert_equal "ruby release/version.rb verify-generated", verification.fetch("run")
    assert_operator steps.index(compilation), :<, steps.index(archive)
    assert_operator steps.index(archive), :<, steps.index(verification)
    assert_operator steps.index(verification), :<, steps.index(steps.find { |step| step["id"] == "testflight-upload" })
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

  def with_version_fixture
    Dir.mktmpdir("kira-release-version") do |root|
      FileUtils.mkdir_p(File.join(root, "release"))
      File.write(File.join(root, "release/version.properties"), "VERSION_NAME=9.0.1\nVERSION_CODE=1\n")
      yield root
    end
  end

  def lane_body(name)
    match = FASTFILE.match(/lane :#{Regexp.escape(name)} do\n(?<body>.*?)(?=^  lane :|^end\s*$)/m)
    refute_nil match, "Fastlane lane #{name} is missing"
    match[:body]
  end
end
