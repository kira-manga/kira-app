# frozen_string_literal: true

require "minitest/autorun"
require "tmpdir"
require_relative "../google-play-open-testing"

class GooglePlayOpenTestingTest < Minitest::Test
  class FakeClient
    attr_reader :deleted_edits

    def initialize(bundles:, apks:, tracks:)
      @bundles = bundles
      @apks = apks
      @tracks = tracks
      @deleted_edits = []
    end

    def create_edit
      "discarded-edit"
    end

    def list_bundles(_edit_id)
      @bundles
    end

    def list_apks(_edit_id)
      @apks
    end

    def list_tracks(_edit_id)
      @tracks
    end

    def delete_edit(edit_id)
      @deleted_edits << edit_id
    end
  end

  def test_release_payload_targets_completed_open_testing_release
    payload = KiraGooglePlay.release_payload(version_code: 1007, version_name: "1.0.5")

    assert_equal "beta", payload.fetch(:track)
    release = payload.fetch(:releases).fetch(0)
    assert_equal "completed", release.fetch(:status)
    assert_equal ["1007"], release.fetch(:versionCodes)
    assert_equal "Kira Manga 1.0.5 (1007)", release.fetch(:name)
  end

  def test_version_codes_reads_every_release
    track = {
      "releases" => [
        {"versionCodes" => ["1001", "1002"]},
        {"versionCodes" => ["1007"]}
      ]
    }

    assert_equal [1001, 1002, 1007], KiraGooglePlay.version_codes(track)
  end

  def test_next_version_code_covers_bundles_apks_and_every_track
    client = FakeClient.new(
      bundles: [{"versionCode" => 1004}],
      apks: [{"versionCode" => "998"}],
      tracks: [
        {"track" => "internal", "releases" => [{"versionCodes" => ["1002"]}]},
        {"track" => "production", "releases" => [{"versionCodes" => ["1003"]}]}
      ]
    )

    Dir.mktmpdir("kira-version-code-test") do |directory|
      path = File.join(directory, "version-code")
      selected = KiraGooglePlay.select_next_version_code(client, path)

      assert_equal 1005, selected
      assert_equal "1005\n", File.read(path)
      assert_equal 0o600, File.stat(path).mode & 0o777
    end
    assert_equal ["discarded-edit"], client.deleted_edits
  end

  def test_missing_uploaded_version_codes_fail_closed_and_discard_edit
    client = FakeClient.new(bundles: [], apks: [], tracks: [])

    Dir.mktmpdir("kira-version-code-test") do |directory|
      assert_raises(SystemExit) do
        KiraGooglePlay.select_next_version_code(client, File.join(directory, "version-code"))
      end
    end
    assert_equal ["discarded-edit"], client.deleted_edits
  end

  def test_selected_version_code_must_still_be_unused
    client = FakeClient.new(
      bundles: [{"versionCode" => 1004}],
      apks: [],
      tracks: []
    )

    KiraGooglePlay.verify_version_code_unused(client, 1005)
    assert_equal ["discarded-edit"], client.deleted_edits
  end

  def test_reused_selected_version_code_fails_closed
    client = FakeClient.new(
      bundles: [{"versionCode" => 1005}],
      apks: [],
      tracks: []
    )

    assert_raises(SystemExit) do
      KiraGooglePlay.verify_version_code_unused(client, 1005)
    end
    assert_equal ["discarded-edit"], client.deleted_edits
  end
end
