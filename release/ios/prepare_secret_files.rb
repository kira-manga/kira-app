require "base64"
require "fileutils"

secret_dir = ENV.fetch("KIRA_SECRET_DIR")
FileUtils.mkdir_p(secret_dir, mode: 0o700)
File.chmod(0o700, secret_dir)

encoded_files = {
  "IOS_CERTIFICATE_BASE64" => "distribution.p12",
  "IOS_PROVISIONING_PROFILE_BASE64" => "distribution.mobileprovision",
  "GOOGLE_SERVICE_INFO_PLIST" => "GoogleService-Info.plist",
  "APP_STORE_CONNECT_PRIVATE_KEY_BASE64" => "AuthKey.p8"
}

missing = encoded_files.keys.select { |name| ENV[name].to_s.empty? }
missing << "IOS_CERTIFICATE_PASSWORD" if ENV["IOS_CERTIFICATE_PASSWORD"].to_s.empty?
abort("Missing required protected values: #{missing.join(', ')}") unless missing.empty?

encoded_files.each do |environment_name, filename|
  destination = File.join(secret_dir, filename)
  begin
    decoded = Base64.strict_decode64(ENV.fetch(environment_name))
  rescue ArgumentError
    abort("Protected file secret is not valid strict Base64: #{environment_name}")
  end
  abort("Protected file secret decoded to an empty file: #{environment_name}") if decoded.empty?

  File.binwrite(destination, decoded)
  File.chmod(0o600, destination)
end

password = ENV.fetch("IOS_CERTIFICATE_PASSWORD")
abort("Certificate password cannot contain a line break") if password.match?(/[\r\n]/)
password_path = File.join(secret_dir, "distribution-password")
File.binwrite(password_path, password)
File.chmod(0o600, password_path)

puts "Protected Apple and Firebase files prepared without displaying their contents"
