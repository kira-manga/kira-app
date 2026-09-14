import UIKit
import XCTest

final class ReaderImageDecodeTests: XCTestCase {
    @MainActor
    func testImageIoReadsMislabeledPngFromAFileAtTheRequestedBound() throws {
        let files = try ReaderTestFiles()
        let file = try files.write(name: "image.jpg")
        let full = try XCTUnwrap(ReaderImageDecode.downsample(fileURL: file, targetWidthPx: 100))
        XCTAssertEqual(full.size.width, 8)
        XCTAssertEqual(full.size.height, 9)
        XCTAssertEqual(try XCTUnwrap(ReaderImageDecode.localAspect(fileURL: file)), 9.0 / 8.0, accuracy: 0.001)
        let small = try XCTUnwrap(ReaderImageDecode.downsample(fileURL: file, targetWidthPx: 2))
        XCTAssertLessThanOrEqual(max(small.size.width, small.size.height), 3)
        XCTAssertEqual(ReaderImageDecode.maxPixelDimension, 12_000, "existing quality ceiling is not reduced")
        XCTAssertTrue(files.exists(file), "decode borrows its input")
    }

    @MainActor
    func testImageIoRejectsHtmlTruncationAndCrcCorrectInvalidPixelData() throws {
        let files = try ReaderTestFiles()
        let invalid = [Data("<html>challenge</html>".utf8), Data(ReaderTestFiles.png.dropLast(8)), corruptPngPixels()]
        for bytes in invalid {
            let file = try files.write(bytes)
            XCTAssertNil(ReaderImageDecode.downsample(fileURL: file, targetWidthPx: 100))
        }
        let good = try files.write()
        XCTAssertNil(ReaderImageDecode.downsample(fileURL: good, targetWidthPx: .nan))
        XCTAssertNil(ReaderImageDecode.downsample(fileURL: good, targetWidthPx: 0))
    }

    @MainActor
    func testLoaderChecksBorrowedLocalFileBudgetBeforeDecoderEntry() throws {
        let files = try ReaderTestFiles()
        let file = try files.write()
        let transport = ReaderFakePageTransport()
        let completed = expectation(description: "local byte policy refusal")
        let loader = ReaderImageLoader(transport: transport, bytePolicy: ReaderPageBytePolicy(limit: 1)) { _, _ in
            XCTFail("above-policy local file reached ImageIO")
            return nil
        }
        loader.load(url: file.absoluteString, headers: [:], targetWidthPx: 100) { result in
            XCTAssertNil(result)
            completed.fulfill()
        }
        wait(for: [completed], timeout: 5)
        XCTAssertTrue(files.exists(file))
        XCTAssertEqual(transport.taskCount, 0)
    }

    // Fixture mutation only: recalculate IDAT CRC so native pixel decoding, not a PNG checksum error,
    // has to reject this complete container. This is not a production validation implementation.
    private func corruptPngPixels() -> Data {
        var bytes = [UInt8](ReaderTestFiles.png)
        var offset = 8
        while offset + 12 <= bytes.count {
            let count = bytes[offset..<offset + 4].reduce(0) { ($0 << 8) | Int($1) }
            if String(bytes: bytes[offset + 4..<offset + 8], encoding: .ascii) == "IDAT" {
                for index in offset + 8..<offset + 8 + count { bytes[index] = 0 }
                var crc = UInt32.max
                for byte in bytes[offset + 4..<offset + 8 + count] {
                    crc ^= UInt32(byte)
                    for _ in 0..<8 { crc = (crc >> 1) ^ ((crc & 1) == 1 ? 0xedb88320 : 0) }
                }
                crc = ~crc
                for index in 0..<4 { bytes[offset + 8 + count + index] = UInt8(truncatingIfNeeded: crc >> (24 - index * 8)) }
                return Data(bytes)
            }
            offset += count + 12
        }
        XCTFail("fixture must contain IDAT")
        return Data(bytes)
    }
}
