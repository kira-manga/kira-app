# frozen_string_literal: true

require "minitest/autorun"
require "open3"
require "rbconfig"
require "yaml"
require_relative "libwebp-notice-fixtures"

class IosDebugIsolationTest < Minitest::Test
  include LibwebpNoticeFixtures

  def test_actual_configuration_validator_accepts_isolated_debug_and_unchanged_store_configuration
    with_configuration do |root|
      output, error, status = validate(root)
      assert status.success?, output + error
      assert_includes output, "Ordinary Debug isolation verified"
    end
  end

  def test_release_override_to_debug_identity_is_rejected_even_when_base_identity_is_canonical
    with_configuration do |root|
      change_project(root) { |text| text.sub("        Release:\n", "        Release:\n          PRODUCT_BUNDLE_IDENTIFIER: me.manga.kira.debug\n") }
      assert_refused(root, "Release must use only the canonical Store bundle ID")
    end
  end

  def test_debug_cannot_inherit_store_identity_team_plist_or_entitlements
    {
      "PRODUCT_BUNDLE_IDENTIFIER: me.manga.kira.debug" => "PRODUCT_BUNDLE_IDENTIFIER: me.manga.kira",
      'DEVELOPMENT_TEAM: ""' => "DEVELOPMENT_TEAM: 7CGZ2343AA",
      "INFOPLIST_FILE: iosApp/Info-Debug.plist" => "INFOPLIST_FILE: iosApp/Info.plist",
      "CODE_SIGN_ENTITLEMENTS: iosApp/iosApp-nopush.entitlements" => "CODE_SIGN_ENTITLEMENTS: iosApp/iosApp.entitlements"
    }.each do |old, replacement|
      with_configuration do |root|
        change_project(root) { |text| text.sub(old, replacement) }
        assert_refused(root, "Debug must")
      end
    end
  end

  def test_debug_cannot_claim_production_urls_or_turn_services_back_on
    ["CFBundleURLTypes", "KiraFirebaseServicesEnabled", "FirebaseMessagingAutoInitEnabled"].each do |key|
      with_configuration do |root|
        rewrite_plist(File.join(root, "iosApp/iosApp/Info-Debug.plist")) do |plist|
          plist[key] = key == "CFBundleURLTypes" ? [{"CFBundleURLSchemes" => ["kiramanga"]}] : true
        end
        assert_refused(root, "Debug")
      end
    end
  end

  def test_real_debug_copy_script_needs_no_firebase_file_and_removes_stale_store_configuration
    with_copy_environment do |environment, source, destination|
      refute File.exist?(source)
      FileUtils.mkdir_p(File.dirname(destination))
      File.write(destination, "stale synthetic Store config")
      output, error, status = run_copy(environment.merge("CONFIGURATION" => "Debug"))
      assert status.success?, output + error
      refute File.exist?(destination)
    end
  end

  def test_real_release_copy_script_requires_and_preserves_the_selected_store_plist_bytes
    with_copy_environment do |environment, source, destination|
      _output, _error, status = run_copy(environment.merge("CONFIGURATION" => "Release"))
      refute status.success?, "Release must refuse a missing Store plist"
      FileUtils.mkdir_p(File.dirname(source))
      File.write(source, "synthetic Store plist bytes\n")
      output, error, status = run_copy(environment.merge("CONFIGURATION" => "Release"))
      assert status.success?, output + error
      assert_equal File.binread(source), File.binread(destination)
    end
  end

  private

  def with_configuration
    with_source_fixture do |root|
      FileUtils.mkdir_p(File.join(root, "release/ios"))
      FileUtils.cp(File.join(ROOT, "release/ios/validate_project_configuration.rb"), File.join(root, "release/ios"))
      FileUtils.cp_r(File.join(ROOT, "release/ios/lib"), File.join(root, "release/ios"))
      %w[Info.plist Info-Debug.plist iosApp.entitlements iosApp-nopush.entitlements].each do |name|
        FileUtils.cp(File.join(ROOT, "iosApp/iosApp", name), File.join(root, "iosApp/iosApp", name))
      end
      yield root
    end
  end

  def change_project(root)
    path = File.join(root, "iosApp/project.yml")
    original = File.read(path)
    modified = yield original
    refute_equal original, modified
    File.write(path, modified)
  end

  def validate(root)
    Open3.capture3(RbConfig.ruby, File.join(root, "release/ios/validate_project_configuration.rb"))
  end

  def assert_refused(root, reason)
    output, error, status = validate(root)
    refute status.success?
    assert_includes output + error, reason
  end

  def with_copy_environment
    Dir.mktmpdir("kira-debug-firebase-copy") do |directory|
      source_root = File.join(directory, "source")
      target_root = File.join(directory, "built")
      environment = {"SRCROOT" => source_root, "TARGET_BUILD_DIR" => target_root, "UNLOCALIZED_RESOURCES_FOLDER_PATH" => "Kira.app"}
      yield environment, File.join(source_root, "iosApp/GoogleService-Info.plist"), File.join(target_root, "Kira.app/GoogleService-Info.plist")
    end
  end

  def run_copy(environment)
    specification = YAML.safe_load(File.read(File.join(ROOT, "iosApp/project.yml")), aliases: false)
    script = specification.fetch("targets").fetch("iosApp").fetch("postBuildScripts")
      .find { |entry| entry["name"] == "Copy Store Firebase configuration (Release only)" }.fetch("script")
    Open3.capture3(environment, "/bin/sh", "-c", script)
  end
end
