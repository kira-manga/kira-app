require "fileutils"
require "json"
require "open3"
require "tmpdir"
require_relative "lib/plist_reader"

EXPECTED_APP_STORE_ID = "6792232678"
EXPECTED_BUNDLE_ID = "me.manga.kira"
EXPECTED_TEAM_ID = "7CGZ2343AA"
EXPECTED_VERSION = "1.0.0"
EXPECTED_DOMAIN = "applinks:kiramanga.me"

archive_path = ENV.fetch("KIRA_ARCHIVE_PATH")
ipa_path = ENV.fetch("KIRA_IPA_PATH")
build_number = ENV.fetch("KIRA_BUILD_NUMBER")
expected_profile_uuid = File.read(ENV.fetch("KIRA_PROFILE_UUID_FILE")).strip
expected_application_identifier = File.read(ENV.fetch("KIRA_APPLICATION_IDENTIFIER_FILE")).strip

abort("Archive is unavailable") unless File.directory?(archive_path)
abort("IPA is unavailable") unless File.file?(ipa_path)
abort("Expected build number is invalid") unless build_number.match?(/\A[1-9]\d*\z/)

def command_success?(*command)
  system(*command, out: File::NULL, err: File::NULL)
end

def capture(*command)
  stdout, _stderr, status = Open3.capture3(*command)
  raise "Artifact inspection command failed" unless status.success?

  stdout
end

def validate_firebase(app_path)
  path = File.join(app_path, "GoogleService-Info.plist")
  raise "Firebase plist is missing from the app bundle" unless File.file?(path)

  firebase = KiraRelease::PlistReader.read(path)
  raise "Bundled Firebase configuration has the wrong bundle ID" unless firebase["BUNDLE_ID"] == EXPECTED_BUNDLE_ID
  %w[API_KEY GOOGLE_APP_ID PROJECT_ID].each do |key|
    value = firebase[key].to_s
    raise "Bundled Firebase configuration is incomplete" if value.empty? || value.match?(/replace|placeholder|example/i)
  end
end

def decode_profile(app_path, destination)
  embedded = File.join(app_path, "embedded.mobileprovision")
  raise "Embedded provisioning profile is missing" unless File.file?(embedded)
  ok = system("security", "cms", "-D", "-i", embedded, "-o", destination, out: File::NULL, err: File::NULL)
  raise "Embedded provisioning profile could not be decoded" unless ok

  KiraRelease::PlistReader.read(destination)
end

def signed_entitlements(app_path)
  stdout, _stderr, status = Open3.capture3("codesign", "-d", "--entitlements", ":-", app_path)
  raise "Signed entitlements could not be read" unless status.success?

  Dir.mktmpdir("kira-entitlements") do |directory|
    path = File.join(directory, "entitlements.plist")
    File.binwrite(path, stdout)
    return KiraRelease::PlistReader.read(path)
  end
end

def validate_profile_and_entitlements(profile, entitlements, expected_profile_uuid, expected_application_identifier)
  profile_entitlements = profile.fetch("Entitlements", {})
  raise "Artifact uses the wrong provisioning profile" unless profile["UUID"] == expected_profile_uuid
  raise "Artifact provisioning profile is expired" unless profile["ExpirationDate"].is_a?(Time) && profile["ExpirationDate"] > Time.now
  raise "Artifact provisioning profile has the wrong team" unless Array(profile["TeamIdentifier"]).include?(EXPECTED_TEAM_ID)
  raise "Artifact provisioning profile allows development debugging" unless profile_entitlements["get-task-allow"] == false
  raise "Artifact provisioning profile contains devices" unless Array(profile["ProvisionedDevices"]).empty?
  raise "Artifact provisioning profile is not App Store distribution" if profile["ProvisionsAllDevices"] == true
  raise "Artifact provisioning profile is not App Store Connect/TestFlight distribution" unless profile_entitlements["beta-reports-active"] == true

  raise "Signed application identifier is incorrect" unless entitlements["application-identifier"] == expected_application_identifier
  raise "Signed team identifier is incorrect" unless entitlements["com.apple.developer.team-identifier"] == EXPECTED_TEAM_ID
  raise "Signed APNs environment is not production" unless entitlements["aps-environment"] == "production"
  domains = Array(entitlements["com.apple.developer.associated-domains"])
  raise "Signed Associated Domains entitlement is missing" unless domains.include?(EXPECTED_DOMAIN)
  raise "Signed artifact permits debugger attachment" unless entitlements["get-task-allow"] != true
end

def validate_no_advertising_identity(binary_path)
  linked_libraries = capture("otool", "-L", binary_path)
  raise "Artifact links Apple's advertising-identifier framework" if linked_libraries.include?("AdSupport.framework")

  _stdout, _stderr, grep_status = Open3.capture3(
    "grep",
    "-a",
    "-E",
    "-q",
    "ASIdentifierManager|advertisingIdentifier",
    binary_path
  )
  raise "Artifact contains advertising-identifier API references" if grep_status.exitstatus == 0
  raise "Artifact advertising-identifier inspection failed" unless grep_status.exitstatus == 1
end

