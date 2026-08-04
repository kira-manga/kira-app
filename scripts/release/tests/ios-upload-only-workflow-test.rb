require "minitest/autorun"

class IosUploadOnlyWorkflowTest < Minitest::Test
  ROOT = File.expand_path("../../..", __dir__)
  FASTFILE = File.read(File.join(ROOT, "fastlane/Fastfile"))
  TESTFLIGHT_WORKFLOW = File.read(File.join(ROOT, ".github/workflows/testflight.yml"))
  CI_WORKFLOW = File.read(File.join(ROOT, ".github/workflows/ci.yml"))

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

  def test_ci_and_testflight_receive_source_authority_configuration
    [CI_WORKFLOW, TESTFLIGHT_WORKFLOW].each do |workflow|
      assert_includes workflow, "KIRA_SOURCE_CONFIG_BASE_URL: ${{ vars.KIRA_SOURCE_CONFIG_BASE_URL }}"
      assert_includes workflow, "KIRA_SOURCE_CONFIG_PINNED_KEYS: ${{ vars.KIRA_SOURCE_CONFIG_PINNED_KEYS }}"
    end


    assert_includes TESTFLIGHT_WORKFLOW, '"$base_url/api/v1/source-config/document"'
    assert_includes TESTFLIGHT_WORKFLOW, "x-config-signing-key-id"
  end

  private

  def lane_body(name)
    match = FASTFILE.match(/lane :#{Regexp.escape(name)} do\n(?<body>.*?)(?=^  lane :|^end\s*$)/m)
    refute_nil match, "Fastlane lane #{name} is missing"
    match[:body]
  end
end
