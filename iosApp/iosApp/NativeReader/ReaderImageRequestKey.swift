import CryptoKit
import Foundation

/// Auth/header changes are a different representation, even for an unchanged URL and decode width.
/// Length-prefixes avoid delimiter collisions; only the digest enters the cache, never raw headers.
func readerImageRequestKey(url: String, headers: [String: String], width: CGFloat) -> String {
    var hash = SHA256()
    func append(_ value: String) {
        let bytes = Data(value.utf8)
        hash.update(data: Data("\(bytes.count):".utf8))
        hash.update(data: bytes)
    }
    append(url)
    append(String(Double(width)))
    for (name, value) in readerSortedHeaders(headers) {
        append(name.lowercased())
        append(value)
    }
    return hash.finalize().map { String(format: "%02x", $0) }.joined()
}

/// Stable ordering also makes duplicate differently-cased HTTP fields deterministic at URLRequest.
func readerSortedHeaders(_ headers: [String: String]) -> [(key: String, value: String)] {
    headers.sorted {
        let lhs = $0.key.lowercased(), rhs = $1.key.lowercased()
        return lhs == rhs ? $0.key < $1.key : lhs < rhs
    }
}
