#!/usr/bin/env ruby
# Credential-free bootstrap guard. The reviewed checkout and hosted runner are
# the trust root; these pins do not replace Gradle dependency verification.
require "digest"
require "json"
require "yaml"

class ToolchainInputError < StandardError; end

class ToolchainInputVerifier
  COMMAND = "/usr/bin/ruby scripts/release/verify-toolchain-inputs.rb".freeze
  INSTALL_COMMAND = "bash scripts/release/install-xcodegen.sh".freeze
  CLEANUP_COMMAND = "bash scripts/release/cleanup-xcodegen.sh".freeze
  SWIFTPM_LOCK = "iosApp/Package.resolved".freeze
  GENERATED_SWIFTPM_LOCK = "iosApp/iosApp.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved".freeze
  SWIFTPM_PREFLIGHT_COMMAND = <<~'SH'.strip.freeze
    set -euo pipefail
    /usr/bin/ruby scripts/release/verify-toolchain-inputs.rb --restore-swiftpm
    xcodebuild -resolvePackageDependencies \
      -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release \
      -destination 'generic/platform=iOS' -derivedDataPath "$RUNNER_TEMP/DerivedData" \
      -clonedSourcePackagesDirPath "$RUNNER_TEMP/DerivedData/SourcePackages" \
      -packageCachePath "$RUNNER_TEMP/kira-swiftpm-cache" \
      -onlyUsePackageVersionsFromResolvedFile -disableAutomaticPackageResolution -skipPackageUpdates \
      CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO
    /usr/bin/ruby scripts/release/verify-toolchain-inputs.rb --check-swiftpm
  SH
  LOCKED_ARCHIVE_COMMAND = <<~'SH'.strip.freeze
    set +x
    /usr/bin/ruby scripts/release/verify-toolchain-inputs.rb --check-swiftpm
    log="$RUNNER_TEMP/xcode-archive.log"
    if ! xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release -destination 'generic/platform=iOS' -archivePath "$KIRA_ARCHIVE_PATH" -derivedDataPath "$RUNNER_TEMP/DerivedData" -clonedSourcePackagesDirPath "$RUNNER_TEMP/DerivedData/SourcePackages" -packageCachePath "$RUNNER_TEMP/kira-swiftpm-cache" -onlyUsePackageVersionsFromResolvedFile -disableAutomaticPackageResolution -skipPackageUpdates -xcconfig "$KIRA_XCCONFIG_PATH" -quiet archive >"$log" 2>&1; then
      ruby release/ios/safe_xcode_log.rb "$log"
      exit 1
    fi
    /usr/bin/ruby scripts/release/verify-toolchain-inputs.rb --check-swiftpm
    echo "Signed App Store archive created; Crashlytics upload is the next mandatory gate"
  SH
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
    verify_swiftpm_pin!
    workflows = Dir.glob(File.join(@root, ".github/workflows/*.{yml,yaml}")).sort
    check!((WORKFLOWS - workflows.map { |file| File.basename(file) }).empty?, "a guarded workflow is missing")
    workflows.each do |file|
      verify_workflow!(file)
    end
  end

  def restore_swiftpm!
    verify_swiftpm_pin!
    path = generated_swiftpm_path(create: true)
    if File.exist?(path) || File.symlink?(path)
      check_swiftpm!
      return
    end
    # Never follow a destination link or silently refresh an existing stale lock.
    File.open(path, File::WRONLY | File::CREAT | File::EXCL, 0o600) { |file| file.write(@swiftpm_bytes) }
    check_swiftpm!
  end

  def check_swiftpm!
    verify_swiftpm_pin!
    path = generated_swiftpm_path(create: false)
    check!(File.file?(path) && File.lstat(path).file? && File.size(path) == @swiftpm_bytes.bytesize &&
      File.binread(path) == @swiftpm_bytes, "generated shipping SwiftPM lock is missing, changed, or symlinked")
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

  def verify_swiftpm_pin!
    pin = @pins.fetch("swiftpm")
    check!(sha256?(pin.fetch("lock_sha256")), "reviewed shipping SwiftPM lock SHA-256 is missing")
    path = File.join(@root, SWIFTPM_LOCK)
    check!(File.file?(path) && File.lstat(path).file? && File.size(path).between?(1, 262_144),
      "reviewed shipping SwiftPM lock is missing, oversized, or symlinked")
    @swiftpm_bytes = File.binread(path)
    check!(Digest::SHA256.hexdigest(@swiftpm_bytes) == pin.fetch("lock_sha256"), "shipping SwiftPM lock digest mismatch")
    lock = JSON.parse(@swiftpm_bytes)
    check!(lock.is_a?(Hash) && lock["version"] == 3 && lock["pins"].is_a?(Array) &&
      lock["pins"].length.between?(1, 64),
      "unsupported shipping SwiftPM lock schema")
    pins = lock.fetch("pins")
    pins.each do |item|
      check!(item.is_a?(Hash) && item.keys.sort == %w[identity kind location state] &&
        item["identity"].is_a?(String) && item["identity"].match?(/\A[a-z0-9_.-]+\z/) &&
        item["kind"] == "remoteSourceControl" && item["location"].is_a?(String) &&
        item["location"].match?(%r{\Ahttps://github\.com/[\w.-]+/[\w.-]+\z}) &&
        item["state"].is_a?(Hash) && item["state"].keys.sort == %w[revision version] &&
        item["state"]["revision"].is_a?(String) && item["state"]["revision"].match?(/\A[0-9a-f]{40}\z/) &&
        item["state"]["version"].is_a?(String) && item["state"]["version"].match?(/\A\d+\.\d+\.\d+(?:[-+][\w.-]+)?\z/),
        "shipping SwiftPM pins must name exact public versions and revisions")
    end
    check!(pins.map { |item| item["identity"] }.uniq.length == pins.length, "duplicate shipping SwiftPM identity")
    firebase = pin.fetch("firebase")
    root_pin = pins.find { |item| item["identity"] == "firebase-ios-sdk" }
    check!(root_pin && root_pin["location"] == firebase.fetch("url") &&
      root_pin["state"]["version"] == firebase.fetch("version"), "shipping SwiftPM Firebase root differs from the pin")
    project = YAML.safe_load(File.read(File.join(@root, "iosApp/project.yml")), aliases: false)
    check!(project.fetch("packages") == {
      "Firebase" => { "url" => firebase.fetch("url"), "exactVersion" => firebase.fetch("version") }
    }, "shipping SwiftPM package declaration differs from the reviewed root")
  end

  def generated_swiftpm_path(create:)
    directory = @root
    File.dirname(GENERATED_SWIFTPM_LOCK).split("/").each_with_index do |part, index|
      directory = File.join(directory, part)
      Dir.mkdir(directory, 0o700) if create && index > 1 && !File.exist?(directory) && !File.symlink?(directory)
      check!(File.directory?(directory) && File.lstat(directory).directory?,
        "generated shipping SwiftPM directory is missing or symlinked")
      next unless index == 1

      project = File.join(directory, "project.pbxproj")
      check!(File.file?(project) && File.lstat(project).file?, "generated shipping Xcode project is missing or symlinked")
    end
    File.join(@root, GENERATED_SWIFTPM_LOCK)
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
      swiftpm_index = nil
      if File.basename(file) == "testflight.yml"
        installers = steps.each_index.select { |index| steps[index]["run"].to_s.strip == INSTALL_COMMAND }
        check!(installers.length == 1 && installers.first > 1, "TestFlight requires one verified XcodeGen installer")
        installer_index = installers.first
        check!(steps[installer_index].keys.sort == %w[name run], "XcodeGen installation cannot be skipped or ignore failure")
        check!(steps.take(installer_index + 1).none? { |step| secrets_in?(step) },
          "protected input precedes verified XcodeGen installation")
        generator = steps.find { |step| step["name"] == "Generate the Xcode project" }
        check!(generator && generator.keys.sort == %w[name run working-directory] &&
          generator["run"].strip == '"$KIRA_XCODEGEN" generate' && generator["working-directory"] == "iosApp" &&
          steps.index(generator) > installer_index &&
          steps.count { |step| step["run"].to_s.strip == '"$KIRA_XCODEGEN" generate' } == 1,
          "project generation must use the verified XcodeGen path exactly once")
        cleanup = steps[steps.index(generator) + 1]
        check!(cleanup && cleanup.keys.sort == %w[if name run] && cleanup["if"] == "${{ always() }}" &&
          cleanup["run"].strip == CLEANUP_COMMAND,
          "XcodeGen cleanup must always immediately follow project generation")
        preflights = steps.each_index.select { |index| steps[index]["name"] == "Resolve the reviewed SwiftPM lock before protected inputs" }
        check!(preflights.length == 1, "TestFlight requires one mandatory locked SwiftPM preflight")
        swiftpm_index = preflights.first
        preflight = steps[swiftpm_index]
        check!(swiftpm_index == steps.index(generator) + 2 && preflight.keys.sort == %w[name run] &&
          preflight["run"].strip == SWIFTPM_PREFLIGHT_COMMAND,
          "locked SwiftPM preflight must follow generation/cleanup without skips or update fallback")
        archives = steps.select { |step| step["id"] == "archive" }
        check!(archives.length == 1 && archives.first.keys.sort == %w[env id name run] &&
          steps.index(archives.first) > swiftpm_index && archives.first["run"].strip == LOCKED_ARCHIVE_COMMAND,
          "archive must use and recheck the same reviewed SwiftPM lock and package directory")
      end
      steps.each_with_index do |step, index|
        verify_use!(step.fetch("uses")) if step.key?("uses")
        next unless secrets_in?(step)

        check!(index > 1, "protected input precedes bootstrap verification: #{name}")
        check!(!installer_index || index > installer_index, "protected input precedes verified XcodeGen installation")
        check!(!swiftpm_index || index > swiftpm_index, "protected input precedes locked SwiftPM preflight")
      end
    end
  end
end

if $PROGRAM_NAME == __FILE__
  abort("Usage: ruby scripts/release/verify-toolchain-inputs.rb [--restore-swiftpm|--check-swiftpm]") unless
    ARGV.empty? || ARGV == ["--restore-swiftpm"] || ARGV == ["--check-swiftpm"]
  begin
    verifier = ToolchainInputVerifier.new(File.expand_path("../..", __dir__))
    verifier.verify!
    verifier.restore_swiftpm! if ARGV == ["--restore-swiftpm"]
    verifier.check_swiftpm! if ARGV == ["--check-swiftpm"]
    puts "Pinned Action, wrapper, XcodeGen metadata, and pre-credential ordering checks passed"
  rescue ToolchainInputError, JSON::ParserError, Psych::Exception, KeyError, IndexError, SystemCallError => error
    abort("Toolchain input verification failed: #{error.message}")
  end
end
