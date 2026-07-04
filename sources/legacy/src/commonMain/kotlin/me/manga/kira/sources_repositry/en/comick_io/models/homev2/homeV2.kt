package me.manga.kira.sources_repositry.en.comick_io.models.homev2

/**
 * KMP port: `kotlin.collections.ArrayList` is final on Kotlin/Native, so the original
 * `class homeV2 : ArrayList<homeV2Item>()` cannot be subclassed in commonMain.
 *
 * Callers only ever use this as a typed list of [homeV2Item] (see ComickRepository),
 * and the wrapper carried no extra members, so a typealias preserves all call sites
 * exactly. Note: kotlinx.serialization works on `List<homeV2Item>` directly.
 */
typealias homeV2 = ArrayList<homeV2Item>
