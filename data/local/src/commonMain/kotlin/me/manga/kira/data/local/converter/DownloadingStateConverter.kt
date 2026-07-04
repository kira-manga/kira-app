package me.manga.kira.data.local.converter

import androidx.room.TypeConverter
import me.manga.kira.presentation.features.download.data.DownloadingState

class DownloadingStateConverter {
    @TypeConverter
    fun fromDownloadState(state: DownloadingState): String = state.name

    @TypeConverter
    fun toDownloadState(name: String): DownloadingState = DownloadingState.valueOf(name)
}

/*
 * §253 audit-trail postscript — cluster280 §253 sweep (2026-05-29)
 * ------------------------------------------------------------------
 * Classification: LIVE / LEGACY.
 *
 * LIVE evidence: this Room TypeConverter pair is registered on the database
 * type-converter set at MangaDatabase.kt:48 ("DownloadingStateConverter::class,"
 * inside the @TypeConverters block, lines 47-53). Room codegen invokes
 * fromDownloadState / toDownloadState whenever it persists or reads the
 * ChapterDownloadEntity.state column (ChapterDownloadEntity.kt:21,
 * "val state: DownloadingState"). The enum it bridges, DownloadingState, is
 * itself LIVE (declared at presentation/features/download/data/DownloadingState.kt:3,
 * already §253-swept in cluster207). No call site references this converter
 * class by name — that is expected: Room's generated database implementation
 * is the sole reacher, the wiring being annotation-driven rather than explicit.
 *
 * LEGACY status: this is pre-rework :shared/commonMain Room infrastructure, NOT
 * a Phase-5.x platform facade. The cluster183 cosibling-converter sweep
 * (Converters.kt postscript, Task #664) deliberately OMITTED this file as a
 * "bare prose-less" zero-classification skip target; cluster280 now closes that
 * gap. The Room subsystem (MangaDatabase + its 8 DAOs + 5 converters) remains
 * the live cell-of-truth that the rework :data strangler-fig impls consume
 * through DAO interfaces, so the converter stays load-bearing.
 *
 * Delta-axes:
 *  1. Platform API: androidx.room.TypeConverter (KMP Room artifact). No
 *     platform-specific surface — the same commonMain converter compiles on
 *     Android, Desktop (JVM), and iOS via the Room KMP plugin.
 *  2. Threading/dispatcher: none owned here. Conversion runs synchronously on
 *     whatever thread Room's generated DAO method executes on; the suspend /
 *     IO-dispatcher boundary lives in the DAO callers, not the converter.
 *  3. Error handling: toDownloadState delegates to DownloadingState.valueOf,
 *     which throws IllegalArgumentException for an unknown persisted name. This
 *     is the legacy contract — there is no defensive fallback, so a renamed enum
 *     constant would surface as a read-time crash, not a silent default. The
 *     enum's 5 constants (QUEUED, RUNNING, SUCCESS, FAILED, COMPRESSING) are
 *     append-only by convention to preserve on-disk-name stability.
 *  4. DI binding mechanism: NOT a Koin single. Binding is by @TypeConverters
 *     annotation on MangaDatabase; the database singleton is what Koin provides
 *     (single<MangaDatabase> per platform), and the converter rides inside it.
 *  5. Persisted form: enum-name String (state.name round-tripping through
 *     valueOf). On-disk column is TEXT; the format is identical to the legacy
 *     pre-port source — confirmed against database-migration-report.md:73
 *     ("enum name | enum name | identical (String)").
 *
 * Nested-comment hazard check: zero legitimate KDoc/comment openers exist in
 * the original file body (the two @TypeConverter functions carry no doc
 * comments). This appended block is balanced — exactly one opener and one
 * closer, with no interior comment delimiters in the prose.
 */
