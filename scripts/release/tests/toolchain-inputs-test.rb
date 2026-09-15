require "minitest/autorun"
require "digest"
require "fileutils"
require "json"
require "open3"
require "rbconfig"
require "tmpdir"
require "yaml"

class ToolchainInputsTest < Minitest::Test
  ROOT = File.expand_path("../../..", __dir__)
  INPUTS = %w[
    release/verified-tools.json gradlew gradlew.bat
    gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties
    scripts/release/verify-toolchain-inputs.rb scripts/release/install-xcodegen.sh
    scripts/release/cleanup-xcodegen.sh
    .github/workflows/ci.yml .github/workflows/android-internal-testing.yml
    .github/workflows/testflight.yml .github/workflows/internal-testing-release.yml
    iosApp/Package.resolved iosApp/project.yml
  ].freeze
  GENERATED_LOCK = "iosApp/iosApp.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved".freeze

  def test_committed_inputs_pass_the_actual_verifier
    with_checkout do |root|
      stdout, stderr, status = verify(root)
      assert status.success?, stderr
      assert_includes stdout, "pre-credential ordering checks passed"
    end
  end

  def test_only_the_reviewed_inline_release_request_is_exempt_from_bootstrap
    %i[changed_command added_step exposed_secret reusable_substitution other_job].each do |mutation|
      with_checkout do |root|
        mutate_workflow(root, "internal-testing-release.yml") do |workflow|
          jobs = workflow.fetch("jobs")
          guard = jobs.fetch("validate-request")
          case mutation
          when :changed_command
            guard.fetch("steps").first["run"] << "echo 'unsupported command'\n"
          when :added_step
            guard.fetch("steps") << { "run" => "echo 'unsupported step'" }
          when :exposed_secret
            guard["env"] = { "TOKEN" => "${{ secrets.KIRA_PACKAGES_READ_TOKEN }}" }
          when :reusable_substitution
            jobs["validate-request"] = { "uses" => "./.github/workflows/android-internal-testing.yml" }
          when :other_job
            jobs["unsupported-request"] = jobs.delete("validate-request")
          end
        end
        expected = mutation == :other_job ? "checkout must be first" : "unsupported inline release request guard"
        assert_rejected(verify(root), expected)
      end
    end
  end

  def test_floating_and_short_action_refs_are_rejected
    ["v4", "ea165f8"].each do |reference|
      with_checkout do |root|
        mutate_workflow(root, "ci.yml") do |workflow|
          workflow.fetch("jobs").fetch("jvm-android").fetch("steps").last["uses"] = "actions/upload-artifact@#{reference}"
        end
        assert_rejected(verify(root), "unreviewed or floating Action reference")
      end
    end
  end

  def test_changed_wrapper_bytes_are_rejected
    with_checkout do |root|
      File.open(File.join(root, "gradle/wrapper/gradle-wrapper.jar"), "ab") { |file| file.write("changed") }
      assert_rejected(verify(root), "wrapper input digest mismatch")
    end
  end

  def test_distribution_checksum_cannot_diverge_from_the_pin
    with_checkout do |root|
      relative = "gradle/wrapper/gradle-wrapper.properties"
      path = File.join(root, relative)
      File.write(path, File.read(path).sub(/^distributionSha256Sum=.*$/, "distributionSha256Sum=#{'0' * 64}"))
      # Even updating this file's digest must not bypass the distribution-level pin.
      mutate_pins(root) { |pins| pins.fetch("gradle").fetch("files")[relative] = Digest::SHA256.file(path).hexdigest }
      assert_rejected(verify(root), "must enforce the pinned distribution checksum")
    end
  end

  def test_bootstrap_must_be_mandatory_and_before_protected_inputs
    %i[late_guard ignored_failure global_secret].each do |mutation|
      with_checkout do |root|
        mutate_workflow(root, "ci.yml") do |workflow|
          steps = workflow.fetch("jobs").fetch("release-verify").fetch("steps")
          case mutation
          when :late_guard
            guard = steps.delete_at(1)
            protected_index = steps.index { |step| step.fetch("env", {}).key?("KEYSTORE_BASE64") }
            steps.insert(protected_index + 1, guard)
          when :ignored_failure
            steps.fetch(1)["continue-on-error"] = true
          when :global_secret
            workflow.fetch("env")["KIRA_PACKAGES_READ_TOKEN"] = "${{ secrets.KIRA_PACKAGES_READ_TOKEN }}"
          end
        end
        expected = mutation == :global_secret ? "workflow-wide secrets" : "mandatory bootstrap verification"
        assert_rejected(verify(root), expected)
      end
    end
  end

  def test_xcodegen_installation_precedes_protected_inputs
    with_checkout do |root|
      mutate_workflow(root, "testflight.yml") do |workflow|
        steps = workflow.fetch("jobs").fetch("build-and-upload").fetch("steps")
        installer = steps.delete_at(steps.index { |step| step["name"] == "Install verified XcodeGen" })
        protected_index = steps.index { |step| step.fetch("env", {}).key?("APP_STORE_CONNECT_KEY_ID") }
        steps.insert(protected_index + 1, installer)
      end
      assert_rejected(verify(root), "protected input precedes verified XcodeGen installation")
    end
  end

  def test_project_generation_cannot_fall_back_to_path_xcodegen
    with_checkout do |root|
      mutate_workflow(root, "testflight.yml") do |workflow|
        steps = workflow.fetch("jobs").fetch("build-and-upload").fetch("steps")
        steps.find { |step| step["name"] == "Generate the Xcode project" }["run"] = "xcodegen generate"
      end
      assert_rejected(verify(root), "project generation must use the verified XcodeGen path")
    end
  end

  def test_cleanup_must_always_immediately_follow_project_generation
    %i[missing late success_only ignored_failure].each do |mutation|
      with_checkout do |root|
        mutate_workflow(root, "testflight.yml") do |workflow|
          steps = workflow.fetch("jobs").fetch("build-and-upload").fetch("steps")
          index = steps.index { |step| step["name"] == "Remove temporary XcodeGen" }
          case mutation
          when :missing then steps.delete_at(index)
          when :late then steps << steps.delete_at(index)
          when :success_only then steps.fetch(index).delete("if")
          when :ignored_failure then steps.fetch(index)["continue-on-error"] = true
          end
        end
        assert_rejected(verify(root), "XcodeGen cleanup must always immediately follow project generation")
      end
    end
  end

  def test_shipping_lock_must_be_present_regular_and_hash_pinned
    %i[missing changed symlink unpinned].each do |mutation|
      with_checkout do |root|
        path = File.join(root, "iosApp/Package.resolved")
        case mutation
        when :missing then File.unlink(path)
        when :changed then File.open(path, "ab") { |file| file.write("\n") }
        when :symlink
          retained = File.join(root, "retained-lock")
          FileUtils.mv(path, retained)
          File.symlink(retained, path)
        when :unpinned then mutate_pins(root) { |pins| pins.fetch("swiftpm")["lock_sha256"] = nil }
        end
        expected = case mutation
        when :changed then "shipping SwiftPM lock digest mismatch"
        when :unpinned then "reviewed shipping SwiftPM lock SHA-256 is missing"
        else "reviewed shipping SwiftPM lock is missing, oversized, or symlinked"
        end
        assert_rejected(verify(root), expected)
      end
    end
  end

  def test_shipping_lock_requires_unique_immutable_pins_and_the_reviewed_firebase_root
    %i[duplicate branch different_firebase].each do |mutation|
      with_checkout do |root|
        path = File.join(root, "iosApp/Package.resolved")
        lock = JSON.parse(File.read(path))
        pins = lock.fetch("pins")
        case mutation
        when :duplicate then pins << pins.first.dup
        when :branch
          pins.first["state"].delete("revision")
          pins.first["state"]["branch"] = "main"
        when :different_firebase
          pins.find { |pin| pin["identity"] == "firebase-ios-sdk" }["state"]["version"] = "12.14.0"
        end
        File.write(path, JSON.pretty_generate(lock) + "\n")
        # A reviewed digest update alone must not waive structural/root constraints.
        mutate_pins(root) { |data| data.fetch("swiftpm")["lock_sha256"] = Digest::SHA256.file(path).hexdigest }
        expected = case mutation
        when :duplicate then "duplicate shipping SwiftPM identity"
        when :branch then "shipping SwiftPM pins must name exact public versions and revisions"
        else "shipping SwiftPM Firebase root differs from the pin"
        end
        assert_rejected(verify(root), expected)
      end
    end
  end

  def test_shipping_project_cannot_change_or_add_a_package_root
    %i[changed added].each do |mutation|
      with_checkout do |root|
        path = File.join(root, "iosApp/project.yml")
        project = YAML.safe_load(File.read(path), aliases: false)
        if mutation == :changed
          project.fetch("packages").fetch("Firebase")["exactVersion"] = "12.14.0"
        else
          project.fetch("packages")["Unreviewed"] = { "url" => "https://github.com/example/package", "from" => "1.0.0" }
        end
        File.write(path, YAML.dump(project))
        assert_rejected(verify(root), "shipping SwiftPM package declaration differs from the reviewed root")
      end
    end
  end

  def test_actual_restore_and_check_bind_only_the_canonical_shipping_project_lock
    with_checkout do |root|
      swiftpm_project_fixture(root)
      canonical = File.binread(File.join(root, "iosApp/Package.resolved"))
      generated = File.join(root, GENERATED_LOCK)
      unrelated = File.join(root, "UIKit.xcodeproj/Package.resolved")
      FileUtils.mkdir_p(File.dirname(unrelated))
      File.write(unrelated, "unrelated\n")
      _, stderr, status = verify(root, "--restore-swiftpm")
      assert status.success?, stderr
      assert_equal canonical, File.binread(generated)
      assert_equal 0o600, File.stat(generated).mode & 0o777
      _, stderr, status = verify(root, "--check-swiftpm")
      assert status.success?, stderr
      File.open(generated, "ab") { |file| file.write("\n") }
      assert_rejected(verify(root, "--check-swiftpm"), "generated shipping SwiftPM lock is missing, changed, or symlinked")
      assert_rejected(verify(root, "--restore-swiftpm"), "generated shipping SwiftPM lock is missing, changed, or symlinked")
      assert_equal canonical + "\n", File.binread(generated), "restore must not silently repair a stale lock"
      File.unlink(generated)
      assert_rejected(verify(root, "--check-swiftpm"), "generated shipping SwiftPM lock is missing, changed, or symlinked")
      refute File.exist?(generated), "check must not restore a missing lock"
      assert_equal canonical, File.binread(File.join(root, "iosApp/Package.resolved"))
      assert_equal "unrelated\n", File.read(unrelated)
    end
  end

  def test_restore_refuses_symlinked_project_directories_and_lock_destinations
    %i[project workspace lock].each do |mutation|
      with_checkout do |root|
        swiftpm_project_fixture(root)
        _, stderr, status = verify(root, "--restore-swiftpm")
        assert status.success?, stderr
        target = case mutation
        when :project then File.join(root, "iosApp/iosApp.xcodeproj")
        when :workspace then File.join(root, "iosApp/iosApp.xcodeproj/project.xcworkspace")
        else File.join(root, GENERATED_LOCK)
        end
        retained = File.join(root, "retained")
        FileUtils.mv(target, retained)
        File.symlink(retained, target)
        expected = mutation == :lock ? "generated shipping SwiftPM lock is missing, changed, or symlinked" :
          "generated shipping SwiftPM directory is missing or symlinked"
        assert_rejected(verify(root, "--restore-swiftpm"), expected)
        assert File.exist?(retained)
        assert File.symlink?(target)
      end
    end
  end

  def test_locked_swiftpm_preflight_is_mandatory_and_precedes_every_protected_input
    %i[missing ignored_failure skipped early_secret].each do |mutation|
      with_checkout do |root|
        mutate_workflow(root, "testflight.yml") do |workflow|
          steps = workflow.fetch("jobs").fetch("build-and-upload").fetch("steps")
          index = steps.index { |step| step["name"] == "Resolve the reviewed SwiftPM lock before protected inputs" }
          case mutation
          when :missing then steps.delete_at(index)
          when :ignored_failure then steps[index]["continue-on-error"] = true
          when :skipped then steps[index]["if"] = "false"
          when :early_secret
            protected = steps.delete_at(steps.index { |step| step["name"] == "Verify protected value names are present" })
            generator = steps.index { |step| step["name"] == "Generate the Xcode project" }
            steps.insert(generator, protected)
          end
        end
        expected = case mutation
        when :missing then "TestFlight requires one mandatory locked SwiftPM preflight"
        when :early_secret then "protected input precedes locked SwiftPM preflight"
        else "locked SwiftPM preflight must follow generation/cleanup without skips or update fallback"
        end
        assert_rejected(verify(root), expected)
      end
    end
  end

  def test_preflight_and_archive_cannot_refresh_pins_switch_paths_or_regenerate
    %i[preflight_update archive_update archive_path missing_archive_check regenerate].each do |mutation|
      with_checkout do |root|
        mutate_workflow(root, "testflight.yml") do |workflow|
          steps = workflow.fetch("jobs").fetch("build-and-upload").fetch("steps")
          preflight = steps.find { |step| step["name"] == "Resolve the reviewed SwiftPM lock before protected inputs" }
          archive = steps.find { |step| step["id"] == "archive" }
          case mutation
          when :preflight_update then preflight["run"].sub!("-onlyUsePackageVersionsFromResolvedFile", "")
          when :archive_update then archive["run"].sub!("-disableAutomaticPackageResolution", "")
          when :archive_path then archive["run"].sub!("$RUNNER_TEMP/DerivedData/SourcePackages", "$RUNNER_TEMP/other-packages")
          when :missing_archive_check
            archive["run"].sub!("/usr/bin/ruby scripts/release/verify-toolchain-inputs.rb --check-swiftpm\n", "")
          when :regenerate
            steps << steps.find { |step| step["name"] == "Generate the Xcode project" }.dup
          end
        end
        expected = case mutation
        when :preflight_update then "locked SwiftPM preflight must follow generation/cleanup without skips or update fallback"
        when :regenerate then "project generation must use the verified XcodeGen path exactly once"
        else "archive must use and recheck the same reviewed SwiftPM lock and package directory"
        end
        assert_rejected(verify(root), expected)
      end
    end
  end

  def test_actual_preflight_passes_locked_unsigned_arguments_and_rejects_resolver_drift
    %i[unchanged changed_lock failed_resolve].each do |outcome|
      with_checkout do |root|
        swiftpm_project_fixture(root)
        runner = File.join(root, "runner")
        FileUtils.mkdir_p(runner)
        trace = File.join(root, "resolve-arguments")
        bin = File.join(root, "fixture-bin")
        ending = case outcome
        when :changed_lock then "printf '\\n' >> #{GENERATED_LOCK}\n"
        when :failed_resolve then "exit 7\n"
        else "exit 0\n"
        end
        executable(File.join(bin, "xcodebuild"), "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$RESOLVE_TRACE\"\n" + ending)
        environment = { "PATH" => "#{bin}:#{ENV.fetch('PATH')}", "RUNNER_TEMP" => runner, "RESOLVE_TRACE" => trace }
        workflow = YAML.safe_load(File.read(File.join(root, ".github/workflows/testflight.yml")), aliases: false)
        script = workflow.fetch("jobs").fetch("build-and-upload").fetch("steps").find do |step|
          step["name"] == "Resolve the reviewed SwiftPM lock before protected inputs"
        end.fetch("run")
        result = Open3.capture3(environment, "/bin/bash", "-e", "-o", "pipefail", "-c", script, chdir: root)
        if outcome == :unchanged
          assert result.last.success?, result[1]
        elsif outcome == :changed_lock
          assert_rejected(result, "generated shipping SwiftPM lock is missing, changed, or symlinked")
        else
          assert_equal 7, result.last.exitstatus
        end
        assert_equal [
          "-resolvePackageDependencies", "-project", "iosApp/iosApp.xcodeproj", "-scheme", "iosApp", "-configuration", "Release",
          "-destination", "generic/platform=iOS", "-derivedDataPath", "#{runner}/DerivedData",
          "-clonedSourcePackagesDirPath", "#{runner}/DerivedData/SourcePackages", "-packageCachePath", "#{runner}/kira-swiftpm-cache",
          "-onlyUsePackageVersionsFromResolvedFile", "-disableAutomaticPackageResolution", "-skipPackageUpdates",
          "CODE_SIGNING_ALLOWED=NO", "CODE_SIGNING_REQUIRED=NO"
        ], File.readlines(trace, chomp: true)
      end
    end
  end

  def test_actual_installer_publishes_only_the_checked_executable
    with_checkout do |root|
      environment = installer_fixture(root)
      stdout, stderr, status = install(root, environment)
      assert status.success?, stderr
      executable = stdout.strip
      assert File.executable?(executable)
      assert executable.start_with?(File.realpath(environment.fetch("RUNNER_TEMP")) + "/kira-xcodegen.")
      assert_equal "KIRA_XCODEGEN=#{executable}\n", File.read(environment.fetch("GITHUB_ENV"))
      assert_equal "download\nextract\nversion:--version\n", File.read(environment.fetch("TOOLCHAIN_TRACE"))
      tool_dir = executable.delete_suffix("/xcodegen/bin/xcodegen")
      files = Dir.glob(File.join(tool_dir, "**", "*"), File::FNM_DOTMATCH).select { |path| File.file?(path) }
      assert_equal %w[
        .kira-xcodegen-owned xcodegen/LICENSE xcodegen/bin/xcodegen
        xcodegen/share/xcodegen/SettingPresets/Platforms/iOS.yml
        xcodegen/share/xcodegen/SettingPresets/base.yml
      ].sort, files.map { |path| path.delete_prefix(tool_dir + "/") }.sort
      assert_equal "#{executable}\n", File.read(File.join(tool_dir, ".kira-xcodegen-owned"))
    end
  end

  def test_actual_cleanup_removes_only_the_published_directory_even_before_generation
    with_checkout do |root|
      environment = installer_fixture(root)
      first, stderr, status = install(root, environment)
      assert status.success?, stderr
      second, stderr, status = install(root, environment)
      assert status.success?, stderr
      first, second = first.strip, second.strip

      # Earlier job failure may leave no published path; that must not scan/delete tools.
      _, stderr, status = cleanup(root, environment)
      assert status.success?, stderr
      assert File.executable?(first)
      assert File.executable?(second)
      2.times do
        _, stderr, status = cleanup(root, environment, first)
        assert status.success?, stderr
        refute File.exist?(first.delete_suffix("/xcodegen/bin/xcodegen"))
        assert File.executable?(second), "cleanup must not remove a different installation"
      end
    end
  end

  def test_install_and_cleanup_share_the_canonical_temporary_root
    with_checkout do |root|
      environment = installer_fixture(root)
      runner = environment.fetch("RUNNER_TEMP")
      alias_path = File.join(root, "runner-alias")
      File.symlink(runner, alias_path)
      environment["RUNNER_TEMP"] = alias_path
      stdout, stderr, status = install(root, environment)
      assert status.success?, stderr
      path = stdout.strip
      assert path.start_with?(File.realpath(runner) + "/kira-xcodegen.")
      _, stderr, status = cleanup(root, environment, path)
      assert status.success?, stderr
      refute File.exist?(path.delete_suffix("/xcodegen/bin/xcodegen"))
    end
  end

  def test_cleanup_refuses_outside_root_unrelated_and_unmarked_directories
    with_checkout do |root|
      environment = installer_fixture(root)
      runner = environment.fetch("RUNNER_TEMP")
      [
        [File.join(root, "outside", "kira-xcodegen.ABC123"), "not an installer-owned temporary path"],
        [File.join(runner, "unrelated"), "not an installer-owned temporary path"],
        [File.join(runner, "kira-xcodegen.ABC123"), "ownership marker is missing or invalid"]
      ].each do |directory, message|
        path = File.join(directory, "xcodegen/bin/xcodegen")
        executable(path, "#!/bin/sh\nexit 99\n")
        assert_rejected(cleanup(root, environment, path), message)
        assert File.executable?(path), "cleanup must preserve an unowned path"
      end
    end
  end

  def test_cleanup_refuses_symlinked_tool_paths_and_ownership_markers
    %i[directory bin_directory binary marker].each do |kind|
      with_checkout do |root|
        environment = installer_fixture(root)
        stdout, stderr, status = install(root, environment)
        assert status.success?, stderr
        path = stdout.strip
        directory = path.delete_suffix("/xcodegen/bin/xcodegen")
        linked_path = case kind
        when :directory then directory
        when :bin_directory then File.dirname(path)
        when :binary then path
        when :marker then File.join(directory, ".kira-xcodegen-owned")
        end
        retained = File.join(root, "retained")
        FileUtils.mv(linked_path, retained)
        File.symlink(retained, linked_path)
        message = kind == :marker ? "ownership marker is missing or invalid" : "refusing symlinked XcodeGen paths"
        assert_rejected(cleanup(root, environment, path), message)
        assert File.exist?(retained)
        assert File.symlink?(linked_path)
      end
    end
  end

  def test_bad_archive_is_rejected_before_extraction_or_execution
    with_checkout do |root|
      environment = installer_fixture(root)
      File.open(environment.fetch("TOOLCHAIN_FIXTURE_ARCHIVE"), "ab") { |file| file.write("changed") }
      assert_rejected(install(root, environment), "archive checksum mismatch")
      assert_unpublished(environment, "download\n")
    end
  end

  def test_bad_binary_is_rejected_before_the_version_probe
    with_checkout do |root|
      environment = installer_fixture(root)
      mutate_pins(root) { |pins| pins.fetch("xcodegen")["binary_sha256"] = "0" * 64 }
      assert_rejected(install(root, environment), "executable checksum mismatch")
      assert_unpublished(environment, "download\nextract\n")
    end
  end

  def test_wrong_version_is_rejected_before_publishing_the_path
    with_checkout do |root|
      environment = installer_fixture(root, version: "2.45.0")
      assert_rejected(install(root, environment), "executable version mismatch")
      assert_unpublished(environment, "download\nextract\nversion:--version\n")
    end
  end

  private

  def with_checkout
    Dir.mktmpdir("kira-toolchain-inputs-") do |root|
      INPUTS.each do |relative|
        destination = File.join(root, relative)
        FileUtils.mkdir_p(File.dirname(destination))
        FileUtils.cp(File.join(ROOT, relative), destination, preserve: true)
      end
      yield root
    end
  end

  def mutate_workflow(root, filename)
    path = File.join(root, ".github/workflows", filename)
    workflow = YAML.safe_load(File.read(path), aliases: false)
    yield workflow
    File.write(path, YAML.dump(workflow))
  end

  def mutate_pins(root)
    path = File.join(root, "release/verified-tools.json")
    pins = JSON.parse(File.read(path))
    yield pins
    File.write(path, JSON.pretty_generate(pins) + "\n")
  end

  def verify(root, *arguments)
    Open3.capture3(RbConfig.ruby, File.join(root, "scripts/release/verify-toolchain-inputs.rb"), *arguments, chdir: root)
  end

  def swiftpm_project_fixture(root)
    directory = File.join(root, "iosApp/iosApp.xcodeproj")
    FileUtils.mkdir_p(directory)
    # Only a file-ownership fixture. Real generated-project/Xcode proof is a separate macOS gate.
    File.write(File.join(directory, "project.pbxproj"), "// fixture project\n")
  end

  def install(root, environment)
    Open3.capture3(environment, "/bin/bash", File.join(root, "scripts/release/install-xcodegen.sh"), chdir: root)
  end

  def cleanup(root, environment, path = nil)
    Open3.capture3(environment.merge("KIRA_XCODEGEN" => path), "/bin/bash",
      File.join(root, "scripts/release/cleanup-xcodegen.sh"), chdir: root)
  end

  def assert_rejected(result, message)
    stdout, stderr, status = result
    refute status.success?, "unexpected success: #{stdout}"
    assert_includes stderr, message
  end

  def assert_unpublished(environment, trace)
    assert_equal "", File.read(environment.fetch("GITHUB_ENV"))
    assert_equal trace, File.read(environment.fetch("TOOLCHAIN_TRACE"))
    assert_empty Dir.glob(File.join(environment.fetch("RUNNER_TEMP"), "kira-xcodegen.*"))
  end

  def executable(path, contents)
    FileUtils.mkdir_p(File.dirname(path))
    File.write(path, contents)
    File.chmod(0o755, path)
  end

  def installer_fixture(root, version: "2.46.0")
    staging = File.join(root, "synthetic-archive")
    binary = File.join(staging, "xcodegen/bin/xcodegen")
    executable(binary, <<~SH)
      #!/bin/sh
      printf 'version:%s\n' "$*" >> "$TOOLCHAIN_TRACE"
      printf 'Version: #{version}\n'
    SH
    {
      "xcodegen/LICENSE" => "Fixture license\n",
      "xcodegen/install.sh" => "#!/bin/sh\nexit 99\n",
      "xcodegen/unused.txt" => "Must not be extracted\n",
      "xcodegen/share/xcodegen/SettingPresets/base.yml" => "PRODUCT_NAME: Fixture\n",
      "xcodegen/share/xcodegen/SettingPresets/Platforms/iOS.yml" => "SDKROOT: iphoneos\n"
    }.each do |relative, contents|
      path = File.join(staging, relative)
      FileUtils.mkdir_p(File.dirname(path))
      File.write(path, contents)
    end
    archive = File.join(root, "fixture.zip")
    assert system("/usr/bin/zip", "-qr", archive, "xcodegen", chdir: staging), "fixture ZIP could not be created"
    mutate_pins(root) do |pins|
      pins.fetch("xcodegen")["sha256"] = Digest::SHA256.file(archive).hexdigest
      pins.fetch("xcodegen")["binary_sha256"] = Digest::SHA256.file(binary).hexdigest
    end

    bin = File.join(root, "fixture-bin")
    executable(File.join(bin, "uname"), "#!/bin/sh\nprintf 'Darwin\\n'\n")
    executable(File.join(bin, "curl"), <<~SH)
      #!/bin/bash
      set -euo pipefail
      printf 'download\n' >> "$TOOLCHAIN_TRACE"
      output=''
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --output) output="$2"; shift 2 ;;
          *) shift ;;
        esac
      done
      [[ -n "$output" ]]
      cp "$TOOLCHAIN_FIXTURE_ARCHIVE" "$output"
    SH
    executable(File.join(bin, "unzip"), <<~SH)
      #!/bin/sh
      printf 'extract\n' >> "$TOOLCHAIN_TRACE"
      exec /usr/bin/unzip "$@"
    SH
    executable(File.join(bin, "xcodegen"), "#!/bin/sh\necho 'PATH XcodeGen must never execute' >&2\nexit 99\n")
    runner = File.join(root, "runner")
    FileUtils.mkdir_p(runner)
    environment_file = File.join(root, "github-env")
    File.write(environment_file, "")
    {
      "PATH" => "#{bin}:#{ENV.fetch('PATH')}", "RUNNER_TEMP" => runner,
      "GITHUB_ENV" => environment_file, "TOOLCHAIN_FIXTURE_ARCHIVE" => archive,
      "TOOLCHAIN_TRACE" => File.join(root, "tool-trace")
    }
  end
end
