import UIKit
import ImageIO

/// Dependency-free image loader for the native reader (no SPM packages, to keep the build self-contained).
///
/// - Fetches over `URLSession` with per-request HTTP headers (Referer / User-Agent from the shared
///   `Page.headers`), or reads a local `file://` / path directly (downloaded chapters) with no network.
/// - Decodes via ImageIO `CGImageSourceCreateThumbnailAtIndex`, downsampling to ~screen width. Width is
///   preserved at device resolution (no blur); only the longest edge is bounded by [maxPixelDimension]
///   as a safety valve for pathologically tall strips.
/// - Coalesces duplicate in-flight requests for the same URL and supports **per-request** cancellation:
///   each `load` returns a unique token and `cancel(token:)` removes only THAT requester — the shared
///   request (and its in-progress decode) is aborted only when the LAST requester leaves. This is
///   load-bearing: a prefetch-cancel or a cell reuse must NOT drop the completion of a *different,
///   still-visible* cell that happens to await the same URL. (Keying cancellation by URL did exactly
///   that — a cancelled prefetch cleared the shared entry, so a visible cell never got its image and
///   stayed black until a re-scroll re-requested the now-cached image.)
/// - Exposes [localAspect]: a cheap header-only (no pixel decode) height/width read for LOCAL pages, so
///   the continuous reader can size cells from real dimensions instead of a full-screen placeholder —
///   avoids the shrink storm when a chapter has hundreds of small pages.
/// - Keeps a cost-bounded in-memory cache of decoded images plus a URLCache disk.
///
/// Quality/perf note (see IOS_NATIVE_READER.md): per-band tiling (CATiledLayer) for ultra-tall strips is
/// a documented follow-up; v1 relies on CoreAnimation's large-layer compositing, which is smooth on iOS.
final class ReaderImageLoader {
    static let shared = ReaderImageLoader()

    private let session: URLSession
    private let memoryCache = NSCache<NSString, UIImage>()
    /// height/width per URL, read cheaply from the image header (no pixel decode). Local pages only.
    private let aspectCache = NSCache<NSString, NSNumber>()
    private let ioQueue = DispatchQueue(label: "me.manga.kira.reader.decode", qos: .userInitiated, attributes: .concurrent)
    /// Decode-concurrency gate (mobile hardening 2026-07-04): the concurrent ioQueue put NO limit
    /// on how many downsample passes run at once, so a fast webtoon fling could have the OS
    /// prefetch window decoding an unbounded set of up-to-56MB strips simultaneously (each
    /// transiently holding encoded Data + CGImageSource + the output image) — a memory-spike /
    /// jetsam vector. At most [3] decodes run concurrently; the rest park on the semaphore
    /// off-main (all decode blocks already run on ioQueue). 3 keeps a slow 12kpx strip from
    /// starving small visible pages while bounding the transient peak.
    private let decodeGate = DispatchSemaphore(value: 3)
    private let lock = NSLock()
    /// (url|width) request-key → the (token, callback) requesters sharing that decode. Keyed by
    /// the SAME composite key as [memoryCache] (L3): coalescing by bare URL let a zoom-triggered
    /// sharper re-decode join an in-flight LOWER-res request and receive the blurry image once.
    private var inFlight: [String: [(token: Int, cb: (UIImage?) -> Void)]] = [:]
    /// token → request-key, so `cancel(token:)` can remove exactly one requester.
    private var tokenURL: [Int: String] = [:]
    private var nextToken: Int = 0
    /// request-key → the token that CREATED the current in-flight record. A finishing task only
    /// touches the registry when it still owns the record — see the generation guard in `finish`.
    private var recordOwner: [String: Int] = [:]
    private var tasks: [String: URLSessionDataTask] = [:]
    private var progressObservations: [String: NSKeyValueObservation] = [:]
    private var lastProgress: [String: Double] = [:]

    /// Hard ceiling on the longest decoded edge (px). Named + documented, adjustable; not a hidden hack.
    private let maxPixelDimension: CGFloat = 12_000

