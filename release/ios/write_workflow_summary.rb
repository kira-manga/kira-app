require "json"

artifact_path = ENV.fetch("KIRA_ARTIFACT_STATUS_FILE")
testflight_path = ENV.fetch("KIRA_TESTFLIGHT_STATUS_FILE")
summary_path = ENV.fetch("GITHUB_STEP_SUMMARY")

artifact = File.file?(artifact_path) ? JSON.parse(File.read(artifact_path)) : {}
testflight = File.file?(testflight_path) ? JSON.parse(File.read(testflight_path)) : {}

version = testflight["version"] || artifact["version"] || "1.0.0"
build = testflight["build"] || artifact["build"] || "unknown"

lines = [
  "## External TestFlight release",
  "",
  "- Version/build: #{version} (#{build})",
  "- Signed archive validation: #{artifact['archive_valid'] ? 'passed' : 'not completed'}",
  "- IPA validation: #{artifact['ipa_valid'] ? 'passed' : 'not completed'}",
  "- Crashlytics dSYM upload: #{artifact['crashlytics_dsym_upload_marker'] ? 'confirmed' : 'not confirmed'}",
  "- App Store Connect processing: #{testflight['processing_state'] || 'not confirmed'}",
  "- External group assigned: #{testflight['external_group_assigned'] ? 'yes' : 'not confirmed'}",
  "- Beta App Review submitted: #{testflight['beta_review_submitted'] ? 'yes' : 'not confirmed'}",
  "- External testing state: #{testflight['external_state'] || 'not confirmed'}",
  "- Approved and available to external testers: #{testflight['available_to_external_testers'] ? 'yes' : 'no/not yet'}",
  "",
  "The External Testing group uses private invitations. Add tester email addresses in App Store Connect after Apple approves the build."
]

File.open(summary_path, "a", 0o600) { |file| file.puts(lines) }
