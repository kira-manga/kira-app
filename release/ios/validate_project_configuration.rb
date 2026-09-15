require "yaml"
require_relative "lib/plist_reader"
require_relative "lib/libwebp_notices"

root = File.expand_path("../..", __dir__)
project_path = File.join(root, "iosApp/project.yml")
info_path = File.join(root, "iosApp/iosApp/Info.plist")
entitlements_path = File.join(root, "iosApp/iosApp/iosApp.entitlements")

project = File.read(project_path)
specification = YAML.safe_load(project, aliases: false)
target = specification.fetch("targets").fetch("iosApp")
effective = %w[Debug Release].to_h do |configuration|
  project_settings = specification.fetch("settings", {})
  target_settings = target.fetch("settings")
  settings = project_settings.fetch("base", {}).merge(project_settings.fetch("configs", {}).fetch(configuration, {}))
    .merge(target_settings.fetch("base", {})).merge(target_settings.fetch("configs").fetch(configuration))
  [configuration, settings]
end
debug = effective.fetch("Debug")
release = effective.fetch("Release")
abort("Release must use only the canonical Store bundle ID") unless release["PRODUCT_BUNDLE_IDENTIFIER"] == "me.manga.kira"
abort("Debug must use the isolated bundle ID") unless debug["PRODUCT_BUNDLE_IDENTIFIER"] == "me.manga.kira.debug"
abort("Debug must not select the production signing team") unless debug["DEVELOPMENT_TEAM"] == ""
abort("Debug must use empty local entitlements") unless debug["CODE_SIGN_ENTITLEMENTS"] == "iosApp/iosApp-nopush.entitlements"
abort("Debug must select its isolated Info.plist") unless debug["INFOPLIST_FILE"] == "iosApp/Info-Debug.plist"
abort("Release must retain its Store Info.plist") unless release["INFOPLIST_FILE"] == "iosApp/Info.plist"
abort("Release must retain its Store signing team") unless release["DEVELOPMENT_TEAM"] == "$(MOBILE_RELEASE_IOS_DEVELOPMENT_TEAM)"
abort("Release must retain production push entitlements") unless release["CODE_SIGN_ENTITLEMENTS"] == "iosApp/iosApp.entitlements" &&
  release["APS_ENVIRONMENT"] == "production"
scheme = specification.fetch("schemes").fetch("iosApp")
abort("Ordinary Run must be Debug and Archive must be Release") unless
  scheme.fetch("run")["config"] == "Debug" && scheme.fetch("archive")["config"] == "Release"
sources = target.fetch("sources").find { |source| source["path"] == "iosApp" }
abort("Store Firebase configuration must not be copied as an unconditional resource") unless
  sources && sources.fetch("excludes", []).include?("GoogleService-Info.plist")
copy_script = target.fetch("postBuildScripts").find { |script| script["name"] == "Copy Store Firebase configuration (Release only)" }
abort("Release-only Firebase resource selection is missing") unless copy_script &&
  copy_script.fetch("script").include?('if [ "${CONFIGURATION}" != "Release" ]; then') &&
  copy_script.fetch("script").include?('rm -f "$DESTINATION"') && copy_script.fetch("script").include?('cp "$SOURCE" "$DESTINATION"')

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
debug_info = KiraRelease::PlistReader.read(File.join(root, "iosApp", debug.fetch("INFOPLIST_FILE")))
abort("Release must retain the Store display name and services") unless
  info["CFBundleDisplayName"] == "Kira Manga" && info["KiraFirebaseServicesEnabled"] == true
abort("Debug must have a distinct display name and disabled services") unless
  debug_info["CFBundleDisplayName"] == "Kira Manga Debug" && debug_info["KiraFirebaseServicesEnabled"] == false
abort("Debug must not claim production URL schemes") unless Array(debug_info["CFBundleURLTypes"]).empty?
abort("Debug must preserve local background work without remote notifications") unless
  debug_info["UIBackgroundModes"] == %w[fetch processing]
abort("Debug analytics must be explicitly deactivated") unless debug_info["FIREBASE_ANALYTICS_COLLECTION_DEACTIVATED"] == true
%w[FirebaseCrashlyticsCollectionEnabled FirebaseMessagingAutoInitEnabled FirebaseInAppMessagingAutomaticDataCollectionEnabled
   FirebaseAppDelegateProxyEnabled].each do |key|
  abort("Debug Firebase service is not explicitly disabled") unless debug_info[key] == false
  abort("Release must not inherit Debug service disabling") if info[key] == false
end
expected_tasks = %w[download.processing download.continued library.refresh].map { |suffix| "$(PRODUCT_BUNDLE_IDENTIFIER).#{suffix}" }
[info, debug_info].each do |plist|
  abort("Background task identifiers must follow the effective bundle ID") unless plist["BGTaskSchedulerPermittedIdentifiers"] == expected_tasks
  abort("Info.plist must use the effective bundle ID") unless plist["CFBundleIdentifier"] == "$(PRODUCT_BUNDLE_IDENTIFIER)"
end
debug_entitlements = KiraRelease::PlistReader.read(File.join(root, "iosApp", debug.fetch("CODE_SIGN_ENTITLEMENTS")))
abort("Debug must not claim push, associations or shared storage groups") unless debug_entitlements == {}
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

KiraRelease::LibwebpNotices.validate_repository!(root)

puts "Committed iOS configuration verified: app 6792232678, bundle me.manga.kira, team 7CGZ2343AA, version 1.0.5"
puts "Ordinary Debug isolation verified: distinct identity/name, no production team, Firebase, APNs or associations"
puts "Vendored libwebp version/digests, legal notices and whole Settings resource configuration verified"
