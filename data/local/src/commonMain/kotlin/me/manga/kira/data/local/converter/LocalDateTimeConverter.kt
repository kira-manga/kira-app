package me.manga.kira.data.local.converter

import androidx.room.TypeConverter
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

// Migration note (Phase 6): java.time.LocalDateTime + ZoneOffset.UTC -> kotlinx.datetime.LocalDateTime
// + TimeZone.UTC. Wire format preserved (Long epoch-millis). Existing DB rows deserialize unchanged.
class LocalDateTimeConverter {
    /** Persist as epoch-milli (UTC). */
    @TypeConverter
    fun fromLocalDateTime(dateTime: LocalDateTime?): Long? =
        dateTime?.toInstant(TimeZone.UTC)?.toEpochMilliseconds()

    /** Read epoch-milli back into a LocalDateTime (UTC). */
    @TypeConverter
    fun toLocalDateTime(millis: Long?): LocalDateTime? =
        millis?.let {
            kotlin.time.Instant.fromEpochMilliseconds(it)
                .toLocalDateTime(TimeZone.UTC)
        }
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster183.staleKdocSweep.cascade,
 * Task #666, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-seventy-eighth sibling of the cluster57-182
 * sweep — leaf 3/4 of the wave-53 commonMain :data/local/converter Room-
 * TypeConverter 4-leaf batch; LocalDateTimeConverter class 3/4).
 *
 *  (a) Inline migration-note comment "Migration-note-Phase-6 + java-time-
 *  LocalDateTime-plus-ZoneOffset-UTC-to-kotlinx-datetime-LocalDateTime-
 *  plus-TimeZone-UTC + Wire-format-preserved-Long-epoch-millis + Existing-
 *  DB-rows-deserialize-unchanged" — LIVE-NOT-STALE for the UTC-anchored
 *  Long-backed converter AND FULFILLED-PORT for the Phase-6
 *  java.time.LocalDateTime + ZoneOffset.UTC → kotlinx.datetime.LocalDateTime
 *  + TimeZone.UTC migration (verified: fromLocalDateTime returns
 *  dateTime.toInstant(TimeZone.UTC).toEpochMilliseconds(); toLocalDateTime
 *  returns kotlin.time.Instant.fromEpochMilliseconds(millis)
 *  .toLocalDateTime(TimeZone.UTC). The UTC anchoring IS load-bearing
 *  because legacy java.time.LocalDateTime → epoch-milli requires an
 *  explicit zone-offset call site, and upstream chose ZoneOffset.UTC to
 *  keep the wire format zone-neutral — the kotlinx.datetime port preserves
 *  this choice via TimeZone.UTC. The wire format Long-epoch-milli IS bit-
 *  identical to legacy: legacy did dateTime.toInstant(ZoneOffset.UTC)
 *  .toEpochMilli(); the port does dateTime.toInstant(TimeZone.UTC)
 *  .toEpochMilliseconds() — both call paths produce the same Instant
 *  semantic and the same Long output. The kotlin.time.Instant intermediate
 *  is mandated because kotlinx.datetime.LocalDateTime.toInstant requires a
 *  TimeZone argument, and the fromEpochMilliseconds factory lives on the
 *  kotlin.time.Instant companion not the kotlinx.datetime.Instant alias.
 *  The "existing DB rows deserialize unchanged" invariant IS load-bearing
 *  for the HistoryItemD.lastReadDate cell-of-truth which uses LocalDateTime
 *  via this exact converter pair in the legacy schema). No follow-up
 *  tracker.
 *
 * Verified: 2-method LocalDateTimeConverter class (fromLocalDateTime +
 * toLocalDateTime — single Long-backed pair, UTC-anchored). Sibling:
 * Converters.kt + LocalDateConverter.kt (cluster183 prior siblings);
 * StringListConverter.kt (cluster183 closing sibling). LEAF 3/4 of the
 * cluster183 commonMain :data/local/converter Room-TypeConverter 4-leaf
 * batch. One classification (with FULFILLED-PORT for Phase-6
 * java.time.LocalDateTime + ZoneOffset.UTC → kotlinx.datetime.LocalDateTime
 * + TimeZone.UTC migration). Original Phase 6-era LocalDateTimeConverter
 * migration prose preserved verbatim per the audit-trail-preservation
 * convention.
 */

