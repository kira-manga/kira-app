import UIKit
import ImageIO

/// File-backed ImageIO only. This preserves the reader's existing 12k edge/quality policy; ImageIO
/// does not expose a hard internal allocator limit, so the cap is not a native RSS guarantee.
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
        guard let source = source(fileURL), let size = dimensions(source) else { return nil }
        return size.height / size.width
    }

    private static func source(_ file: URL) -> CGImageSource? {
        guard let source = CGImageSourceCreateWithURL(file as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
              isComplete(source) else { return nil }
        return source
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
