#!/usr/bin/env ruby
# frozen_string_literal: true

require "base64"
require "json"
require "net/http"
require "openssl"
require "uri"

module KiraGooglePlay
  PACKAGE_NAME = "me.manga.kira"
  INTERNAL_TRACK = "internal"
  OPEN_TRACK = "beta"
  API_ROOT = "https://androidpublisher.googleapis.com/androidpublisher/v3"
  TOKEN_URL = "https://oauth2.googleapis.com/token"
  SCOPE = "https://www.googleapis.com/auth/androidpublisher"

  module_function

  def release_payload(version_code:, version_name:)
    {
      track: OPEN_TRACK,
      releases: [
        {
          name: "Kira Manga #{version_name} (#{version_code})",
          versionCodes: [version_code.to_s],
          status: "completed"
        }
      ]
    }
  end

  def version_codes(track)
    Array(track["releases"])
      .flat_map { |release| Array(release["versionCodes"]) }
      .map { |code| Integer(code, 10) }
  end

  class Client
    def initialize(service_account_json)
      @credentials = JSON.parse(service_account_json)
      @token = access_token
    rescue JSON::ParserError, KeyError, OpenSSL::PKey::PKeyError
      abort "FAIL Google Play service-account credentials are invalid"
    end

    def create_edit
      request(Net::HTTP::Post, app_path("edits")).fetch("id")
    end

    def get_track(edit_id, track)
      request(Net::HTTP::Get, app_path("edits/#{edit_id}/tracks/#{track}"))
    end

    def list_tracks(edit_id)
      request(Net::HTTP::Get, app_path("edits/#{edit_id}/tracks")).fetch("tracks", [])
    end

    def list_bundles(edit_id)
      request(Net::HTTP::Get, app_path("edits/#{edit_id}/bundles")).fetch("bundles", [])
    end

    def list_apks(edit_id)
      request(Net::HTTP::Get, app_path("edits/#{edit_id}/apks")).fetch("apks", [])
    end

    def update_track(edit_id, track, payload)
      request(Net::HTTP::Put, app_path("edits/#{edit_id}/tracks/#{track}"), payload)
    end

    def validate_edit(edit_id)
      request(Net::HTTP::Post, app_path("edits/#{edit_id}:validate"))
    end

    def commit_edit(edit_id)
      request(Net::HTTP::Post, app_path("edits/#{edit_id}:commit"))
    end

    def delete_edit(edit_id)
      request(Net::HTTP::Delete, app_path("edits/#{edit_id}"))
    end

    private

    def app_path(suffix)
      "applications/#{PACKAGE_NAME}/#{suffix}"
    end

    def request(method, path, body = nil)
      uri = URI("#{API_ROOT}/#{path}")
      request = method.new(uri)
      request["Authorization"] = "Bearer #{@token}"
      if body
        request["Content-Type"] = "application/json"
        request.body = JSON.generate(body)
      end

      response = Net::HTTP.start(
        uri.host,
        uri.port,
        use_ssl: true,
        open_timeout: 30,
        read_timeout: 120
      ) { |http| http.request(request) }

      return {} if response.is_a?(Net::HTTPNoContent)
      return JSON.parse(response.body) if response.is_a?(Net::HTTPSuccess)

      abort "FAIL Google Play API request: #{safe_error(response)}"
    rescue JSON::ParserError
      abort "FAIL Google Play API returned invalid JSON"
    end

    def safe_error(response)
      parsed = JSON.parse(response.body)
      message = parsed.dig("error", "message")
      message = "HTTP #{response.code}" if message.nil? || message.empty?
      message.gsub(/[\r\n]+/, " ")[0, 500]
    rescue JSON::ParserError
      "HTTP #{response.code}"
    end

    def access_token
      now = Time.now.to_i
      header = base64url(JSON.generate(alg: "RS256", typ: "JWT"))
      claims = base64url(
        JSON.generate(
          iss: @credentials.fetch("client_email"),
          scope: SCOPE,
          aud: TOKEN_URL,
          iat: now,
          exp: now + 3600
        )
      )
      signing_input = "#{header}.#{claims}"
      key = OpenSSL::PKey::RSA.new(@credentials.fetch("private_key"))
      signature = base64url(key.sign(OpenSSL::Digest::SHA256.new, signing_input))

      uri = URI(TOKEN_URL)
      request = Net::HTTP::Post.new(uri)
      request["Content-Type"] = "application/x-www-form-urlencoded"
      request.body = URI.encode_www_form(
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion: "#{signing_input}.#{signature}"
      )
      response = Net::HTTP.start(
        uri.host,
        uri.port,
        use_ssl: true,
        open_timeout: 30,
        read_timeout: 60
      ) { |http| http.request(request) }
      abort "FAIL Google Play OAuth authentication" unless response.is_a?(Net::HTTPSuccess)

      JSON.parse(response.body).fetch("access_token")
    end

    def base64url(value)
      Base64.urlsafe_encode64(value, padding: false)
    end
  end

  def with_discarded_edit(client)
    edit_id = client.create_edit
    begin
      yield edit_id
    ensure
      client.delete_edit(edit_id)
      puts "PASS temporary Google Play edit deleted without publication"
    end
  end

  def preflight(client, version_name)
    with_discarded_edit(client) do |edit_id|
      internal = client.get_track(edit_id, INTERNAL_TRACK)
      version_code = version_codes(internal).max
      abort "FAIL Internal Testing has no release available for Open Testing validation" unless version_code

      client.update_track(
        edit_id,
        OPEN_TRACK,
        release_payload(version_code: version_code, version_name: version_name)
      )
      client.validate_edit(edit_id)
      puts "PASS Google Play Internal and Open Testing tracks are publishable"
    end
  end

  def uploaded_version_codes(client, edit_id)
    bundle_codes = client.list_bundles(edit_id).map { |bundle| Integer(bundle.fetch("versionCode").to_s, 10) }
    apk_codes = client.list_apks(edit_id).map { |apk| Integer(apk.fetch("versionCode").to_s, 10) }
    track_codes = client.list_tracks(edit_id).flat_map { |track| version_codes(track) }
    (bundle_codes + apk_codes + track_codes).uniq.sort
  rescue KeyError, ArgumentError
    abort "FAIL Google Play returned an invalid uploaded version code"
  end

  def select_next_version_code(client, output_path)
    abort "FAIL KIRA_BUILD_NUMBER_FILE is missing" if output_path.to_s.empty?

    highest = with_discarded_edit(client) do |edit_id|
      codes = uploaded_version_codes(client, edit_id)
      abort "FAIL Google Play has no uploaded Android version code" if codes.empty?
      codes.max
    end
    selected = highest + 1
    abort "FAIL next Android version code exceeds Google Play limits" if selected > 2_100_000_000

    File.write(output_path, "#{selected}\n")
    File.chmod(0o600, output_path)
    puts "PASS highest uploaded Android version code is #{highest}"
    puts "PASS selected unused Android version code #{selected}"
    selected
  end

  def verify_version_code_unused(client, version_code)
    with_discarded_edit(client) do |edit_id|
      if uploaded_version_codes(client, edit_id).include?(version_code)
        abort "FAIL selected Android version code is already uploaded"
      end
    end
    puts "PASS selected Android version code remains unused"
  end

  def publish(client, version_code, version_name)
    edit_id = client.create_edit
    committed = false
    begin
      internal = client.get_track(edit_id, INTERNAL_TRACK)
      unless version_codes(internal).include?(version_code)
        abort "FAIL expected version code is not active on Internal Testing"
      end

      client.update_track(
        edit_id,
        OPEN_TRACK,
        release_payload(version_code: version_code, version_name: version_name)
      )
      client.validate_edit(edit_id)
      client.commit_edit(edit_id)
      committed = true
      puts "PASS version published to Google Play Open Testing"
    ensure
      client.delete_edit(edit_id) unless committed
    end

    with_discarded_edit(client) do |verification_edit_id|
      internal = client.get_track(verification_edit_id, INTERNAL_TRACK)
      open = client.get_track(verification_edit_id, OPEN_TRACK)
      abort "FAIL Internal Testing verification" unless version_codes(internal).include?(version_code)
      abort "FAIL Open Testing verification" unless version_codes(open).include?(version_code)
      puts "PASS version is active on Internal and Open Testing"
    end
  end
