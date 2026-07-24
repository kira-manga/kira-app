# frozen_string_literal: true

require "minitest/autorun"
require_relative "../google-play-open-testing"

class GooglePlayOpenTestingTest < Minitest::Test
  def test_release_payload_targets_completed_open_testing_release
    payload = KiraGooglePlay.release_payload(version_code: 1007, version_name: "1.0.0")

    assert_equal "beta", payload.fetch(:track)
    release = payload.fetch(:releases).fetch(0)
    assert_equal "completed", release.fetch(:status)
    assert_equal ["1007"], release.fetch(:versionCodes)
    assert_equal "Kira Manga 1.0.0 (1007)", release.fetch(:name)
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
end
