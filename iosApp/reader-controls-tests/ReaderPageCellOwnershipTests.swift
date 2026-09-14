import UIKit
import XCTest

final class ReaderPageCellOwnershipTests: XCTestCase {
    @MainActor
    func testBothCellKindsCancelReplacedUrlsAndHeadersAndIgnoreLateCompletions() {
        for cell in cellFixtures() {
            let old = readerTestImage(.red), current = readerTestImage(.blue)
            cell.configure("https://reader.test/old", ["Cookie": "old"], 100)
            let first = cell.loader.requests[0]
            cell.configure("https://reader.test/new", ["Cookie": "old"], 100)
            XCTAssertTrue(cell.loader.cancelled.contains(first.token))
            first.completion(old)
            XCTAssertNil(cell.imageView.image)
            let second = cell.loader.requests[1]
            cell.configure("https://reader.test/new", ["Cookie": "new"], 100)
            XCTAssertTrue(cell.loader.cancelled.contains(second.token))
            second.completion(old)
            XCTAssertNil(cell.imageView.image)
            cell.loader.requests[2].completion(current)
            XCTAssertTrue(cell.imageView.image === current)
            cell.reuse()
            XCTAssertTrue(cell.loader.cancelled.contains(cell.loader.requests[2].token))
            cell.loader.requests[2].completion(old)
            XCTAssertNil(cell.imageView.image)
        }
    }

    @MainActor
    func testSameImageZoomReloadKeepsTheDisplayedImageUntilReplacement() {
        for cell in cellFixtures() {
            let image = readerTestImage()
            cell.configure("https://reader.test/same", [:], 100)
            let first = cell.loader.requests[0]
            first.completion(image)
            cell.configure("https://reader.test/same", [:], 200)
            XCTAssertTrue(cell.loader.cancelled.contains(first.token))
            XCTAssertTrue(cell.imageView.image === image, "higher-resolution reload must not flash a blank page")
            XCTAssertEqual(cell.loader.requests[1].width, first.width * 2)
            first.completion(nil)
            XCTAssertTrue(cell.imageView.image === image)
            cell.configure("https://reader.test/same", ["Cookie": "changed"], 200)
            XCTAssertNil(cell.imageView.image, "a changed representation cannot retain the previous authenticated image")
        }
    }

    @MainActor
    func testLoaderReplacementCancelsOnTheOldOwnerForBothCellKinds() {
        let webtoon = ReaderPageCell(frame: .zero)
        let paged = ReaderPagedCell(frame: .zero)
        let old = ReaderFakeImageLoader(), next = ReaderFakeImageLoader()
        webtoon.imageLoader = old
        paged.imageLoader = old
        webtoon.configure(url: "https://reader.test/a", headers: [:], widthPt: 100, onAspect: nil)
        paged.configure(url: "https://reader.test/b", headers: [:], widthPt: 100)
        webtoon.imageLoader = next
        paged.imageLoader = next
        XCTAssertEqual(Set(old.cancelled), Set(old.requests.map { $0.token }))
        XCTAssertTrue(next.cancelled.isEmpty())
        old.requests.forEach { $0.completion(readerTestImage(.red)) }
        XCTAssertNil(webtoon.imageView.image)
        XCTAssertNil(paged.imageView.image)
    }

    @MainActor
    func testSynchronousCompletionCannotOverwriteAReentrantSuccessorToken() {
        let cell = ReaderPageCell(frame: .zero)
        let loader = ReaderFakeImageLoader()
        cell.imageLoader = loader
        let image = readerTestImage()
        loader.onLoad = { request in if request.url.hasSuffix("first") { request.completion(image) } }
        cell.configure(url: "https://reader.test/first", headers: [:], widthPt: 100) { _ in
            cell.configure(url: "https://reader.test/second", headers: [:], widthPt: 100, onAspect: nil)
        }
        XCTAssertEqual(loader.requests.count, 2)
        XCTAssertEqual(loader.cancelled, [loader.requests[0].token])
        cell.prepareForReuse()
        XCTAssertEqual(loader.cancelled, loader.requests.map { $0.token }, "reuse must still own the successor, not the late old token")
    }

    @MainActor
    func testReentrantLoaderSwapReturnsTheObsoleteTokenToItsCorrectOwner() {
        let cell = ReaderPagedCell(frame: .zero)
        let old = ReaderFakeImageLoader(), next = ReaderFakeImageLoader()
        cell.imageLoader = old
        old.onLoad = { _ in
            cell.imageLoader = next
            cell.configure(url: "https://reader.test/new", headers: [:], widthPt: 100)
        }
        cell.configure(url: "https://reader.test/old", headers: [:], widthPt: 100)
        old.onLoad = nil
        XCTAssertEqual(old.cancelled, [old.requests[0].token])
        XCTAssertTrue(next.cancelled.isEmpty())
        cell.prepareForReuse()
        XCTAssertEqual(next.cancelled, [next.requests[0].token])
    }

    @MainActor
    func testDeinitCancelsPendingTokensWithoutCallbacksRetainingCells() {
        let loader = ReaderFakeImageLoader()
        weak var webtoon: ReaderPageCell?
        weak var paged: ReaderPagedCell?
        autoreleasepool {
            let first = ReaderPageCell(frame: .zero)
            let second = ReaderPagedCell(frame: .zero)
            webtoon = first; paged = second
            first.imageLoader = loader; second.imageLoader = loader
            first.configure(url: "https://reader.test/a", headers: [:], widthPt: 100, onAspect: nil)
            second.configure(url: "https://reader.test/b", headers: [:], widthPt: 100)
        }
        XCTAssertNil(webtoon)
        XCTAssertNil(paged)
        XCTAssertEqual(Set(loader.cancelled), Set(loader.requests.map { $0.token }))
    }

    @MainActor
    private func cellFixtures() -> [ReaderCellFixture] {
        let webtoon = ReaderPageCell(frame: .zero), paged = ReaderPagedCell(frame: .zero)
        let first = ReaderFakeImageLoader(), second = ReaderFakeImageLoader()
        webtoon.imageLoader = first; paged.imageLoader = second
        return [
            ReaderCellFixture(loader: first, imageView: webtoon.imageView,
                              configure: { webtoon.configure(url: $0, headers: $1, widthPt: $2, onAspect: nil) },
                              reuse: { webtoon.prepareForReuse() }),
            ReaderCellFixture(loader: second, imageView: paged.imageView,
                              configure: { paged.configure(url: $0, headers: $1, widthPt: $2) },
                              reuse: { paged.prepareForReuse() }),
        ]
    }
}

@MainActor
private struct ReaderCellFixture {
    let loader: ReaderFakeImageLoader
    let imageView: UIImageView
    let configure: (String, [String: String], CGFloat) -> Void
    let reuse: () -> Void
}
