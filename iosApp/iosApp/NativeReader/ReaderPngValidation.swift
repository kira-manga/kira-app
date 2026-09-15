import Foundation
import zlib

/// A PNG integrity gate, not an image decoder. ImageIO must separately accept the same snapshot.
/// The 1 GiB limit bounds filtered validation work, not native allocations/RSS or source pixels.
enum ReaderPngValidation {
    static let maxFilteredBytes: UInt64 = 1_024 * 1_024 * 1_024
    private static let scratchBytes = 32 * 1_024
    private static let signature: [UInt8] = [137, 80, 78, 71, 13, 10, 26, 10]

    struct Dimensions: Equatable {
        let width: UInt32
        let height: UInt32
    }

    static func hasSignature(_ data: Data) -> Bool { data.starts(with: signature) }

    /// Layout hints may check framing/admission without claiming compressed pixels are valid.
    static func admittedDimensions(_ data: Data) -> Dimensions? {
        data.withUnsafeBytes { raw in frame(raw.bindMemory(to: UInt8.self))?.dimensions }
    }

    static func validatedDimensions(_ data: Data) -> Dimensions? {
        data.withUnsafeBytes { raw in
            let bytes = raw.bindMemory(to: UInt8.self)
            guard let parsed = frame(bytes), inflatePixels(bytes, parsed) else { return nil }
            return parsed.dimensions
        }
    }

    /// The same checked admission used before inflate; allows small arithmetic-boundary tests.
    static func filteredByteCount(width: UInt32, height: UInt32, bitDepth: UInt8,
                                  colorType: UInt8, interlace: UInt8) -> UInt64? {
        layout(width: width, height: height, bitDepth: bitDepth, colorType: colorType,
               interlace: interlace)?.byteCount
    }

    private struct Pass {
        let rowBytes: UInt64
        let rows: UInt64
    }

    private struct Layout {
        let passes: [Pass] // At most seven; never one allocation per row or IDAT.
        let byteCount: UInt64
    }

    private struct Frame {
        let dimensions: Dimensions
        let layout: Layout
        let firstIDAT: Int
        let afterIDAT: Int
        let compressedBytes: UInt64
    }

    private static func layout(width: UInt32, height: UInt32, bitDepth: UInt8,
                               colorType: UInt8, interlace: UInt8) -> Layout? {
        guard width > 0, height > 0, width <= UInt32(Int32.max), height <= UInt32(Int32.max),
              interlace <= 1 else { return nil }
        let samples: UInt64
        switch colorType {
        case 0: guard [1, 2, 4, 8, 16].contains(Int(bitDepth)) else { return nil }; samples = 1
        case 2: guard bitDepth == 8 || bitDepth == 16 else { return nil }; samples = 3
        case 3: guard [1, 2, 4, 8].contains(Int(bitDepth)) else { return nil }; samples = 1
        case 4: guard bitDepth == 8 || bitDepth == 16 else { return nil }; samples = 2
        case 6: guard bitDepth == 8 || bitDepth == 16 else { return nil }; samples = 4
        default: return nil
        }
        let steps: [(UInt64, UInt64, UInt64, UInt64)] = interlace == 0 ? [(0, 0, 1, 1)] : [
            (0, 0, 8, 8), (4, 0, 8, 8), (0, 4, 4, 8), (2, 0, 4, 4),
            (0, 2, 2, 4), (1, 0, 2, 2), (0, 1, 1, 2),
        ]
        var passes: [Pass] = []
        var total: UInt64 = 0
        for (x, y, dx, dy) in steps {
            guard UInt64(width) > x, UInt64(height) > y else { continue }
            let columns = (UInt64(width) - x + dx - 1) / dx
            let rows = (UInt64(height) - y + dy - 1) / dy
            let (bits, overflow) = columns.multipliedReportingOverflow(by: samples * UInt64(bitDepth))
            let (roundedBits, roundingOverflow) = bits.addingReportingOverflow(7)
            guard !overflow, !roundingOverflow else { return nil }
            let rowBytes = roundedBits / 8
            let (stride, strideOverflow) = rowBytes.addingReportingOverflow(1)
            guard !strideOverflow, stride > 0, stride <= maxFilteredBytes - total,
                  rows <= (maxFilteredBytes - total) / stride else { return nil }
            total += stride * rows // The division above proves both multiplication and sum fit.
            passes.append(Pass(rowBytes: rowBytes, rows: rows))
        }
        return passes.isEmpty ? nil : Layout(passes: passes, byteCount: total)
    }

