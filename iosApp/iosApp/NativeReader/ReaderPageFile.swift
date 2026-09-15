import Foundation

/// Only transport-created files have this owner. Local downloaded chapter files are never owned here.
final class ReaderPageFile {
    let url: URL
    let byteCount: Int64
    private let lock = NSLock()
    private var removed = false
    private let fileManager: FileManager

    init(url: URL, byteCount: Int64, fileManager: FileManager = .default) {
        self.url = url
        self.byteCount = byteCount
        self.fileManager = fileManager
    }

    func discard() {
        lock.lock()
        let shouldRemove = !removed
        removed = true
        lock.unlock()
        if shouldRemove { try? fileManager.removeItem(at: url) }
    }

    deinit { discard() }
}

enum ReaderPageTransferError: Error, Equatable {
    case byteLimit
    case emptyOrUnreadable
    case httpStatus(Int)
    case missingHTTPResponse
    case cancelled
    case missingFile
}

struct ReaderPageBytePolicy {
    static let defaultLimit: Int64 = 32 * 1024 * 1024
    let limit: Int64

    init(limit: Int64 = Self.defaultLimit) {
        precondition(limit > 0 && limit <= Int64(Int.max))
        self.limit = limit
    }

    func checkProgress(actual: Int64, expected: Int64) throws {
        if actual > limit || expected > limit { throw ReaderPageTransferError.byteLimit }
    }

    func fileSize(_ url: URL, fileManager: FileManager = .default) throws -> Int64 {
        let attributes = try fileManager.attributesOfItem(atPath: url.path)
        guard attributes[.type] as? FileAttributeType == .typeRegular,
              let size = attributes[.size] as? NSNumber, size.int64Value > 0 else {
            throw ReaderPageTransferError.emptyOrUnreadable
        }
        if size.int64Value > limit { throw ReaderPageTransferError.byteLimit }
        return size.int64Value
    }
}

protocol ReaderPageTask: AnyObject {
    func resume()
    func cancel()
}

extension URLSessionTask: ReaderPageTask {}

protocol ReaderPageDownloading: AnyObject {
    /// Returns a suspended task. The subscriber registry must own it before it can be resumed.
    func task(for request: URLRequest, progress: @escaping (Double) -> Void,
              completion: @escaping (Result<ReaderPageFile, Error>) -> Void) -> ReaderPageTask?
    func invalidate()
}
