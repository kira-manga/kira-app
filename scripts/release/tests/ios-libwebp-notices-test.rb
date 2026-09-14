# frozen_string_literal: true

require "minitest/autorun"
require_relative "libwebp-notice-fixtures"

class IosLibwebpNoticesTest < Minitest::Test
  include LibwebpNoticeFixtures

  def test_valid_source_and_packaged_notice_values
    with_source_fixture { |root| assert NOTICES.validate_repository!(root) }
    with_app_fixture { |app| assert NOTICES.validate_app!(app) }
  end

  def test_each_missing_or_tampered_public_notice_is_rejected
    %w[COPYING PATENTS].product([:missing, :tampered]).each do |name, change|
      with_source_fixture do |root|
        path = File.join(root, NOTICES::VENDOR_PATH, name)
        change == :missing ? File.unlink(path) : File.binwrite(path, File.binread(path) + "changed\n")
        assert_raises(NOTICES::Error) { NOTICES.validate_repository!(root) }
      end
    end
  end

  def test_changed_native_archive_or_header_is_rejected_without_relabeling_it
    %w[ios-arm64/libwebp.a include/webp/encode.h].each do |relative|
      with_source_fixture do |root|
        path = File.join(root, NOTICES::VENDOR_PATH, relative)
        bytes = File.binread(path)
        bytes.setbyte(0, bytes.getbyte(0) ^ 1)
        File.binwrite(path, bytes)
        assert_raises(NOTICES::Error) { NOTICES.validate_repository!(root) }
      end
    end
  end

  def test_wrong_version_or_missing_manifest_member_is_rejected
    changes = [
      ->(record) { record["version"] = "1.4.0" },
      ->(record) { record.fetch("files_sha256").delete("include/webp/types.h") }
    ]
    changes.each do |change|
      with_source_fixture do |root|
        path = File.join(root, NOTICES::VENDOR_PATH, "provenance.json")
        record = JSON.parse(File.read(path))
        change.call(record)
        File.write(path, JSON.pretty_generate(record) + "\n")
        assert_raises(NOTICES::Error) { NOTICES.validate_repository!(root) }
      end
    end
  end

  def test_resource_wiring_requires_real_whole_bundle_membership_not_comment_text
    changes = [
      ->(sources) { sources.pop },
      ->(sources) { sources.last["type"] = "group" },
      ->(sources) { sources.last.delete("buildPhase") },
      ->(sources) { sources.last["optional"] = true },
      ->(sources) { sources.first["excludes"].delete("Settings.bundle") },
      ->(sources) { sources << sources.last.dup },
      ->(sources) { sources.last["path"] += "/Root.plist" }
    ]
    changes.each do |change|
      with_source_fixture do |root|
        path = File.join(root, "iosApp/project.yml")
        project = YAML.safe_load(File.read(path), aliases: false)
        change.call(project.fetch("targets").fetch("iosApp").fetch("sources"))
        File.write(path, YAML.dump(project) + "# path: iosApp/Settings.bundle type: file buildPhase: resources\n")
        assert_raises(NOTICES::Error) { NOTICES.validate_repository!(root) }
      end
    end
  end

  def test_missing_bundle_root_child_or_locale_refuses
    ["Settings.bundle", "Settings.bundle/Root.plist", "Settings.bundle/Acknowledgements.plist",
      "Settings.bundle/ar.lproj/Root.strings"].each do |relative|
      with_app_fixture do |app|
        FileUtils.rm_r(File.join(app, relative))
        assert_raises(NOTICES::Error) { NOTICES.validate_app!(app) }
      end
    end
  end

  def test_root_requires_the_visible_suffix_free_child_link
    changes = [
      ->(root) { root["PreferenceSpecifiers"] = [] },
      ->(root) { root["PreferenceSpecifiers"].first["File"] = "../Acknowledgements" },
      ->(root) { root["PreferenceSpecifiers"].first["File"] = "Acknowledgements.plist" },
      ->(root) { root["PreferenceSpecifiers"].first["SupportedUserInterfaceIdioms"] = ["Phone"] },
      ->(root) { root["StringsTable"] = "Missing" }
    ]
    changes.each do |change|
      with_app_fixture do |app|
        rewrite_plist(File.join(app, "Settings.bundle/Root.plist"), &change)
        assert_raises(NOTICES::Error) { NOTICES.validate_app!(app) }
      end
    end
  end

  def test_complete_original_footers_version_and_unlocalized_child_are_required
    changes = [
      ->(child) { child["PreferenceSpecifiers"][0]["FooterText"] = "" },
      ->(child) { child["PreferenceSpecifiers"][0]["FooterText"].sub!(/THIS SOFTWARE.*\z/m, "") },
      ->(child) { child["PreferenceSpecifiers"][1]["FooterText"].chop! },
      ->(child) { child["PreferenceSpecifiers"][0]["Title"] = "libwebp 1.4.0 — COPYING" },
      ->(child) { child["StringsTable"] = "Acknowledgements" },
      ->(child) { child["PreferenceSpecifiers"][1]["SupportedUserInterfaceIdioms"] = ["Pad"] }
    ]
    changes.each do |change|
      with_app_fixture do |app|
        rewrite_plist(File.join(app, "Settings.bundle/Acknowledgements.plist"), &change)
        assert_raises(NOTICES::Error) { NOTICES.validate_app!(app) }
      end
    end
  end

  def test_localized_legal_override_or_bad_navigation_is_rejected
    with_app_fixture do |app|
      write_plist(File.join(app, "Settings.bundle/ar.lproj/Acknowledgements.plist"), {"PreferenceSpecifiers" => []})
      assert_raises(NOTICES::Error) { NOTICES.validate_app!(app) }
    end
    ["", NOTICES::NAVIGATION_KEY, "A changed packaged label"].each do |label|
      with_app_fixture do |app|
        write_plist(File.join(app, "Settings.bundle/ar.lproj/Root.strings"), {NOTICES::NAVIGATION_KEY => label})
        assert_raises(NOTICES::Error) { NOTICES.validate_app!(app) }
      end
    end
  end

  def test_symbolic_link_notice_inputs_and_packaged_resources_are_rejected
    with_source_fixture do |root|
      path = File.join(root, NOTICES::VENDOR_PATH, "COPYING")
      File.unlink(path)
      File.symlink(File.join(ROOT, NOTICES::VENDOR_PATH, "COPYING"), path)
      assert_raises(NOTICES::Error) { NOTICES.validate_repository!(root) }
    end
    with_app_fixture do |app|
      path = File.join(app, "Settings.bundle/Acknowledgements.plist")
      File.unlink(path)
      File.symlink(File.join(SETTINGS, "Acknowledgements.plist"), path)
      assert_raises(NOTICES::Error) { NOTICES.validate_app!(app) }
    end
  end

  def test_equivalent_xml_serialization_does_not_require_file_hash_equality
    with_app_fixture do |app|
      NOTICES::RESOURCE_PATHS.each do |relative|
        rewrite_plist(File.join(app, "Settings.bundle", relative)) { |value| value.replace(value.to_a.reverse.to_h) }
      end
      root = File.join(app, "Settings.bundle/Root.plist")
      xml = replace_root_link_scalar(File.binread(root), "Acknow<![CDATA[ledgements]]>")
      File.binwrite(root, xml.sub("<key>File</key>", "<key>Fi<![CDATA[le]]></key>"))
      assert NOTICES.validate_app!(app)
    end
  end

  def test_complete_split_scalars_preserve_existing_typed_plist_values
    Dir.mktmpdir("kira-plist-scalar-test") do |directory|
      path = File.join(directory, "scalar.plist")
      File.write(path, <<~XML)
        <?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict>
          <key>App<![CDATA[ID]]></key><string>me.manga<![CDATA[.kira]]><!--ignored-->&amp;more</string>
          <key>count</key><integer>1<![CDATA[2]]></integer>
          <key>ratio</key><real>1<![CDATA[.25]]></real>
          <key>data</key><data>aG<![CDATA[VsbG8=]]></data>
          <key>date</key><date>2026-09-13<![CDATA[T00:00:00Z]]></date>
          <key>booleans</key><array><true/><false/></array>
        </dict></plist>
      XML
      assert_equal({
        "AppID" => "me.manga.kira&more", "count" => 12, "ratio" => 1.25,
        "data" => "hello", "date" => Time.utc(2026, 9, 13), "booleans" => [true, false]
      }, KiraRelease::PlistReader.read(path))
    end
  end

  def test_unreachable_split_link_and_nested_scalar_content_are_rejected
    ["Acknowledgements<![CDATA[Missing]]>", "Acknowledgements<string>Missing</string>"].each do |scalar|
      with_app_fixture do |app|
        path = File.join(app, "Settings.bundle/Root.plist")
        File.binwrite(path, replace_root_link_scalar(File.binread(path), scalar))
        assert_raises(NOTICES::Error) { NOTICES.validate_app!(app) }
      end
    end
  end

  def test_compiled_binary_plists_preserve_values_on_macos
    skip "Apple plutil binary conversion is an explicit macOS boundary" unless RUBY_PLATFORM.include?("darwin")

    with_app_fixture do |app|
      NOTICES::RESOURCE_PATHS.each do |relative|
        assert system("/usr/bin/plutil", "-convert", "binary1", File.join(app, "Settings.bundle", relative))
      end
      assert NOTICES.validate_app!(app)
    end
  end
end
