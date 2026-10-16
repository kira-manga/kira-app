# frozen_string_literal: true

require "minitest/autorun"
require "tmpdir"
require_relative "../google-play-open-testing"

class GooglePlayOpenTestingTest < Minitest::Test
  class FakeClient
    attr_reader :deleted_edits, :calls

    def initialize(bundles:, apks:, tracks:)
      @bundles = bundles
      @apks = apks
      @tracks = tracks
      @deleted_edits = []
      @calls = []
    end

    def create_edit
      @calls << [:create_edit]
      "discarded-edit"
    end

    def get_track(edit_id, track)
      @calls << [:get_track, edit_id, track]
      @tracks.fetch(0)
    end

    def list_bundles(edit_id)
      @calls << [:list_bundles, edit_id]
      @bundles
    end

    def list_apks(edit_id)
      @calls << [:list_apks, edit_id]
      @apks
    end

    def list_tracks(edit_id)
      @calls << [:list_tracks, edit_id]
      @tracks
    end

    def delete_edit(edit_id)
      @calls << [:delete_edit, edit_id]
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

  def test_internal_preflight_only_checks_read_access_without_requiring_a_release
    client = FakeClient.new(bundles: [], apks: [], tracks: [{"track" => "internal"}])

    output, = capture_io { KiraGooglePlay.preflight_internal(client) }

    assert_match "Internal Testing is readable", output
    assert_match "upload/commit permissions are not verified", output
    assert_read_only_internal_edit(client)
  end

  def test_internal_verification_requires_the_selected_completed_version
    client = FakeClient.new(bundles: [], apks: [], tracks: [
      {"track" => "internal", "releases" => [
        {"status" => "completed", "versionCodes" => ["1005", "1007"]},
        {"status" => "draft", "versionCodes" => ["1008"]}
      ]}
    ])

    output, = capture_io { KiraGooglePlay.verify_internal(client, 1007) }

    assert_match "version code 1007 is completed on Google Play Internal Testing", output
    assert_read_only_internal_edit(client)
  end

  def test_internal_verification_rejects_a_version_only_on_open_testing
    client = FakeClient.new(bundles: [], apks: [], tracks: [
      {"track" => "internal", "releases" => [{"status" => "completed", "versionCodes" => ["1006"]}]},
      {"track" => "beta", "releases" => [{"status" => "completed", "versionCodes" => ["1007"]}]}
    ])

    capture_io { assert_raises(SystemExit) { KiraGooglePlay.verify_internal(client, 1007) } }

    assert_read_only_internal_edit(client)
  end

  def test_internal_verification_rejects_noncompleted_releases
    ["draft", "inProgress", "halted", nil].each do |status|
      client = FakeClient.new(bundles: [], apks: [], tracks: [
        {"track" => "internal", "releases" => [
          {"status" => "completed", "versionCodes" => ["1006"]},
          {"status" => status, "versionCodes" => ["1007"]}
        ]}
      ])

      capture_io { assert_raises(SystemExit) { KiraGooglePlay.verify_internal(client, 1007) } }

      assert_read_only_internal_edit(client)
    end
  end

  def test_internal_verification_rejects_an_empty_track
    client = FakeClient.new(bundles: [], apks: [], tracks: [{"track" => "internal", "releases" => []}])

    capture_io { assert_raises(SystemExit) { KiraGooglePlay.verify_internal(client, 1007) } }

    assert_read_only_internal_edit(client)
  end

  def test_internal_checks_reject_a_response_for_a_different_track
    ["beta", "production"].each do |track|
      [[:preflight_internal], [:verify_internal, 1007]].each do |method, *arguments|
        client = FakeClient.new(bundles: [], apks: [], tracks: [
          {"track" => track, "releases" => [{"status" => "completed", "versionCodes" => ["1007"]}]}
        ])

        capture_io { assert_raises(SystemExit) { KiraGooglePlay.public_send(method, client, *arguments) } }

        assert_read_only_internal_edit(client)
      end
    end
  end

  def test_internal_verification_rejects_invalid_version_input_before_api_access
    [0, -1, "1007", 1007.0, nil].each do |version_code|
      client = FakeClient.new(bundles: [], apks: [], tracks: [])

      capture_io { assert_raises(SystemExit) { KiraGooglePlay.verify_internal(client, version_code) } }

      assert_empty client.calls
    end
  end

  private

  def assert_read_only_internal_edit(client)
    # Unexpected update/validate/commit calls are unsupported by the fake and also fail the test.
    assert_equal [
      [:create_edit],
      [:get_track, "discarded-edit", "internal"],
      [:delete_edit, "discarded-edit"]
    ], client.calls
    assert_equal ["discarded-edit"], client.deleted_edits
  end
end
