import UIKit
import ImageIO
import Darwin

/// One bounded, immutable file snapshot feeds PNG validation and ImageIO, never a second file open.
/// The existing 12k output-edge policy and the PNG work cap are not native allocation/RSS guarantees.
enum ReaderImageDecode {
    static let maxPixelDimension: CGFloat = 12_000

    static func downsample(fileURL: URL, targetWidthPx: CGFloat) -> UIImage? {
        guard targetWidthPx.isFinite, targetWidthPx > 0,
              let source = source(fileURL), let size = dimensions(source) else { return nil }
        let requestedEdge = size.width >= size.height ? targetWidthPx : (targetWidthPx * size.height / size.width).rounded(.up)
        let edge = min(requestedEdge, min(maxPixelDimension, max(size.width, size.height)))
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: edge,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary),
              isComplete(source), image.width > 0, image.height > 0,
              CGFloat(max(image.width, image.height)) <= edge.rounded(.up) else { return nil }
        return UIImage(cgImage: image)
    }

    /// Header-only layout hint, never an authority to publish a page as decoded/valid.
    static func localAspect(fileURL: URL) -> CGFloat? {
        guard let source = source(fileURL, requirePixels: false), let size = dimensions(source) else { return nil }
        return size.height / size.width
    }

    private static func source(_ file: URL, requirePixels: Bool = true) -> CGImageSource? {
        guard let data = snapshot(file) else { return nil }
        let isPng = ReaderPngValidation.hasSignature(data)
        let png = isPng ? (requirePixels ? ReaderPngValidation.validatedDimensions(data) :
            ReaderPngValidation.admittedDimensions(data)) : nil
        guard !isPng || png != nil,
              let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              isComplete(source) else { return nil }
        if isPng || CGImageSourceGetType(source) as String? == "public.png" {
            guard let png = png, CGImageSourceGetType(source) as String? == "public.png",
                  let size = dimensions(source), size.width == CGFloat(png.width), size.height == CGFloat(png.height)
            else { return nil }
        }
        return source
    }

    private static func snapshot(_ file: URL) -> Data? {
        guard file.isFileURL, let handle = try? FileHandle(forReadingFrom: file) else { return nil }
        defer { try? handle.close() }
        var attributes = stat()
        guard fstat(handle.fileDescriptor, &attributes) == 0,
              attributes.st_mode & mode_t(S_IFMT) == mode_t(S_IFREG),
              attributes.st_size > 0, attributes.st_size <= ReaderPageBytePolicy.defaultLimit else { return nil }
        let limit = Int(ReaderPageBytePolicy.defaultLimit)
        var retained = Data()
        retained.reserveCapacity(Int(attributes.st_size))
        do {
            while let part = try handle.read(upToCount: min(32 * 1_024, limit - retained.count + 1)), !part.isEmpty {
                guard part.count <= limit - retained.count else { return nil }
                retained.append(part)
            }
        } catch { return nil }
        // Owned bytes, not mapped mutable file pages. Growth after fstat cannot evade the read bound.
        return retained.isEmpty ? nil : retained
    }

    private static func isComplete(_ source: CGImageSource) -> Bool {
        CGImageSourceGetCount(source) > 0 && CGImageSourceGetStatus(source) == .statusComplete &&
            CGImageSourceGetStatusAtIndex(source, 0) == .statusComplete
    }

    private static func dimensions(_ source: CGImageSource) -> CGSize? {
        guard let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? NSNumber,
              let height = properties[kCGImagePropertyPixelHeight] as? NSNumber else { return nil }
        let size = CGSize(width: CGFloat(width.doubleValue), height: CGFloat(height.doubleValue))
        guard size.width.isFinite, size.height.isFinite, size.width > 0, size.height > 0 else { return nil }
        return size
    }
}
