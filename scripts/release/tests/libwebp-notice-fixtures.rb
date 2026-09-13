# frozen_string_literal: true

require "cgi"
require "fileutils"
require "tmpdir"
require_relative "../../../release/ios/lib/libwebp_notices"

module LibwebpNoticeFixtures
  ROOT = File.expand_path("../../..", __dir__)
  SETTINGS = File.join(ROOT, "iosApp/iosApp/Settings.bundle")
  NOTICES = KiraRelease::LibwebpNotices

  def with_source_fixture
    Dir.mktmpdir("kira-libwebp-source-test") do |root|
      FileUtils.mkdir_p(File.join(root, "platform/libs"))
      FileUtils.cp_r(File.join(ROOT, NOTICES::VENDOR_PATH), File.join(root, "platform/libs"))
      FileUtils.mkdir_p(File.join(root, "iosApp/iosApp"))
      FileUtils.cp(File.join(ROOT, "iosApp/project.yml"), File.join(root, "iosApp/project.yml"))
      install_settings(File.join(root, "iosApp/iosApp"))
      yield root
    end
  end

  def with_app_fixture
    Dir.mktmpdir("kira-libwebp-app-test") do |directory|
      app = File.join(directory, "Fixture.app")
      install_settings(app)
      yield app
    end
  end

  def install_settings(app)
    FileUtils.mkdir_p(app)
    FileUtils.cp_r(SETTINGS, app)
  end

  def rewrite_plist(path)
    value = KiraRelease::PlistReader.read(path)
    yield value
    write_plist(path, value)
  end

  def replace_root_link_scalar(xml, scalar_xml)
    original = "<string>Acknowledgements</string>"
    raise "Expected exactly one raw Root link" unless xml.scan(original).length == 1

    # Do not parse/re-serialize malformed XML: that could erase the regression input.
    xml.sub(original, "<string>#{scalar_xml}</string>")
  end

  def write_plist(path, value)
    FileUtils.mkdir_p(File.dirname(path))
    File.write(path, %(<?xml version="1.0" encoding="UTF-8"?><plist version="1.0">#{plist_value(value)}</plist>\n))
  end

  def plist_value(value)
    case value
    when Hash
      "<dict>" + value.map { |key, item| "<key>#{CGI.escapeHTML(key)}</key>#{plist_value(item)}" }.join + "</dict>"
    when Array then "<array>#{value.map { |item| plist_value(item) }.join}</array>"
    when String then "<string>#{CGI.escapeHTML(value)}</string>"
    when Integer then "<integer>#{value}</integer>"
    when TrueClass then "<true/>"
    when FalseClass then "<false/>"
    when Time then "<date>#{value.utc.iso8601}</date>"
    else raise "Unsupported notice-test plist value"
    end
  end

  def ipa_entries(app_name = "Kira.app")
    entries = NOTICES::RESOURCE_PATHS.to_h do |relative|
      ["Payload/#{app_name}/Settings.bundle/#{relative}", File.binread(File.join(SETTINGS, relative))]
    end
    entries["Payload/#{app_name}/unrelated-resource.txt"] = "Never extract or execute this entry.\n"
    entries
  end

  def write_ipa(path, entries)
    # Exercise the real, already-locked adapter dependency; absence is not a skipped/stubbed test.
    gem "rubyzip", "= 2.4.1"
    require "zip"
    Zip::OutputStream.open(path) do |zip|
      entries.each do |name, contents|
        zip.put_next_entry(name)
        zip.write(contents)
      end
    end
  end

  def with_ipa(entries = ipa_entries)
    Dir.mktmpdir("kira-libwebp-ipa-test") do |directory|
      path = File.join(directory, "candidate.ipa")
      write_ipa(path, entries)
      with_environment("TMPDIR" => directory) { yield path, directory }
    end
  end

  def with_environment(values)
    previous = values.to_h { |name, _value| [name, ENV[name]] }
    values.each { |name, value| ENV[name] = value }
    yield
  ensure
    previous.each { |name, value| ENV[name] = value }
  end
end
