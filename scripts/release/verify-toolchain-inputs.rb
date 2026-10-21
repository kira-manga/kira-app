#!/usr/bin/env ruby
# Credential-free bootstrap guard. The reviewed checkout and hosted runner are
# the trust root; these pins do not replace Gradle dependency verification.
require "digest"
require "json"
require "rexml/document"
require "shellwords"
require "yaml"

class ToolchainInputError < StandardError; end

class ToolchainInputVerifier
  COMMAND = "/usr/bin/ruby scripts/release/verify-toolchain-inputs.rb".freeze
  INSTALL_COMMAND = "bash scripts/release/install-xcodegen.sh".freeze
  CLEANUP_COMMAND = "bash scripts/release/cleanup-xcodegen.sh".freeze
  LIBFFI_PREPARE_COMMAND = "python3 -B scripts/release/prepare-libffi-source.py prepare".freeze
  LIBFFI_VERIFY_COMMAND = "python3 -B scripts/release/prepare-libffi-source.py verify".freeze
  RUNTIME_INSTALLER = "scripts/release/install-toolchain-inputs.py".freeze
  RUNTIME_COMMAND = "/usr/bin/python3 -I -B #{RUNTIME_INSTALLER}".freeze
  RUNTIME_STOP_CONDITION = "${{ always() && env.KIRA_VERIFIED_JAVA_AREA != '' }}".freeze
  RUNTIME_DISPOSAL_CONDITION = "${{ always() && (env.KIRA_VERIFIED_JAVA_AREA == '' || steps.stop-owned-gradle.outcome == 'success') }}".freeze
  RUNTIME_ENV = %w[
    PATH JAVA_HOME JAVA_OPTS JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS GRADLE_OPTS
    RUBYOPT RUBYLIB GEM_HOME GEM_PATH BUNDLER_VERSION RUNNER_TOOL_CACHE
    KONAN_DATA_DIR KONAN_USE_INTERNAL_SERVER
  ].freeze
  NATIVE_SERIAL_OPTIONS = "-Dorg.gradle.parallel=false -Dorg.gradle.workers.max=1".freeze
  NATIVE_SOURCE_FILES = %w[
    release/libffi-source.json scripts/release/prepare-libffi-source.py
    scripts/release/libffi-cfi-startproc.patch scripts/release/libffi-source-smoke.c
  ].freeze
  SWIFTPM_LOCK = "iosApp/Package.resolved".freeze
  GENERATED_SWIFTPM_LOCK = "iosApp/iosApp.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved".freeze
  SWIFTPM_PREFLIGHT_COMMAND = <<~'SH'.strip.freeze
    set -euo pipefail
    env -u GEM_HOME -u GEM_PATH /usr/bin/ruby scripts/release/verify-toolchain-inputs.rb --restore-swiftpm
    xcodebuild -resolvePackageDependencies \
      -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release \
      -destination 'generic/platform=iOS' -derivedDataPath "$RUNNER_TEMP/DerivedData" \
      -clonedSourcePackagesDirPath "$RUNNER_TEMP/DerivedData/SourcePackages" \
      -packageCachePath "$RUNNER_TEMP/kira-swiftpm-cache" \
      -onlyUsePackageVersionsFromResolvedFile -disableAutomaticPackageResolution -skipPackageUpdates \
      CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO
    env -u GEM_HOME -u GEM_PATH /usr/bin/ruby scripts/release/verify-toolchain-inputs.rb --check-swiftpm
  SH
  LOCKED_ARCHIVE_COMMAND = <<~'SH'.strip.freeze
    set +x
    env -u GEM_HOME -u GEM_PATH /usr/bin/ruby scripts/release/verify-toolchain-inputs.rb --check-swiftpm
    python3 -B scripts/release/prepare-libffi-source.py verify
    trap './gradlew --stop' EXIT
    log="$RUNNER_TEMP/xcode-archive.log"
    if ! xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release -destination 'generic/platform=iOS' -archivePath "$KIRA_ARCHIVE_PATH" -derivedDataPath "$RUNNER_TEMP/DerivedData" -clonedSourcePackagesDirPath "$RUNNER_TEMP/DerivedData/SourcePackages" -packageCachePath "$RUNNER_TEMP/kira-swiftpm-cache" -onlyUsePackageVersionsFromResolvedFile -disableAutomaticPackageResolution -skipPackageUpdates -xcconfig "$KIRA_XCCONFIG_PATH" -quiet archive >"$log" 2>&1; then
      ruby release/ios/safe_xcode_log.rb "$log"
      exit 1
    fi
    ./gradlew --stop
    python3 -B scripts/release/prepare-libffi-source.py verify
    env -u GEM_HOME -u GEM_PATH /usr/bin/ruby scripts/release/verify-toolchain-inputs.rb --check-swiftpm
    echo "Signed App Store archive created; Crashlytics upload is the next mandatory gate"
  SH
  # Native Gradle owns graph resolution. These source guards bind only reviewed inputs and order;
  # they do not manufacture metadata, lock state, or a successful platform graph observation.
  GRADLE_PROJECT_DIRS = ["", "app", "composeApp", "desktopApp", "core", "domain", "data", "data/local", "data/remote", "data/download", "platform", "presentation", "ui", "sources/contracts", "sources/engine", "sources/config", "sources/legacy"].freeze
  GRADLE_LOCK_DIRS = (GRADLE_PROJECT_DIRS + ["sources"]).freeze # Real implicit Gradle parent.
  GRADLE_HOST_LOCK_FILES = %w[composeApp desktopApp ui data platform].flat_map do |directory|
    %w[gradle.lockfile gradle-macos-arm64.lockfile].map { |name| "#{directory}/#{name}" }
  end.freeze
  GRADLE_GRAPH_FILES = (GRADLE_PROJECT_DIRS.map { |directory| [directory, "build.gradle.kts"].reject(&:empty?).join("/") } +
    %w[settings.gradle.kts gradle.properties gradle/libs.versions.toml]).sort.freeze
  GRADLE_REQUIRED_ARGUMENTS = %w[
    --dependency-verification=strict
    --no-configuration-cache
    --no-build-cache
    --no-daemon
    -PkiraUseMavenLocal=false
    -Porg.gradle.java.installations.auto-download=false
    -Pandroid.builder.sdkDownload=false
  ].freeze
  GRADLE_OPTIONAL_ARGUMENTS = %w[
    --offline --stacktrace --rerun-tasks --continue --max-workers=1 --max-workers=2
    -Dorg.gradle.jvmargs=-Xmx3g -Pkotlin.compiler.execution.strategy=in-process
    -PallowPlaceholderGoogleServices=true
  ].freeze
  ANDROID_FIREBASE_CLEANUP_CONDITION = "${{ always() && (steps.android-firebase.outcome == 'success' || steps.android-firebase.outcome == 'failure') }}".freeze
  ANDROID_GRADLE_PREFLIGHT_NAME = "Consume the verified Android graph before protected inputs".freeze
  APPLE_GRADLE_PREFLIGHT_NAME = "Consume the verified Apple Gradle graph before protected inputs".freeze
  NATIVE_GRADLE_LOCKING = <<~'KOTLIN'.strip.freeze
    check(gradle.startParameter.dependencyVerificationMode.name == "STRICT") {
        "Kira builds require strict Gradle dependency verification"
    }
    gradle.beforeProject {
        dependencyLocking {
            lockAllConfigurations()
            lockMode.set(org.gradle.api.artifacts.dsl.LockMode.STRICT)
            // Compose/Skiko select host-native modules even in shared metadata graphs.
            // Preserve each observed graph; combining their members breaks STRICT locks.
            if (path in setOf(":composeApp", ":desktopApp", ":ui", ":data", ":platform")) {
                val os = System.getProperty("os.name")
                val arch = System.getProperty("os.arch")
                val hostLock = when {
                    os == "Linux" && arch in setOf("amd64", "x86_64") -> "gradle.lockfile"
                    os == "Mac OS X" && arch in setOf("aarch64", "arm64") -> "gradle-macos-arm64.lockfile"
                    else -> error("No reviewed dependency locks for build host $os/$arch")
                }
                lockFile.set(file(hostLock))
            }
        }
        // Register before project evaluation, including the root plugin classpath. A late allprojects
        // block would miss root buildscript resolution and give incomplete plugin lock coverage.
        buildscript.configurations.configureEach {
            resolutionStrategy.activateDependencyLocking()
        }
    }
  KOTLIN
  ANDROID_GRADLE_PREFLIGHT_COMMAND = <<~'SH'.strip.freeze
    set -euo pipefail
    [[ ! -e app/google-services.json && ! -L app/google-services.json ]] || {
      echo 'Unsigned preflight requires an absent Firebase slot' >&2
      exit 1
    }
    cleanup_placeholder() {
      local original_status=$?
      unset KIRA_PACKAGES_READ_TOKEN MOBILE_RELEASE_PROJECT_READ_TOKEN GITHUB_TOKEN GH_TOKEN
      ./gradlew --stop || original_status=1
      if [[ ! -f app/google-services.json || -L app/google-services.json ]] ||
          ! cmp -s app/google-services.json.example app/google-services.json; then
        echo 'Unsigned preflight Firebase slot changed; refusing cleanup' >&2
        exit 1
      fi
      rm -- app/google-services.json || exit 1
      exit "$original_status"
    }
    ( set -o noclobber; cat app/google-services.json.example > app/google-services.json )
    trap cleanup_placeholder EXIT
    ./gradlew :app:lintRelease :app:verifyReleaseBundleIdentity --dependency-verification=strict --no-configuration-cache --no-build-cache --no-daemon -PkiraUseMavenLocal=false -Porg.gradle.java.installations.auto-download=false -Pandroid.builder.sdkDownload=false -PallowPlaceholderGoogleServices=true --max-workers=2 -Dorg.gradle.jvmargs=-Xmx3g -Pkotlin.compiler.execution.strategy=in-process --stacktrace
  SH
  CI_ANDROID_GRADLE_PREFLIGHT_COMMAND = <<~'SH'.strip.freeze
    set -euo pipefail
    [[ ! -e app/google-services.json && ! -L app/google-services.json ]] || {
      echo 'Unsigned preflight requires an absent Firebase slot' >&2
      exit 1
    }
    cleanup_placeholder() {
      local original_status=$?
      unset KIRA_PACKAGES_READ_TOKEN MOBILE_RELEASE_PROJECT_READ_TOKEN GITHUB_TOKEN GH_TOKEN
      ./gradlew --stop || original_status=1
      if [[ ! -f app/google-services.json || -L app/google-services.json ]] ||
          ! cmp -s app/google-services.json.example app/google-services.json; then
        echo 'Unsigned preflight Firebase slot changed; refusing cleanup' >&2
        exit 1
      fi
      rm -- app/google-services.json || exit 1
      exit "$original_status"
    }
    ( set -o noclobber; cat app/google-services.json.example > app/google-services.json )
    trap cleanup_placeholder EXIT
    ./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease :app:bundleRelease --dependency-verification=strict --no-configuration-cache --no-build-cache --no-daemon -PkiraUseMavenLocal=false -Porg.gradle.java.installations.auto-download=false -Pandroid.builder.sdkDownload=false -PallowPlaceholderGoogleServices=true --max-workers=2 -Dorg.gradle.jvmargs=-Xmx3g -Pkotlin.compiler.execution.strategy=in-process --stacktrace
  SH
  APPLE_GRADLE_PREFLIGHT_COMMAND = <<~'SH'.strip.freeze
    python3 -B scripts/release/prepare-libffi-source.py verify
    stop_preflight() {
      local original_status=$?
      unset KIRA_PACKAGES_READ_TOKEN MOBILE_RELEASE_PROJECT_READ_TOKEN GITHUB_TOKEN GH_TOKEN
      ./gradlew --stop || original_status=1
      exit "$original_status"
    }
    trap stop_preflight EXIT
    printf 'Hosted physical RAM bytes: '; sysctl -n hw.memsize
    df -h "$RUNNER_TEMP"
    ./gradlew :composeApp:compileKotlinIosArm64 :composeApp:compileKotlinIosSimulatorArm64 :composeApp:linkReleaseFrameworkIosArm64 --dependency-verification=strict --no-configuration-cache --no-build-cache --no-daemon -PkiraUseMavenLocal=false -Porg.gradle.java.installations.auto-download=false -Pandroid.builder.sdkDownload=false -Pkotlin.compiler.execution.strategy=in-process --stacktrace
    ruby release/version.rb verify-generated
    python3 -B scripts/release/prepare-libffi-source.py verify
  SH
  XCODE_GRADLE_COMMAND = <<~'SH'.strip.freeze
    set -eu
    cd "$SRCROOT/.."
    if [ "$CONFIGURATION" = "Release" ]; then
      set -- --offline
    else
      set --
    fi
    ./gradlew :composeApp:embedAndSignAppleFrameworkForXcode --dependency-verification=strict --no-configuration-cache --no-build-cache --no-daemon -PkiraUseMavenLocal=false -Porg.gradle.java.installations.auto-download=false -Pandroid.builder.sdkDownload=false "$@"
  SH

  CAPTURE_FILES = %w[
    .github/workflows/toolchain-input-capture.yml scripts/release/capture-gradle-inputs.init.gradle
  ].freeze
  # Existing bounded no-Store controller: its delegated Python owns a fixed Gradle menu.
  # Bind both unchanged files instead of granting arbitrary workflow argv exceptions.
  CHECK_ONLY_SOURCE_FILES = %w[
    .github/workflows/source-runtime-checks.yml scripts/release/check-source-runtime-ios.py
  ].freeze
  QUALIFICATION_WORKFLOW = ".github/workflows/toolchain-input-qualification.yml".freeze
  GRADLE_CONSUMER_FILES = (%w[fastlane/Fastfile iosApp/project.yml] + CHECK_ONLY_SOURCE_FILES + [QUALIFICATION_WORKFLOW]).sort.freeze
  FASTLANE_GRADLE_COMMAND = <<~'RUBY'.strip.freeze
    sh(File.join(root, "gradlew"), "--project-dir", root, "--no-daemon", "--max-workers=2",
       "--dependency-verification=strict", "--no-configuration-cache", "--no-build-cache", "--offline",
       "-PkiraUseMavenLocal=false", "-Porg.gradle.java.installations.auto-download=false", "-Pandroid.builder.sdkDownload=false",
       "-Dorg.gradle.jvmargs=-Xmx3g", "-Pkotlin.compiler.execution.strategy=in-process", ":app:verifyReleaseBundleIdentity",
       "-Pkira.releaseBundleToVerify=#{File.expand_path(aab_path)}")
  RUBY

  # The hosted OS and selected Xcode remain bootstrap trust roots, not archive-byte claims.
  XCODE_IDENTITY_COMMAND = <<~'SH'.strip.freeze
    [[ "$(/usr/bin/xcodebuild -version)" == $'Xcode 26.6\nBuild version 17F113' ]] || {
      echo 'Selected Xcode version/build does not match the reviewed runtime' >&2
      exit 1
    }
  SH

  WRAPPER_FILES = %w[
    gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar
    gradle/wrapper/gradle-wrapper.properties
  ].freeze
  WORKFLOWS = %w[ci.yml android-internal-testing.yml testflight.yml internal-testing-release.yml mobile-release-checks.yml].freeze
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
    verify_gradle_graph!
    verify_runtime_selections!
    verify_runtime_archives!
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

  def checked_gradle_file(relative, digest)
    path = @root
    relative.split("/").each_with_index do |part, index|
      path = File.join(path, part)
      check!(!File.symlink?(path), "Gradle input is missing or symlinked: #{relative}")
      check!(File.directory?(path), "Gradle input directory is missing: #{relative}") if index < relative.split("/").length - 1
    end
    check!(File.file?(path), "Gradle input is missing or symlinked: #{relative}")
    check!(sha256?(digest) && Digest::SHA256.file(path).hexdigest == digest, "Gradle input digest mismatch: #{relative}")
    File.binread(path)
  end

  def verify_gradle_graph!
    graph = @pins.fetch("gradle").fetch("dependency_graph")
    check!(sha256?(graph["metadata_sha256"]), "reviewed Gradle dependency verification metadata SHA-256 is missing")
    files = graph.fetch("files")
    check!(files.is_a?(Hash) && files.keys.sort == GRADLE_GRAPH_FILES.sort, "Gradle graph source input set changed")
    files.each { |relative, digest| checked_gradle_file(relative, digest) }
    check!(File.read(File.join(@root, "settings.gradle.kts")).include?(NATIVE_GRADLE_LOCKING),
      "native strict Gradle verification and early project/buildscript locking are required")

    locks = graph.fetch("lockfiles")
    possible_locks = GRADLE_LOCK_DIRS.flat_map do |directory|
      %w[gradle.lockfile buildscript-gradle.lockfile].map { |name| [directory, name].reject(&:empty?).join("/") }
    end
    possible_locks = (possible_locks + GRADLE_HOST_LOCK_FILES).uniq
    present = possible_locks.select { |relative| File.exist?(File.join(@root, relative)) || File.symlink?(File.join(@root, relative)) }
    check!(locks.is_a?(Hash) && locks.key?("buildscript-gradle.lockfile") &&
      (GRADLE_HOST_LOCK_FILES - locks.keys).empty? &&
      (locks.keys - possible_locks).empty? && locks.keys.sort == present.sort,
      "reviewed native Gradle lockfile set is missing or changed")
    locks.each { |relative, digest| checked_gradle_file(relative, digest) }

    bytes = checked_gradle_file("gradle/verification-metadata.xml", graph.fetch("metadata_sha256"))
    check!(!bytes.match?(/<!\s*(?:DOCTYPE|ENTITY)/i), "Gradle verification metadata cannot declare entities")
    document = REXML::Document.new(bytes)
    root = document.root
    check!(root && root.name == "verification-metadata" &&
      root.namespace == "https://schema.gradle.org/dependency-verification", "unsupported Gradle verification metadata")
    configuration = root.elements["configuration"]
    check!(configuration && configuration.elements["verify-metadata"]&.text == "true" &&
      configuration.elements["verify-signatures"]&.text == "false" &&
      configuration.elements.map(&:name).sort == %w[verify-metadata verify-signatures],
      "Gradle must verify descriptors and cannot use trust or signature exemptions")
    artifacts = root.get_elements("components/component/artifact")
    check!(!artifacts.empty? && artifacts.all? { |artifact|
      checksums = artifact.get_elements("sha256")
      checksums.length == 1 && artifact.elements.to_a.length == 1 &&
        checksums.first.elements.to_a.empty? && sha256?(checksums.first.attributes["value"])
    }, "every Gradle artifact and descriptor needs one reviewed SHA-256 without exemptions")
    capture = graph.fetch("acquisition_files")
    check!(capture.is_a?(Hash) && capture.keys.sort == CAPTURE_FILES.sort, "bounded acquisition source set changed")
    capture.each { |relative, digest| checked_gradle_file(relative, digest) }
    consumers = graph.fetch("consumer_files")
    check!(consumers.is_a?(Hash) && consumers.keys.sort == GRADLE_CONSUMER_FILES,
      "signed Gradle consumer source set changed")
    consumers.each { |relative, digest| checked_gradle_file(relative, digest) }
    fastlane = File.read(File.join(@root, "fastlane/Fastfile"))
    check!(fastlane.include?(FASTLANE_GRADLE_COMMAND.lines.map { |line| "    #{line}" }.join.rstrip) &&
      fastlane.scan(/\bgradlew\b/).length == 1,
      "Fastlane AAB inspection must use the same strict offline Gradle graph")
    project = YAML.safe_load(File.read(File.join(@root, "iosApp/project.yml")), aliases: false)
    scripts = project.fetch("targets").fetch("iosApp").fetch("preBuildScripts").select do |script|
      script["name"] == "Build & embed Kotlin/Compose framework"
    end
    check!(scripts.length == 1 && scripts.first.fetch("script").strip == XCODE_GRADLE_COMMAND,
      "Xcode Release embedding must consume the strict Gradle graph offline")
  end

  def verify_gradle_workflow!(filename, job_name, steps)
    # The standalone acquisition workflow is byte-bound above and cannot be reused by a
    # release job. Its fixed task menu writes UNREVIEWED candidates, never accepted metadata.
    return if filename == "toolchain-input-capture.yml"
    return if filename == "source-runtime-checks.yml" # Both exact controller files are byte-bound.
    if filename == File.basename(QUALIFICATION_WORKFLOW)
      # The exact byte-bound controller uses only the same fixed resolver plus one offline
      # unsigned build. Unlike acquisition it cannot rewrite the accepted native inputs.
      check!(steps.none? { |step| step["run"].to_s.match?(/--(?:write-locks|write-verification-metadata|update-locks)\b/) },
        "unsigned qualification cannot write or update accepted Gradle inputs")
      return
    end

    invocations = []
    steps.each_with_index do |step, index|
      script = step["run"].to_s.gsub(/\\\r?\n/, " ")
      # --stop does not evaluate a project or resolve artifacts. Permit only these exact
      # cleanup forms, not arbitrary shell-wrapped Gradle or a verification/update bypass.
      script = script.gsub(/^[ \t]*trap '\.\/gradlew --stop' EXIT[ \t]*$/, "")
      script = script.gsub(/^[ \t]*\.\/gradlew --stop(?: \|\| (?:original_status|status)=1)?[ \t]*$/, "")
      lines = script.lines.select { |line| line.match?(/\A\s*\.\/gradlew(?:\s|$)/) }
      check!(lines.length == script.scan(%r{\./gradlew\b}).length, "Gradle must use a direct reviewed wrapper invocation")
      lines.each do |line|
        arguments = Shellwords.split(line.strip).drop(1)
        check!(GRADLE_REQUIRED_ARGUMENTS.all? { |argument| arguments.count(argument) == 1 } &&
          arguments.all? { |argument|
            GRADLE_REQUIRED_ARGUMENTS.include?(argument) || GRADLE_OPTIONAL_ARGUMENTS.include?(argument) ||
              argument.match?(/\A:[A-Za-z][A-Za-z0-9]*(?::[A-Za-z][A-Za-z0-9]*)*\z/)
          },
          "Gradle must consume strict locked inputs without updates, substitution, or alternate scripts")
        invocations << [index, arguments]
      end
    end
    profile = case [filename, job_name]
    when ["android-internal-testing.yml", "build-and-upload"] then [ANDROID_GRADLE_PREFLIGHT_NAME, ANDROID_GRADLE_PREFLIGHT_COMMAND]
    when ["ci.yml", "release-verify"] then [ANDROID_GRADLE_PREFLIGHT_NAME, CI_ANDROID_GRADLE_PREFLIGHT_COMMAND]
    when ["testflight.yml", "build-and-upload"] then [APPLE_GRADLE_PREFLIGHT_NAME, APPLE_GRADLE_PREFLIGHT_COMMAND]
    end
    return unless profile

    indices = steps.each_index.select { |index| steps[index]["name"] == profile.first }
    check!(indices.length == 1, "one mandatory pre-signing Gradle graph consumption is required")
    preflight_index = indices.first
    preflight = steps[preflight_index]
    package_env = { "KIRA_PACKAGES_READ_TOKEN" => "${{ secrets.KIRA_PACKAGES_READ_TOKEN }}" }
    check!(preflight.keys.sort == %w[env name run] && preflight["env"] == package_env &&
      preflight["run"].strip == profile.last,
      "pre-signing Gradle graph consumption cannot be skipped, weakened, or receive signing inputs")
    steps.each_with_index do |step, index|
      next if index == preflight_index

      check!(index > preflight_index, "protected input precedes verified Gradle graph consumption") if secrets_in?(step)
      check!((step.fetch("env", {}).keys & %w[KIRA_PACKAGES_READ_TOKEN MOBILE_RELEASE_PROJECT_READ_TOKEN GITHUB_TOKEN GH_TOKEN]).empty?,
        "package-read credentials must not reach signed Gradle consumption")
    end
    check!(invocations.all? { |index, arguments|
      index == preflight_index || (index > preflight_index && arguments.include?("--offline") &&
        !arguments.include?("-PallowPlaceholderGoogleServices=true"))
    },
      "signed Gradle consumption must be offline and follow the verified graph preflight")
    return if filename == "testflight.yml"

    decoder_name, keystore_name, cleanup_name = filename == "android-internal-testing.yml" ?
      ["Decode real Firebase configuration", "Reconstruct the Android upload keystore in the runner temp directory",
        "Remove temporary release credentials and Firebase configuration"] :
      ["Provision real Firebase config for signed bundle", "Decode release keystore", "Remove decoded Android credentials"]
    decoder = steps.find { |step| step["name"] == decoder_name }
    keystore = steps.find { |step| step["name"] == keystore_name }
    cleanup = steps.find { |step| step["name"] == cleanup_name }
    check!(decoder && decoder["id"] == "android-firebase" && steps.index(decoder) > preflight_index &&
      steps.count { |step| step["id"] == "android-firebase" } == 1 && cleanup &&
      keystore && steps.index(keystore) > steps.index(decoder) && steps.index(cleanup) > steps.index(keystore) &&
      cleanup["if"] == ANDROID_FIREBASE_CLEANUP_CONDITION,
      "Android credential cleanup cannot remove a refused preflight Firebase slot")
  end

  def verify_runtime_selections!
    runtime = @pins.fetch("runtime_selections")
    java, ruby, xcode = runtime.values_at("java", "ruby", "xcode")
    check!(java == { "distribution" => "temurin", "version" => "21.0.12.1+1" } &&
      ruby.fetch("version") == "3.3.12" &&
      xcode.fetch("developer_dir") == "/Applications/Xcode_#{xcode.fetch('version')}.app/Contents/Developer" &&
      XCODE_IDENTITY_COMMAND.include?("Xcode #{xcode.fetch('version')}\\nBuild version #{xcode.fetch('build')}"),
      "runtime selections require exact supported Java, Ruby, and Xcode identities")
    check!(ruby.fetch("files").keys.sort == %w[Gemfile Gemfile.lock], "Ruby dependency inputs must remain pinned")
    ruby.fetch("files").each do |relative, digest|
      path = File.join(@root, relative)
      check!(File.lstat(path).file? && sha256?(digest) && Digest::SHA256.file(path).hexdigest == digest,
        "Ruby dependency input digest mismatch: #{relative}")
    end
    check!(File.read(File.join(@root, "Gemfile.lock")).split("\nBUNDLED WITH\n").last.strip == ruby.fetch("bundler"),
      "Ruby selection must use the already-reviewed locked Bundler version")
  end

  def verify_runtime_archives!
    pins = @pins.fetch("runtime_archives")
    check!(pins.keys.sort == %w[bundler installer_sha256 java ruby], "runtime archive role set changed")
    checked_gradle_file(RUNTIME_INSTALLER, pins.fetch("installer_sha256"))
    bundler = pins.fetch("bundler")
    check!(bundler.is_a?(Hash) && bundler.keys.sort == %w[sha256 size url version] &&
      bundler["version"] == "4.0.16" && bundler["version"] == @pins.fetch("runtime_selections").fetch("ruby").fetch("bundler") &&
      bundler["url"] == "https://rubygems.org/gems/bundler-4.0.16.gem" && sha256?(bundler["sha256"]) &&
      bundler["size"].is_a?(Integer) && bundler["size"].between?(1, 2 * 1024**2),
      "Bundler requires the exact reviewed gem URL, version, size and SHA-256 pin")
    java_base = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/"
    ruby_base = "https://github.com/ruby/ruby-builder/releases/download/ruby-3.3.12/"
    layouts = {
      "java" => {
        "linux-x64" => [{ "url" => "#{java_base}OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz" }],
        "macos-arm64" => [{ "url" => "#{java_base}OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12.1_1.tar.gz" }]
      },
      "ruby" => {
        "linux-x64" => [{ "url" => "#{ruby_base}ruby-3.3.12-ubuntu-24.04-x64.tar.gz", "directory" => "x64" }],
        "macos-arm64" => [{ "url" => "#{ruby_base}ruby-3.3.12-darwin-arm64.tar.gz", "directory" => "arm64" }]
      }
    }
    native = @pins.fetch("native_source_bootstrap")
    check!(native.is_a?(Hash) && native.keys.sort == NATIVE_SOURCE_FILES.sort,
      "reviewed native source bootstrap input set changed")
    native.each { |relative, digest| checked_gradle_file(relative, digest) }
    layouts.each do |role, hosts|
      check!(pins.fetch(role).keys.sort == hosts.keys.sort, "runtime archive host set changed")
      hosts.each do |host, expected|
        selected = pins.fetch(role).fetch(host)
        selected = [selected]
        check!(selected.is_a?(Array) && selected.length == expected.length, "runtime archive set changed")
        selected.zip(expected).each_with_index do |(pin, layout), index|
          check!(pin.is_a?(Hash) && pin.reject { |key, _| %w[sha256 size].include?(key) } == layout &&
            sha256?(pin["sha256"]) && pin.key?("size") &&
            (!pin.key?("size") || (pin["size"].is_a?(Integer) && pin["size"].between?(1, 1024**3))),
            "runtime archives require exact provider URLs, layouts, and SHA-256 pins")
        end
      end
    end
  end

  def verify_runtime_environment!(node)
    keys = node.fetch("env", {}).keys
    check!(keys.none? { |key|
      RUNTIME_ENV.include?(key) || key.start_with?("KIRA_VERIFIED_", "ORG_GRADLE_PROJECT_", "JAVA_HOME_")
    }, "workflow environment cannot override verified runtime inputs")
  end

  def verify_runtime_workflow!(filename, job_name, job)
    return unless WORKFLOWS.include?(filename)

    runtime = @pins.fetch("runtime_selections")
    steps = job.fetch("steps")
    steps.each { |step| verify_runtime_environment!(step) }
    source_env = job.fetch("env", {})
    native_job = filename == "testflight.yml" || [filename, job_name] == ["ci.yml", "ios"]
    if native_job
      check!(source_env["GRADLE_OPTS"] == NATIVE_SERIAL_OPTIONS,
        "native Release work must keep the reviewed serial worker policy")
      verify_runtime_environment!({ "env" => source_env.reject { |key, _| key == "GRADLE_OPTS" } })
    else
      verify_runtime_environment!(job)
    end
    apple = filename == "testflight.yml" || [filename, job_name] == ["ci.yml", "ios"]
    shipping = %w[android-internal-testing.yml testflight.yml].include?(filename)
    check!(job["runs-on"] == runtime.fetch("runners").fetch(apple ? "macos" : "linux") &&
      steps.none? { |step| step["uses"].to_s.start_with?("actions/setup-java@") },
      "runtime selection must match the reviewed runner and verified Java installer")
    roles = ["java"] + (shipping ? ["ruby"] : [])
    installers = roles.to_h do |role|
      indices = steps.each_index.select { |index| steps[index]["run"].to_s.strip == "#{RUNTIME_COMMAND} #{role}" }
      check!(indices.length == 1 && indices.first > 1 && steps[indices.first].keys.sort == %w[name run],
        "runtime selection must match the reviewed mandatory #{role} archive installer")
      [role, indices.first]
    end
    stop_indices = steps.each_index.select { |index| steps[index]["id"] == "stop-owned-gradle" }
    check!(stop_indices.length == 1, "one non-ignored owned worker-stop barrier is required")
    stop_index = stop_indices.first
    stop = steps[stop_index]
    check!(stop.keys.sort == %w[id if name run] && stop["if"] == RUNTIME_STOP_CONDITION &&
      stop["run"].to_s.strip == "./gradlew --stop",
      "one non-ignored owned worker-stop barrier is required")
    check!(steps.count { |step| step["run"].to_s.include?(RUNTIME_INSTALLER) } == roles.length + 1 &&
      steps.last.keys.sort == %w[if name run] && steps.last["if"] == RUNTIME_DISPOSAL_CONDITION &&
      steps.last["run"].strip == "#{RUNTIME_COMMAND} cleanup",
      "verified runtime cleanup must always be the final step behind the worker-stop barrier")
    steps.each_with_index do |step, index|
      command = step["run"].to_s
      # No Java area means the mandatory installer never published a usable runtime, so no
      # Gradle consumer can have started. Otherwise retain owned inputs/outputs on stop failure.
      disposable_outputs = command.include?("scripts/release/clean-mobile-build.py")
      if disposable_outputs || command.strip == "python3 -B scripts/release/prepare-libffi-source.py cleanup" ||
          command.strip == "#{RUNTIME_COMMAND} cleanup"
        condition = RUNTIME_DISPOSAL_CONDITION
        evidence = { "android-internal-testing.yml" => "preserve-artifacts", "mobile-release-checks.yml" => "preserve-evidence" }[filename]
        condition = condition.sub(" }}", " && steps.#{evidence}.outcome != 'failure' }}") if disposable_outputs && evidence
        check!(index > stop_index && step.keys.sort == %w[if name run] && step["if"] == condition,
          "owned input/output disposal must follow the successful worker-stop barrier")
      end
      if index != stop_index && (command.include?("./gradlew") || command.include?("xcodebuild ") ||
          command.include?("fastlane android upload_internal") || command.include?(LIBFFI_PREPARE_COMMAND) ||
          command.include?(LIBFFI_VERIFY_COMMAND))
        check!(index < stop_index, "owned worker-stop barrier must follow every build consumer")
      end
      if secrets_in?(step)
        check!(installers.values.all? { |installer| index > installer }, "protected input precedes verified runtime installation")
      end
      gradle_setup = step["uses"].to_s.start_with?("gradle/actions/setup-gradle@")
      if gradle_setup || step["run"].to_s.include?("./gradlew")
        check!(index > installers.fetch("java"),
          "Gradle consumption precedes verified runtime installation")
      end
      next unless gradle_setup

      # The pinned Action's write-enabled post hook executes Gradle after all job steps.
      # Read-only caching keeps restore behavior but never executes a deleted JAVA_HOME in post.
      check!((step.keys - %w[name uses with]).empty? && step["with"] == { "cache-read-only" => true },
        "Gradle setup must retain read-only caches before runtime cleanup")
    end
    if shipping
      ruby = steps.select { |step| step["uses"].to_s.start_with?("ruby/setup-ruby@") }
      check!(ruby.length == 1 && (ruby.first.keys - %w[name uses with]).empty? && ruby.first["with"] == {
        "ruby-version" => runtime.fetch("ruby").fetch("version"), "bundler" => "none",
        "bundler-cache" => true, "self-hosted" => true
      } && steps.index(ruby.first) == installers.fetch("ruby") + 1,
        "runtime selection must match the reviewed verified Ruby and locked Bundler without download fallback")
    end
    return unless apple

    check!(job.fetch("env", {})["DEVELOPER_DIR"] == runtime.fetch("xcode").fetch("developer_dir") &&
      steps.none? { |step| step.fetch("env", {}).key?("DEVELOPER_DIR") } &&
      steps.fetch(2).keys.sort == %w[name run] && steps[2]["name"] == "Verify the selected Xcode identity" &&
      steps[2]["run"].strip == XCODE_IDENTITY_COMMAND,
      "runtime selection must match the reviewed Xcode path and mandatory version/build probe")
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
    verify_runtime_environment!(workflow) if WORKFLOWS.include?(File.basename(file))
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
      steps.each { |step| verify_use!(step.fetch("uses")) if step.key?("uses") }
      installer_index = nil
      swiftpm_index = nil
      libffi_index = nil
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
        native_installers = steps.each_index.select { |index| steps[index]["run"].to_s.strip == LIBFFI_PREPARE_COMMAND }
        check!(native_installers.length == 1 && native_installers.first > swiftpm_index,
          "TestFlight requires one verified libffi source bootstrap")
        libffi_index = native_installers.first
        check!(steps[libffi_index].keys.sort == %w[name run], "libffi source bootstrap cannot be skipped or ignore failure")
        native_compile = steps.find { |step| step["name"] == APPLE_GRADLE_PREFLIGHT_NAME }
        check!(native_compile && steps.index(native_compile) > libffi_index &&
          !native_compile.key?("if") && !native_compile.key?("continue-on-error") &&
          native_compile["run"].to_s.lines.map(&:strip).first == LIBFFI_VERIFY_COMMAND &&
          native_compile["run"].to_s.lines.map(&:strip).last == LIBFFI_VERIFY_COMMAND,
          "native compilation must verify the private source dependency before and after execution")
        archives = steps.select { |step| step["id"] == "archive" }
        check!(archives.length == 1 && archives.first.keys.sort == %w[env id name run] &&
          steps.index(archives.first) > swiftpm_index && archives.first["run"].strip == LOCKED_ARCHIVE_COMMAND,
          "archive must use and recheck the same reviewed SwiftPM lock and package directory")
      end
      steps.each_with_index do |step, index|
        next unless secrets_in?(step)

        check!(index > 1, "protected input precedes bootstrap verification: #{name}")
        check!(!installer_index || index > installer_index, "protected input precedes verified XcodeGen installation")
        check!(!swiftpm_index || index > swiftpm_index, "protected input precedes locked SwiftPM preflight")
        check!(!libffi_index || index > libffi_index, "protected input precedes verified libffi source bootstrap")
      end
      verify_runtime_workflow!(File.basename(file), name, job)
      verify_gradle_workflow!(File.basename(file), name, steps)
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
    puts "Pinned Action, wrapper, strict Gradle graph, XcodeGen metadata, and pre-credential ordering checks passed"
  rescue ToolchainInputError, JSON::ParserError, REXML::ParseException, Psych::Exception, ArgumentError, KeyError, IndexError, SystemCallError => error
    abort("Toolchain input verification failed: #{error.message}")
  end
end