    private static func word(_ bytes: UnsafeBufferPointer<UInt8>, _ offset: Int) -> UInt32 {
        (UInt32(bytes[offset]) << 24) | (UInt32(bytes[offset + 1]) << 16) |
            (UInt32(bytes[offset + 2]) << 8) | UInt32(bytes[offset + 3])
    }

    private static func frame(_ bytes: UnsafeBufferPointer<UInt8>) -> Frame? {
        guard bytes.count >= 33, bytes.count <= Int(ReaderPageBytePolicy.defaultLimit),
              signature.enumerated().allSatisfy({ bytes[$0.offset] == $0.element }),
              word(bytes, 8) == 13, word(bytes, 12) == 0x49484452,
              bytes[26] == 0, bytes[27] == 0 else { return nil }
        let width = word(bytes, 16), height = word(bytes, 20), depth = bytes[24], color = bytes[25]
        guard let rows = layout(width: width, height: height, bitDepth: depth,
                                colorType: color, interlace: bytes[28]) else { return nil }
        var offset = 8, paletteEntries = 0, firstIDAT = 0, afterIDAT = 0
        var sawData = false, closedData = false, sawTransparency = false
        var compressedBytes: UInt64 = 0
        while offset < bytes.count {
            guard bytes.count - offset >= 12 else { return nil }
            let size = word(bytes, offset)
            guard size <= UInt32(Int32.max), UInt64(size) <= UInt64(bytes.count - offset - 12) else { return nil }
            let count = Int(size), next = offset + count + 12, type = word(bytes, offset + 4)
            for index in offset + 4..<offset + 8 {
                let byte = bytes[index]
                guard (65...90).contains(byte) || (97...122).contains(byte) else { return nil }
            }
            guard bytes[offset + 6] & 32 == 0 else { return nil } // PNG's reserved type bit.
            let crc = crc32(0, bytes.baseAddress!.advanced(by: offset + 4), uInt(count + 4))
            guard UInt32(truncatingIfNeeded: crc) == word(bytes, offset + count + 8) else { return nil }
            if sawData && type != 0x49444154 { closedData = true }
            switch type {
            case 0x49484452: // IHDR
                guard offset == 8, count == 13 else { return nil }
            case 0x504c5445: // PLTE
                guard !sawData, !sawTransparency, paletteEntries == 0, color != 0, color != 4,
                      count > 0, count <= 768, count % 3 == 0 else { return nil }
                paletteEntries = count / 3
                guard color != 3 || paletteEntries <= (1 << Int(depth)) else { return nil }
            case 0x74524e53: // tRNS; other ancillary chunks remain ImageIO's semantic responsibility.
                guard !sawData, !sawTransparency else { return nil }
                switch color {
                case 0: guard count == 2 else { return nil }
                case 2: guard count == 6 else { return nil }
                case 3: guard paletteEntries > 0, count > 0, count <= paletteEntries else { return nil }
                default: return nil
                }
                sawTransparency = true
            case 0x49444154: // All consecutive IDAT payloads form ONE zlib stream, including empty chunks.
                guard !closedData, color != 3 || paletteEntries > 0 else { return nil }
                if !sawData { firstIDAT = offset }
                sawData = true
                afterIDAT = next
                compressedBytes += UInt64(count) // Disjoint ranges within the <=32 MiB snapshot.
            case 0x49454e44: // IEND
                guard count == 0, sawData, compressedBytes > 0, next == bytes.count else { return nil }
                return Frame(dimensions: Dimensions(width: width, height: height), layout: rows,
                             firstIDAT: firstIDAT, afterIDAT: afterIDAT, compressedBytes: compressedBytes)
            default:
                guard bytes[offset + 4] & 32 != 0 else { return nil } // Unknown critical chunk.
            }
            offset = next
        }
        return nil
    }

