import Foundation
import Security

private func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data("Signing identity import failed: \(message)\n".utf8))
    exit(EXIT_FAILURE)
}

guard CommandLine.arguments.count == 4 else {
    fail("expected PKCS#12 path, password-file path, and keychain path")
}

let containerPath = CommandLine.arguments[1]
let passwordPath = CommandLine.arguments[2]
let keychainPath = CommandLine.arguments[3]

let containerData: Data
var passwordData: Data
do {
    containerData = try Data(contentsOf: URL(fileURLWithPath: containerPath))
    passwordData = try Data(contentsOf: URL(fileURLWithPath: passwordPath))
} catch {
    fail("protected input file is unavailable")
}

defer {
    passwordData.resetBytes(in: 0..<passwordData.count)
}

guard !containerData.isEmpty, !passwordData.isEmpty else {
    fail("protected input file is empty")
}
guard !passwordData.contains(0x0A), !passwordData.contains(0x0D) else {
    fail("password file contains a line break")
}
guard let password = String(data: passwordData, encoding: .utf8) else {
    fail("password file is not UTF-8")
}

var keychain: SecKeychain?
guard SecKeychainOpen(keychainPath, &keychain) == errSecSuccess, let keychain else {
    fail("temporary keychain could not be opened")
}

var trustedApplications = [SecTrustedApplication]()
for path in ["/usr/bin/codesign", "/usr/bin/security"] {
    var application: SecTrustedApplication?
    guard SecTrustedApplicationCreateFromPath(path, &application) == errSecSuccess,
          let application else {
        fail("trusted signing application could not be registered")
    }
    trustedApplications.append(application)
}

var access: SecAccess?
guard SecAccessCreate(
    "Kira temporary signing identity" as CFString,
    trustedApplications as CFArray,
    &access,
) == errSecSuccess, let access else {
    fail("temporary key access policy could not be created")
}

let options = [
    kSecImportExportPassphrase as String: password,
    kSecImportExportKeychain as String: keychain,
    kSecImportExportAccess as String: access,
] as CFDictionary
var importedItems: CFArray?
guard SecPKCS12Import(containerData as CFData, options, &importedItems) == errSecSuccess else {
    fail("PKCS#12 authentication or import was rejected")
}
guard let importedItems, CFArrayGetCount(importedItems) > 0 else {
    fail("PKCS#12 contained no importable signing identity")
}

print("Distribution signing identity imported from protected files")
