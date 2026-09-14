import UIKit
import XCTest

final class WebtoonPrefetchOwnershipTests: XCTestCase {
    @MainActor
    func testDuplicateUrlsKeepOneTokenPerRowAndRepeatedPrefetchDoesNotOverwriteIt() throws {
        let fixture = try WebtoonPrefetchFixture()
        let visibleTokens = Set(fixture.loader.requests.map { $0.token })
        let baseline = Set(fixture.loader.cancelled)
        let requests = fixture.prefetch([3, 4])
        XCTAssertEqual(requests.count, 2)
        XCTAssertEqual(requests[0].url, requests[1].url)
        XCTAssertNotEqual(requests[0].token, requests[1].token)
        XCTAssertTrue(fixture.prefetch([3]).isEmpty())
        fixture.controller.collectionView(fixture.collection, cancelPrefetchingForItemsAt: [IndexPath(item: 3, section: 0)])
        let changed = Set(fixture.loader.cancelled).subtracting(baseline)
        XCTAssertTrue(changed.contains(requests[0].token))
        XCTAssertFalse(changed.contains(requests[1].token))
        XCTAssertTrue(changed.isDisjoint(with: visibleTokens), "prefetch cancellation cannot steal a visible subscriber")
        fixture.controller.collectionView(fixture.collection, cancelPrefetchingForItemsAt: [IndexPath(item: 4, section: 0)])
        XCTAssertTrue(fixture.loader.cancelled.contains(requests[1].token))
    }

    @MainActor
    func testHeaderReplacementCancelsOldPrefetchAndNewRequestsUseFreshHeaders() throws {
        let fixture = try WebtoonPrefetchFixture()
        let prior = fixture.prefetch([3, 4])
        fixture.set(fixture.pages.map { ReaderPageItem(url: $0.url, headers: ["Cookie": "fresh"]) })
        prior.forEach { XCTAssertTrue(fixture.loader.cancelled.contains($0.token)) }
        let refreshed = fixture.prefetch([3, 4])
        XCTAssertEqual(refreshed.count, 2)
        XCTAssertTrue(refreshed.allSatisfy { $0.headers == ["Cookie": "fresh"] })
    }

    @MainActor
    func testOrdinaryAppendRetainsTokensButAppendWithChangedExistingHeadersCancelsThem() throws {
        let fixture = try WebtoonPrefetchFixture()
        let prior = try XCTUnwrap(fixture.prefetch([3]).first)
        fixture.set(fixture.pages + [ReaderPageItem(url: "https://reader.test/appended", headers: [:])])
        XCTAssertFalse(fixture.loader.cancelled.contains(prior.token), "an unchanged append is not content replacement")
        XCTAssertTrue(fixture.prefetch([3]).isEmpty())
        let refreshed = fixture.pages.map { ReaderPageItem(url: $0.url, headers: ["Cookie": "new"]) }
        fixture.set(refreshed + [ReaderPageItem(url: "https://reader.test/next", headers: [:])])
        XCTAssertTrue(fixture.loader.cancelled.contains(prior.token))
        let after = try XCTUnwrap(fixture.prefetch([3]).first)
        XCTAssertEqual(after.headers, ["Cookie": "new"])
        XCTAssertNotEqual(after.token, prior.token)
    }

    @MainActor
    func testWidthChangeAndDisappearanceCancelOwnedPrefetches() throws {
        let fixture = try WebtoonPrefetchFixture()
        let before = try XCTUnwrap(fixture.prefetch([3]).first)
        fixture.controller.view.frame.size.width = 480
        fixture.controller.viewDidLayoutSubviews()
        XCTAssertTrue(fixture.loader.cancelled.contains(before.token))
        let resized = try XCTUnwrap(fixture.prefetch([3]).first)
        XCTAssertEqual(resized.width, 480 * UIScreen.main.scale)
        fixture.controller.viewDidDisappear(false)
        XCTAssertTrue(fixture.loader.cancelled.contains(resized.token))
    }

    @MainActor
    func testRealDoubleTapZoomActionCancelsPrefetchBeforeResharpening() throws {
        let fixture = try WebtoonPrefetchFixture()
        let before = try XCTUnwrap(fixture.prefetch([3]).first)
        let selector = NSSelectorFromString("handleDoubleTap:")
        XCTAssertTrue(fixture.controller.responds(to: selector))
        _ = fixture.controller.perform(selector, with: fixture.controller.zoomDoubleTap)
        XCTAssertTrue(fixture.loader.cancelled.contains(before.token))
        let zoomed = try XCTUnwrap(fixture.prefetch([3]).first)
        XCTAssertEqual(zoomed.width, before.width * 2.5)
    }

    @MainActor
    func testDeinitCancelsOutstandingPrefetchWithoutImageCallbacksRetainingTheController() throws {
        let loader = ReaderFakeImageLoader()
        weak var controller: WebtoonReaderViewController?
        var tokens: [String] = []
        try autoreleasepool {
            var fixture: WebtoonPrefetchFixture? = try WebtoonPrefetchFixture(loader: loader)
            controller = fixture?.controller
            tokens = fixture?.prefetch([3, 4]).map { $0.token } ?? []
            fixture = nil
        }
        XCTAssertNil(controller)
        XCTAssertEqual(tokens.count, 2)
        tokens.forEach { XCTAssertTrue(loader.cancelled.contains($0)) }
    }
}

@MainActor
private final class WebtoonPrefetchFixture {
    let loader: ReaderFakeImageLoader
    let controller: WebtoonReaderViewController
    let collection: UICollectionView
    private(set) var pages: [ReaderPageItem] = []

    init(loader: ReaderFakeImageLoader = ReaderFakeImageLoader()) throws {
        let controller = WebtoonReaderViewController(onPageChanged: { _ in }, onReachedEnd: {}, imageLoader: loader)
        controller.loadViewIfNeeded()
        controller.view.frame = CGRect(x: 0, y: 0, width: 320, height: 640)
        controller.view.layoutIfNeeded()
        let collection = try XCTUnwrap(readerDescendants(controller.view).compactMap { $0 as? UICollectionView }.first)
        self.loader = loader
        self.controller = controller
        self.collection = collection
        // Drive only the real prefetch delegate methods selected by the test, not UIKit heuristics.
        collection.isPrefetchingEnabled = false
        set((0..<12).map { _ in ReaderPageItem(url: "https://reader.test/duplicate", headers: [:]) })
    }

    func set(_ pages: [ReaderPageItem]) {
        self.pages = pages
        controller.setContent(pages: pages, rows: pages.enumerated().map { .image($0.element, pageIndex: $0.offset) })
        collection.layoutIfNeeded()
    }

    func prefetch(_ indices: [Int]) -> [ReaderFakeImageLoader.Request] {
        let start = loader.requests.count
        controller.collectionView(collection, prefetchItemsAt: indices.map { IndexPath(item: $0, section: 0) })
        return Array(loader.requests.dropFirst(start))
    }
}

@MainActor
private func readerDescendants(_ view: UIView) -> [UIView] {
    view.subviews.flatMap { [$0] + readerDescendants($0) }
}
