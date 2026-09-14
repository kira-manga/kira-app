import UIKit
import XCTest

final class ReaderImageLoaderBehaviorTests: XCTestCase {
    @MainActor
    func testCoalescedSubscribersKeepIndependentCancellationAndHeadersSeparateRepresentations() throws {
        let files = try ReaderTestFiles()
        let transport = ReaderFakePageTransport()
        let image = readerTestImage()
        let loader = ReaderImageLoader(transport: transport, fileDecoder: { _, _ in image })
        var abandonedCalls = 0
        let owner = loader.load(url: "https://reader.test/page.jpg", headers: ["Cookie": "old"], targetWidthPx: 100) { _ in abandonedCalls += 1 }
        let completed = expectation(description: "surviving subscribers")
        completed.expectedFulfillmentCount = 2
        let survivor = loader.load(url: "https://reader.test/page.jpg", headers: ["cookie": "old"], targetWidthPx: 100) { result in
            XCTAssertTrue(result === image)
            completed.fulfill()
        }
        loader.cancel(token: owner)
        XCTAssertEqual(transport.taskCount, 1)
        XCTAssertEqual(transport.transfer(0).task.cancelCount, 0, "one cancelled subscriber cannot cancel its survivor")
        XCTAssertNotEqual(owner, survivor)
        loader.load(url: "https://reader.test/page.jpg", headers: ["Cookie": "new", "Referer": "https://reader.test/"], targetWidthPx: 100) { result in
            XCTAssertTrue(result === image)
            completed.fulfill()
        }
        XCTAssertEqual(transport.taskCount, 2)
        XCTAssertEqual(transport.transfer(1).request.value(forHTTPHeaderField: "Cookie"), "new")
        XCTAssertEqual(transport.transfer(1).request.value(forHTTPHeaderField: "Referer"), "https://reader.test/")
        XCTAssertEqual(transport.transfer(0).task.resumeCount, 1)
        transport.complete(0, with: try files.owned())
        transport.complete(1, with: try files.owned())
        wait(for: [completed], timeout: 5)
        XCTAssertEqual(abandonedCalls, 0)
    }

    @MainActor
    func testOffMainCacheDeliveryCanStillBeCancelledAndMainHitStaysSynchronous() throws {
        let files = try ReaderTestFiles()
        let transport = ReaderFakePageTransport()
        let image = readerTestImage()
        let loader = ReaderImageLoader(transport: transport, fileDecoder: { _, _ in image })
        let warm = expectation(description: "cache warmed")
        loader.load(url: "https://reader.test/cache", headers: [:], targetWidthPx: 100) { _ in warm.fulfill() }
        transport.complete(0, with: try files.owned())
        wait(for: [warm], timeout: 5)
        let ready = DispatchSemaphore(value: 0)
        let token = ReaderLockedValue("")
        let offMainCalls = ReaderLockedValue(0)
        DispatchQueue.global().async {
            token.set(loader.load(url: "https://reader.test/cache", headers: [:], targetWidthPx: 100) { _ in
                offMainCalls.set(offMainCalls.get() + 1)
            })
            ready.signal()
        }
        // Hold main delivery until the background load returns its independently cancellable token.
        XCTAssertEqual(ready.wait(timeout: .now() + 5), .success)
        XCTAssertFalse(token.get().isEmpty)
        loader.cancel(token: token.get())
        readerDrainMain()
        XCTAssertEqual(offMainCalls.get(), 0)
        var synchronous = false
        XCTAssertEqual(loader.load(url: "https://reader.test/cache", headers: [:], targetWidthPx: 100) { result in
            synchronous = true
            XCTAssertTrue(result === image)
        }, "")
        XCTAssertTrue(synchronous)
        XCTAssertEqual(transport.taskCount, 1)
    }

    @MainActor
    func testAQueuedProgressDeliveryIsCancelledPerSubscriber() {
        let transport = ReaderFakePageTransport()
        let loader = ReaderImageLoader(transport: transport)
        var firstProgress: [Double] = [], secondProgress: [Double] = []
        let first = loader.load(url: "https://reader.test/progress", headers: [:], targetWidthPx: 100,
                                onProgress: { firstProgress.append($0) }) { _ in }
        let second = loader.load(url: "https://reader.test/progress", headers: [:], targetWidthPx: 100,
                                 onProgress: { secondProgress.append($0) }) { _ in }
        transport.progress(0, 0.5)
        loader.cancel(token: first)
        readerDrainMain()
        XCTAssertTrue(firstProgress.isEmpty)
        XCTAssertEqual(secondProgress, [0.5])
        loader.cancel(token: second)
        XCTAssertEqual(transport.transfer(0).task.cancelCount, 1)
    }

    @MainActor
    func testLateTransportFileAfterAllSubscribersCancelIsDiscardedWithoutDecode() throws {
        let files = try ReaderTestFiles()
        let file = try files.owned()
        let transport = ReaderFakePageTransport()
        let loader = ReaderImageLoader(transport: transport, fileDecoder: { _, _ in XCTFail("late file decoded"); return nil })
        let token = loader.load(url: "https://reader.test/late", headers: [:], targetWidthPx: 100) { _ in XCTFail("late completion") }
        loader.cancel(token: token)
        transport.complete(0, with: file)
        XCTAssertFalse(files.exists(file.url))
        XCTAssertEqual(loader.queuedFileByteCount, 0)
    }

    func testHeaderHashIsCanonicalDelimitedAndDoesNotExposeHeaders() {
        let first = readerImageRequestKey(url: "https://reader.test/page", headers: ["Cookie": "private-value", "Accept": "image/*"], width: 100)
        let reordered = readerImageRequestKey(url: "https://reader.test/page", headers: ["accept": "image/*", "cookie": "private-value"], width: 100)
        XCTAssertEqual(first, reordered)
        XCTAssertEqual(first.count, 64)
        XCTAssertFalse(first.contains("private-value"))
        XCTAssertNotEqual(first, readerImageRequestKey(url: "https://reader.test/page", headers: ["cookie": "changed", "accept": "image/*"], width: 100))
        XCTAssertNotEqual(first, readerImageRequestKey(url: "https://reader.test/page", headers: ["cookie": "private-value", "accept": "image/*"], width: 200))
        XCTAssertNotEqual(readerImageRequestKey(url: "x", headers: ["ab": "c"], width: 1),
                          readerImageRequestKey(url: "x", headers: ["a": "bc"], width: 1))
    }

    func testDefaultCacheConfigurationIsRetainedWithoutClaimingDownloadTaskCacheParity() {
        let configuration = ReaderImageLoader.defaultConfiguration()
        XCTAssertEqual(configuration.requestCachePolicy, .returnCacheDataElseLoad)
        XCTAssertEqual(configuration.urlCache?.memoryCapacity, 16 * 1024 * 1024)
        XCTAssertEqual(configuration.urlCache?.diskCapacity, 256 * 1024 * 1024)
        XCTAssertEqual(configuration.timeoutIntervalForRequest, 30)
        // Actual URLSessionDownloadTask HTTP cache behavior requires a real native wire scenario.
    }
}
