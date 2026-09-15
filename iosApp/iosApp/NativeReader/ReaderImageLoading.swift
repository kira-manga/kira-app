import UIKit

/// The native reader uses per-header requests, OS-streamed temporary files and file-backed ImageIO.
/// Duplicate URL/headers/width requests coalesce, but every subscriber has an independent cancel token.
/// Queued decodes retain neither response Data nor a file owner: cancelling their last subscriber
/// releases their file immediately. Active native decodes release ownership when ImageIO returns.
final class ReaderImageLoader: ReaderImageLoading {
    static let shared = ReaderImageLoader()

    private let transport: ReaderPageDownloading
    private let bytePolicy: ReaderPageBytePolicy
    private let memoryCache = NSCache<NSString, UIImage>()
    private let aspectCache = NSCache<NSString, NSNumber>()
    private let ioQueue: DispatchQueue
    private let fileDecoder: ((URL, CGFloat) -> UIImage?)?
    /// Preserve three native decode slots and the existing 12k edge/width quality policy. Waiting
    /// closures hold only identity, not encoded pages or the loader. This is not an ImageIO RSS cap.
    private let decodeGate = DispatchSemaphore(value: 3)
    private let requests = ReaderImageRequests()

    /// Disk bytes awaiting a decode slot, not bytes in an encoded in-memory queue.
    var queuedFileByteCount: Int64 { requests.queuedFileByteCount }

    init(transport: ReaderPageDownloading? = nil,
         bytePolicy: ReaderPageBytePolicy = ReaderPageBytePolicy(),
         decodeQueue: DispatchQueue = DispatchQueue(label: "me.manga.kira.reader.decode", qos: .userInitiated, attributes: .concurrent),
         fileDecoder: ((URL, CGFloat) -> UIImage?)? = nil) {
        self.bytePolicy = bytePolicy
        self.transport = transport ?? ReaderPageTransport(configuration: Self.defaultConfiguration(), policy: bytePolicy)
        ioQueue = decodeQueue
        self.fileDecoder = fileDecoder
        configureMemoryCache()
        NotificationCenter.default.addObserver(self, selector: #selector(purgeMemoryCache),
                                               name: UIApplication.didReceiveMemoryWarningNotification, object: nil)
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
        requests.cancelAll()
        // URLSession retains its delegate; explicit invalidation breaks that transport/session cycle.
        transport.invalidate()
    }

    static func defaultConfiguration() -> URLSessionConfiguration {
        let config = URLSessionConfiguration.default
        config.requestCachePolicy = .returnCacheDataElseLoad
        config.urlCache = URLCache(memoryCapacity: 16 * 1024 * 1024, diskCapacity: 256 * 1024 * 1024,
                                   diskPath: "kira_reader_images")
        config.timeoutIntervalForRequest = 30
        // These are the existing cache settings. DownloadTask cache parity needs native verification.
        return config
    }

    private func configureMemoryCache() {
        let memory = ProcessInfo.processInfo.physicalMemory
        let megabytes = memory < 2 * 1024 * 1024 * 1024 ? 64 : (memory <= 4 * 1024 * 1024 * 1024 ? 128 : 256)
        memoryCache.totalCostLimit = megabytes * 1024 * 1024
    }

    @objc private func purgeMemoryCache() { memoryCache.removeAllObjects() }

    /// Header-only aspect for local layout, never media-validation or cache-publication authority.
    func localAspect(_ url: String) -> CGFloat? {
        guard let file = localURL(url), (try? bytePolicy.fileSize(file)) != nil else { return nil }
        let key = url as NSString
        if let cached = aspectCache.object(forKey: key) { return CGFloat(cached.doubleValue) }
        guard let aspect = ReaderImageDecode.localAspect(fileURL: file) else { return nil }
        aspectCache.setObject(NSNumber(value: Double(aspect)), forKey: key)
        return aspect
    }

