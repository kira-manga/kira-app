# frozen_string_literal: true

module KiraRelease
  module Version
    class Error < StandardError; end

    ROOT = File.expand_path("..", __dir__)
    ENVIRONMENT_KEYS = %w[KIRA_VERSION_NAME KIRA_APP_VERSION MOBILE_RELEASE_VERSION_NAME MARKETING_VERSION].freeze
    GENERATED_PATH = "composeApp/build/generated/sourceRemote/commonMain/me/manga/kira/sources/runtime/GeneratedSourceRemoteConfig.kt"
    VERSION_PATTERN = /\A(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\z/
    module_function

    # These are release-only checks. Local/debug Gradle override precedence is unchanged.
    def selected_name(root: ROOT, environment: ENV)
      version = committed_name(root)
      ENVIRONMENT_KEYS.each do |key|
        next unless environment.key?(key)

        raise Error, "Explicit release version conflicts with the committed version" unless environment[key] == version
      end
      version
    end

    def committed_name(root)
      properties = read_bounded(File.join(root, "release/version.properties"), 16 * 1024)
      versions = []
      properties.each_line do |line|
        line = line.delete_suffix("\n").delete_suffix("\r")
        next if line.strip.empty? || line.lstrip.start_with?("#", "!")

        match = /\A([A-Z_]+)=(.*)\z/.match(line)
        raise Error, "Invalid committed release version properties" unless match

        versions << match[2] if match[1] == "VERSION_NAME"
      end
      raise Error, "A unique canonical committed release version is required" unless
        versions.length == 1 && versions.first.bytesize <= 64 && VERSION_PATTERN.match?(versions.first)

      versions.first
    end

    def verify_generated(root: ROOT, environment: ENV)
      version = selected_name(root: root, environment: environment)
      generated = read_bounded(File.join(root, GENERATED_PATH), 64 * 1024)
      declarations = generated.each_line.select { |line| line.match?(/\A[ \t]*const val APP_VERSION:/) }.map(&:strip)
      expected = "const val APP_VERSION: String = \"#{version}\""
      raise Error, "Generated source-catalog version differs from the selected release" unless declarations == [expected]

      version
    end

    def read_bounded(path, limit)
      value = File.binread(path, limit + 1) || ""
      raise Error, "Release version input exceeds its bound" if value.bytesize > limit

      value
    rescue SystemCallError, IOError
      raise Error, "Required release version input is unavailable"
    end
  end
end

if $PROGRAM_NAME == __FILE__
  abort("Usage: ruby release/version.rb [verify-generated]") unless ARGV.empty? || ARGV == ["verify-generated"]
  begin
    puts(ARGV.empty? ? KiraRelease::Version.selected_name : KiraRelease::Version.verify_generated)
  rescue KiraRelease::Version::Error => error
    abort("Release version verification failed: #{error.message}")
  end
end
