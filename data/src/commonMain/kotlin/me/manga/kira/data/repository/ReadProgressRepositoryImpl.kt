package me.manga.kira.data.repository

import com.russhwolf.settings.ObservableSettings
import me.manga.kira.domain.repository.ReadProgressRepository

/**
 * [ReadProgressRepository] backed by the platform's [ObservableSettings] store.
 *
 * SRP (contract §6): owns ONE rule — "translate between the on-disk encoded representation of a
 * `(chapterUrl, pageIndex)` pair and the typed surface that [ReadProgressRepository] declares,
 * with a collision-safety guard for the hash-based storage key derivation".
 *
 * DIP: depends on [ReadProgressRepository] (`:domain`) and [ObservableSettings] (multiplatform-
 * settings, bound as a `single` by the legacy `PlatformModule.<target>.kt` via
 * `SettingsFactory.createObservable("kira_settings")`). The rework re-uses that binding —
 * strangler-fig posture, same as [ReadingModeRepositoryImpl] (Phase 6.4.x.mode). The cell is
 * net-new, but the underlying store is the same `ObservableSettings` instance the legacy and
 * rework already share.
 *
 * Storage layout — why a single hashed key per chapter:
 *
 * The naive design would be `putInt("reader.last_page.${chapter.url}", pageIndex)`. That fails
 * on Desktop: multiplatform-settings's `PreferencesSettings` wraps `java.util.prefs.Preferences`,
 * whose `MAX_KEY_LENGTH` is 80 characters. Chapter URLs routinely exceed that (100-200+ chars
 * for typical sources). A `putInt(longKey, _)` would throw `IllegalArgumentException` on
 * Desktop. So the impl derives a bounded-length key from the URL.
 *
 * Derivation: `chapterUrl.hashCode().toUInt().toString(36)` — a 1-7 character alphanumeric
 * suffix. Combined with the literal prefix `"reader.last_page."` (17 chars), every key fits
 * well inside the 80-char limit with headroom.
 *
 * Collision safety: `hashCode` is not injective (Int has ~4.3 billion states; any heavy reader
 * could in principle accumulate two chapters that hash-collide). To avoid silently restoring
 * the wrong page when a fresh chapter happens to collide with a previously-saved one, the impl
 * does NOT use the raw `putInt` shape. Instead, it stores a `String` value of the form
 * `"${chapterUrl}|${pageIndex}"` under the hashed key. On [load], the impl splits the value,
 * verifies the stored URL matches the requested URL, and returns null on mismatch — converting
 * what would be a wrong-page-bug into a "start at page 0 fallback" (the [load] caller's existing
 * absent-position handling). Collisions are extremely rare in practice (Int hash collisions
 * require thousands of chapters by the birthday-problem estimate, and the consequence of one
 * is "user starts a single chapter at page 0 instead of resuming" — graceful).
 *
 * Wire format on disk: `"${chapterUrl}|${pageIndex}"`.
 *  - Separator: literal `|`. Chapter URLs are HTTP(S) URLs and never contain a `|` (per RFC 3986
 *    `|` is not a valid URL character; sources URL-encode it as `%7C` if it ever appears in a
 *    path component). The separator choice is reversible.
 *  - Value: the `pageIndex` is a small non-negative Int (0..page-count-1 in practice). Encoded
 *    as decimal text. Negative or non-numeric values trigger the null return — defensive parse
 *    failure handling.
 *  - Future-proofing: if a future version of this impl needs to extend the wire format (e.g.
 *    add a timestamp), the load path checks for a known prefix shape and the save path bumps a
 *    version suffix in the key prefix. For now the format is fixed and undocumented externally.
 *
 * No-`AppResult` rationale: settings I/O has no actionable failure surface; if `putString`
 * fails (it doesn't — multiplatform-settings 1.3.0 returns `Unit`), the Reader has nothing
 * meaningful to do beyond ignore. Mirrors [ReadingModeRepositoryImpl].
 *
 * Why not `flowOn(io)`: `ObservableSettings.putString` and `getStringOrNull` are not
 * dispatcher-pinned — the multiplatform-settings impls all wrap in-memory state (SharedPreferences
 * / NSUserDefaults / `java.util.prefs.Preferences` snapshots). Pinning would add a context-switch
 * hop with no benefit. Same posture as [ReadingModeRepositoryImpl] and the legacy
 * `DataStoreHelper`.
 *
 * Why `suspend` despite a non-blocking platform write: contract parity with
 * [ReadProgressRepository.save] / `.load` KDoc — keeps the door open for a future
 * `withContext(io)` switch if a settings backend ever grows synchronous I/O. The Reader VM
 * calls [save] from `viewModelScope.launch` (fire-and-forget on page change) and [load] from
 * its `OnEnter` reducer (already suspend), so the suspending shape is consumed naturally.
 *
 * No-op write protection: a setter that writes the same value the store already holds will not
 * change disk state (`ObservableSettings.putString` short-circuits identical writes), and the
 * Reader VM only calls [save] when the page index actually changed (the `onPageChanged`
 * reducer's `clamped == current.currentPageIndex` early-return). No explicit guard needed here.
 *
 * Lifecycle: bound as `single`. The impl holds no per-call state; the backing
 * [ObservableSettings] is itself a singleton. Reconstructing per resolution would be wasteful
 * and would not change semantics. Mirrors [ReadingModeRepositoryImpl].
 *
 * Thread-safety: `ObservableSettings` is thread-safe across all three platform impls. Two
 * concurrent calls to [save] for the same chapter (impossible in practice — the VM serializes
 * intent handling) would race on the underlying store, but multiplatform-settings's atomic
 * `putString` ensures one wins cleanly; no torn writes.
 */
