import Foundation
import XCTest

/// Real suspended URLSession tasks + delegate/file-policy seam. No test resumes a network task;
/// these do not establish real wire caching, progress cadence, disk ceilings or low-memory RSS.
final class ReaderPageTransportTests: XCTestCase {
    func testExactUnknownAndUnderstatedLengthsAreCheckedAgainstTheActualCompletedFile() throws {
        for declared in [nil, 1, 4] as [Int?] {
            let fixture = try NativeReaderTransferFixture(limit: 4)
            let transfer = try fixture.register()
            XCTAssertEqual(transfer.task.state, .suspended)
            let original = try fixture.files.write(Data([1, 2, 3, 4]))
            fixture.transport.receiveFile(taskID: transfer.task.taskIdentifier, response: fixture.response(length: declared), location: original)
            fixture.finish(transfer)
            let file = try transfer.outcome.success()
            XCTAssertEqual(file.byteCount, 4)
            XCTAssertFalse(fixture.files.exists(original))
            XCTAssertTrue(fixture.files.exists(file.url))
            let permissions = try fixture.files.manager.attributesOfItem(atPath: file.url.path)[.posixPermissions] as? NSNumber
            XCTAssertEqual(permissions?.intValue, 0o600)
            file.discard()
            file.discard()
            XCTAssertFalse(fixture.files.exists(file.url))
            XCTAssertEqual(transfer.outcome.count, 1)
        }
    }

    func testOverLimitActualOrDeclaredLengthCannotBeAdopted() throws {
        for (count, declared) in [(5, nil), (5, 1), (4, 5)] as [(Int, Int?)] {
            let fixture = try NativeReaderTransferFixture(limit: 4)
            let transfer = try fixture.register()
            let original = try fixture.files.write(Data(repeating: 1, count: count))
            fixture.transport.receiveFile(taskID: transfer.task.taskIdentifier, response: fixture.response(length: declared), location: original)
            fixture.finish(transfer)
            XCTAssertEqual(transfer.outcome.failure() as? ReaderPageTransferError, .byteLimit)
            XCTAssertTrue(fixture.files.exists(original), "URLSession still owns a rejected location")
            XCTAssertFalse(fixture.files.exists(fixture.ownedDirectory))
        }
    }

    func testNonHttpFailedStatusEmptyAndNonRegularFilesFailClosed() throws {
        let cases: [(Int?, Bool, ReaderPageTransferError)] = [
            (nil, false, .missingHTTPResponse), (403, false, .httpStatus(403)),
            (200, false, .emptyOrUnreadable), (200, true, .emptyOrUnreadable),
        ]
        for (status, isDirectory, expected) in cases {
            let fixture = try NativeReaderTransferFixture(limit: 4)
            let transfer = try fixture.register()
            let input: URL
            if isDirectory {
                input = fixture.files.root.appendingPathComponent("not-a-file", isDirectory: true)
                try fixture.files.manager.createDirectory(at: input, withIntermediateDirectories: false)
            } else {
                input = try fixture.files.write(Data())
            }
            let response = status.map { fixture.response(status: $0) }
            fixture.transport.receiveFile(taskID: transfer.task.taskIdentifier, response: response, location: input)
            fixture.finish(transfer)
            XCTAssertEqual(transfer.outcome.failure() as? ReaderPageTransferError, expected)
            XCTAssertFalse(fixture.files.exists(fixture.ownedDirectory))
        }
    }

    func testProgressPolicyReasonSurvivesCancellationAndDoesNotPoisonAnotherTask() throws {
        let fixture = try NativeReaderTransferFixture(limit: 4)
        let rejected = try fixture.register()
        let accepted = try fixture.register()
        fixture.transport.urlSession(.shared, downloadTask: rejected.task, didWriteData: 5,
                                     totalBytesWritten: 5, totalBytesExpectedToWrite: -1)
        fixture.finish(rejected, error: URLError(.cancelled))
        XCTAssertEqual(rejected.outcome.failure() as? ReaderPageTransferError, .byteLimit)
        let original = try fixture.files.write(Data([1, 2, 3, 4]))
        fixture.transport.receiveFile(taskID: accepted.task.taskIdentifier, response: fixture.response(), location: original)
        fixture.finish(accepted)
        let file = try accepted.outcome.success()
        XCTAssertEqual(file.byteCount, 4)
        fixture.finish(rejected, error: URLError(.cancelled))
        XCTAssertEqual(rejected.outcome.count, 1, "native cancellation must not erase or duplicate the policy result")
        XCTAssertEqual(accepted.outcome.count, 1)
        file.discard()
    }

