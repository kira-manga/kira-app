require "cgi"

build_number = File.read(ENV.fetch("KIRA_BUILD_NUMBER_FILE")).strip
profile_uuid = File.read(ENV.fetch("KIRA_PROFILE_UUID_FILE")).strip
abort("Build number must be a positive integer") unless build_number.match?(/\A[1-9]\d*\z/)
abort("Provisioning profile UUID is invalid") unless profile_uuid.match?(/\A[0-9A-Fa-f-]+\z/)

xcconfig = <<~XCCONFIG
  KIRA_CODE_SIGN_STYLE = Manual
  KIRA_CODE_SIGN_IDENTITY = Apple Distribution
  KIRA_PROVISIONING_PROFILE_SPECIFIER = #{profile_uuid}
  KIRA_OTHER_CODE_SIGN_FLAGS = --keychain #{ENV.fetch("KIRA_KEYCHAIN_PATH")}
  MARKETING_VERSION = 1.0.5
  CURRENT_PROJECT_VERSION = #{build_number}
  KIRA_APP_STORE_ID = 6792232678
  KIRA_CRASH_DIAGNOSTICS_ENABLED = NO
XCCONFIG
File.write(ENV.fetch("KIRA_XCCONFIG_PATH"), xcconfig)
File.chmod(0o600, ENV.fetch("KIRA_XCCONFIG_PATH"))

bundle_id = "me.manga.kira"
export_options = <<~PLIST
  <?xml version="1.0" encoding="UTF-8"?>
  <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
  <plist version="1.0">
  <dict>
    <key>destination</key>
    <string>export</string>
    <key>manageAppVersionAndBuildNumber</key>
    <false/>
    <key>method</key>
    <string>app-store-connect</string>
    <key>provisioningProfiles</key>
    <dict>
      <key>#{CGI.escapeHTML(bundle_id)}</key>
      <string>#{CGI.escapeHTML(profile_uuid)}</string>
    </dict>
    <key>signingCertificate</key>
    <string>Apple Distribution</string>
    <key>signingStyle</key>
    <string>manual</string>
    <key>stripSwiftSymbols</key>
    <true/>
    <key>teamID</key>
    <string>7CGZ2343AA</string>
    <key>thinning</key>
    <string>&lt;none&gt;</string>
  </dict>
  </plist>
PLIST
File.write(ENV.fetch("KIRA_EXPORT_OPTIONS_PATH"), export_options)
File.chmod(0o600, ENV.fetch("KIRA_EXPORT_OPTIONS_PATH"))

puts "Release configuration generated for version 1.0.5 build #{build_number} with crash diagnostics disabled"
