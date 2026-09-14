import UIKit
import XCTest

final class WebtoonReaderBoundaryTests: XCTestCase {
    @MainActor
    func testSkippedBoundaryPreservesLaterPageViewportAndReaderState() async throws {
        try await assertBoundaryChange(from: "Chapter 1", to: "Chapter 2")
    }

    @MainActor
    func testRecoveredBoundaryPreservesLaterPageViewportAndReaderState() async throws {
        try await assertBoundaryChange(from: nil, to: "Recovered chapter")
    }

    @MainActor
    func testTerminalBoundaryPreservesLaterPageViewportAndReaderState() async throws {
        try await assertBoundaryChange(from: "Chapter 1", to: nil)
    }

    @MainActor
    func testAppendReconfiguresChangedPrefixBoundaryWithoutResettingViewport() async throws {
        let fixture = try WebtoonFixture(next: "Skipped chapter")
        defer { fixture.close() }
        try await fixture.scrollToLaterPage()
        let before = try fixture.capture()
        let appended = try fixture.makePages(count: 2, name: "appended", height: 120)
        var rows = fixture.rows
        rows[8] = .boundary(finished: "Chapter 0 corrected", next: "Recovered chapter")
        rows += appended.enumerated().map { .image($0.element, pageIndex: 8 + $0.offset) }
        rows.append(.boundary(finished: "Recovered chapter", next: nil))

        fixture.apply(rows)
        await fixture.finishUIKitUpdates()

        try fixture.assertPreserved(before)
        XCTAssertEqual(fixture.collectionView.numberOfItems(inSection: 0), 12)
        try fixture.assertBoundary(finished: "Chapter 0 corrected", next: "Recovered chapter")
    }

    @MainActor
    func testFreshHeadersRebindVisiblePageWithoutResettingViewport() async throws {
        let fixture = try WebtoonFixture(next: "Chapter 1")
        defer { fixture.close() }
        try await fixture.scrollToLaterPage()
        let before = try fixture.capture()
        var rows = fixture.rows
        let headers = ["X-Reader-Regression": "fresh"]
        let page = ReaderPageItem(url: fixture.pages[7].url, headers: headers)
        rows[7] = .image(page, pageIndex: 7)

        fixture.apply(rows)
        await fixture.finishUIKitUpdates()

        try fixture.assertPreserved(before, imageMayReload: true)
        let cell = try fixture.imageCell(at: 7)
        let boundHeaders: [String: String] = try stored("pageHeaders", in: cell)
        XCTAssertEqual(boundHeaders, headers, "The real visible page must rebind fresh headers")
        XCTAssertNotNil(cell.imageView.image)
    }

    @MainActor
    func testChapterReplacementStillSeedsNewImagesAndHonorsNewResume() async throws {
        let fixture = try WebtoonFixture(next: "Chapter 1")
        defer { fixture.close() }
        try await fixture.scrollToLaterPage()
        let before = try fixture.capture()
        let replacement = try fixture.makePages(count: 5, name: "replacement", height: 80)
        let rows = replacement.enumerated().map { ReaderFeedRowItem.image($0.element, pageIndex: $0.offset) }
            + [.boundary(finished: "New chapter", next: nil)]

        fixture.apply(rows)
        fixture.controller.setResume(2)
        try await fixture.waitForLocalLayout(pageCount: 5, aspect: 1)

        let generation: Int = try stored("aspectSeedGeneration", in: fixture.controller)
        let didScroll: Bool = try stored("didUserScroll", in: fixture.controller)
        let prefetch: [String: String] = try stored("prefetchTokens", in: fixture.controller)
        XCTAssertEqual(generation, before.seedGeneration + 1)
        XCTAssertFalse(didScroll, "A genuine replacement resets the prior chapter's user-scroll latch")
        XCTAssertTrue(prefetch.isEmpty)
        XCTAssertEqual(fixture.collectionView.numberOfItems(inSection: 0), 6)
        let target = try fixture.frame(at: 2)
        XCTAssertEqual(fixture.collectionView.contentOffset.y, target.minY, accuracy: 0.5)
        XCTAssertEqual(try fixture.frame(at: 0).height, fixture.collectionView.bounds.width, accuracy: 0.5)
        let cell = try fixture.imageCell(at: 2)
        let boundURL: String = try stored("pageURL", in: cell)
        XCTAssertEqual(boundURL, replacement[2].url)
    }

