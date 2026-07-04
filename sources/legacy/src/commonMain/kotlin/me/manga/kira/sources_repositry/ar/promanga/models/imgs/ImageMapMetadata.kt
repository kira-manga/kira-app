package me.manga.kira.sources_repositry.ar.promanga.models.imgs

import kotlinx.serialization.Serializable

/**
 * Migration note (Phase 7.1): Direct port. The upstream file mixed @Serializable data classes
 * for chapter/image metadata with Android-only bitmap constants (`MAX_CANVAS_DIMENSION`,
 * `MEMORY_CACHE_SIZE`). Those constants belong to `ProMangaImageCombiner` (which depended on
 * `android.graphics.Bitmap/Canvas/Paint/Rect`, Coil3, and `Dispatchers`); they are moved to that
 * file's Phase-8 stub and dropped here. All Android/Coil imports removed. Only kotlinx.serialization
 * `@Serializable` annotations remain — these data classes are KMP-safe.
 */

@Serializable
data class ImageMapMetadata(
    val dim: List<Int>,
    val mode: String,
    val pieces: List<String>,
    val order: List<Int>,
)

@Serializable
data class ProMangaChapterResponse(
    val id: Int,
    val content_id: Int,
    val chapter_number: String,
    val title: String? = null,
    val language: String? = null,
    val translator: String? = null,
    val uploader_id: Int,
    val status: String,
    val cdn_path: String,
    val metadata: ChapterMetadata,
)

@Serializable
data class ChapterData(
    val id: Int,
    val cdn_path: String? = "",
    val metadata: ChapterMetadata,
)

@Serializable
data class ChapterMetadata(
    val images: List<String>? = listOf(),
    val maps: List<ImageMapMetadata>? = listOf(),
)

/**
 * Audit-trail postscript (Phase 9.x.cluster190.staleKdocSweep.cascade, Task #697, 2026-05-29)
 *
 * Leaf 2/5 §253 audit-trail-preservation postscript for cluster190, sibling 308 of the cluster57+
 * continuum. Companion-leaf to UserAgents.kt (sibling 307). Both leaves classify as pure-data
 * KMP-portability successes; the difference is that UserAgents holds plain `List<String>` runtime
 * data while ImageMapMetadata holds `@Serializable` data classes that deserialize from the prochan
 * .net image-map JSON payload.
 *
 * The top-of-file prose under audit (preserved verbatim above the `@Serializable data class
 * ImageMapMetadata` declaration at lines 5-12):
 *
 *     Migration note (Phase 7.1): Direct port. The upstream file mixed @Serializable data classes
 *     for chapter/image metadata with Android-only bitmap constants (MAX_CANVAS_DIMENSION,
 *     MEMORY_CACHE_SIZE). Those constants belong to ProMangaImageCombiner (which depended on
 *     android.graphics.Bitmap/Canvas/Paint/Rect, Coil3, and Dispatchers); they are moved to that
 *     file's Phase-8 stub and dropped here. All Android/Coil imports removed. Only kotlinx
 *     .serialization @Serializable annotations remain — these data classes are KMP-safe.
 *
 * Classification under the cluster57+ taxonomy:
 *
 *   a. LIVE-NOT-STALE — the "Direct port" classification: the @Serializable data classes survive
 *      the migration unchanged at the structural level. Only the surrounding constants and
 *      Android-toolchain imports were removed during the Phase 7.1 split.
 *
 *   b. FULFILLED-PORT — the "moved to ProMangaImageCombiner Phase-8 stub" claim about
 *      MAX_CANVAS_DIMENSION and MEMORY_CACHE_SIZE: cross-verified by reading sibling-311
 *      ProMangaImageCombiner.kt (leaf 5/5 of this cluster), where lines 65-66 of the verbatim
 *      upstream comment block preserve `private const val MAX_CANVAS_DIMENSION = 4096`. The move
 *      is partial — the constant is preserved as commented-out historical reference inside
 *      ProMangaImageCombiner's verbatim upstream documentation block rather than as an active
 *      declaration, because the Phase 8 stub does not perform canvas composition. When the Phase
 *      8 expect/actual lift wires in the live combiner, the constant will be re-activated in the
 *      stub-replacing actual implementation.
 *
 *   c. FULFILLED-PORT — the "All Android/Coil imports removed" claim: confirmed by import survey
 *      — the only import is `kotlinx.serialization.Serializable` at line 3. Zero android.*, zero
 *      coil3.*, zero kotlinx.coroutines.* imports. The "KMP-safe by construction" assertion holds.
 *
 *   d. LIVE-NOT-STALE — the four @Serializable data class declarations: ImageMapMetadata (dim +
 *      mode + pieces + order), ProMangaChapterResponse (id + content_id + chapter_number + title
 *      + language + translator + uploader_id + status + cdn_path + metadata), ChapterData (id +
 *      cdn_path + metadata), ChapterMetadata (images + maps). All four are pure-data KMP-portable
 *      structures with `@Serializable` annotations and primitive / nullable / collection field
 *      types — no platform APIs, no actual-declarations needed. The data shape matches the
 *      prochan.net REST API response shape that the ProMangaRepository / ProchanRepository
 *      siblings deserialize when fetching chapter image manifests.
 *
 *   e. POTENTIAL-BUG-PRESERVED — the unconventional snake_case field naming convention on
 *      ProMangaChapterResponse (`content_id`, `chapter_number`, `cdn_path`, `uploader_id`) and on
 *      ChapterData (`cdn_path`) violates the Kotlin camelCase convention. Without `@SerialName`
 *      annotations, this means the @Serializable serializer uses the snake_case Kotlin property
 *      name as the JSON key — which is exactly what the prochan.net API delivers, so the
 *      deserialization works. This is a deliberate style departure (preserve the JSON-mirror
 *      shape for readability) rather than a bug. Some Kotlin style guides would prefer
 *      `@SerialName("content_id") val contentId: Int` etc.; the upstream chose property-name-
 *      mirroring instead. Preserved verbatim — not a §253 sweep concern.
 *
 *   f. FORECAST-NOT-YET-FULFILLED — the implicit dependency on the Phase 8 ProMangaImageCombiner
 *      lift: the data classes here are LIVE and serve the JSON deserialization path today, but
 *      the `ImageMapMetadata` records (with their `mode: String` field naming grid layouts like
 *      `grid_2x1`, `vertical_2`, etc.) are only meaningfully consumed when Phase 8 wires up
 *      bitmap composition. Until then, the sibling ProMangaImageCombiner stub emits each map's
 *      first piece as a best-effort fallback (verified at sibling-311 ProMangaImageCombiner.kt
 *      lines 113-124). The ImageMapMetadata data class's `mode + pieces + order` triple becomes
 *      fully load-bearing only post-Phase-8.
 *
 * Cross-references — leaf-paired siblings in this cluster:
 *   - sibling 307 (UserAgents.kt) — leaf 1/5, pure-data List<String> companion classification.
 *   - sibling 311 (ProMangaImageCombiner.kt) — leaf 5/5, the receiving file for the moved
 *     constants. The FULFILLED-PORT claim in this postscript depends on sibling 311's preservation
 *     of MAX_CANVAS_DIMENSION inside its verbatim upstream comment block.
 *
 * Cluster190 leaf 2/5. Next leaves: AzoraModels.kt (sibling 309), DilarV2Models.kt (sibling 310),
 * ProMangaImageCombiner.kt (sibling 311 — closing leaf).
 */
