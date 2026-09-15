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
        let invalid: [(String, Data)] = [
            ("html", Data("<html>challenge</html>".utf8)),
            ("truncated-iend", Data(ReaderTestFiles.png.dropLast(8))),
            ("crc-correct-zero-idat", corruptPngPixels()),
        ]
        for (name, bytes) in invalid {
            let file = try files.write(bytes)
            XCTAssertNil(ReaderImageDecode.downsample(fileURL: file, targetWidthPx: 100), name)
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

    @MainActor
    func testPngAcceptsSplitStreamPacked16BitAndAdam7Layouts() throws {
        let files = try ReaderTestFiles()
        for fixture in ReaderPngTestFixtures.valid {
            XCTAssertEqual(ReaderPngValidation.validatedDimensions(fixture.data),
                           ReaderPngValidation.Dimensions(width: fixture.width, height: fixture.height), fixture.name)
            let file = try files.write(fixture.data)
            // A source wider than 12k remains admissible; 12k is the OUTPUT ceiling, not a source cap.
            let target: CGFloat = fixture.name == "above12k-source-edge" ? 20_000 : 100
            let image = ReaderImageDecode.downsample(fileURL: file, targetWidthPx: target)
            XCTAssertNotNil(image, fixture.name)
            if let image = image {
                XCTAssertLessThanOrEqual(max(image.size.width, image.size.height), ReaderImageDecode.maxPixelDimension,
                                         fixture.name)
            }
        }
    }

    @MainActor
    func testPngRejectsCrcFramingAndHeaderOrderFailures() throws {
        let files = try ReaderTestFiles()
        for fixture in ReaderPngTestFixtures.framingFailures {
            XCTAssertNil(ReaderPngValidation.validatedDimensions(fixture.data), fixture.name)
            let file = try files.write(fixture.data)
            XCTAssertNil(ReaderImageDecode.downsample(fileURL: file, targetWidthPx: 100), fixture.name)
        }
    }

    @MainActor
    func testPngRejectsInvalidZlibStreamsAndFilteredRows() throws {
        let files = try ReaderTestFiles()
        for fixture in ReaderPngTestFixtures.streamFailures {
            XCTAssertNil(ReaderPngValidation.validatedDimensions(fixture.data), fixture.name)
            let file = try files.write(fixture.data)
            XCTAssertNil(ReaderImageDecode.downsample(fileURL: file, targetWidthPx: 100), fixture.name)
        }
    }

    @MainActor
    func testPngChecksFilteredWorkBeforeInflationWithoutAddingASourcePixelCap() throws {
        // Arithmetic only: never inflate or allocate the one-GiB boundary described by these headers.
        XCTAssertEqual(ReaderPngValidation.filteredByteCount(width: 1, height: 536_870_912, bitDepth: 1,
                                                            colorType: 0, interlace: 0), 1_073_741_824)
        XCTAssertNil(ReaderPngValidation.filteredByteCount(width: 1, height: 536_870_913, bitDepth: 1,
                                                          colorType: 0, interlace: 0))
        XCTAssertEqual(ReaderPngValidation.filteredByteCount(width: 4_097, height: 4_097, bitDepth: 1,
                                                            colorType: 0, interlace: 0), 2_105_858,
                       "Swift must not acquire Native's 16M source-pixel admission")
        XCTAssertNil(ReaderPngValidation.filteredByteCount(width: UInt32(Int32.max), height: UInt32(Int32.max),
                                                          bitDepth: 16, colorType: 6, interlace: 0))
        XCTAssertNil(ReaderPngValidation.filteredByteCount(width: .max, height: .max, bitDepth: 16,
                                                          colorType: 6, interlace: 0))
        XCTAssertNil(ReaderPngValidation.filteredByteCount(width: 0, height: 1, bitDepth: 1,
                                                          colorType: 0, interlace: 0))
        XCTAssertNil(ReaderPngValidation.filteredByteCount(width: 1, height: 0, bitDepth: 1,
                                                          colorType: 0, interlace: 0))
        let files = try ReaderTestFiles()
        for fixture in ReaderPngTestFixtures.workFailures {
            XCTAssertNil(ReaderPngValidation.admittedDimensions(fixture.data), fixture.name)
            XCTAssertNil(ReaderPngValidation.validatedDimensions(fixture.data), fixture.name)
            let file = try files.write(fixture.data)
            XCTAssertNil(ReaderImageDecode.downsample(fileURL: file, targetWidthPx: 100), fixture.name)
            XCTAssertNil(ReaderImageDecode.localAspect(fileURL: file), fixture.name)
        }
    }

    // Fixture mutation only: recalculate IDAT CRC so compressed-stream validation, not a PNG checksum error,
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
