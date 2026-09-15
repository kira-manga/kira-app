package me.manga.kira.platform.backup

/**
 * Version 1 mobile backup admission policy. These are resource ceilings, not ZIP authenticity or
 * native allocator guarantees. Production uses these defaults; constructor overrides may only
 * tighten them (small boundary fixtures need not allocate production-sized archives).
 */
data class BackupImportPolicy(
    val archive: BackupZipLimits = BackupZipLimits(),
    val json: BackupJsonLimits = BackupJsonLimits(),
    val records: BackupRecordLimits = BackupRecordLimits(),
    val downloads: BackupDownloadLimits = BackupDownloadLimits(),
) {
    companion object {
        const val VERSION = 1
    }
}

/** Limits checked before a ZIP reader may allocate its entry/implicit-directory index. */
data class BackupZipLimits(
    val maxArchiveBytes: Long = BackupImportCeilings.INPUT_BYTES,
    val maxEntries: Int = BackupImportCeilings.OUTER_ENTRIES,
    val maxDirectoryBytes: Long = BackupImportCeilings.DIRECTORY_BYTES,
) {
    init {
        require(maxArchiveBytes in 1..BackupImportCeilings.INPUT_BYTES)
        require(maxEntries in 1..BackupImportCeilings.OUTER_ENTRIES)
        require(maxDirectoryBytes in 1..BackupImportCeilings.DIRECTORY_BYTES)
    }
}

/** String ceilings count decoded UTF-8 bytes, including strings inside ignored additive fields. */
data class BackupJsonLimits(
    val maxBytes: Long = BackupImportCeilings.JSON_BYTES,
    val maxDepth: Int = BackupImportCeilings.JSON_DEPTH,
    val maxStringBytes: Int = BackupImportCeilings.STRING_BYTES,
    val maxDescriptionBytes: Int = BackupImportCeilings.DESCRIPTION_BYTES,
) {
    init {
        require(maxBytes in 1..BackupImportCeilings.JSON_BYTES)
        require(maxDepth in 1..BackupImportCeilings.JSON_DEPTH)
        require(maxStringBytes in 1..BackupImportCeilings.STRING_BYTES)
        require(maxDescriptionBytes in 1..BackupImportCeilings.DESCRIPTION_BYTES)
    }

    // A lexical token consumes at least one manifest byte. This explicit scanner-work budget also
    // applies to unknown fields, without inventing a lower compatibility ceiling than the byte cap.
    val maxTokens: Long get() = maxBytes
}

/** Combined chapter/history admission is checked before DTO list allocation, then checked again. */
data class BackupRecordLimits(
    val maxMangas: Int = BackupImportCeilings.MANGAS,
    val maxChaptersAndHistory: Int = BackupImportCeilings.RECORDS,
) {
    init {
        require(maxMangas in 1..BackupImportCeilings.MANGAS)
        require(maxChaptersAndHistory in 1..BackupImportCeilings.RECORDS)
    }
}

/** Outer CBZ storage and inner expansion are independent budgets; both count actual bytes. */
data class BackupDownloadLimits(
    val maxCbzBytes: Long = BackupImportCeilings.CBZ_BYTES,
    val maxStagedBytes: Long = BackupImportCeilings.ALL_CBZ_BYTES,
    val maxInnerEntries: Int = BackupImportCeilings.INNER_ENTRIES,
    val maxExpandedBytesPerCbz: Long = BackupImportCeilings.CBZ_BYTES,
    val maxExpandedBytes: Long = BackupImportCeilings.ALL_CBZ_BYTES,
) {
    init {
        require(maxCbzBytes in 1..BackupImportCeilings.CBZ_BYTES)
        require(maxStagedBytes in 1..BackupImportCeilings.ALL_CBZ_BYTES)
        require(maxInnerEntries in 1..BackupImportCeilings.INNER_ENTRIES)
        require(maxExpandedBytesPerCbz in 1..BackupImportCeilings.CBZ_BYTES)
        require(maxExpandedBytes in 1..BackupImportCeilings.ALL_CBZ_BYTES)
    }
}

private object BackupImportCeilings {
    const val INPUT_BYTES = 0xFFFFFFFFL - 64L * 1024 * 1024 // Existing export ZIP32 ceiling.
    const val OUTER_ENTRIES = 65_000
    const val DIRECTORY_BYTES = 16L * 1024 * 1024
    const val JSON_BYTES = 8L * 1024 * 1024
    const val JSON_DEPTH = 32
    const val STRING_BYTES = 16 * 1024
    const val DESCRIPTION_BYTES = 64 * 1024
    const val MANGAS = 10_000
    const val RECORDS = 100_000
    const val CBZ_BYTES = 512L * 1024 * 1024
    const val ALL_CBZ_BYTES = 4L * 1024 * 1024 * 1024
    const val INNER_ENTRIES = 2_000
}
