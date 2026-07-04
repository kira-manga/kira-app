import Foundation
import ComposeApp

/// Single home for the native reader's user-facing strings. They come from the shared compose-resources
/// (resolved in `ReaderHostSwitch.ios` and handed over via `ReaderNativeBridge`), so they match the
/// Compose reader's translations in every locale; the English literals are only a fallback for the brief
/// window before the bridge strings are set. (`cancel` is unused dead code kept as a plain literal.)
enum ReaderStrings {
    private static var s: IosReaderStrings? { ReaderNativeBridge.shared.strings }

    static var failedToLoadImage: String { s?.failedToLoadImage ?? "Failed to load image" }
    static var retry: String { s?.retry ?? "Retry" }
    static var openInWebView: String { s?.openInWebView ?? "Open in WebView" }
    static var readingMode: String { s?.readingMode ?? "Reading mode" }
    static var cancel: String { "Cancel" }
    static var couldntLoadChapter: String { s?.couldntLoadChapter ?? "Couldn't load this chapter" }
    static var addToLibraryFirst: String { s?.addToLibraryFirst ?? "Add this manga to your library first" }
    static var nextChapter: String { s?.nextChapter ?? "Next chapter" }
    static var lastChapter: String { s?.lastChapter ?? "You're at the last chapter" }

    static func finished(_ chapter: String) -> String { "\(s?.finishedPrefix ?? "Finished:") \(chapter)" }

    /// `ReadingMode.name` → display label (parity with the Compose `ReadingModeDialog`).
    static func readingModeLabel(_ name: String) -> String {
        switch name {
        case "DEFAULT": return s?.modeDefault ?? "Default"
        case "RIGHT_TO_LEFT": return s?.modeRtl ?? "Right to left"
        case "LEFT_TO_RIGHT": return s?.modeLtr ?? "Left to right"
        case "VERTICAL": return s?.modeVertical ?? "Vertical"
        case "WEBTOON": return s?.modeWebtoon ?? "Webtoon"
        case "CONTINUOUS_VERTICAL": return s?.modeContinuous ?? "Continuous vertical"
        default: return name
        }
    }
}
