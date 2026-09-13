# frozen_string_literal: true

require "minitest/autorun"
require_relative "libwebp-notice-fixtures"
require_relative "../../../release/ios/lib/libwebp_ipa_notices"

class IosLibwebpIpaNoticesTest < Minitest::Test
  include LibwebpNoticeFixtures
  ADAPTER = KiraRelease::LibwebpIpaNotices
  CHILD = "Payload/Kira.app/Settings.bundle/Acknowledgements.plist"

  def test_tiny_zip_checks_only_notice_resources_and_removes_its_staging
    with_ipa do |path, directory|
      assert ADAPTER.validate!(path)
      assert_equal ["candidate.ipa"], Dir.children(directory)
    end
  end

  def test_missing_or_changed_legal_notice_refuses_and_cleans_staging
    [:missing, :changed].each do |change|
      entries = ipa_entries
      change == :missing ? entries.delete(CHILD) : entries[CHILD] = entries[CHILD].sub("AS IS", "CHANGED")
      assert_zip_refuses(entries)
    end
  end

  def test_missing_and_ambiguous_payload_apps_refuse
    assert_zip_refuses({"unrelated.txt" => "No application"})
    assert_zip_refuses(ipa_entries.merge(ipa_entries("Second.app")))
  end

  def test_traversal_entry_is_never_staged
    entries = ipa_entries.merge("Payload/Kira.app/Settings.bundle/../escape.txt" => "Never write me")
    assert_zip_refuses(entries)
  end

  def test_unexpected_localized_child_and_case_alias_refuse
    entries = ipa_entries.merge("Payload/Kira.app/Settings.bundle/ar.lproj/Acknowledgements.plist" => "hidden legal text")
    assert_zip_refuses(entries)
    entries = ipa_entries.merge("Payload/Kira.app/settings.bundle/Root.plist" => "case alias")
    assert_zip_refuses(entries)
  end

  def test_oversized_required_resource_refuses_before_unbounded_read
    entries = ipa_entries
    entries[CHILD] = "x" * (NOTICES::MAX_RESOURCE_BYTES + 1)
    assert_zip_refuses(entries)
  end

  def test_invalid_paths_are_rejected_by_the_same_metadata_gate
    ["/absolute", "C:/absolute", "Payload//Kira.app/file", "Payload/./Kira.app/file",
      "Payload/Kira.app/../file", "Payload/Kira.app/back\\slash", "Payload/zero\0byte"].each do |name|
      refute ADAPTER.valid_name?(name), name.inspect
    end
    assert ADAPTER.valid_name?("Payload/Kira.app/Settings.bundle/en.lproj/Root.strings")
  end

  def test_non_zip_input_refuses_without_a_fallback_extractor
    Dir.mktmpdir("kira-libwebp-invalid-zip") do |directory|
      path = File.join(directory, "candidate.ipa")
      File.write(path, "not a ZIP archive")
      assert_raises(NOTICES::Error) { ADAPTER.validate!(path) }
      assert_equal ["candidate.ipa"], Dir.children(directory)
    end
  end

  private

  def assert_zip_refuses(entries)
    with_ipa(entries) do |path, directory|
      assert_raises(NOTICES::Error) { ADAPTER.validate!(path) }
      assert_equal ["candidate.ipa"], Dir.children(directory)
    end
  end
end
