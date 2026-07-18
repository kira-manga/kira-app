require "openssl"

p12_path = File.join(ENV.fetch("KIRA_SECRET_DIR"), "distribution.p12")
password_path = File.join(ENV.fetch("KIRA_SECRET_DIR"), "distribution-password")
certificate_path = ENV.fetch("KIRA_CERTIFICATE_PEM")
private_key_path = ENV.fetch("KIRA_PRIVATE_KEY_PEM")

password = File.binread(password_path)
abort("Certificate password cannot be empty") if password.empty?

begin
  container = OpenSSL::PKCS12.new(File.binread(p12_path), password)
rescue OpenSSL::PKCS12::PKCS12Error
  abort("Distribution certificate could not be opened with the supplied password")
ensure
  password.clear
end

certificate = container.certificate
private_key = container.key
abort("Distribution certificate container is incomplete") unless certificate && private_key

File.binwrite(certificate_path, certificate.to_pem)
File.chmod(0o600, certificate_path)
File.binwrite(private_key_path, private_key.to_pem)
File.chmod(0o600, private_key_path)

puts "Distribution certificate and private key extracted without displaying signing metadata"
