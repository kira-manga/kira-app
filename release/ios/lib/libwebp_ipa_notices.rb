# frozen_string_literal: true

require "fileutils"
require "rubygems"
require "tmpdir"
require_relative "libwebp_notices"

module KiraRelease
  # Notice-only inspection: not signature, candidate identity or upload authorization.
  module LibwebpIpaNotices
    MAX_IPA_BYTES = 2 * 1024 * 1024 * 1024
    MAX_ENTRIES = 20_000
    MAX_NAME_BYTES = 1024

    module_function

    def validate!(ipa_path)
      check!(File.file?(ipa_path) && File.size(ipa_path).between?(1, MAX_IPA_BYTES), "IPA is unavailable or oversized")
      load_zip!
      Zip::File.open(ipa_path) do |zip|
        entries = zip.entries
        check!(entries.length.between?(1, MAX_ENTRIES), "IPA entry inventory is empty or oversized")
        entries.each { |entry| check!(valid_name?(entry.name), "IPA contains an invalid entry path") }
        roots = entries.filter_map { |entry| entry.name[%r{\APayload/[^/]+\.app(?=/|\z)}] }.uniq
        check!(roots.length == 1, "IPA must contain exactly one Payload app")
        resources = notice_entries(entries, roots.first)
        Dir.mktmpdir("kira-libwebp-notices") do |directory|
          bundle = File.join(directory, "Settings.bundle")
          resources.each do |relative, entry|
            destination = File.join(bundle, relative) # relative comes only from RESOURCE_PATHS.
            FileUtils.mkdir_p(File.dirname(destination))
            File.binwrite(destination, read_entry(entry))
          end
          LibwebpNotices.validate_app!(directory)
        end
      end
      true
    rescue LibwebpNotices::Error
      raise
    rescue StandardError
      raise LibwebpNotices::Error, "libwebp notices: IPA inspection failed"
    end

    def load_zip!
      gem "rubyzip", "= 2.4.1"
      require "zip"
    rescue LoadError
      raise LibwebpNotices::Error, "libwebp notices: the existing locked RubyZip 2.4.1 is required; no fallback extraction"
    end

    def notice_entries(entries, app_root)
      prefix = "#{app_root}/Settings.bundle/"
      required = LibwebpNotices::RESOURCE_PATHS.to_h { |relative| [prefix + relative, relative] }
      directories = [prefix] + LibwebpNotices::LOCALES.map { |locale| "#{prefix}#{locale}.lproj/" }
      members = entries.select do |entry|
        entry.name.downcase.start_with?(prefix.downcase) || entry.name.downcase == prefix.chop.downcase
      end
      resources = {}
      members.each do |entry|
        if directories.include?(entry.name)
          check!(entry.directory?, "Settings directory entry has the wrong type")
        else
          relative = required[entry.name]
          check!(relative && entry.file? && !resources.key?(relative), "unexpected, linked or duplicate Settings resource")
          resources[relative] = entry
        end
      end
      check!(resources.keys.sort == LibwebpNotices::RESOURCE_PATHS.sort, "IPA is missing required Settings notice resources")
      resources
    end

    def read_entry(entry)
      limit = LibwebpNotices::MAX_RESOURCE_BYTES
      check!(entry.size.between?(1, limit), "IPA notice resource is empty or oversized")
      contents = entry.get_input_stream { |stream| stream.read(limit + 1) }
      check!(contents && contents.bytesize == entry.size && contents.bytesize <= limit, "IPA notice resource exceeded its bounded read")
      contents
    end

    def valid_name?(name)
      return false if name.empty? || name.bytesize > MAX_NAME_BYTES || name.start_with?("/") || name.match?(/\A[A-Za-z]:/)
      return false if name.include?("\\") || name.include?("\0")

      parts = name.split("/", -1)
      parts.pop if parts.last == ""
      parts.none? { |part| ["", ".", ".."].include?(part) }
    end

    def check!(condition, message)
      LibwebpNotices.check!(condition, message)
    end
  end
end
