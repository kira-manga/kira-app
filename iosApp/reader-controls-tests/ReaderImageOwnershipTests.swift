import UIKit
import XCTest

final class ReaderImageOwnershipTests: XCTestCase {
    @MainActor
    func testFourthQueuedFileIsDeletedImmediatelyWhenItsLastSubscriberCancels() throws {
        let files = try ReaderTestFiles()
        let transport = ReaderFakePageTransport()
        let decoder = ReaderBlockedDecoder(image: readerTestImage(), starts: 3)
        let queue = DispatchQueue(label: "reader-test.slots", attributes: .concurrent)
        let loader = ReaderImageLoader(transport: transport, decodeQueue: queue, fileDecoder: decoder.decode)
        var tokens: [String] = []
        var fourthCompletions = 0
        defer { tokens.forEach { loader.cancel(token: $0) }; decoder.release(3) }
        for index in 0..<3 {
            tokens.append(loader.load(url: "https://reader.test/\(index)", headers: [:], targetWidthPx: 100) { _ in })
            transport.complete(index, with: try files.owned())
        }
        wait(for: [decoder.started], timeout: 5)
        let fourth = try files.owned()
        let token = loader.load(url: "https://reader.test/3", headers: [:], targetWidthPx: 100) { _ in fourthCompletions += 1 }
        transport.complete(3, with: fourth)
        XCTAssertEqual(loader.queuedFileByteCount, fourth.byteCount)
        loader.cancel(token: token)
        XCTAssertFalse(files.exists(fourth.url), "no wait for an occupied decode semaphore is permitted")
        XCTAssertEqual(loader.queuedFileByteCount, 0)
        XCTAssertEqual(decoder.count, 3)
        decoder.release(3)
        readerDrain(queue)
        XCTAssertEqual(fourthCompletions, 0)
        XCTAssertEqual(decoder.count, 3, "an abandoned fourth file must never enter the decoder")
        tokens.append(loader.load(url: "https://reader.test/3", headers: [:], targetWidthPx: 100) { _ in })
        XCTAssertEqual(transport.taskCount, 5, "cancelled queued work must not warm the cache")
    }

    @MainActor
    func testCancelledActiveDecodeCannotFinishOrCacheOverItsSuccessor() throws {
        let files = try ReaderTestFiles()
        let oldFile = try files.owned()
        let newFile = try files.owned()
        let oldImage = readerTestImage(.red), newImage = readerTestImage(.blue)
        let oldStarted = expectation(description: "old active decoder")
        let releaseOld = DispatchSemaphore(value: 0)
        let queue = DispatchQueue(label: "reader-test.generation", attributes: .concurrent)
        let transport = ReaderFakePageTransport()
        let loader = ReaderImageLoader(transport: transport, decodeQueue: queue) { url, _ in
            if url == oldFile.url {
                oldStarted.fulfill()
                XCTAssertEqual(releaseOld.wait(timeout: .now() + 10), .success)
                return oldImage
            }
            return newImage
        }
        defer { releaseOld.signal() }
        var oldCallbacks = 0
        let old = loader.load(url: "https://reader.test/same", headers: [:], targetWidthPx: 100) { _ in oldCallbacks += 1 }
        transport.complete(0, with: oldFile)
        wait(for: [oldStarted], timeout: 5)
        loader.cancel(token: old)
        XCTAssertTrue(files.exists(oldFile.url), "an active decoder retains its file until native return")
        let newFinished = expectation(description: "successor finished")
        loader.load(url: "https://reader.test/same", headers: [:], targetWidthPx: 100) { image in
            XCTAssertTrue(image === newImage)
            newFinished.fulfill()
        }
        transport.complete(1, with: newFile)
        wait(for: [newFinished], timeout: 5)
        releaseOld.signal()
        readerDrain(queue)
        XCTAssertEqual(oldCallbacks, 0)
        XCTAssertFalse(files.exists(oldFile.url))
        XCTAssertFalse(files.exists(newFile.url))
        var synchronous = false
        let hit = loader.load(url: "https://reader.test/same", headers: [:], targetWidthPx: 100) { image in
            synchronous = true
            XCTAssertTrue(image === newImage, "late cancelled result must not replace the successor cache entry")
        }
        XCTAssertEqual(hit, "")
        XCTAssertTrue(synchronous)
        XCTAssertEqual(transport.taskCount, 2)
    }

    @MainActor
    func testLoaderDeinitDiscardsPendingFileWithoutWaitingForTheDecodeQueue() throws {
        let files = try ReaderTestFiles()
        let file = try files.owned()
        let transport = ReaderFakePageTransport()
        let queue = DispatchQueue(label: "reader-test.suspended")
        queue.suspend()
        var resumed = false
        defer { if !resumed { queue.resume() } }
        weak var weakLoader: ReaderImageLoader?
        autoreleasepool {
            var loader: ReaderImageLoader? = ReaderImageLoader(transport: transport, decodeQueue: queue) { _, _ in
                XCTFail("disowned queued file reached the decoder")
                return nil
            }
            weakLoader = loader
            loader?.load(url: "https://reader.test/pending", headers: [:], targetWidthPx: 100) { _ in XCTFail("abandoned callback") }
            transport.complete(0, with: file)
            XCTAssertTrue(files.exists(file.url))
            loader = nil
        }
        XCTAssertNil(weakLoader, "queued closures must not retain the loader across the gate")
        XCTAssertFalse(files.exists(file.url))
        XCTAssertEqual(transport.invalidateCount, 1)
        queue.resume()
        resumed = true
        readerDrain(queue)
    }

    @MainActor
    func testLocalFilesAreBorrowedAndSurviveCancellationAndLoaderDestruction() throws {
        let files = try ReaderTestFiles()
        let local = try files.write()
        let transport = ReaderFakePageTransport()
        let queue = DispatchQueue(label: "reader-test.local")
        queue.suspend()
        var resumed = false
        defer { if !resumed { queue.resume() } }
        var loader: ReaderImageLoader? = ReaderImageLoader(transport: transport, decodeQueue: queue) { _, _ in
            XCTFail("cancelled local decode should not start")
            return nil
        }
        let token = try XCTUnwrap(loader?.load(url: local.absoluteString, headers: [:], targetWidthPx: 100) { _ in XCTFail("cancelled callback") })
        loader?.cancel(token: token)
        loader = nil
        XCTAssertTrue(files.exists(local))
        XCTAssertEqual(transport.taskCount, 0)
        queue.resume()
        resumed = true
        readerDrain(queue)
        XCTAssertTrue(files.exists(local))
    }
}
