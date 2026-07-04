import Foundation

/// Lightweight, opt-in diagnostics for the native reader image/snapshot pipeline. Flip [enabled] to
/// `true` (e.g. in `iOSApp.init`) and read the Xcode console while scrolling a chapter.
///
/// Logs, per stage: thread (MAIN vs bg), elapsed ms, and (for the main thread) a HITCH marker when a task
/// exceeds the per-frame budget — so you can see exactly where a stutter originates (download / decode /
/// downsample / image assignment / snapshot apply) and on which thread it runs.
///
/// Zero overhead when disabled (every entry point early-returns before doing any work).
enum ReaderPerfLog {
    /// Master switch. Keep `false` in committed code; flip locally to profile.
    static var enabled = false

    /// One frame at 60 Hz ≈ 16.7 ms; warn earlier so we catch work that *competes* with rendering.
    static var hitchBudgetMs: Double = 8

    static func now() -> UInt64 { DispatchTime.now().uptimeNanoseconds }
    static func ms(since start: UInt64) -> Double { Double(DispatchTime.now().uptimeNanoseconds &- start) / 1_000_000 }

    private static var thread: String { Thread.isMainThread ? "MAIN" : "bg" }

    static func log(_ stage: String, _ detail: @autoclosure () -> String = "") {
        guard enabled else { return }
        NSLog("[ReaderPerf] %@ [%@] %@", stage, thread, detail())
    }

    /// Run `block`, logging its duration; if on the main thread and over budget, mark it as a HITCH.
    @discardableResult
    static func mainTask<T>(_ name: String, _ block: () -> T) -> T {
        guard enabled else { return block() }
        let start = now()
        let result = block()
        let elapsed = ms(since: start)
        if elapsed > hitchBudgetMs {
            NSLog("[ReaderPerf] ⚠️ HITCH %@ %.1fms [%@]", name, elapsed, thread)
        } else {
            NSLog("[ReaderPerf] %@ %.1fms [%@]", name, elapsed, thread)
        }
        return result
    }

    /// Short, log-friendly tail of a URL.
    static func tail(_ url: String) -> String { String(url.suffix(40)) }
}
