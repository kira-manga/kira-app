package me.manga.kira.platform.cbz

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import libwebp.WebPEncodeRGBA
import libwebp.WebPFree
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Linkage smoke test for the vendored libwebp cinterop. A test target is a full **executable** link
 * (unlike the umbrella framework, which tolerates undefined symbols), so if `_WebPEncodeRGBA` were not
 * actually pulled in from `platform/libs/libwebp/<slice>/libwebp.a` this test would fail to link — proving
 * the `-staticLibrary`/`-libraryPath` propagation works end-to-end. It also confirms the encoder is
 * functional by encoding a tiny synthetic RGBA buffer and asserting a non-empty WebP result.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLibWebpLinkageTest {

    @Test
    fun webpEncodeRgba_links_and_encodes_synthetic_pixels() {
        val width = 8
        val height = 8
        val stride = width * 4
        memScoped {
            val rgba = allocArray<UByteVar>(stride * height)
            // Fill with a simple opaque gradient so the encoder has real content to compress.
            for (i in 0 until stride * height) {
                rgba[i] = if (i % 4 == 3) 255u else (i % 256).toUByte()
            }
            val out = alloc<CPointerVar<UByteVar>>()
            val size = WebPEncodeRGBA(rgba, width, height, stride, 75f, out.ptr)
            val ptr = out.value
            try {
                assertTrue(size.toLong() > 0L, "WebPEncodeRGBA returned size=$size")
                assertTrue(ptr != null, "WebPEncodeRGBA produced a null output pointer")
            } finally {
                if (ptr != null) WebPFree(ptr)
            }
        }
    }
}