    /// Completion is always main-thread. A main-thread memory-cache hit completes synchronously and
    /// returns ""; other loads return an independent subscriber token, including off-main cache hits.
    @discardableResult
    func load(url: String, headers: [String: String], targetWidthPx: CGFloat,
              onProgress: ((Double) -> Void)? = nil, completion: @escaping (UIImage?) -> Void) -> String {
        let key = readerImageRequestKey(url: url, headers: headers, width: targetWidthPx)
        if Thread.isMainThread, let cached = memoryCache.object(forKey: key as NSString) { completion(cached); return "" }
        let registration = requests.register(key: key, progress: onProgress, completion: completion)
        let owner = registration.token
        guard registration.isOwner else { return "\(owner)" }
        let finish: (UIImage?) -> Void = { [weak self] image in self?.finish(image, key: key, owner: owner) }
        if let cached = memoryCache.object(forKey: key as NSString) { finish(cached); return "\(owner)" }
        guard targetWidthPx.isFinite, targetWidthPx > 0 else { finish(nil); return "\(owner)" }
        if let file = localURL(url) {
            decodeLocal(file, key: key, owner: owner, width: targetWidthPx, finish: finish)
        } else {
            download(url, headers: headers, key: key, owner: owner, width: targetWidthPx, finish: finish)
        }
        return "\(owner)"
    }

    func cancel(token: String) { requests.cancel(token: token) }

    private func download(_ url: String, headers: [String: String], key: String, owner: Int,
                          width: CGFloat, finish: @escaping (UIImage?) -> Void) {
        guard let remote = URL(string: url), remote.scheme == "https" || remote.scheme == "http" else { finish(nil); return }
        var request = URLRequest(url: remote)
        for (name, value) in readerSortedHeaders(headers) { request.setValue(value, forHTTPHeaderField: name) }
        let registry = requests
        let task = transport.task(for: request, progress: { [weak registry] fraction in
            registry?.reportProgress(fraction, key: key, owner: owner)
        }, completion: { [weak self] result in
            switch result {
            case .failure: finish(nil)
            case .success(let file):
                guard let self = self, self.requests.store(file: file, key: key, owner: owner) else { file.discard(); return }
                self.decodePending(key: key, owner: owner, width: width, finish: finish)
            }
        })
        guard let task = task else { finish(nil); return }
        if requests.attach(task: task, key: key, owner: owner) { task.resume() }
    }

    private func decodePending(key: String, owner: Int, width: CGFloat, finish: @escaping (UIImage?) -> Void) {
        let gate = decodeGate, requests = requests
        ioQueue.async { [weak self] in
            guard requests.isActive(key: key, owner: owner) else { return }
            gate.wait()
            defer { gate.signal() }
            guard let self = self, let file = requests.takeFile(key: key, owner: owner) else { return }
            defer { file.discard() }
            finish(self.decode(file.url, width: width))
        }
    }

    private func decodeLocal(_ file: URL, key: String, owner: Int, width: CGFloat, finish: @escaping (UIImage?) -> Void) {
        let gate = decodeGate, requests = requests
        ioQueue.async { [weak self] in
            guard requests.isActive(key: key, owner: owner) else { return }
            gate.wait()
            defer { gate.signal() }
            guard let self = self, requests.isActive(key: key, owner: owner) else { return }
            // Downloaded chapter files are borrowed. Never wrap or delete them as transport temporaries.
            finish(self.decode(file, width: width))
        }
    }

    private func decode(_ file: URL, width: CGFloat) -> UIImage? {
        guard (try? bytePolicy.fileSize(file)) != nil else { return nil }
        let start = ReaderPerfLog.now()
        let image: UIImage?
        if let decoder = fileDecoder { image = decoder(file, width) }
        else { image = ReaderImageDecode.downsample(fileURL: file, targetWidthPx: width) }
        ReaderPerfLog.log("decode.done", "\(Int(ReaderPerfLog.ms(since: start)))ms")
        return image
    }

    private func finish(_ image: UIImage?, key: String, owner: Int) {
        requests.finish(key: key, owner: owner, image: image) {
            guard let image = image else { return }
            let bytes = image.size.width * image.size.height * image.scale * image.scale * 4
            guard bytes.isFinite, bytes >= 0, bytes < CGFloat(Int.max) else { return }
            memoryCache.setObject(image, forKey: key as NSString, cost: Int(bytes))
        }
    }

    private func localURL(_ path: String) -> URL? {
        if path.hasPrefix("/") { return URL(fileURLWithPath: path) }
        if let url = URL(string: path), url.isFileURL { return url }
        return nil
    }
}