    @MainActor
    private func assertBoundaryChange(from oldNext: String?, to next: String?) async throws {
        let fixture = try WebtoonFixture(next: oldNext)
        defer { fixture.close() }
        try await fixture.scrollToLaterPage()
        try fixture.assertBoundary(finished: "Chapter 0", next: oldNext)
        let before = try fixture.capture()
        var rows = fixture.rows
        rows[8] = .boundary(finished: "Chapter 0 corrected", next: next)

        fixture.apply(rows)
        await fixture.finishUIKitUpdates()

        try fixture.assertPreserved(before)
        try fixture.assertBoundary(finished: "Chapter 0 corrected", next: next)
    }
}

/// Real UIKit controller/cells/loader, local PNGs, and the production delegate entry point. This does
/// not synthesize touch gestures or bootstrap the shipping app. Reflection only supplements the visible
/// offset/layout/cell assertions, and an unavailable private field is a test failure, never a pass.
@MainActor
private final class WebtoonFixture {
    let controller: WebtoonReaderViewController
    let window: UIWindow
    let collectionView: UICollectionView
    private let directory: URL
    private let animationsWereEnabled = UIView.areAnimationsEnabled
    private(set) var rows: [ReaderFeedRowItem] = []
    var pages: [ReaderPageItem] {
        rows.compactMap { if case .image(let page, _) = $0 { return page }; return nil }
    }