    func testProgressDeclaredLengthAlsoCancelsBeforeACompletedFileIsDelivered() throws {
        let fixture = try NativeReaderTransferFixture(limit: 4)
        let transfer = try fixture.register()
        fixture.transport.urlSession(.shared, downloadTask: transfer.task, didWriteData: 1,
                                     totalBytesWritten: 1, totalBytesExpectedToWrite: 5)
        fixture.finish(transfer, error: URLError(.cancelled))
        XCTAssertEqual(transfer.outcome.failure() as? ReaderPageTransferError, .byteLimit)
        XCTAssertEqual(transfer.outcome.count, 1)
    }

    func testInvalidationBetweenFileAndCompletionDiscardsExactlyItsOwnedFile() throws {
        let fixture = try NativeReaderTransferFixture(limit: 4)
        let transfer = try fixture.register()
        let original = try fixture.files.write(Data([1, 2, 3, 4]))
        let unrelated = try fixture.files.write(Data([9]), name: "previous-good-page")
        fixture.transport.receiveFile(taskID: transfer.task.taskIdentifier, response: fixture.response(), location: original)
        let owned = try fixture.files.manager.contentsOfDirectory(at: fixture.ownedDirectory, includingPropertiesForKeys: nil)
        XCTAssertEqual(owned.count, 1)
        XCTAssertEqual(transfer.outcome.count, 0)
        fixture.transport.invalidate()
        fixture.finish(transfer)
        XCTAssertEqual(transfer.outcome.failure() as? ReaderPageTransferError, .cancelled)
        XCTAssertEqual(transfer.outcome.count, 1)
        XCTAssertTrue(owned.allSatisfy { !fixture.files.exists($0) })
        XCTAssertTrue(fixture.files.exists(unrelated))
    }

    func testMoveSetupFailureNeverDeletesTheOsInputOrAnExistingDestination() throws {
        let fixture = try NativeReaderTransferFixture(limit: 4)
        let transfer = try fixture.register()
        // A regular file where the transport requires its private directory forces setup failure.
        let prior = try fixture.files.write(Data([9]), name: "owned")
        let original = try fixture.files.write(Data([1, 2, 3, 4]))
        fixture.transport.receiveFile(taskID: transfer.task.taskIdentifier, response: fixture.response(), location: original)
        fixture.finish(transfer)
        XCTAssertNotNil(transfer.outcome.failure())
        XCTAssertTrue(fixture.files.exists(original))
        XCTAssertEqual(try Data(contentsOf: prior), Data([9]))
    }
}

private final class NativeReaderTransferFixture {
    let files: ReaderTestFiles
    let ownedDirectory: URL
    let transport: ReaderPageTransport

    init(limit: Int64) throws {
        let files = try ReaderTestFiles()
        let directory = files.root.appendingPathComponent("owned", isDirectory: true)
        self.files = files
        ownedDirectory = directory
        transport = ReaderPageTransport(configuration: .ephemeral, policy: ReaderPageBytePolicy(limit: limit), directory: directory)
    }
    deinit { transport.invalidate() }

    func register() throws -> NativeReaderTransfer {
        let outcome = NativeReaderTransferOutcome()
        let request = URLRequest(url: URL(string: "https://reader.invalid/test-only-suspended-task")!)
        let task = try XCTUnwrap(transport.task(for: request, progress: { _ in }, completion: outcome.append) as? URLSessionDownloadTask)
        return NativeReaderTransfer(task: task, outcome: outcome)
    }

    func response(status: Int = 200, length: Int? = nil) -> HTTPURLResponse {
        HTTPURLResponse(url: URL(string: "https://reader.invalid/page.jpg")!, statusCode: status,
                        httpVersion: "HTTP/1.1", headerFields: length.map { ["Content-Length": "\($0)"] })!
    }

    func finish(_ transfer: NativeReaderTransfer, error: Error? = nil) {
        transport.urlSession(.shared, task: transfer.task, didCompleteWithError: error)
    }
}

private struct NativeReaderTransfer {
    let task: URLSessionDownloadTask
    let outcome: NativeReaderTransferOutcome
}

private final class NativeReaderTransferOutcome {
    private let condition = NSCondition()
    private var values: [Result<ReaderPageFile, Error>] = []
    var count: Int { condition.lock(); defer { condition.unlock() }; return values.count }
    func append(_ value: Result<ReaderPageFile, Error>) {
        condition.lock()
        values.append(value)
        condition.broadcast()
        condition.unlock()
    }
    func success() throws -> ReaderPageFile { try XCTUnwrap(first()).get() }
    func failure() -> Error? {
        if case .failure(let error)? = first() { return error }
        XCTFail("expected failed transfer")
        return nil
    }
    private func first() -> Result<ReaderPageFile, Error>? {
        condition.lock()
        defer { condition.unlock() }
        let deadline = Date(timeIntervalSinceNow: 5)
        while values.isEmpty {
            if !condition.wait(until: deadline) { return nil }
        }
        return values.first
    }
}