end

if $PROGRAM_NAME == __FILE__
  begin
    mode = ARGV.fetch(0, "")
    allowed_modes = %w[select-version-code verify-version-code-unused preflight publish]
    usage = "select-version-code|verify-version-code-unused|preflight|publish"
    abort "Usage: google-play-open-testing.rb #{usage}" unless allowed_modes.include?(mode)

    service_account_json = ENV.fetch("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON", "")
    abort "FAIL GOOGLE_PLAY_SERVICE_ACCOUNT_JSON is missing" if service_account_json.empty?

    package_name = ENV.fetch("KIRA_ANDROID_PACKAGE_NAME", KiraGooglePlay::PACKAGE_NAME)
    abort "FAIL unexpected Android package name" unless package_name == KiraGooglePlay::PACKAGE_NAME

    client = KiraGooglePlay::Client.new(service_account_json)
    if mode == "select-version-code"
      KiraGooglePlay.select_next_version_code(client, ENV.fetch("KIRA_BUILD_NUMBER_FILE", ""))
    elsif mode == "verify-version-code-unused"
      version_code = Integer(ENV.fetch("KIRA_BUILD_NUMBER"), 10)
      abort "FAIL KIRA_BUILD_NUMBER must be positive" unless version_code.positive?
      KiraGooglePlay.verify_version_code_unused(client, version_code)
    elsif mode == "preflight"
      version_name = ENV.fetch("KIRA_VERSION_NAME", "")
      abort "FAIL KIRA_VERSION_NAME is missing" if version_name.empty?
      KiraGooglePlay.preflight(client, version_name)
    else
      version_name = ENV.fetch("KIRA_VERSION_NAME", "")
      abort "FAIL KIRA_VERSION_NAME is missing" if version_name.empty?
      version_code = Integer(ENV.fetch("KIRA_BUILD_NUMBER"), 10)
      abort "FAIL KIRA_BUILD_NUMBER must be positive" unless version_code.positive?
      KiraGooglePlay.publish(client, version_code, version_name)
    end
  rescue KeyError, ArgumentError
    abort "FAIL required Google Play release input is missing or invalid"
  end
end
