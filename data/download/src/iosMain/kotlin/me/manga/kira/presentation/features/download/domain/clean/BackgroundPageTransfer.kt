package me.manga.kira.presentation.features.download.domain.clean

import me.manga.kira.platform.download.BackgroundTransport
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageMediaInspector

/**
 * OS page-transfer handoff and the checks used when recovering its published page files.
 * Transport ownership and inspection behavior are unchanged; the recovery byte ceiling keeps the
 * engine's original [PageBytePolicy] default rather than inheriting a codec's decoded-memory budget.
 */
class BackgroundPageTransfer(
    val transport: BackgroundTransport,
    val mediaInspector: PageMediaInspector,
    val pageBytePolicy: PageBytePolicy = PageBytePolicy(),
)
