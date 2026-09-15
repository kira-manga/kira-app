package me.manga.kira.presentation.features.download.domain.clean

import me.manga.kira.platform.media.PageByteLimitExceeded
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageInspection
import me.manga.kira.platform.media.PageMediaInspector
import okio.FileSystem
import okio.IOException
import okio.Path

/** A manifest-bound set, not a filtered list whose smaller size can masquerade as completeness. */
internal data class ValidatedPageRoster(
    val expected: List<Int>,
    val validated: Map<Int, Path>,
) {
    val indices: Set<Int> get() = validated.keys
    val completePaths: List<String>?
        get() =
            if (expected.isNotEmpty() && expected.all { it in validated }) {
                expected.map { validated.getValue(it).toString() }
            } else {
                null
            }
}

internal fun DownloadManifest.hasValidPageRoster(): Boolean =
    pages.isNotEmpty() &&
        pages.all { it.attempts >= 0 } &&
        pages.map { it.index }.toSet() == pages.indices.toSet()

/** Unknown indices, partials, duplicate valid candidates and invalid/over-policy leftovers cannot count. */
internal fun inspectPageRoster(
    system: FileSystem,
    directory: Path,
    manifest: DownloadManifest,
    inspector: PageMediaInspector,
    policy: PageBytePolicy = PageBytePolicy(),
): ValidatedPageRoster {
    require(manifest.hasValidPageRoster()) { "Invalid chapter page roster" }
    val expected = manifest.pages.map { it.index }.sorted()
    if (manifest.pages.any { it.policyRejected } || !system.exists(directory)) {
        return ValidatedPageRoster(expected, emptyMap())
    }
    val candidates =
        system
            .list(directory)
            .mapNotNull { path ->
                PageFileNames.pageIndexFromName(path.name)?.takeIf { it in expected }?.let { it to path }
            }.groupBy({ it.first }, { it.second })
    val valid = mutableMapOf<Int, Path>()
    for (index in expected) {
        val matches =
            candidates[index].orEmpty().filter { path ->
                val metadata = system.metadataOrNull(path)
                if (metadata?.isRegularFile != true) return@filter false
                try {
                    policy.checkFileSize(metadata.size)
                } catch (_: PageByteLimitExceeded) {
                    return@filter false
                } catch (_: IOException) {
                    return@filter false
                }
                inspector.inspect(path) is PageInspection.Valid
            }
        // A leftover alternate must not choose stale bytes by arbitrary lexical ordering. Redownload
        // an ambiguous index; successful publication cleans alternates only after replacing safely.
        if (matches.size == 1) valid[index] = matches.single()
    }
    return ValidatedPageRoster(expected, valid)
}