    init(next: String?) throws {
        let reader = WebtoonReaderViewController(onPageChanged: { _ in }, onReachedEnd: {})
        let hostWindow = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 640))
        reader.loadViewIfNeeded()
        hostWindow.rootViewController = reader
        let collection = try XCTUnwrap(descendants(of: reader.view).compactMap { $0 as? UICollectionView }.first)
        controller = reader
        window = hostWindow
        collectionView = collection
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("WebtoonBoundary-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let initialPages = try makePages(count: 8, name: "initial", height: 120)
        UIView.setAnimationsEnabled(false)
        window.makeKeyAndVisible()
        window.layoutIfNeeded()
        controller.view.layoutIfNeeded()
        // Aspect seeding still reads every real image. Disable automatic pixel prefetch so the test
        // can own one known, not-yet-decoded prefetch through the actual datasource callback.
        collectionView.isPrefetchingEnabled = false
        apply(initialPages.enumerated().map { .image($0.element, pageIndex: $0.offset) }
            + [.boundary(finished: "Chapter 0", next: next)])
        controller.setResume(0)
    }

    func close() {
        controller.collectionView(collectionView, cancelPrefetchingForItemsAt: [IndexPath(item: 2, section: 0)])
        window.isHidden = true
        window.rootViewController = nil
        UIView.setAnimationsEnabled(animationsWereEnabled)
        try? FileManager.default.removeItem(at: directory)
    }

    func makePages(count: Int, name: String, height: CGFloat) throws -> [ReaderPageItem] {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let size = CGSize(width: 80, height: height)
        let png = UIGraphicsImageRenderer(size: size, format: format).pngData { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: size))
        }
        return try (0..<count).map { index in
            let file = directory.appendingPathComponent("\(name)-\(index).png")
            try png.write(to: file)
            return ReaderPageItem(url: file.absoluteString, headers: [:])
        }
    }

    func apply(_ newRows: [ReaderFeedRowItem]) {
        rows = newRows
        controller.setContent(pages: pages, rows: rows)
        collectionView.layoutIfNeeded()
    }

    func finishUIKitUpdates() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            DispatchQueue.main.async { continuation.resume() }
        }
        window.layoutIfNeeded()
        collectionView.layoutIfNeeded()
    }

    func waitForLocalLayout(pageCount: Int, aspect: CGFloat) async throws {
        let deadline = Date().addingTimeInterval(5)
        repeat {
            collectionView.layoutIfNeeded()
            let aspects: [Int: CGFloat] = try stored("aspects", in: controller)
            let cells = collectionView.visibleCells.compactMap { $0 as? ReaderPageCell }
            if aspects.count == pageCount && aspects.values.allSatisfy({ abs($0 - aspect) < 0.001 }) &&
                !cells.isEmpty && cells.allSatisfy({ $0.imageView.image != nil }) {
                return
            }
            try await Task.sleep(nanoseconds: 10_000_000)
        } while Date() < deadline
        XCTFail("Real local-image seed/decode did not settle")
        throw FixtureError.localImagesDidNotSettle
    }

    func scrollToLaterPage() async throws {
        try await waitForLocalLayout(pageCount: 8, aspect: 1.5)
        // Drive the registered production delegate, then move the real collection view. This is not
        // claimed to be a synthesized finger drag; setResume(0) deliberately remains stale.
        XCTAssertTrue(collectionView.delegate === controller)
        collectionView.delegate?.scrollViewWillBeginDragging?(collectionView)
        collectionView.setContentOffset(CGPoint(x: 0, y: collectionView.contentSize.height - collectionView.bounds.height), animated: false)
        try await waitForLocalLayout(pageCount: 8, aspect: 1.5)
        XCTAssertGreaterThan(collectionView.contentOffset.y, try frame(at: 6).minY)
        XCTAssertNotNil(try imageCell(at: 7).imageView.image)
        controller.collectionView(collectionView, prefetchItemsAt: [IndexPath(item: 2, section: 0)])
        let prefetch: [String: String] = try stored("prefetchTokens", in: controller)
        XCTAssertFalse(prefetch.isEmpty, "Fixture must exercise retained prefetch ownership")
    }

    func capture() throws -> ViewportState {
        let didScroll: Bool = try stored("didUserScroll", in: controller)
        let restoreTarget: Int = try stored("restoreTargetPage", in: controller)
        XCTAssertTrue(didScroll)
        XCTAssertEqual(restoreTarget, 0, "Later-page scrolling must not update the original resume target")
        return ViewportState(offset: collectionView.contentOffset, firstFrame: try frame(at: 0),
                             imageCell: try imageCell(at: 7),
                             seedGeneration: try stored("aspectSeedGeneration", in: controller),
                             aspects: try stored("aspects", in: controller),
                             prefetch: try stored("prefetchTokens", in: controller))
    }

    func assertPreserved(_ before: ViewportState, imageMayReload: Bool = false) throws {
        XCTAssertEqual(collectionView.contentOffset.x, before.offset.x, accuracy: 0.5)
        XCTAssertEqual(collectionView.contentOffset.y, before.offset.y, accuracy: 0.5)
        XCTAssertEqual(try frame(at: 0), before.firstFrame, "Offscreen decoded dimensions must survive")
        let visiblePage = try imageCell(at: 7)
        XCTAssertNotNil(visiblePage.imageView.image)
        if !imageMayReload { XCTAssertTrue(visiblePage === before.imageCell, "Unchanged images must not reload") }
        let generation: Int = try stored("aspectSeedGeneration", in: controller)
        let didScroll: Bool = try stored("didUserScroll", in: controller)
        let aspects: [Int: CGFloat] = try stored("aspects", in: controller)
        let prefetch: [String: String] = try stored("prefetchTokens", in: controller)
        XCTAssertEqual(generation, before.seedGeneration, "Content-only updates must not start a new resume seed")
        XCTAssertTrue(didScroll)
        for (position, aspect) in before.aspects { XCTAssertEqual(aspects[position], aspect) }
        XCTAssertEqual(prefetch, before.prefetch)
    }

    func assertBoundary(finished: String, next: String?) throws {
        let cell = try XCTUnwrap(collectionView.cellForItem(at: IndexPath(item: 8, section: 0)) as? ReaderBoundaryCell)
        let texts = descendants(of: cell).compactMap { $0 as? UILabel }.filter { !$0.isHidden }.compactMap { $0.text }
        XCTAssertTrue(texts.contains(ReaderStrings.finished(finished)))
        XCTAssertTrue(texts.contains(next ?? ReaderStrings.lastChapter))
        XCTAssertEqual(texts.contains(ReaderStrings.nextChapter), next != nil)
        XCTAssertEqual(texts.count, next == nil ? 2 : 3, "Do not leave stale boundary labels visible")
    }

    func frame(at position: Int) throws -> CGRect {
        try XCTUnwrap(collectionView.layoutAttributesForItem(at: IndexPath(item: position, section: 0))).frame
    }

    func imageCell(at position: Int) throws -> ReaderPageCell {
        try XCTUnwrap(collectionView.cellForItem(at: IndexPath(item: position, section: 0)) as? ReaderPageCell)
    }
}

private struct ViewportState {
    let offset: CGPoint
    let firstFrame: CGRect
    let imageCell: ReaderPageCell
    let seedGeneration: Int
    let aspects: [Int: CGFloat]
    let prefetch: [String: String]
}

private enum FixtureError: Error { case localImagesDidNotSettle }

private func stored<T>(_ name: String, in object: Any) throws -> T {
    try XCTUnwrap(Mirror(reflecting: object).children.first { $0.label == name }?.value as? T,
                  "Required production field is not observable: \(name)")
}

@MainActor
private func descendants(of view: UIView) -> [UIView] {
    view.subviews.flatMap { [$0] + descendants(of: $0) }
}
