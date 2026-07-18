required_names = %w[
  APP_STORE_CONNECT_KEY_ID
  APP_STORE_CONNECT_ISSUER_ID
  TESTFLIGHT_FEEDBACK_EMAIL
  BETA_REVIEW_CONTACT_FIRST_NAME
  BETA_REVIEW_CONTACT_LAST_NAME
  BETA_REVIEW_CONTACT_EMAIL
  BETA_REVIEW_CONTACT_PHONE
  IOS_PROVISIONING_PROFILE_SPECIFIER
]

values = required_names.to_h do |name|
  value = ENV[name].to_s.strip
  abort("Missing required protected value name: #{name}") if value.empty?
  abort("Protected value contains a control character: #{name}") if value.match?(/[\x00-\x1F\x7F]/)
  [name, value]
end

email_pattern = /\A[^\s@]+@[^\s@]+\.[^\s@]+\z/
%w[TESTFLIGHT_FEEDBACK_EMAIL BETA_REVIEW_CONTACT_EMAIL].each do |name|
  abort("Protected email value is malformed: #{name}") unless values.fetch(name).match?(email_pattern)
end

%w[BETA_REVIEW_CONTACT_FIRST_NAME BETA_REVIEW_CONTACT_LAST_NAME].each do |name|
  length = values.fetch(name).length
  abort("Protected reviewer name has an unsupported length: #{name}") unless length.between?(1, 100)
end

phone = values.fetch("BETA_REVIEW_CONTACT_PHONE")
phone_digits = phone.scan(/\d/).length
abort("Protected reviewer phone is malformed") unless phone.match?(/\A[+()\d .-]+\z/) && phone_digits.between?(7, 20)

key_id = values.fetch("APP_STORE_CONNECT_KEY_ID")
abort("App Store Connect key ID has an unsupported format") unless key_id.match?(/\A[A-Z0-9]{10}\z/i)

issuer_id = values.fetch("APP_STORE_CONNECT_ISSUER_ID")
uuid_pattern = /\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z/i
abort("App Store Connect issuer ID has an unsupported format") unless issuer_id.match?(uuid_pattern)

metadata_files = {
  "beta description" => File.expand_path("../testflight/beta-description.txt", __dir__),
  "Beta App Review notes" => File.expand_path("../testflight/beta-review-notes.txt", __dir__),
  "What to Test" => File.expand_path("../testflight/what-to-test.txt", __dir__)
}
forbidden_activation_instruction = %r{
  kiramanga://activate|
  kiramanga\.me/activate|
  reveal\s+sources\s+management|
  activate\s+sources\s+management
}ix

metadata_files.each do |label, path|
  abort("TestFlight #{label} file is unavailable") unless File.file?(path)
  text = File.read(path).strip
  abort("TestFlight #{label} is empty") if text.empty?
  abort("TestFlight #{label} exceeds Apple's 4,000-byte limit") if text.bytesize > 4_000
  abort("TestFlight #{label} contains a private activation instruction") if text.match?(forbidden_activation_instruction)
end

puts "Protected contact formats and TestFlight metadata validated without displaying values"
