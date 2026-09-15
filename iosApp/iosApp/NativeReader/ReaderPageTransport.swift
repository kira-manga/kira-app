import Foundation

/// URLSession owns streaming to an OS file. No full response Data is accumulated or queued by us.
/// Progress cancellation is coarse (not a hard OS temporary-disk/RSS cap); the final file is checked
/// again before ownership can pass to the decode queue. Every native task has one terminal callback.
final class ReaderPageTransport: NSObject, ReaderPageDownloading, URLSessionDownloadDelegate {
    private final class Transfer {
        let task: URLSessionDownloadTask
        let progress: (Double) -> Void
        let completion: (Result<ReaderPageFile, Error>) -> Void
        var failure: Error?
        var file: ReaderPageFile?

        init(task: URLSessionDownloadTask, progress: @escaping (Double) -> Void,
             completion: @escaping (Result<ReaderPageFile, Error>) -> Void) {
            self.task = task
            self.progress = progress
            self.completion = completion
        }
    }

    private let lock = NSLock()
    private let policy: ReaderPageBytePolicy
    private let directory: URL
    private let fileManager: FileManager
    private var session: URLSession?
    private var transfers: [Int: Transfer] = [:]

    init(configuration: URLSessionConfiguration, policy: ReaderPageBytePolicy = ReaderPageBytePolicy(),
         directory: URL = FileManager.default.temporaryDirectory.appendingPathComponent("kira-reader-pages", isDirectory: true),
         fileManager: FileManager = .default) {
        self.policy = policy
        self.directory = directory
        self.fileManager = fileManager
        super.init()
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        queue.name = "me.manga.kira.reader.transfer"
        session = URLSession(configuration: configuration, delegate: self, delegateQueue: queue)
    }

    func task(for request: URLRequest, progress: @escaping (Double) -> Void,
              completion: @escaping (Result<ReaderPageFile, Error>) -> Void) -> ReaderPageTask? {
        lock.lock()
        defer { lock.unlock() }
        guard let session = session else { return nil }
        let task = session.downloadTask(with: request)
        transfers[task.taskIdentifier] = Transfer(task: task, progress: progress, completion: completion)
        return task
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        lock.lock()
        guard let transfer = transfers[downloadTask.taskIdentifier] else { lock.unlock(); return }
        do {
            try policy.checkProgress(actual: totalBytesWritten, expected: totalBytesExpectedToWrite)
        } catch {
            if transfer.failure == nil { transfer.failure = error }
            lock.unlock()
            downloadTask.cancel()
            return
        }
        let progress = transfer.progress
        let failed = transfer.failure != nil
        lock.unlock()
        if !failed && totalBytesExpectedToWrite > 0 {
            progress(min(1, max(0, Double(totalBytesWritten) / Double(totalBytesExpectedToWrite))))
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        receiveFile(taskID: downloadTask.taskIdentifier, response: downloadTask.response, location: location)
    }

    /// Also used by leaf file-publication tests; the real delegate above passes the native response.
    func receiveFile(taskID: Int, response: URLResponse?, location: URL) {
        lock.lock()
        guard let transfer = transfers[taskID], transfer.failure == nil, transfer.file == nil else { lock.unlock(); return }
        lock.unlock()
        do {
            let file = try adoptFile(response: response, location: location)
            lock.lock()
            if transfers[taskID] === transfer && transfer.failure == nil && transfer.file == nil {
                transfer.file = file
            }
            lock.unlock()
            // Invalidation can race the move. An unclaimed local owner removes only its own file.
        } catch {
            lock.lock()
            if transfers[taskID] === transfer, transfer.failure == nil { transfer.failure = error }
            lock.unlock()
        }
    }

    private func adoptFile(response: URLResponse?, location: URL) throws -> ReaderPageFile {
        guard let response = response as? HTTPURLResponse else { throw ReaderPageTransferError.missingHTTPResponse }
        guard (200...299).contains(response.statusCode) else { throw ReaderPageTransferError.httpStatus(response.statusCode) }
        try policy.checkProgress(actual: 0, expected: response.expectedContentLength)
        _ = try policy.fileSize(location, fileManager: fileManager)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try fileManager.setAttributes([.posixPermissions: 0o700], ofItemAtPath: directory.path)
        let target = directory.appendingPathComponent("page-\(UUID().uuidString).partial")
        // moveItem never overwrites a collision. Only a successful move gives us deletion rights.
        try fileManager.moveItem(at: location, to: target)
        do {
            try fileManager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: target.path)
            let count = try policy.fileSize(target, fileManager: fileManager)
            return ReaderPageFile(url: target, byteCount: count, fileManager: fileManager)
        } catch {
            // Cleanup is best effort; never replace the original file-policy/IO failure with it.
            try? fileManager.removeItem(at: target)
            throw error
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        let transfer = transfers.removeValue(forKey: task.taskIdentifier)
        lock.unlock()
        guard let transfer = transfer else { return }
        if let failure = transfer.failure ?? error {
            transfer.file?.discard()
            transfer.completion(.failure(failure))
        } else if let file = transfer.file {
            transfer.completion(.success(file))
        } else {
            transfer.completion(.failure(ReaderPageTransferError.missingFile))
        }
    }

    func urlSession(_ session: URLSession, didBecomeInvalidWithError error: Error?) {
        finishOutstanding(error ?? ReaderPageTransferError.cancelled)
    }

    func invalidate() {
        lock.lock()
        let session = self.session
        self.session = nil
        lock.unlock()
        finishOutstanding(ReaderPageTransferError.cancelled)
        session?.invalidateAndCancel()
    }

    private func finishOutstanding(_ failure: Error) {
        lock.lock()
        session = nil
        let outstanding = Array(transfers.values)
        transfers.removeAll()
        lock.unlock()
        for transfer in outstanding {
            transfer.file?.discard()
            transfer.task.cancel()
            transfer.completion(.failure(transfer.failure ?? failure))
        }
    }
}