    private struct Scanlines {
        let layout: Layout
        var pass = 0
        var rowsLeft: UInt64
        var payloadLeft: UInt64 = 0
        var consumed: UInt64 = 0

        init(_ layout: Layout) { self.layout = layout; rowsLeft = layout.passes[0].rows }

        mutating func consume(_ bytes: UnsafeMutableRawBufferPointer, count: Int) -> Bool {
            guard UInt64(count) <= layout.byteCount - consumed else { return false }
            var offset = 0
            while offset < count {
                guard pass < layout.passes.count else { return false }
                if payloadLeft == 0 {
                    guard rowsLeft > 0, bytes[offset] <= 4 else { return false }
                    offset += 1
                    rowsLeft -= 1
                    payloadLeft = layout.passes[pass].rowBytes
                }
                let skip = min(payloadLeft, UInt64(count - offset))
                offset += Int(skip) // Skip whole payload spans, not individual decoded pixels.
                payloadLeft -= skip
                if payloadLeft == 0 && rowsLeft == 0 {
                    pass += 1
                    if pass < layout.passes.count { rowsLeft = layout.passes[pass].rows }
                }
            }
            consumed += UInt64(count)
            return true
        }

        var complete: Bool { consumed == layout.byteCount && pass == layout.passes.count && payloadLeft == 0 }
    }

    private static func inflatePixels(_ bytes: UnsafeBufferPointer<UInt8>, _ frame: Frame) -> Bool {
        var stream = z_stream()
        guard inflateInit_(&stream, zlibVersion(), Int32(MemoryLayout<z_stream>.size)) == Z_OK else { return false }
        defer { _ = inflateEnd(&stream) } // Every success/refusal after initialization frees native state.
        var rows = Scanlines(frame.layout)
        var nextChunk = frame.firstIDAT
        var scratch = [UInt8](repeating: 0, count: scratchBytes)
        return scratch.withUnsafeMutableBytes { output in
            while true {
                if stream.avail_in == 0 {
                    stream.next_in = nil
                    while nextChunk < frame.afterIDAT {
                        let count = Int(word(bytes, nextChunk)), payload = nextChunk + 8
                        nextChunk += count + 12
                        if count == 0 { continue }
                        stream.next_in = UnsafeMutablePointer(mutating: bytes.baseAddress!.advanced(by: payload))
                        stream.avail_in = uInt(count)
                        break
                    }
                }
                let before = stream.avail_in
                // One extra byte detects overflow without expanding a full unnecessary scratch chunk.
                let capacity = Int(min(UInt64(scratchBytes), frame.layout.byteCount - rows.consumed + 1))
                stream.next_out = output.bindMemory(to: UInt8.self).baseAddress!
                stream.avail_out = uInt(capacity)
                let result = inflate(&stream, Z_NO_FLUSH)
                guard stream.avail_in <= before, stream.avail_out <= uInt(capacity) else { return false }
                let produced = capacity - Int(stream.avail_out)
                guard rows.consume(output, count: produced) else { return false }
                if result == Z_STREAM_END {
                    // Reject a second stream or any compressed tail, including not-yet-fed IDAT bytes.
                    return rows.complete && stream.avail_in == 0 && UInt64(stream.total_in) == frame.compressedBytes
                }
                // Z_NEED_DICT/Z_DATA_ERROR/Z_BUF_ERROR and a nonprogressing call all refuse.
                // At a chunk boundary the next input is supplied before calling again; at final EOF
                // this also permits draining buffered output, but never treats truncation as success.
                guard result == Z_OK, stream.avail_in < before || produced > 0 else { return false }
            }
        }
    }
}
