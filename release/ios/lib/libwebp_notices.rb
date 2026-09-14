# frozen_string_literal: true

require "digest"
require "json"
require "yaml"
require_relative "plist_reader"

module KiraRelease
  module LibwebpNotices
    class Error < StandardError; end

    REPOSITORY_ROOT = File.expand_path("../../..", __dir__)
    VENDOR_PATH = "platform/libs/libwebp"
    VERSION = "1.5.0"
    PROVENANCE_SHA256 = "c2a658e9188b7afb476183c6c469fa159f9f10faa02aa223ee6b6dedeb48bb31"
    NOTICE_NAMES = %w[COPYING PATENTS].freeze
    LOCALES = %w[en ar de es fr id in it ja pt ru tr].freeze
    NAVIGATION_KEY = "KIRA_THIRD_PARTY_NOTICES"
    RESOURCE_PATHS = (["Root.plist", "Acknowledgements.plist"] +
      LOCALES.map { |locale| "#{locale}.lproj/Root.strings" }).freeze
    MAX_RESOURCE_BYTES = 32 * 1024

    module_function

    def validate_repository!(root = REPOSITORY_ROOT)
      record = provenance(root)
      vendor = File.join(root, VENDOR_PATH)
      record.fetch("files_sha256").each do |relative, expected|
        path = contained_path(vendor, relative)
        check!(Digest::SHA256.file(path).hexdigest == expected, "vendored #{relative} digest changed")
      end
      validate_resource_wiring!(File.join(root, "iosApp/project.yml"))
      validate_app!(File.join(root, "iosApp/iosApp"), repository_root: root)
      true
    end

    def validate_app!(app_path, repository_root: REPOSITORY_ROOT)
      bundle = contained_path(app_path, "Settings.bundle", directory: true)
      source_bundle = contained_path(File.join(repository_root, "iosApp/iosApp"), "Settings.bundle", directory: true)
      expected_labels = labels(source_bundle)
      expected_texts = notice_texts(repository_root)
      validate_bundle!(bundle, expected_texts, expected_labels)
      true
    end

    def validate_resource_wiring!(project_path)
      project = YAML.safe_load(File.read(project_path), aliases: false)
      sources = project.is_a?(Hash) && project.dig("targets", "iosApp", "sources")
      check!(sources.is_a?(Array) && sources.length == 2, "expected recursive app source and one whole Settings resource")
      parents = sources.select { |source| source.is_a?(Hash) && source["path"] == "iosApp" }
      bundles = sources.select { |source| source.is_a?(Hash) && source["path"] == "iosApp/Settings.bundle" }
      check!(parents.length == 1 && Array(parents.first["excludes"]).include?("Settings.bundle"),
        "Settings.bundle must be excluded from recursive app sources")
      expected = {"path" => "iosApp/Settings.bundle", "type" => "file", "buildPhase" => "resources"}
      check!(bundles == [expected], "Settings.bundle must be one nonoptional whole-file resource")
      true
    rescue Psych::Exception, TypeError
      raise Error, "libwebp notices: invalid XcodeGen resource configuration"
    end

    def validate_bundle!(bundle, texts, expected_labels)
      expected_children = ["Root.plist", "Acknowledgements.plist"] + LOCALES.map { |locale| "#{locale}.lproj" }
      check!(Dir.children(bundle).sort == expected_children.sort, "unexpected or missing Settings resources")
      root = read_resource(bundle, "Root.plist")
      expected_root = {
        "StringsTable" => "Root",
        "PreferenceSpecifiers" => [
          {"Type" => "PSChildPaneSpecifier", "Title" => NAVIGATION_KEY, "File" => "Acknowledgements"}
        ]
      }
      check!(root == expected_root, "Root must expose the unmasked Acknowledgements child pane")
      child = read_resource(bundle, "Acknowledgements.plist")
      groups = NOTICE_NAMES.map do |name|
        {"Type" => "PSGroupSpecifier", "Title" => "libwebp #{VERSION} — #{name}", "FooterText" => texts.fetch(name)}
      end
      # Exact parsed values preserve every legal byte, including final newlines. No StringsTable,
      # idiom filter or alternate localized child may replace or hide either footer.
      check!(child == {"PreferenceSpecifiers" => groups}, "complete original libwebp #{VERSION} legal footers required")
      check!(labels(bundle) == expected_labels, "packaged navigation localizations differ from source")
    end

    def provenance(root)
      path = contained_path(File.join(root, VENDOR_PATH), "provenance.json")
      contents = bounded_read(path)
      record = JSON.parse(contents)
      check!(record.is_a?(Hash) && record["name"] == "libwebp" && record["version"] == VERSION,
        "wrong libwebp provenance version")
      check!(Digest::SHA256.hexdigest(contents) == PROVENANCE_SHA256, "provenance must retain the reviewed finite digest record")
      record
    rescue JSON::ParserError
      raise Error, "libwebp notices: invalid provenance record"
    end

    def notice_texts(root)
      record = provenance(root)
      NOTICE_NAMES.to_h do |name|
        text = bounded_read(contained_path(File.join(root, VENDOR_PATH), name))
        check!(Digest::SHA256.hexdigest(text) == record.fetch("files_sha256").fetch(name), "#{name} digest changed")
        text.force_encoding(Encoding::UTF_8)
        check!(text.valid_encoding?, "#{name} must remain UTF-8")
        [name, text]
      end
    end

    def labels(bundle)
      LOCALES.to_h do |locale|
        directory = contained_path(bundle, "#{locale}.lproj", directory: true)
        check!(Dir.children(directory) == ["Root.strings"], "#{locale} must not override or mask legal resources")
        values = read_resource(bundle, "#{locale}.lproj/Root.strings")
        label = values.is_a?(Hash) && values[NAVIGATION_KEY]
        check!(values.is_a?(Hash) && values.keys == [NAVIGATION_KEY] && label.is_a?(String) &&
          !label.strip.empty? && label != NAVIGATION_KEY, "#{locale} navigation localization is missing")
        check!(label == "Third-Party Notices", "default English navigation label changed") if locale == "en"
        [locale, values]
      end
    end

    def read_resource(bundle, relative)
      path = contained_path(bundle, relative)
      check!(File.size(path).between?(1, MAX_RESOURCE_BYTES), "oversized or empty Settings resource")
      PlistReader.read(path)
    rescue Error
      raise
    rescue StandardError
      raise Error, "libwebp notices: invalid plist resource #{relative}"
    end

    def contained_path(base, relative, directory: false)
      parts = relative.split("/")
      check!(!relative.start_with?("/") && !relative.include?("\\") &&
        parts.none? { |part| ["", ".", ".."].include?(part) }, "invalid notice resource path")
      check!(File.directory?(base) && !File.symlink?(base), "notice resource root is missing or linked")
      path = base
      parts.each do |part|
        path = File.join(path, part)
        check!(!File.symlink?(path), "notice resources must not be symbolic links")
      end
      check!(directory ? File.directory?(path) : File.file?(path), "required notice resource #{relative} is missing")
      path
    end

    def bounded_read(path)
      contents = File.binread(path, MAX_RESOURCE_BYTES + 1)
      check!(contents.bytesize.between?(1, MAX_RESOURCE_BYTES), "oversized or empty notice input")
      contents
    end

    def check!(condition, message)
      raise Error, "libwebp notices: #{message}" unless condition
    end
  end
end