    private init() {
        let cfg = URLSessionConfiguration.default
        cfg.requestCachePolicy = .returnCacheDataElseLoad
        cfg.urlCache = URLCache(memoryCapacity: 16 * 1024 * 1024,
                                diskCapacity: 256 * 1024 * 1024,
                                diskPath: "kira_reader_images")
        cfg.timeoutIntervalForRequest = 30
        session = URLSession(configuration: cfg)
        // Device-tiered decoded-image budget (mobile hardening 2026-07-04): a flat 256MB was a
        // large slice of the jetsam budget on 2GB devices, sitting on top of decode transients and
        // layer backing stores. Tiers mirror the KMP DeviceTier RAM thresholds
        // (core/.../heap/DeviceTier.kt: LOW < 2GB, MID ≤ 4GB). Smaller tiers just re-decode on
        // scroll-back — NSCache + the memory-warning purge already accept that failure mode.
        let physicalMemory = ProcessInfo.processInfo.physicalMemory
        let cacheLimitMB: Int
        if physicalMemory < 2 * 1024 * 1024 * 1024 {
            cacheLimitMB = 64
        } else if physicalMemory <= 4 * 1024 * 1024 * 1024 {
            cacheLimitMB = 128
        } else {
            cacheLimitMB = 256
        }
        memoryCache.totalCostLimit = cacheLimitMB * 1024 * 1024
        NotificationCenter.default.addObserver(
            self, selector: #selector(purgeMemoryCache),
            name: UIApplication.didReceiveMemoryWarningNotification, object: nil)
    }

    @objc private func purgeMemoryCache() { memoryCache.removeAllObjects() }

    private func key(_ url: String, _ targetWidthPx: CGFloat) -> NSString { "\(url)|\(Int(targetWidthPx))" as NSString }

