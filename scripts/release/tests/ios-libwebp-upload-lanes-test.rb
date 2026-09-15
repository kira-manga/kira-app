# frozen_string_literal: true

require "minitest/autorun"
require_relative "libwebp-notice-fixtures"

class IosLibwebpUploadLanesTest < Minitest::Test
  include LibwebpNoticeFixtures
  FASTFILE_PATH = File.join(ROOT, "fastlane/Fastfile")
  CHILD = "Payload/Kira.app/Settings.bundle/Acknowledgements.plist"
  LANES = %i[upload_only upload_external].freeze

  # Evaluate the entire production Fastfile, but provide no Fastlane/Spaceship/network runtime.
  # Only API, app lookup, group mutation and upload boundaries become instance-level spies.
  class FastfileHarness
    module UI
      def self.user_error!(message)
        raise ArgumentError, message
      end

      def self.message(_message); end
      def self.success(_message); end
    end

    class << self
      attr_reader :lanes

      def opt_out_usage; end
      def default_platform(_name); end
      def desc(_description); end

      def platform(name, &body)
        @platform = name
        class_eval(&body)
      end

      def lane(name, &body)
        @lanes ||= {}
        @lanes[[@platform, name]] = body
      end
    end

    attr_reader :calls, :uploads
    attr_accessor :artifact_inspection_failure

    def initialize
      @calls = []
      @uploads = []
      define_singleton_method(:connect_api_key) { @calls << :api; :fixture_api_key }
      define_singleton_method(:verified_app) { @calls << :app; :fixture_app }
      define_singleton_method(:ensure_testflight_groups) { |app| @calls << [:groups, app] }
      define_singleton_method(:upload_to_testflight) do |**options|
        @calls << :upload
        @uploads << options
      end
      define_singleton_method(:sh) do |*command|
        @calls << [:artifact_inspection, command]
        raise @artifact_inspection_failure if @artifact_inspection_failure
      end
      define_singleton_method(:upload_to_play_store) do |**options|
        @calls << :upload
        @uploads << options
      end
    end

    def run_lane(name, platform: :ios)
      instance_exec(&self.class.lanes.fetch([platform, name]))
    end
  end

  FastfileHarness.class_eval(File.read(FASTFILE_PATH), FASTFILE_PATH, 1)

  def test_real_upload_only_lane_preserves_non_distributing_options
    with_ipa do |path, directory|
      harness = FastfileHarness.new
      with_lane_environment(path) { harness.run_lane(:upload_only) }
      assert_equal [:api, :app, :upload], harness.calls
      assert_equal [common_upload_options(path).merge(
        distribute_external: false, submit_beta_review: false, notify_external_testers: false
      )], harness.uploads
      assert_equal ["candidate.ipa"], Dir.children(directory)
    end
  end

  def test_real_upload_external_lane_preserves_review_and_group_options
    with_ipa do |path, directory|
      harness = FastfileHarness.new
      with_lane_environment(path) { harness.run_lane(:upload_external) }
      assert_equal [:api, :app, [:groups, :fixture_app], :upload], harness.calls
      assert_equal [external_upload_options(path)], harness.uploads
      assert_equal ["candidate.ipa"], Dir.children(directory)
    end
  end

  def test_missing_notice_blocks_both_real_lanes_before_any_api_or_upload_call
    entries = ipa_entries
    entries.delete(CHILD)
    assert_both_lanes_refuse(entries)
  end

  def test_tampered_notice_blocks_both_real_lanes_before_any_api_or_upload_call
    entries = ipa_entries
    entries[CHILD] = entries.fetch(CHILD).sub("AS IS", "CHANGED")
    assert_both_lanes_refuse(entries)
  end

  def test_split_root_link_blocks_both_real_lanes_before_any_api_or_upload_call
    entries = ipa_entries
    root = "Payload/Kira.app/Settings.bundle/Root.plist"
    entries[root] = replace_root_link_scalar(entries.fetch(root), "Acknowledgements<![CDATA[Missing]]>")
    assert_both_lanes_refuse(entries)
  end

  def test_existing_local_ipa_and_build_number_guards_still_precede_api_calls
    with_ipa do |path, _directory|
      LANES.product([{"KIRA_IPA_PATH" => path + ".missing"}, {"KIRA_BUILD_NUMBER" => "0"}]).each do |lane, change|
        harness = FastfileHarness.new
        with_lane_environment(path) do
          with_environment(change) { assert_raises(ArgumentError) { harness.run_lane(lane) } }
        end
        assert_empty harness.calls
        assert_empty harness.uploads
      end
    end
  end

  def test_android_upload_inspects_the_exact_selected_aab_before_the_uploader
    with_android_lane_environment do |path|
      harness = FastfileHarness.new
      harness.run_lane(:upload_internal, platform: :android)
      command = [File.join(ROOT, "gradlew"), "--project-dir", ROOT, ":app:verifyReleaseBundleIdentity",
                 "-Pkira.releaseBundleToVerify=#{path}"]
      assert_equal [[:artifact_inspection, command], :upload], harness.calls
      assert_equal path, harness.uploads.fetch(0).fetch(:aab)
      assert_equal "me.manga.kira", harness.uploads.fetch(0).fetch(:package_name)
    end
  end

  def test_android_binary_identity_refusal_prevents_any_upload
    with_android_lane_environment do |_path|
      harness = FastfileHarness.new
      harness.artifact_inspection_failure = ArgumentError.new("fixture inspector refusal: development AAB")
      assert_raises(ArgumentError) { harness.run_lane(:upload_internal, platform: :android) }
      assert_equal [:artifact_inspection], harness.calls.map(&:first)
      assert_empty harness.uploads
    end
  end

  private

  # Only the task/uploader boundary is doubled here. Binary protobuf rejection is exercised by
  # :app:testReleaseBundleIdentity against the same inspector used by the production task.
  def with_android_lane_environment
    Dir.mktmpdir("kira-android-upload-guard") do |directory|
      path = File.join(directory, "selected candidate.aab")
      mapping = File.join(directory, "mapping.txt")
      File.write(path, "command-boundary fixture; not a signed AAB")
      File.write(mapping, "fixture")
      with_environment({
        "KIRA_ANDROID_AAB_PATH" => path, "KIRA_ANDROID_MAPPING_PATH" => mapping,
        "KIRA_BUILD_NUMBER" => "7", "KIRA_VERSION_NAME" => "1.0.5",
        "GOOGLE_PLAY_SERVICE_ACCOUNT_JSON" => "fixture-not-credentials"
      }) { yield path }
    end
  end

  def assert_both_lanes_refuse(entries)
    with_ipa(entries) do |path, directory|
      LANES.each do |lane|
        harness = FastfileHarness.new
        with_lane_environment(path) do
          error = assert_raises(NOTICES::Error) { harness.run_lane(lane) }
          assert_match(/libwebp notices:/, error.message)
        end
        assert_empty harness.calls
        assert_empty harness.uploads
        assert_equal ["candidate.ipa"], Dir.children(directory)
      end
    end
  end

  def with_lane_environment(path, &body)
    with_environment({
      "KIRA_IPA_PATH" => path, "KIRA_BUILD_NUMBER" => "7",
      "TESTFLIGHT_FEEDBACK_EMAIL" => "feedback@notice-fixture.invalid",
      "BETA_REVIEW_CONTACT_FIRST_NAME" => "Notice", "BETA_REVIEW_CONTACT_LAST_NAME" => "Fixture",
      "BETA_REVIEW_CONTACT_EMAIL" => "review@notice-fixture.invalid",
      "BETA_REVIEW_CONTACT_PHONE" => "+12025550123"
    }, &body)
  end

  def common_upload_options(path)
    {
      api_key: :fixture_api_key, apple_id: "6792232678", app_identifier: "me.manga.kira",
      app_platform: "ios", app_version: "1.0.5", build_number: "7", ipa: path,
      skip_waiting_for_build_processing: false, wait_processing_interval: 30,
      wait_processing_timeout_duration: 3_600, uses_non_exempt_encryption: false,
      expire_previous_builds: false
    }
  end

  def external_upload_options(path)
    common_upload_options(path).merge(
      distribute_external: true, groups: ["External Testing"], submit_beta_review: true,
      notify_external_testers: true, reject_build_waiting_for_review: false,
      beta_app_review_info: {
        contact_first_name: "Notice", contact_last_name: "Fixture",
        contact_email: "review@notice-fixture.invalid", contact_phone: "+12025550123",
        demo_account_required: false, notes: beta_text("beta-review-notes.txt")
      },
      localized_app_info: {"en-US" => {
        feedback_email: "feedback@notice-fixture.invalid", marketing_url: "https://kiramanga.me",
        privacy_policy_url: "https://kiramanga.me/privacy", description: beta_text("beta-description.txt")
      }},
      localized_build_info: {"en-US" => {whats_new: beta_text("what-to-test.txt")}}
    )
  end

  def beta_text(name)
    File.read(File.join(ROOT, "release/testflight", name)).strip
  end
end
