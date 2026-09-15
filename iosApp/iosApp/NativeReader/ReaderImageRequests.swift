import UIKit

/// The native cells depend only on request/subscriber ownership, not on URLSession or ImageIO.
protocol ReaderImageLoading: AnyObject {
    @discardableResult
    func load(url: String, headers: [String: String], targetWidthPx: CGFloat,
              onProgress: ((Double) -> Void)?, completion: @escaping (UIImage?) -> Void) -> String
    func cancel(token: String)
    func localAspect(_ url: String) -> CGFloat?
}

/// Queued work captures a key/generation, never encoded Data or an owned file. Cancelling the last
/// subscriber discards the queued file immediately, even when all three decode slots are occupied.
final class ReaderImageRequests {
    private struct Subscriber {
        let token: Int
        let progress: ((Double) -> Void)?
        let completion: (UIImage?) -> Void
    }

    private final class Record {
        let owner: Int
        var subscribers: [Subscriber]
        var task: ReaderPageTask?
        var lastProgress: Double = 0
        var file: ReaderPageFile?

        init(_ subscriber: Subscriber) {
            owner = subscriber.token
            subscribers = [subscriber]
        }
    }

    private let lock = NSLock()
    private var records: [String: Record] = [:]
    private var tokenKeys: [Int: String] = [:]
    private var nextToken = 0

    /// Disk bytes awaiting a decode slot; no corresponding in-memory encoded Data is retained.
    var queuedFileByteCount: Int64 {
        lock.lock()
        defer { lock.unlock() }
        return records.values.reduce(0) { $0 + ($1.file?.byteCount ?? 0) }
    }

    func register(key: String, progress: ((Double) -> Void)?, completion: @escaping (UIImage?) -> Void)
        -> (token: Int, isOwner: Bool) {
        lock.lock()
        defer { lock.unlock() }
        let subscriber = Subscriber(token: nextToken, progress: progress, completion: completion)
        nextToken &+= 1
        tokenKeys[subscriber.token] = key
        if let record = records[key] {
            record.subscribers.append(subscriber)
            return (subscriber.token, false)
        }
        records[key] = Record(subscriber)
        return (subscriber.token, true)
    }

    func isActive(key: String, owner: Int) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return records[key]?.owner == owner
    }

    func store(file: ReaderPageFile, key: String, owner: Int) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let record = records[key], record.owner == owner, record.file == nil else { return false }
        record.file = file
        record.task = nil
        return true
    }

    /// An active decoder takes ownership. Cancellation may suppress its result but cannot delete a
    /// file under ImageIO; that decoder releases its file as soon as the native call returns.
    func takeFile(key: String, owner: Int) -> ReaderPageFile? {
        lock.lock()
        defer { lock.unlock() }
        guard let record = records[key], record.owner == owner else { return nil }
        defer { record.file = nil }
        return record.file
    }

    /// Cancellation can race construction. A task disowned before attachment must not be resumed.
    func attach(task: ReaderPageTask, key: String, owner: Int) -> Bool {
        lock.lock()
        guard let record = records[key], record.owner == owner else {
            lock.unlock()
            task.cancel()
            return false
        }
        record.task = task
        lock.unlock()
        return true
    }

    func reportProgress(_ fraction: Double, key: String, owner: Int) {
        lock.lock()
        guard let record = records[key], record.owner == owner, fraction.isFinite,
              fraction - record.lastProgress >= 0.01 else { lock.unlock(); return }
        record.lastProgress = fraction
        let subscribers = record.subscribers
        lock.unlock()
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            for subscriber in subscribers where self.hasSubscriber(subscriber.token, key: key) {
                subscriber.progress?(fraction)
            }
        }
    }

    /// Cache publication and record removal share the ownership lock. A cancelled decode cannot
    /// populate the cache or finish a successor request with the same URL/headers/width key.
    func finish(key: String, owner: Int, image: UIImage?, cache: () -> Void) {
        lock.lock()
        guard let record = records[key], record.owner == owner else { lock.unlock(); return }
        cache()
        let subscribers = record.subscribers
        records[key] = nil
        let file = record.file
        record.file = nil
        lock.unlock()
        file?.discard()
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            for subscriber in subscribers {
                self.lock.lock()
                let active = self.tokenKeys[subscriber.token] == key
                self.tokenKeys[subscriber.token] = nil
                self.lock.unlock()
                if active { subscriber.completion(image) }
            }
        }
    }

    func cancel(token: String) {
        guard let token = Int(token) else { return }
        lock.lock()
        guard let key = tokenKeys.removeValue(forKey: token) else { lock.unlock(); return }
        let record = records[key]
        record?.subscribers.removeAll { $0.token == token }
        var task: ReaderPageTask?
        var file: ReaderPageFile?
        if let record = record, record.subscribers.isEmpty {
            records[key] = nil
            file = record.file
            record.file = nil
            task = record.task
        }
        lock.unlock()
        file?.discard()
        task?.cancel()
    }

    func cancelAll() {
        lock.lock()
        let outstanding = Array(records.values)
        records.removeAll()
        tokenKeys.removeAll()
        lock.unlock()
        for record in outstanding {
            record.file?.discard()
            record.task?.cancel()
        }
    }

    private func hasSubscriber(_ token: Int, key: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return tokenKeys[token] == key
    }
}
