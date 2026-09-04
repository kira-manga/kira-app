require_relative "lib/plist_reader"

root = File.expand_path("../..", __dir__)
project_path = File.join(root, "iosApp/project.yml")
info_path = File.join(root, "iosApp/iosApp/Info.plist")
entitlements_path = File.join(root, "iosApp/iosApp/iosApp.entitlements")

project = File.read(project_path)
required_project_lines = [
  "PRODUCT_BUNDLE_IDENTIFIER: me.manga.kira",
  "MARKETING_VERSION: \"1.0.5\"",
  "KIRA_APP_STORE_ID: \"6792232678\"",
  "KIRA_CRASH_DIAGNOSTICS_ENABLED: \"NO\"",
  "exactVersion: \"12.15.0\"",
  "MOBILE_RELEASE_IOS_CODE_SIGN_STYLE: \"$(KIRA_CODE_SIGN_STYLE)\"",
  "MOBILE_RELEASE_IOS_CODE_SIGN_IDENTITY: \"$(KIRA_CODE_SIGN_IDENTITY)\"",
  "MOBILE_RELEASE_IOS_PROVISIONING_PROFILE_SPECIFIER: \"$(KIRA_PROVISIONING_PROFILE_SPECIFIER)\"",
  "MOBILE_RELEASE_IOS_DEVELOPMENT_TEAM: 7CGZ2343AA",
  "CODE_SIGN_STYLE: \"$(MOBILE_RELEASE_IOS_CODE_SIGN_STYLE)\"",
  "CODE_SIGN_IDENTITY: \"$(MOBILE_RELEASE_IOS_CODE_SIGN_IDENTITY)\"",
  "PROVISIONING_PROFILE_SPECIFIER: \"$(MOBILE_RELEASE_IOS_PROVISIONING_PROFILE_SPECIFIER)\"",
  "OTHER_CODE_SIGN_FLAGS: \"$(inherited) $(KIRA_OTHER_CODE_SIGN_FLAGS)\"",
  "DEVELOPMENT_TEAM: \"$(MOBILE_RELEASE_IOS_DEVELOPMENT_TEAM)\"",
  "APS_ENVIRONMENT: production",
  "CODE_SIGN_ENTITLEMENTS: iosApp/iosApp.entitlements",
  'if [ "${MOBILE_RELEASE_DEFER_EXTERNAL_UPLOADS:-0}" = "1" ]; then'
]
missing_lines = required_project_lines.reject { |line| project.include?(line) }
abort("iOS project configuration is incomplete") unless missing_lines.empty?

release_start = project.index("        Release:\n")
release_end = release_start && project.index("    dependencies:\n", release_start)
abort("iOS Release target settings are missing") unless release_start && release_end
release_settings = project[release_start...release_end]
release_only_mappings = [
  "CODE_SIGN_STYLE: \"$(MOBILE_RELEASE_IOS_CODE_SIGN_STYLE)\"",
  "CODE_SIGN_IDENTITY: \"$(MOBILE_RELEASE_IOS_CODE_SIGN_IDENTITY)\"",
  "PROVISIONING_PROFILE_SPECIFIER: \"$(MOBILE_RELEASE_IOS_PROVISIONING_PROFILE_SPECIFIER)\""
]
release_only_mappings.each do |mapping|
  abort("Mobile Release Kit signing mapping escaped the Release application target") unless
    release_settings.include?(mapping) && project.scan(mapping).length == 1
end

defer_guard = 'if [ "${MOBILE_RELEASE_DEFER_EXTERNAL_UPLOADS:-0}" = "1" ]; then'
optional_guard = 'if [ "${CRASHLYTICS_DSYM_UPLOAD_OPTIONAL:-0}" = "1" ]; then'
defer_position = project.index(defer_guard)
optional_position = project.index(optional_guard)
abort("Mobile Release Kit Crashlytics deferral must run before optional legacy handling") unless
  defer_position && optional_position && defer_position < optional_position

info = KiraRelease::PlistReader.read(info_path)
abort("Info.plist does not use the configured bundle ID") unless info["CFBundleIdentifier"] == "$(PRODUCT_BUNDLE_IDENTIFIER)"
abort("Info.plist does not use the configured marketing version") unless info["CFBundleShortVersionString"] == "$(MARKETING_VERSION)"
abort("Info.plist does not use the configured build number") unless info["CFBundleVersion"] == "$(CURRENT_PROJECT_VERSION)"
abort("Info.plist does not use the configured App Store ID") unless info["KiraAppStoreID"] == "$(KIRA_APP_STORE_ID)"
abort("Crash diagnostics flag is not configuration-driven") unless
  info["KiraCrashDiagnosticsEnabled"] == "$(KIRA_CRASH_DIAGNOSTICS_ENABLED)"
abort("Export-compliance declaration is not false") unless info["ITSAppUsesNonExemptEncryption"] == false

entitlements = KiraRelease::PlistReader.read(entitlements_path)
abort("Release APNs entitlement is not configuration-driven") unless entitlements["aps-environment"] == "$(APS_ENVIRONMENT)"
domains = Array(entitlements["com.apple.developer.associated-domains"])
abort("Associated Domains entitlement is missing kiramanga.me") unless domains.include?("applinks:kiramanga.me")

puts "Committed iOS configuration verified: app 6792232678, bundle me.manga.kira, team 7CGZ2343AA, version 1.0.5"
