require "minitest/autorun"
require "yaml"
require "open3"
require "tmpdir"
require "fileutils"

class MobileTestingWorkflowTest < Minitest::Test
  ROOT = File.expand_path("../../..", __dir__)

  def workflow(name)
    YAML.safe_load(File.read(File.join(ROOT, ".github/workflows/#{name}.yml")), aliases: false)
  end

  def test_both_uploads_require_the_same_successful_checks
    jobs = workflow("internal-testing-release").fetch("jobs")
    assert_equal "./.github/workflows/mobile-release-checks.yml", jobs.fetch("checks").fetch("uses")
    %w[android ios].each do |platform|
      job = jobs.fetch(platform)
      assert_equal %w[validate-request checks], job.fetch("needs")
      refute job.key?("continue-on-error")
      refute_match(/always\(|failure\(/, job.fetch("if"))
      assert_includes job.fetch("if"), "inputs.platform == 'both'"
      assert_includes job.fetch("if"), "[skip both]"
    end
  end

  def test_only_the_parent_can_trigger_store_workflows
    %w[android-internal-testing testflight].each do |name|
      child = workflow(name)
      assert_equal ["workflow_call"], (child["on"] || child[true]).keys
      condition = child.fetch("jobs").fetch("build-and-upload").fetch("if")
      assert_includes condition, "github.workflow == 'Internal Testing Release'"
      assert_includes condition, "github.ref == 'refs/heads/internal-testing'"
    end
    parent = workflow("internal-testing-release")
    assert_equal ["internal-testing"], (parent["on"] || parent[true]).dig("push", "branches")
    assert_equal "both", (parent["on"] || parent[true]).dig("workflow_dispatch", "inputs", "platform", "default")
  end

  def test_test_gate_does_not_have_signing_or_store_credentials_or_ignored_failures
    checks = workflow("mobile-release-checks")
    serialized = checks.to_s
    refute_match(/ANDROID_KEYSTORE|GOOGLE_PLAY_SERVICE_ACCOUNT|APP_STORE_CONNECT|IOS_CERTIFICATE/, serialized)
    checks.fetch("jobs").each_value do |job|
      refute job.key?("continue-on-error")
      job.fetch("steps").each { |step| refute step.key?("continue-on-error") }
    end
    assert_includes serialized, ":data:local:desktopTest"
    assert_includes serialized, ":data:remote:desktopTest"
    assert_includes serialized, ":app:testDebugUnitTest"
    assert_includes serialized, ":app:testReleaseBundleIdentity"
    refute_includes serialized, ":desktopApp:"
  end

  def test_android_only_publishes_internal_and_reads_back_the_exact_release
    steps = workflow("android-internal-testing").fetch("jobs").fetch("build-and-upload").fetch("steps")
    commands = steps.map { |step| step["run"].to_s }.join("\n")
    assert_includes commands, "google-play-open-testing.rb preflight-internal"
    assert_includes commands, "google-play-open-testing.rb verify-version-code-unused"
    assert_includes commands, "fastlane android upload_internal"
    assert_includes commands, "google-play-open-testing.rb verify-internal"
    refute_match(/google-play-open-testing.rb (publish|preflight)\s*$/, commands)
    assert_operator commands.index("fastlane android upload_internal"), :<,
                    commands.index("google-play-open-testing.rb verify-internal")
    verification = steps.find { |step| step["run"].to_s.include?(".rb verify-internal") }
    refute verification.key?("continue-on-error")
    refute verification.key?("if")
  end

  def test_each_gradle_build_batch_stops_its_workers
    %w[mobile-release-checks android-internal-testing testflight].each do |name|
      steps = workflow(name).fetch("jobs").values.flat_map { |job| job.fetch("steps") }
      batches = steps.select do |step|
        command = step["run"].to_s
        command.include?("./gradlew ") || command.include?("quiet archive") || command.include?("fastlane android upload_internal")
      end
      refute_empty batches
      batches.each { |step| assert_includes step.fetch("run"), "trap './gradlew --stop' EXIT" }
    end
  end

  def test_cleanup_keeps_source_and_refuses_tracked_or_symlinked_outputs
    Dir.mktmpdir("kira-clean-test") do |directory|
      root = File.join(directory, "checkout")
      FileUtils.mkdir_p(File.join(root, "scripts/release"))
      FileUtils.cp(File.join(ROOT, "scripts/release/clean-mobile-build.py"), File.join(root, "scripts/release"))
      assert system("git", "init", "-q", root)
      environment = {"GITHUB_ACTIONS" => "true", "GITHUB_WORKSPACE" => root}
      script = File.join(root, "scripts/release/clean-mobile-build.py")
      File.write(File.join(root, "source.txt"), "keep")
      FileUtils.mkdir_p(File.join(root, "app/build"))
      File.write(File.join(root, "app/build/output"), "disposable")
      _out, err, status = Open3.capture3(environment, "python3", "-B", script)
      assert status.success?, err
      assert_equal "keep", File.read(File.join(root, "source.txt"))
      refute File.exist?(File.join(root, "app/build"))

      outside = File.join(directory, "outside")
      FileUtils.mkdir_p(outside)
      File.symlink(outside, File.join(root, "app/build"))
      _out, err, status = Open3.capture3(environment, "python3", "-B", script)
      refute status.success?
      assert_includes err, "symlinked output"
      assert File.directory?(outside)
      File.unlink(File.join(root, "app/build"))

      FileUtils.mkdir_p(File.join(root, "app/build"))
      File.write(File.join(root, "app/build/required"), "keep")
      assert system("git", "-C", root, "add", "app/build/required")
      _out, err, status = Open3.capture3(environment, "python3", "-B", script)
      refute status.success?
      assert_includes err, "tracked path"
      assert_equal "keep", File.read(File.join(root, "app/build/required"))

      _out, err, status = Open3.capture3(environment.merge("GITHUB_ACTIONS" => "false"), "python3", "-B", script)
      refute status.success?
      assert_includes err, "restricted to its own GitHub Actions checkout"
    end
  end
end
