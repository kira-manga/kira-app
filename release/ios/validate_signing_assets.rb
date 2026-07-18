require "openssl"
require "time"
require_relative "lib/plist_reader"

EXPECTED_BUNDLE_ID = "me.manga.kira"
EXPECTED_TEAM_ID = "7CGZ2343AA"
EXPECTED_ASSOCIATED_DOMAIN = "applinks:kiramanga.me"

profile = KiraRelease::PlistReader.read(ENV.fetch("KIRA_PROFILE_PLIST"))
source_entitlements = KiraRelease::PlistReader.read(ENV.fetch("KIRA_SOURCE_ENTITLEMENTS"))
firebase = KiraRelease::PlistReader.read(ENV.fetch("KIRA_FIREBASE_PLIST"))
certificate = OpenSSL::X509::Certificate.new(File.binread(ENV.fetch("KIRA_CERTIFICATE_PEM")))
private_key = OpenSSL::PKey.read(File.binread(ENV.fetch("KIRA_PRIVATE_KEY_PEM")))
connect_key = OpenSSL::PKey.read(File.binread(ENV.fetch("APP_STORE_CONNECT_KEY_PATH")))

profile_name = profile["Name"].to_s
profile_uuid = profile["UUID"].to_s
profile_expiration = profile["ExpirationDate"]
profile_entitlements = profile.fetch("Entitlements", {})
application_identifier = profile_entitlements["application-identifier"].to_s
team_identifier = profile_entitlements["com.apple.developer.team-identifier"].to_s

abort("Provisioning profile name is missing") if profile_name.empty?
abort("Provisioning profile UUID is missing") if profile_uuid.empty?
abort("Provisioning profile specifier does not match its metadata") unless profile_name == ENV.fetch("IOS_PROVISIONING_PROFILE_SPECIFIER")
abort("Provisioning profile is expired") unless profile_expiration.is_a?(Time) && profile_expiration > Time.now
abort("Provisioning profile does not belong to the expected team") unless Array(profile["TeamIdentifier"]).include?(EXPECTED_TEAM_ID)
abort("Provisioning profile signed team entitlement is incorrect") unless team_identifier == EXPECTED_TEAM_ID
abort("Provisioning profile is not for the expected explicit bundle ID") unless application_identifier.end_with?(".#{EXPECTED_BUNDLE_ID}")
abort("Provisioning profile permits development debugging") unless profile_entitlements["get-task-allow"] == false
abort("Provisioning profile contains device assignments") unless Array(profile["ProvisionedDevices"]).empty?
abort("Provisioning profile is enterprise/ad hoc rather than App Store Connect") if profile["ProvisionsAllDevices"] == true
abort("Provisioning profile is missing App Store beta reporting") unless profile_entitlements["beta-reports-active"] == true
abort("Provisioning profile APNs environment is not production") unless profile_entitlements["aps-environment"] == "production"
profile_domains = Array(profile_entitlements["com.apple.developer.associated-domains"])
# Apple currently represents the profile-level Associated Domains capability
# as a wildcard. The app's own signed entitlements remain responsible for the
# exact applinks destination and are validated immediately below and again in
# the archived/exported application.
profile_authorizes_domains = profile_domains.include?("*") || profile_domains.include?(EXPECTED_ASSOCIATED_DOMAIN)
abort("Provisioning profile is missing the associated-domains capability") unless profile_authorizes_domains
abort("Provisioning profile is not for iOS") unless Array(profile["Platform"]).include?("iOS")

certificate_common_name = certificate.subject.to_a.find { |name, _value, _type| name == "CN" }&.at(1).to_s
certificate_type = "Apple Distribution" if certificate_common_name.start_with?("Apple Distribution:")
abort("Certificate is not an Apple distribution certificate") unless certificate_type
abort("Distribution certificate is not currently valid") unless certificate.not_before <= Time.now && certificate.not_after > Time.now
abort("Certificate private key does not match the certificate") unless certificate.public_key.to_der == private_key.public_key.to_der
profile_certificates = Array(profile["DeveloperCertificates"])
abort("Provisioning profile is not compatible with the supplied distribution certificate") unless profile_certificates.include?(certificate.to_der)

abort("Source entitlements do not declare production APNs through APS_ENVIRONMENT") unless source_entitlements["aps-environment"] == "$(APS_ENVIRONMENT)"
source_domains = Array(source_entitlements["com.apple.developer.associated-domains"])
abort("Source entitlements are missing the associated domain") unless source_domains.include?(EXPECTED_ASSOCIATED_DOMAIN)

abort("Firebase plist bundle ID is incorrect") unless firebase["BUNDLE_ID"] == EXPECTED_BUNDLE_ID
%w[API_KEY GOOGLE_APP_ID PROJECT_ID].each do |key|
  value = firebase[key].to_s
  abort("Firebase plist is incomplete") if value.empty? || value.match?(/replace|placeholder|example/i)
end

connect_curve = connect_key.respond_to?(:group) ? connect_key.group.curve_name : nil
abort("App Store Connect key is not a valid EC private key") unless connect_key.private? && connect_curve == "prime256v1"

File.write(ENV.fetch("KIRA_PROFILE_UUID_FILE"), "#{profile_uuid}\n")
File.chmod(0o600, ENV.fetch("KIRA_PROFILE_UUID_FILE"))
File.write(ENV.fetch("KIRA_APPLICATION_IDENTIFIER_FILE"), "#{application_identifier}\n")
File.chmod(0o600, ENV.fetch("KIRA_APPLICATION_IDENTIFIER_FILE"))

puts "Certificate type: #{certificate_type}"
puts "Certificate expires: #{certificate.not_after.utc.iso8601}"
puts "Provisioning profile name: #{profile_name}"
puts "Provisioning profile expires: #{profile_expiration.utc.iso8601}"
puts "Provisioning profile verified for App Store Connect distribution, team #{EXPECTED_TEAM_ID}, bundle #{EXPECTED_BUNDLE_ID}"
puts "Provisioning capabilities verified: production Push Notifications and Associated Domains"
puts "Firebase iOS configuration verified for bundle #{EXPECTED_BUNDLE_ID}"
puts "App Store Connect private key format verified"