def validate_app(app_path, build_number, expected_profile_uuid, expected_application_identifier, profile_output)
  info_path = File.join(app_path, "Info.plist")
  raise "App Info.plist is missing" unless File.file?(info_path)
  info = KiraRelease::PlistReader.read(info_path)
  raise "Artifact bundle ID is incorrect" unless info["CFBundleIdentifier"] == EXPECTED_BUNDLE_ID
  raise "Artifact marketing version is incorrect" unless info["CFBundleShortVersionString"] == EXPECTED_VERSION
  raise "Artifact build number is incorrect" unless info["CFBundleVersion"].to_s == build_number
  raise "Artifact App Store ID is incorrect" unless info["KiraAppStoreID"].to_s == EXPECTED_APP_STORE_ID
  crash_diagnostics_enabled = info["KiraCrashDiagnosticsEnabled"] == true ||
    %w[yes true 1].include?(info["KiraCrashDiagnosticsEnabled"].to_s.downcase)
  raise "Internal TestFlight crash diagnostics are not enabled" unless crash_diagnostics_enabled
  raise "Artifact export-compliance declaration is incorrect" unless info["ITSAppUsesNonExemptEncryption"] == false
  raise "Privacy manifest is missing from the app bundle" unless File.file?(File.join(app_path, "PrivacyInfo.xcprivacy"))
  validate_firebase(app_path)

  raise "App code signature verification failed" unless command_success?("codesign", "--verify", "--deep", "--strict", app_path)
  architectures = capture("lipo", "-archs", File.join(app_path, info.fetch("CFBundleExecutable"))).split
  raise "Artifact does not contain arm64 device code" unless architectures.include?("arm64")
  raise "Artifact contains non-device architectures" unless architectures == ["arm64"]
  validate_no_advertising_identity(File.join(app_path, info.fetch("CFBundleExecutable")))

  profile = decode_profile(app_path, profile_output)
  entitlements = signed_entitlements(app_path)
  validate_profile_and_entitlements(profile, entitlements, expected_profile_uuid, expected_application_identifier)
  [info, File.join(app_path, info.fetch("CFBundleExecutable"))]
end

def uuids(path)
  output = capture("dwarfdump", "--uuid", path)
  output.scan(/UUID: ([0-9A-Fa-f-]+)/).flatten.map(&:upcase).sort
end

archive_info = KiraRelease::PlistReader.read(File.join(archive_path, "Info.plist"))
archive_properties = archive_info.fetch("ApplicationProperties", {})
abort("Archive bundle ID is incorrect") unless archive_properties["CFBundleIdentifier"] == EXPECTED_BUNDLE_ID
abort("Archive marketing version is incorrect") unless archive_properties["CFBundleShortVersionString"] == EXPECTED_VERSION
abort("Archive build number is incorrect") unless archive_properties["CFBundleVersion"].to_s == build_number

archive_app = File.join(archive_path, "Products/Applications/Kira.app")
archive_dsym = File.join(archive_path, "dSYMs/Kira.app.dSYM")
abort("Archive app is missing") unless File.directory?(archive_app)
abort("Archive dSYM is missing") unless File.directory?(archive_dsym)

Dir.mktmpdir("kira-artifact-validation") do |directory|
  _archive_app_info, archive_binary = validate_app(
    archive_app,
    build_number,
    expected_profile_uuid,
    expected_application_identifier,
    File.join(directory, "archive-profile.plist")
  )
  binary_uuids = uuids(archive_binary)
  dsym_uuids = uuids(archive_dsym)
  abort("Archive executable or dSYM has no UUID") if binary_uuids.empty? || dsym_uuids.empty?
  abort("Archive executable UUIDs do not match its dSYM") unless binary_uuids == dsym_uuids

  ipa_directory = File.join(directory, "ipa")
  FileUtils.mkdir_p(ipa_directory)
  abort("IPA could not be expanded") unless command_success?("ditto", "-x", "-k", ipa_path, ipa_directory)
  ipa_apps = Dir.glob(File.join(ipa_directory, "Payload", "*.app"))
  abort("IPA does not contain exactly one app") unless ipa_apps.length == 1
  _ipa_app_info, ipa_binary = validate_app(
    ipa_apps.first,
    build_number,
    expected_profile_uuid,
    expected_application_identifier,
    File.join(directory, "ipa-profile.plist")
  )
  abort("IPA executable UUIDs differ from the signed archive") unless uuids(ipa_binary) == binary_uuids
end

result = {
  app_store_id: EXPECTED_APP_STORE_ID,
  bundle_id: EXPECTED_BUNDLE_ID,
  team_id: EXPECTED_TEAM_ID,
  version: EXPECTED_VERSION,
  build: build_number,
  archive_valid: true,
  ipa_valid: true,
  distribution_signing_valid: true,
  push_notifications_valid: true,
  associated_domains_valid: true,
  advertising_identifiers_absent: true,
  crash_diagnostics_enabled: true,
  dsym_uuid_match: true,
  crashlytics_dsym_upload_marker: File.file?(ENV.fetch("CRASHLYTICS_DSYM_UPLOAD_MARKER"))
}
abort("Crashlytics dSYM upload was not confirmed") unless result[:crashlytics_dsym_upload_marker]

File.write(ENV.fetch("KIRA_ARTIFACT_STATUS_FILE"), JSON.pretty_generate(result) + "\n")
File.chmod(0o600, ENV.fetch("KIRA_ARTIFACT_STATUS_FILE"))
puts "Signed archive and IPA validated for App Store Connect distribution"
puts "Application identifier, team, version, build, Push Notifications, Associated Domains, and dSYM UUIDs verified"
