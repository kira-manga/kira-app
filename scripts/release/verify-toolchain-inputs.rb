#!/usr/bin/env ruby
# Credential-free bootstrap guard. The reviewed checkout and hosted runner are
# the trust root; these pins do not replace Gradle or SwiftPM dependency locks.
require "digest"
require "json"
require "yaml"

class ToolchainInputError < StandardError; end

class ToolchainInputVerifier
  COMMAND = "/usr/bin/ruby scripts/release/verify-toolchain-inputs.rb".freeze
  INSTALL_COMMAND = "bash scripts/release/install-xcodegen.sh".freeze
  CLEANUP_COMMAND = "bash scripts/release/cleanup-xcodegen.sh".freeze
  WRAPPER_FILES = %w[
    gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar
    gradle/wrapper/gradle-wrapper.properties
  ].freeze
  WORKFLOWS = %w[ci.yml android-internal-testing.yml testflight.yml internal-testing-release.yml].freeze
  # This one inline branch gate reads no checkout files and receives no secrets.
  # Any changed command or job shape requires review, not a shell-only exemption.
  INLINE_RELEASE_REQUEST_JOB = {
    "name" => "Validate release request", "runs-on" => "ubuntu-latest",
    "steps" => [{
      "name" => "Require the internal-testing branch",
      "run" => <<~'BASH'
        if [[ "$GITHUB_REF" != "refs/heads/internal-testing" ]]; then
          echo '::error::Select the internal-testing branch before starting a release'
          exit 1
        fi
      BASH
    }]
  }.freeze

  def initialize(root)
    @root = root
    @pins = JSON.parse(File.read(File.join(root, "release/verified-tools.json")))
  end

  def verify!
    check!(@pins.fetch("schema_version") == 1, "unsupported verified-tools schema")
    @pins.fetch("actions").each do |action, commit|
      check!(action.match?(%r{\A[\w.-]+/[\w.-]+(?:/[\w.-]+)*\z}) &&
        commit.match?(/\A[0-9a-f]{40}\z/), "Action pins must be full commit IDs")
    end
    verify_gradle!
    verify_xcodegen_pin!
    workflows = Dir.glob(File.join(@root, ".github/workflows/*.{yml,yaml}")).sort
    check!((WORKFLOWS - workflows.map { |file| File.basename(file) }).empty?, "a guarded workflow is missing")
    workflows.each do |file|
      verify_workflow!(file)
    end
  end

  private

  def check!(condition, message)
    raise ToolchainInputError, message unless condition
  end

  def sha256?(value)
    value.is_a?(String) && value.match?(/\A[0-9a-f]{64}\z/)
  end

  def verify_gradle!
    gradle = @pins.fetch("gradle")
    version = gradle.fetch("version")
    url = "https://services.gradle.org/distributions/gradle-#{version}-bin.zip"
    check!(version.match?(/\A\d+\.\d+\.\d+\z/) && gradle.fetch("distribution_url") == url,
      "Gradle must select an exact official release")
    check!(sha256?(gradle.fetch("distribution_sha256")), "Gradle distribution SHA-256 is missing")
    files = gradle.fetch("files")
    check!(files.keys.sort == WRAPPER_FILES.sort, "all four wrapper files must be pinned")
    files.each do |relative, digest|
      path = File.join(@root, relative)
      check!(File.lstat(path).file? && sha256?(digest) && Digest::SHA256.file(path).hexdigest == digest,
        "wrapper input digest mismatch: #{relative}")
    end
    check!(File.executable?(File.join(@root, "gradlew")), "gradlew must remain executable")

    properties = {}
    File.foreach(File.join(@root, "gradle/wrapper/gradle-wrapper.properties")) do |line|
      next if line.strip.empty? || line.start_with?("#")

      key, value = line.strip.split("=", 2)
      check!(value && !properties.key?(key), "duplicate or invalid wrapper property")
      properties[key] = value.gsub('\\:', ':')
    end
    expected = {
      "distributionBase" => "GRADLE_USER_HOME", "distributionPath" => "wrapper/dists",
      "distributionUrl" => url, "distributionSha256Sum" => gradle.fetch("distribution_sha256"),
      "networkTimeout" => "10000", "retries" => "0", "retryBackOffMs" => "500",
      "validateDistributionUrl" => "true", "zipStoreBase" => "GRADLE_USER_HOME",
      "zipStorePath" => "wrapper/dists"
    }
    check!(properties == expected, "wrapper properties must enforce the pinned distribution checksum")
  end

  def verify_xcodegen_pin!
    tool = @pins.fetch("xcodegen")
    version = tool.fetch("version")
    check!(version.match?(/\A\d+\.\d+\.\d+\z/) &&
      tool.fetch("url") == "https://github.com/yonaskolb/XcodeGen/releases/download/#{version}/xcodegen.zip",
      "XcodeGen must select an exact official release asset")
    check!(sha256?(tool.fetch("sha256")) && sha256?(tool.fetch("binary_sha256")) &&
      tool.fetch("binary_path") == "xcodegen/bin/xcodegen", "XcodeGen archive and binary must be pinned")
  end

  def secrets_in?(node)
    JSON.generate(node).match?(/\bsecrets\s*(?:\.|\[)/i)
  end

  def verify_use!(reference, local_workflow: false)
    if local_workflow && reference.match?(%r{\A\./\.github/workflows/[\w-]+\.ya?ml\z})
      check!(File.file?(File.join(@root, reference)), "local workflow is missing")
      return
    end
    action, commit = reference.split("@", 2)
    check!(commit && commit.match?(/\A[0-9a-f]{40}\z/) && @pins.fetch("actions")[action] == commit,
      "unreviewed or floating Action reference: #{reference}")
  end

  def verify_workflow!(file)
    workflow = YAML.safe_load(File.read(file), aliases: false)
    check!(!secrets_in?(workflow.reject { |key, _| key == "jobs" }), "workflow-wide secrets precede bootstrap verification")
    check!(!workflow.dig("defaults", "run", "working-directory"), "bootstrap must run from the checkout root")
    workflow.fetch("jobs").each do |name, job|
      inline_request = File.basename(file) == "internal-testing-release.yml" && name == "validate-request"
      check!(job == INLINE_RELEASE_REQUEST_JOB, "unsupported inline release request guard") if inline_request
      if job.key?("uses")
        verify_use!(job.fetch("uses"), local_workflow: true)
        next
      end
      check!(!secrets_in?(job.reject { |key, _| key == "steps" }), "job-wide secrets precede bootstrap verification: #{name}")
      check!(!job.key?("continue-on-error"), "bootstrap job cannot ignore failure: #{name}")
      check!(!job.dig("defaults", "run", "working-directory"), "bootstrap must run from the checkout root")
      [workflow, job].each do |node|
        shell = node.dig("defaults", "run", "shell")
        check!(shell.nil? || shell == "bash", "bootstrap requires the fail-fast runner shell")
      end
      next if inline_request

      steps = job.fetch("steps")
      checkout = steps.fetch(0)
      check!(checkout["uses"] == "actions/checkout@#{@pins.fetch('actions').fetch('actions/checkout')}" &&
        (checkout.keys - %w[name uses with]).empty? && checkout.fetch("with") == { "persist-credentials" => false },
        "checkout must be first, pinned, and not retain credentials: #{name}")
      guard = steps.fetch(1)
      check!(guard.keys.sort == %w[name run] && guard.fetch("run").strip == COMMAND,
        "mandatory bootstrap verification must immediately follow checkout: #{name}")
      installer_index = nil
      if File.basename(file) == "testflight.yml"
        installers = steps.each_index.select { |index| steps[index]["run"].to_s.strip == INSTALL_COMMAND }
        check!(installers.length == 1 && installers.first > 1, "TestFlight requires one verified XcodeGen installer")
        installer_index = installers.first
        check!(steps[installer_index].keys.sort == %w[name run], "XcodeGen installation cannot be skipped or ignore failure")
        generator = steps.find { |step| step["name"] == "Generate the Xcode project" }
        check!(generator && generator.keys.sort == %w[name run working-directory] &&
          generator["run"].strip == '"$KIRA_XCODEGEN" generate' && generator["working-directory"] == "iosApp" &&
          steps.index(generator) > installer_index, "project generation must use the verified XcodeGen path")
        cleanup = steps[steps.index(generator) + 1]
        check!(cleanup && cleanup.keys.sort == %w[if name run] && cleanup["if"] == "${{ always() }}" &&
          cleanup["run"].strip == CLEANUP_COMMAND,
          "XcodeGen cleanup must always immediately follow project generation")
      end
      steps.each_with_index do |step, index|
        verify_use!(step.fetch("uses")) if step.key?("uses")
        next unless secrets_in?(step)

        check!(index > 1, "protected input precedes bootstrap verification: #{name}")
        check!(!installer_index || index > installer_index, "protected input precedes verified XcodeGen installation")
      end
    end
  end
end

if $PROGRAM_NAME == __FILE__
  abort("Usage: ruby scripts/release/verify-toolchain-inputs.rb") unless ARGV.empty?
  begin
    ToolchainInputVerifier.new(File.expand_path("../..", __dir__)).verify!
    puts "Pinned Action, wrapper, XcodeGen metadata, and pre-credential ordering checks passed"
  rescue ToolchainInputError, JSON::ParserError, Psych::Exception, KeyError, IndexError, SystemCallError => error
    abort("Toolchain input verification failed: #{error.message}")
  end
end
