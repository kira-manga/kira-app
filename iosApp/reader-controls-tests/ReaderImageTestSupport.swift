import UIKit
import XCTest

/// Source-only fixtures and deliberately injectable transport/decoder seams, not native wire proof.
final class ReaderTestFiles {
    let root: URL
    let manager = FileManager.default
    static let png = Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAAAnKFCDAAAAC0lEQVR42mNgQAcAABIAAeRVjecAAAAASUVORK5CYII=")!

    init() throws {
        root = manager.temporaryDirectory.appendingPathComponent("reader-leaf-\(UUID().uuidString)", isDirectory: true)
        try manager.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
    }

    deinit { try? manager.removeItem(at: root) }

    func write(_ bytes: Data = ReaderTestFiles.png, name: String = UUID().uuidString) throws -> URL {
        let url = root.appendingPathComponent(name)
        try bytes.write(to: url)
        try manager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        return url
    }

    func owned(_ bytes: Data = ReaderTestFiles.png) throws -> ReaderPageFile {
        ReaderPageFile(url: try write(bytes), byteCount: Int64(bytes.count))
    }

    func exists(_ url: URL) -> Bool { manager.fileExists(atPath: url.path) }
}

final class ReaderFakePageTask: ReaderPageTask {
    private let lock = NSLock()
    private var resumes = 0
    private var cancellations = 0
    var resumeCount: Int { lock.lock(); defer { lock.unlock() }; return resumes }
    var cancelCount: Int { lock.lock(); defer { lock.unlock() }; return cancellations }
    func resume() { lock.lock(); resumes += 1; lock.unlock() }
    func cancel() { lock.lock(); cancellations += 1; lock.unlock() }
}

final class ReaderFakePageTransport: ReaderPageDownloading {
    final class Transfer {
        let request: URLRequest
        let task = ReaderFakePageTask()
        var progress: ((Double) -> Void)?
        var completion: ((Result<ReaderPageFile, Error>) -> Void)?
        init(_ request: URLRequest, progress: @escaping (Double) -> Void,
             completion: @escaping (Result<ReaderPageFile, Error>) -> Void) {
            self.request = request
            self.progress = progress
            self.completion = completion
        }
    }

    private let lock = NSLock()
    private var transfers: [Transfer] = []
    private var invalidations = 0
    var taskCount: Int { lock.lock(); defer { lock.unlock() }; return transfers.count }
    var invalidateCount: Int { lock.lock(); defer { lock.unlock() }; return invalidations }
    func transfer(_ index: Int) -> Transfer { lock.lock(); defer { lock.unlock() }; return transfers[index] }

    func task(for request: URLRequest, progress: @escaping (Double) -> Void,
              completion: @escaping (Result<ReaderPageFile, Error>) -> Void) -> ReaderPageTask? {
        let transfer = Transfer(request, progress: progress, completion: completion)
        lock.lock()
        transfers.append(transfer)
        lock.unlock()
        return transfer.task
    }

    func complete(_ index: Int, with file: ReaderPageFile) {
        lock.lock()
        let completion = transfers[index].completion
        transfers[index].completion = nil
        transfers[index].progress = nil
        lock.unlock()
        if let completion = completion { completion(.success(file)) } else { file.discard() }
    }

    func progress(_ index: Int, _ fraction: Double) {
        lock.lock()
        let callback = transfers[index].progress
        lock.unlock()
        callback?(fraction)
    }

    func invalidate() {
        lock.lock()
        invalidations += 1
        let callbacks = transfers.compactMap { $0.completion }
        let tasks = transfers.map { $0.task }
        transfers.forEach { $0.completion = nil; $0.progress = nil }
        lock.unlock()
        tasks.forEach { $0.cancel() }
        callbacks.forEach { $0(.failure(ReaderPageTransferError.cancelled)) }
    }
}

final class ReaderBlockedDecoder {
    let started: XCTestExpectation
    private let permits = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private let image: UIImage
    private var calls: [URL] = []
    var count: Int { lock.lock(); defer { lock.unlock() }; return calls.count }

    init(image: UIImage, starts: Int) {
        self.image = image
        started = XCTestExpectation(description: "native decode slots occupied")
        started.expectedFulfillmentCount = starts
    }

    func decode(_ url: URL, _ width: CGFloat) -> UIImage? {
        lock.lock()
        calls.append(url)
        lock.unlock()
        started.fulfill()
        XCTAssertEqual(permits.wait(timeout: .now() + 10), .success, "test must release its decoder")
        return image
    }

    func release(_ count: Int) { for _ in 0..<count { permits.signal() } }
}

/// Cell tests intentionally deliver obsolete completions too, to exercise the real generation guards.
final class ReaderFakeImageLoader: ReaderImageLoading {
    struct Request {
        let token: String
        let url: String
        let headers: [String: String]
        let width: CGFloat
        let progress: ((Double) -> Void)?
        let completion: (UIImage?) -> Void
    }
    private(set) var requests: [Request] = []
    private(set) var cancelled: [String] = []
    var onLoad: ((Request) -> Void)?

    func load(url: String, headers: [String: String], targetWidthPx: CGFloat,
              onProgress: ((Double) -> Void)?, completion: @escaping (UIImage?) -> Void) -> String {
        let request = Request(token: "token-\(requests.count)", url: url, headers: headers,
                              width: targetWidthPx, progress: onProgress, completion: completion)
        requests.append(request)
        onLoad?(request)
        return request.token
    }
    func cancel(token: String) { cancelled.append(token) }
    func localAspect(_ url: String) -> CGFloat? { nil }
}

final class ReaderLockedValue<T> {
    private let lock = NSLock()
    private var value: T
    init(_ value: T) { self.value = value }
    func get() -> T { lock.lock(); defer { lock.unlock() }; return value }
    func set(_ value: T) { lock.lock(); self.value = value; lock.unlock() }
}

extension XCTestCase {
    @MainActor
    func readerDrain(_ queue: DispatchQueue) {
        let drained = expectation(description: "owned decode queue drained")
        queue.async(flags: .barrier) { drained.fulfill() }
        wait(for: [drained], timeout: 5)
        readerDrainMain()
    }

    @MainActor
    func readerDrainMain() {
        let drained = expectation(description: "main deliveries drained")
        DispatchQueue.main.async { drained.fulfill() }
        wait(for: [drained], timeout: 5)
    }
}

@MainActor
func readerTestImage(_ color: UIColor = .blue) -> UIImage {
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    return UIGraphicsImageRenderer(size: CGSize(width: 8, height: 9), format: format).image { context in
        color.setFill()
        context.fill(CGRect(x: 0, y: 0, width: 8, height: 9))
    }
}