    /// Cheap height/width for a LOCAL page, from its image header — **no pixel decode**. Cached. Returns
    /// `nil` for remote URLs (dimensions need the download) or unreadable files. Safe to call off-main.
    func localAspect(_ url: String) -> CGFloat? {
        guard url.hasPrefix("file:") || url.hasPrefix("/") else { return nil }
        let nsKey = url as NSString
        if let cached = aspectCache.object(forKey: nsKey) { return CGFloat(cached.doubleValue) }
        let path = url.hasPrefix("file:") ? (URL(string: url)?.path ?? url) : url
        guard let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL,
                                                   [kCGImageSourceShouldCache: false] as CFDictionary),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let w = (props[kCGImagePropertyPixelWidth] as? CGFloat), w > 0,
              let h = (props[kCGImagePropertyPixelHeight] as? CGFloat), h > 0 else { return nil }
        let aspect = h / w
        aspectCache.setObject(NSNumber(value: Double(aspect)), forKey: nsKey)
        return aspect
    }

    /// Load + downsample. `completion` is invoked on the main thread. Returns a unique **cancel token**
    /// (empty string when the image was already cached — nothing to cancel).
    @discardableResult
    func load(url: String,
              headers: [String: String],
              targetWidthPx: CGFloat,
              onProgress: ((Double) -> Void)? = nil,
              completion: @escaping (UIImage?) -> Void) -> String {
        let cacheKey = key(url, targetWidthPx)
        let requestKey = cacheKey as String
        if let cached = memoryCache.object(forKey: cacheKey) {
            completion(cached)
            return ""
        }
        lock.lock()
        let token = nextToken
        nextToken += 1
        tokenURL[token] = requestKey
        if inFlight[requestKey] != nil {
            inFlight[requestKey]?.append((token, completion))
            lock.unlock()
            return "\(token)"
        }
        inFlight[requestKey] = [(token, completion)]
        recordOwner[requestKey] = token
        lock.unlock()

        let t0 = ReaderPerfLog.now()
        ReaderPerfLog.log("load.start", ReaderPerfLog.tail(url))

        let ownerToken = token
        let finish: (UIImage?) -> Void = { [weak self] image in
            guard let self = self else { return }
            if let image = image {
                let cost = Int(image.size.width * image.size.height * image.scale * image.scale * 4)
                self.memoryCache.setObject(image, forKey: cacheKey, cost: cost)
            }
            self.lock.lock()
            // Generation guard (audit P1): after cancel-of-the-last-requester, a NEW load for the
            // same url|width registers its own record + task. The CANCELLED task's completion
            // still fires (data == nil) — without this guard it delivered nil to the NEW record's
            // requesters and unregistered them, so the live task later found nobody to notify
            // (image cached but never delivered → cell stuck on the error placeholder during fast
            // webtoon scrolling). A stale finish may still cache above; it must not touch the
            // registry or deliver.
            guard self.recordOwner[requestKey] == ownerToken else {
                self.lock.unlock()
                return
            }
            let entries = self.inFlight[requestKey] ?? []
            self.inFlight[requestKey] = nil
            self.recordOwner[requestKey] = nil
            for e in entries { self.tokenURL[e.token] = nil }
            self.tasks[requestKey] = nil
            self.progressObservations[requestKey]?.invalidate()
            self.progressObservations[requestKey] = nil
            self.lastProgress[requestKey] = nil
            self.lock.unlock()
            DispatchQueue.main.async { entries.forEach { $0.cb(image) } }
        }

        // Local / downloaded page → no network.
        if url.hasPrefix("file:") || url.hasPrefix("/") {
            ioQueue.async { [weak self] in
                guard let self = self else { return }
                let path = url.hasPrefix("file:") ? (URL(string: url)?.path ?? url) : url
                ReaderPerfLog.log("decode.start", "local \(ReaderPerfLog.tail(url))")
                let dt = ReaderPerfLog.now()
                self.decodeGate.wait()
                let image = self.downsample(fileURL: URL(fileURLWithPath: path), targetWidthPx: targetWidthPx)
                self.decodeGate.signal()
                ReaderPerfLog.log("decode.done", "\(Int(ReaderPerfLog.ms(since: dt)))ms out=\(image.map { "\(Int($0.size.width))x\(Int($0.size.height))" } ?? "nil")")
                finish(image)
            }
            return "\(token)"
        }

        guard let realURL = URL(string: url) else { finish(nil); return "\(token)" }
        var request = URLRequest(url: realURL)
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        let task = session.dataTask(with: request) { [weak self] data, _, _ in
            guard let self = self else { return }
            guard let data = data, !data.isEmpty else { finish(nil); return }
            ReaderPerfLog.log("download.done", "\(ReaderPerfLog.tail(url)) \(data.count)B \(Int(ReaderPerfLog.ms(since: t0)))ms")
            self.ioQueue.async {
                ReaderPerfLog.log("decode.start", ReaderPerfLog.tail(url))
                let dt = ReaderPerfLog.now()
                self.decodeGate.wait()
                let image = self.downsample(data: data, targetWidthPx: targetWidthPx)
                self.decodeGate.signal()
                ReaderPerfLog.log("decode.done", "\(Int(ReaderPerfLog.ms(since: dt)))ms out=\(image.map { "\(Int($0.size.width))x\(Int($0.size.height))" } ?? "nil")")
                finish(image)
            }
        }
        lock.lock()
        tasks[requestKey] = task
        // Determinate progress (when the server sends Content-Length). KVO on the task's Progress;
        // throttled to ≥1% advances so the UI is not spammed and scrolling is unaffected.
        if let onProgress = onProgress {
            lastProgress[requestKey] = 0
            progressObservations[requestKey] = task.progress.observe(\.fractionCompleted, options: [.new]) { [weak self] prog, _ in
                guard let self = self else { return }
                let f = prog.fractionCompleted
                self.lock.lock()
                let advanced = f - (self.lastProgress[requestKey] ?? 0) >= 0.01
                if advanced { self.lastProgress[requestKey] = f }
                self.lock.unlock()
                if advanced { DispatchQueue.main.async { onProgress(f) } }
            }
        }
        lock.unlock()
        task.resume()
        return "\(token)"
    }

    /// Cancel ONE requester (by the token `load` returned). The shared decode/download is aborted only
    /// when no requesters remain — so cancelling a prefetch never drops a visible cell's coalesced
    /// completion. Harmless if the token already finished or was cached (`""`).
    func cancel(token: String) {
        guard let t = Int(token) else { return }
        lock.lock()
        guard let requestKey = tokenURL[t] else { lock.unlock(); return }
        tokenURL[t] = nil
        inFlight[requestKey]?.removeAll { $0.token == t }
        if inFlight[requestKey]?.isEmpty == true {
            inFlight[requestKey] = nil
            // The record is dead: disown it so the cancelled task's late completion can't claim
            // a successor record for this key (generation guard in `finish`).
            recordOwner[requestKey] = nil
            tasks[requestKey]?.cancel()
            tasks[requestKey] = nil
            progressObservations[requestKey]?.invalidate()
            progressObservations[requestKey] = nil
            lastProgress[requestKey] = nil
        }
        lock.unlock()
    }

    // MARK: - ImageIO downsampling

    private func downsample(data: Data, targetWidthPx: CGFloat) -> UIImage? {
        guard let src = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        return downsample(source: src, targetWidthPx: targetWidthPx)
    }

    private func downsample(fileURL: URL, targetWidthPx: CGFloat) -> UIImage? {
        guard let src = CGImageSourceCreateWithURL(fileURL as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        return downsample(source: src, targetWidthPx: targetWidthPx)
    }

    private func downsample(source: CGImageSource, targetWidthPx: CGFloat) -> UIImage? {
        let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        let w = (props?[kCGImagePropertyPixelWidth] as? CGFloat) ?? targetWidthPx
        let h = (props?[kCGImagePropertyPixelHeight] as? CGFloat) ?? targetWidthPx
        let maxPixel: CGFloat
        if w <= 0 || h <= 0 || w >= h {
            maxPixel = min(targetWidthPx, maxPixelDimension)
        } else {
            // Tall strip: bound by the height that corresponds to a target-width fit (keeps width crisp),
            // capped by the safety ceiling. Never upscales beyond source.
            maxPixel = min((targetWidthPx * h / w).rounded(.up), maxPixelDimension)
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return UIImage(cgImage: cg)
    }
}
