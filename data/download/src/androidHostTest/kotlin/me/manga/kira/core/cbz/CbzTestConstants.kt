package me.manga.kira.core.cbz

// Independent fixture values, never read from production settings or decoder results.
internal const val CBZ_SMALL_PAGE_WIDTH = 32
internal const val CBZ_SMALL_PAGE_HEIGHT = 33
internal const val CBZ_SECOND_PAGE_WIDTH = 33
internal const val CBZ_REGION_PAGE_WIDTH = 8
internal const val CBZ_FAULT_PAGE_WIDTH = 4
internal const val CBZ_LOW_REGION_HEIGHT = 6000
internal const val CBZ_SPLIT_PAGE_HEIGHT = 6005
internal const val CBZ_REGION_TAIL_HEIGHT = 5
internal const val CBZ_UNSPLIT_AVIF_HEIGHT = 5
internal const val CBZ_CANCEL_PAGE_HEIGHT = 12_001
internal const val CBZ_AVIF_PAGE_WIDTH = 3
internal const val CBZ_AVIF_HEADER_LENGTH: Byte = 12
internal const val CBZ_NOISY_PAGE_COUNT = 12
internal const val CBZ_NOISY_PAGE_SIDE = 512
internal const val CBZ_LOW_QUALITY = 70
internal const val CBZ_MID_QUALITY = 75
internal const val CBZ_HIGH_QUALITY = 85
internal const val CBZ_MILLIS_PER_SECOND = 1000

internal val CBZ_SMALL_PAGE_DIMENSIONS =
    listOf(
        CBZ_SMALL_PAGE_WIDTH to CBZ_SMALL_PAGE_HEIGHT,
        CBZ_SECOND_PAGE_WIDTH to CBZ_SMALL_PAGE_HEIGHT,
    )

// ZIP record sizes/offsets and masks used only by the bounded archive-corruption fixtures.
internal const val CBZ_ZIP_END_SIZE = 22L
internal const val CBZ_ZIP_END_OFFSET_DISTANCE = 6L
internal const val CBZ_ZIP_UINT_MASK = 0xffff_ffffL
internal const val CBZ_ZIP_CENTRAL_SIGNATURE = 0x504b0102
internal const val CBZ_ZIP_CRC_OFFSET = 16L
internal const val CBZ_ZIP_CORRUPT_MASK = 0xff
