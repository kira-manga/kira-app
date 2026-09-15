package me.manga.kira.platform.media

import okio.IOException
import okio.Path

/** Validates supported page bytes without a full-resolution validation bitmap or server metadata. */
interface PageMediaInspector {
    /** The caller must retain and publish these same immutable encoded bytes after validation. */
    fun inspect(encoded: ByteArray): PageInspection

    /** Inspects an immutable file snapshot; callers publish by atomic move, never by rewriting it. */
    fun inspect(path: Path): PageInspection
}

/** Actual format reported by the native image parser and checked against the encoded framing. */
enum class PageImageFormat(
    val extension: String,
) {
    JPEG("jpg"),
    PNG("png"),
    WEBP("webp"),
    GIF("gif"),
    BMP("bmp"),
    AVIF("avif"),
}

/** Positive native image dimensions, not dimensions guessed from a URL or response header. */
data class PageImageMetadata(
    val format: PageImageFormat,
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0)
    }

    val pixelCount: Long get() = width.toLong() * height
}

/** Validation failures never establish that preserving the original bytes would produce a page. */
sealed interface PageInspection {
    data class Valid(
        val metadata: PageImageMetadata,
    ) : PageInspection

    data class Invalid(
        val reason: PageInvalidReason,
    ) : PageInspection

    data class ReadFailure(
        val cause: Throwable,
    ) : PageInspection

    data class Rejected(
        val reason: PageInspectionRejection,
        val maximum: Long? = null,
        val observed: Long? = null,
    ) : PageInspection
}

/** Missing/unsupported framing or an incomplete/failed native validation decode. */
enum class PageInvalidReason { EMPTY, UNSUPPORTED_FORMAT, INCOMPLETE_OR_CORRUPT }

/** Policy/capability rejection is NOT_VALID and not retryable; it is not codec-budget preservation. */
enum class PageInspectionRejection {
    ENCODED_BYTES,
    SOURCE_PIXELS,
    SOURCE_AXIS,
    DECODER_UNAVAILABLE,

    /** The bounded native API cannot distinguish malformed input from its allocation/scale limit. */
    BOUNDED_DECODER_REJECTED,
}

/** Converts a failed inspection to the download/writer's normal failure path without losing its type. */
fun PageInspection.requireValid(): PageImageMetadata =
    when (this) {
        is PageInspection.Valid -> metadata
        else -> throw PageMediaException(this)
    }

/** Contains only a stable failure code in its message, never a page URL, request header, or file path. */
class PageMediaException(
    val inspection: PageInspection,
) : IOException(inspection.failureCode())

private fun PageInspection.failureCode(): String =
    when (this) {
        is PageInspection.Valid -> error("A valid page is not a media failure")
        is PageInspection.Invalid -> "Invalid downloaded image: $reason"
        is PageInspection.ReadFailure -> "Downloaded image could not be read"
        is PageInspection.Rejected -> "$PAGE_POLICY_REJECTED_PREFIX$reason"
    }

const val PAGE_POLICY_REJECTED_PREFIX: String = "__page_policy_rejected__:"

/** Stable persisted transfer code; retry rules never depend on localized decoder text. */
fun isPagePolicyRejection(message: String?): Boolean = message?.startsWith(PAGE_POLICY_REJECTED_PREFIX) == true
