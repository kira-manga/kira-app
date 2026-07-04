package me.manga.kira.domain.model.language

/**
 * A user-selectable interface language.
 *
 * Phase 7.x.language rework. The canonical model for an entry in the language picker. (Pre-retire,
 * a `:shared` equivalent `LanguageOption` lived at
 * `me.manga.kira.presentation.features.language.data.LanguageOption` with the same two fields.
 * That class was retired in Phase 9.x.language.retire / §183 once the legacy `LanguageSelectionScreen`
 * + legacy `LanguageViewModel` were no longer user-reachable. This `Language` type is now the
 * single in-tree model for the picker.) The rework re-homes the shape into `:domain` so consumers
 * ([me.manga.kira.domain.repository.LanguageRepository] +
 *  [me.manga.kira.domain.usecase.language.GetSupportedLanguagesUseCase]) don't reach into
 * `:shared` for a model type.
 *
 * - [code] is the IETF language tag (`en`, `ar`, `de`, `es`, `fr`, `in`, `it`, `ja`, `pt`, `ru`,
 *   `tr` — matching the upstream `Res.array.supported_languages` + legacy
 *   `LANGUAGE_DISPLAY_NAMES` map). Persisted verbatim into the legacy
 *   `DataStoreHelper.languageFlow` via [me.manga.kira.domain.usecase.language.SetLanguageUseCase].
 * - [displayName] is the **native endonym** — the language's name in its own script
 *   (`English` / `العربية` / `日本語`). Decoupled from `java.util.Locale.getDisplayLanguage(...)`
 *   because `java.util.Locale` is JVM-only and unavailable in commonMain. The values are sourced
 *   from the same hand-curated table the legacy `LanguageScreenRoute.kt` adapter uses; the rework
 *   keeps the table inside [me.manga.kira.data.repository.LanguageRepositoryImpl] (single
 *   source of truth for the rework path).
 *
 * Contract §6 SRP: one rule — "the two fields the picker needs to render and persist one
 * language". No methods, no derivation, no Compose dependencies.
 *
 * Contract §17: no `!!`, no `Any`, no `lateinit` — plain data class.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster137.staleKdocSweep.cascade,
 * Task #593, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-twenty-eighth sibling of the cluster57-136
 * sweep — fourth file of the wave-24 fifth-cluster 5-subpackage joint
 * batch alongside AppMetadata plus ComplaintSummary plus HistoryEntry
 * plus SettingsSnapshot):
 *  (a) "Phase-7.x.language-rework + canonical-model-for-an-entry-in-
 *  the-language-picker + Pre-retire-a-:shared-equivalent-LanguageOption-
 *  lived-at-presentation-features-language-data-LanguageOption-with-
 *  the-same-two-fields + That-class-was-retired-in-Phase-9.x.language.
 *  retire-§183-once-the-legacy-LanguageSelectionScreen-plus-legacy-
 *  LanguageViewModel-were-no-longer-user-reachable + This-Language-type-
 *  is-now-the-single-in-tree-model-for-the-picker + rework-re-homes-
 *  the-shape-into-:domain-so-consumers-LanguageRepository-plus-Get-
 *  SupportedLanguagesUseCase-do-not-reach-into-:shared-for-a-model-type"
 *  — LIVE-NOT-STALE plus FULFILLED-PREDICTION. Verified via recursive
 *  grep: Language is consumed by GetSupportedLanguagesUseCase plus
 *  LanguageRepositoryImpl plus LanguageState plus LanguageViewModel
 *  plus LanguageScreen plus LanguageReworkScreenRoute. The legacy
 *  LanguageOption.kt was deleted in Phase 9.x.language.retire (Task
 *  #350 COMPLETE) — zero remaining :shared references to LanguageOption
 *  anywhere in the tree; the in-tree Language data class in :domain is
 *  now the SINGLE model for the picker as predicted.
 *  (b) "code-is-the-IETF-language-tag-en-ar-de-es-fr-in-it-ja-pt-ru-tr
 *  + matching-the-upstream-Res.array.supported_languages-plus-legacy-
 *  LANGUAGE_DISPLAY_NAMES-map + Persisted-verbatim-into-the-legacy-
 *  DataStoreHelper.languageFlow-via-SetLanguageUseCase + displayName-
 *  is-the-native-endonym-the-language-name-in-its-own-script-English-
 *  العربية-日本語 + Decoupled-from-java.util.Locale.getDisplayLanguage-
 *  because-java.util.Locale-is-JVM-only-and-unavailable-in-commonMain
 *  + values-are-sourced-from-the-same-hand-curated-table-the-legacy-
 *  LanguageScreenRoute-adapter-uses + rework-keeps-the-table-inside-
 *  LanguageRepositoryImpl-single-source-of-truth-for-the-rework-path +
 *  Contract-§6-SRP-one-rule-the-two-fields-the-picker-needs-to-render-
 *  and-persist-one-language + No-methods-no-derivation-no-Compose-
 *  dependencies + Contract-§17-no-!!-no-Any-no-lateinit-plain-data-
 *  class" — LIVE-NOT-STALE plus FULFILLED-PREDICTION. Verified:
 *  LanguageRepositoryImpl.kt holds the 11-language hand-curated table
 *  with native endonym displayName values (English, العربية, Deutsch,
 *  Español, Français, Bahasa Indonesia, Italiano, 日本語, Português,
 *  Русский, Türkçe) exactly as predicted. SetLanguageUseCase writes
 *  the code field to DataStoreHelper.languageFlow via the legacy data
 *  store. Zero java.util.Locale import anywhere in :domain or :data.
 *  Two classifications STAND on their own merits. Original Phase
 *  7.x.language-era prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
data class Language(
    val code: String,
    val displayName: String,
)