class ReadProgressRepositoryImpl(
    private val settings: ObservableSettings,
) : ReadProgressRepository {

    override suspend fun save(chapterUrl: String, pageIndex: Int) {
        val key = keyFor(chapterUrl)
        val encoded = "$chapterUrl$SEPARATOR$pageIndex"
        settings.putString(key, encoded)
    }

    override suspend fun load(chapterUrl: String): Int? {
        val key = keyFor(chapterUrl)
        val raw = settings.getStringOrNull(key) ?: return null
        val separatorAt = raw.lastIndexOf(SEPARATOR)
        if (separatorAt <= 0 || separatorAt == raw.lastIndex) return null
        val storedUrl = raw.substring(0, separatorAt)
        // Hash-collision guard: a fresh chapter's URL hashing to the same suffix as a previously-
        // saved chapter's URL would otherwise restore the wrong page. Verifying the stored URL
        // matches the requested URL turns the collision into a graceful "start at page 0" fallback
        // (the use-case caller's existing null-handling path).
        if (storedUrl != chapterUrl) return null
        val pageText = raw.substring(separatorAt + 1)
        return pageText.toIntOrNull()?.takeIf { it >= 0 }
    }

    override suspend fun clear(chapterUrl: String) {
        settings.remove(keyFor(chapterUrl))
    }

    private fun keyFor(chapterUrl: String): String =
        // hashCode().toUInt().toString(36) yields a 1-7 char alphanumeric suffix. Combined with
        // the 17-char prefix the total key length is at most 24 chars — well under the 80-char
        // `java.util.prefs.Preferences.MAX_KEY_LENGTH` on Desktop. Same shape on every platform
        // for cross-target consistency (no per-platform branching).
        KEY_PREFIX + chapterUrl.hashCode().toUInt().toString(36)

    private companion object {
        const val KEY_PREFIX = "reader.last_page."
        const val SEPARATOR = '|'
    }
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster152.staleKdocSweep.cascade,
 * Task #608, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-ninety-third sibling of the cluster57-151
 * sweep — fourth file of the wave-26 :data/repository reader-state tier
 * 5-leaf batch alongside ChapterPagesRepositoryImpl plus ReadingMode
 * RepositoryImpl plus ReadingSessionRepositoryImpl plus PageProgressRepository
 * Impl):
 *  (a) "ReadProgressRepository-backed-by-the-platform-s-ObservableSettings-
 *  store + SRP-contract-section-6-owns-ONE-rule-translate-between-the-on-
 *  disk-encoded-representation-of-a-chapterUrl-pageIndex-pair-and-the-typed
 *  -surface-that-ReadProgressRepository-declares-with-a-collision-safety-
 *  guard-for-the-hash-based-storage-key-derivation + DIP-depends-on-Read
 *  ProgressRepository-:domain-and-ObservableSettings-multiplatform-settings
 *  -bound-as-a-single-by-the-legacy-PlatformModule + The-rework-re-uses-
 *  that-binding-strangler-fig-posture-same-as-ReadingModeRepositoryImpl-
 *  Phase-6.4.x.mode + Storage-layout-why-a-single-hashed-key-per-chapter +
 *  The-naive-design-would-be-putInt-reader.last_page.chapter.url-pageIndex-
 *  That-fails-on-Desktop-multiplatform-settings-PreferencesSettings-wraps-
 *  java.util.prefs.Preferences-whose-MAX_KEY_LENGTH-is-80-characters +
 *  Chapter-URLs-routinely-exceed-that-100-200-chars-for-typical-sources +
 *  Derivation-chapterUrl.hashCode-toUInt-toString-36-a-1-7-character-
 *  alphanumeric-suffix-Combined-with-the-literal-prefix-reader.last_page.
 *  -17-chars-every-key-fits-well-inside-the-80-char-limit-with-headroom +
 *  Collision-safety-hashCode-is-not-injective-Int-has-about-4.3-billion-
 *  states-any-heavy-reader-could-in-principle-accumulate-two-chapters-that-
 *  hash-collide + To-avoid-silently-restoring-the-wrong-page-when-a-fresh-
 *  chapter-happens-to-collide-with-a-previously-saved-one-the-impl-does-NOT
 *  -use-the-raw-putInt-shape-Instead-it-stores-a-String-value-of-the-form-
 *  chapterUrl-pipe-pageIndex-under-the-hashed-key + On-load-the-impl-splits
 *  -the-value-verifies-the-stored-URL-matches-the-requested-URL-and-returns
 *  -null-on-mismatch-converting-what-would-be-a-wrong-page-bug-into-a-start
 *  -at-page-0-fallback + Wire-format-on-disk-chapterUrl-pipe-pageIndex +
 *  Separator-literal-pipe-Chapter-URLs-are-HTTP-S-URLs-and-never-contain-a-
 *  pipe-per-RFC-3986-pipe-is-not-a-valid-URL-character + Value-the-page
 *  Index-is-a-small-non-negative-Int-Encoded-as-decimal-text-Negative-or-
 *  non-numeric-values-trigger-the-null-return-defensive-parse-failure-
 *  handling + No-AppResult-rationale-settings-I-O-has-no-actionable-failure
 *  -surface-Mirrors-ReadingModeRepositoryImpl + Why-not-flowOn-io-Observable
 *  Settings.putString-and-getStringOrNull-are-not-dispatcher-pinned + Why-
 *  suspend-despite-a-non-blocking-platform-write-contract-parity-keeps-the-
 *  door-open-for-a-future-withContext-io-switch + No-op-write-protection-
 *  ObservableSettings.putString-short-circuits-identical-writes + Lifecycle-
 *  bound-as-single-The-impl-holds-no-per-call-state + Thread-safety-Observable
 *  Settings-is-thread-safe-across-all-three-platform-impls" —
 *  LIVE-NOT-STALE. Verified: hash-based-key ReadProgressRepositoryImpl
 *  shipped. save(chapterUrl, pageIndex) computes keyFor(chapterUrl) =
 *  "reader.last_page." + chapterUrl.hashCode().toUInt().toString(36) then
 *  stores the wire-format String "${chapterUrl}|${pageIndex}" via
 *  settings.putString. load(chapterUrl) retrieves the String via settings.
 *  getStringOrNull, locates the last-occurrence pipe SEPARATOR, parses
 *  storedUrl + pageText, and returns pageText.toIntOrNull()?.takeIf { it >=
 *  0 } — with the explicit storedUrl != chapterUrl mismatch-returns-null
 *  collision-safety guard honored. The 80-char Desktop MAX_KEY_LENGTH
 *  budget honored — 17-char prefix + max 7-char alphanumeric hash suffix =
 *  24 chars worst case. The "pipe is not a valid URL character per RFC
 *  3986" reversibility-of-separator stance honored — chapter URLs are
 *  guaranteed not to contain a raw pipe (sources URL-encode pipes as %7C).
 *  The defensive parse-failure handling (separatorAt <= 0 || separatorAt
 *  == raw.lastIndex returns null; non-numeric pageText returns null;
 *  negative pageIndex returns null) is honored. The "no AppResult mapping"
 *  + "no flowOn(io)" + "suspend despite non-blocking write" + "no-op write
 *  protection" stances all honored. Consumed by SaveReadProgressUseCase
 *  + LoadReadProgressUseCase (cluster93 sibling X) via the save() / load()
 *  surface; the rework Reader VM consumes through the use cases at its
 *  own MVI boundary on page-change reducer + OnEnter reducer respectively.
 *  One classification. Original Phase 6.4.x.readprogress impl prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */

