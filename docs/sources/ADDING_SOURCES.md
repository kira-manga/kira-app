# Source configs — the complete developer guide

> Adding, editing, testing, disabling, and removing manga sources in Kira Manga.
> Written for a developer who has never touched the source system. Last verified against the code
> 2026-07-09 (source-lifecycle hardening batch). Companion deep-dive:
> [`../ENGINEERING_NOTES.md`](../ENGINEERING_NOTES.md) §1; working rules: [`../../CLAUDE.md`](../../CLAUDE.md).

---

## 1. Where source configs live (and where they don't)

**Sources are compiled into the app. There are no runtime JSON files.**

- The entire source catalog is ONE JSON document embedded as a Kotlin string constant:
  **`CONFIG_BACKED_SOURCES_JSON`** in
  `composeApp/src/commonMain/kotlin/me/manga/kira/sources/runtime/BundledSourcesConfig.kt`.
  It contains every source the app has ever shipped: the **generic** stanzas (`engine:"generic"`,
  executed by the config-driven engine) and the metadata-only **legacy** stanzas
  (`engine:"legacy"`, lifecycle/host metadata for the hand-written Kotlin scrapers).
- **`CONFIG_BACKED_APIS`** (same file) is the allow-list of apis served by the generic engine.
  A source runs generic **only** when its api is in this set AND its stanza says
  `engine:"generic"` — both are required (`DefaultSourceRegistry.isConfigBacked`).
- The app **never scans a directory, never reads a `.json` file, and never fetches configs from
  the network**. The remote-config channel exists in code (`RemoteSourceConfigManager`) but is
  double-locked off: `remote = null` in `di/SourcesGenericModule.kt`, and
  `DenyRemoteSignatureVerifier` rejects every signature. The Room `source_config_cache` table is
  the (currently never-written) cache tier for a future signed remote feed.
- Consequence: **every source change — add, edit, disable, remove — requires editing
  `BundledSourcesConfig.kt`, rebuilding, and shipping a release.** Users get the change when they
  update the app. There is no server-side switch.

**When is the document loaded?** Once per process. `RemoteSourceConfigManager` parses + validates
the bundled string eagerly in its constructor (first Koin injection, at app start); `App.kt`'s
startup block then calls `refresh()` (a near-no-op with remote disabled) and `syncSourceCatalog()`
(projects config truth into the Room `sources` table — enabled/baseUrl/siteState — and migrates
stored URLs on host moves). The parsed document is held in memory; it is never re-read during the
app's lifetime.

## 2. How to add a new source

Checklist (details below):

1. Choose a **stable api string** — this is the source's permanent identity (see §3). Not a
   display name; never reused; never renamed.
2. Add an `engine:"generic"` stanza to `CONFIG_BACKED_SOURCES_JSON`.
3. Add the api to `CONFIG_BACKED_APIS`.
4. Set `"enabled": true` if the source should be default-enabled when first seeded, and
   `"lifecycle": "active"` (the default). **Note the current reality: seeding honors
   `isEnabled = enabled && lifecycle == "active"`, and all shipped stanzas leave `enabled` unset
   (= `false`) — sources start disabled and users turn them on in the Sources screen
   (onboarding / Settings → Sources).** A source that never gets enabled never appears in Home.
5. Define `baseUrl` (must start with `http`) — the generic engine's API/site base. `imageBase` if
   covers/pages live on a different host.
6. Define the endpoints. **Required: `home` or `featured`, plus `search`, `details`, `pages`.**
   `chapters` is optional (separate chapter-list request). There is NO legacy fallback — a missing
   endpoint is a user-visible failure on every use of that verb (§4). The
   `ConfigBackedSourceCompletenessTest` build gate enforces all four.
7. Headers: static headers go in `"headers": { ... }`; `"usesCapturedHeaders": true` (the default)
   additionally merges headers captured by the in-app WebView Cloudflare solver (`cf_clearance`,
   cookies, User-Agent), stored per-api in DataStore. Set it `false` for clean JSON APIs (see
   Azora).
8. `previousHosts` / `previousImageHosts`: leave empty for a new source. They exist for **domain
   moves** (§7) — append the OLD host when `baseUrl` changes so stored library/chapter/history
   URLs are rewritten and user-configured mirrors are protected.
9. **Registry pin**: every stanza api must name a source known to the `MangaSource` enum
   (`sources/legacy/.../sources_repositry/data/MangaSource.kt`) — `LegacyStanzaCompletenessTest`
   fails the build otherwise ("ghost stanza" gate). All 12 existing generic sources are
   *conversions* of legacy sources, so the entry already existed. A **brand-new** source therefore
   also needs a `MangaSource` enum entry (api, language, baseUrl, priority). ⚠️ That file is a
   standing owner-WIP untouchable — coordinate with the owner before editing it.
10. Add a `<Source>PilotParityTest.kt` in `:composeApp` commonTest with real captured HTML/JSON
    fixtures asserting each verb's parsed output (see any existing `*PilotParityTest.kt`).
11. If the site needs an engine capability that doesn't exist yet (new transform, new pagination
    type, new date strategy): implement it in `:sources:engine`, whitelist the name in
    `DefaultStrategyRegistry`, and add a golden test in `:sources:engine`. Configs may only
    reference names compiled into that registry — the validator rejects everything else
    (fail-closed; do not weaken).

Derive selectors/paths from the legacy parser in `sources_repositry/` when converting (that tree
is the read-only spec).

## 3. Source api rules — the permanent identity

The `api` string is the **primary key of the entire system**. It is:

- the stanza key in the config document and the `CONFIG_BACKED_APIS` entry;
- the `sources` table primary key (`SourcesEntity.name`);
- the `saved_manga.api` / `history_items.api` / `chapter_downloads.api` column value on every row
  a user ever created from this source (plain strings — **no foreign key, no cascade**);
- the DataStore key for captured Cloudflare headers;
- the backup format's source reference (`BackupManga.api`).

Rules:

- **Once shipped, NEVER rename an api.** There is no migration mechanism. After a rename, every
  existing library row, history row, and captured-header record still carries the old string —
  they become permanent dead references: Details refresh fails with "Unknown source api=…",
  non-downloaded chapters can't be read online, and nothing ever reconciles them. (Downloaded
  chapters, read state, and covers survive — they're keyed by DB ids — but the source link is
  gone forever.)
- **Don't use a display name as the api.** `displayName` is the UI label and may change freely;
  the api may not. (Historical warts like `"Team X"`/`"Mangamello Plus"` — apis with spaces —
  are grandfathered; don't add new ones.)
- Never reuse a retired api for a different site: old library rows would suddenly point at the
  new site's URLs.

## 4. Required endpoint behavior — there is NO legacy fallback

Since the Phase 5/6 registry hardening, `DefaultSourceRegistry.get()` returns the **bare**
`GenericSourceClient` for a config-backed api. `FallbackSourceClient` is retained-but-unwired
rollback material. Consequences:

- A **missing endpoint** returns `AppError.Validation.Required("endpoint:<verb>")` to the user on
  every use of that verb. It does not fall back to the legacy scraper. (Older docs claiming
  "an omitted verb transparently falls back to legacy" are obsolete.)
- A **wrong-but-Success** verb (e.g. empty chapters because a selector went stale) is a visible
  regression — nothing catches it at runtime. This is why every verb needs a parity test with
  real fixtures, and why the owner rule is *a source is 100% generic or fully legacy — no
  per-verb split*.
- Downloads: `RegistryChapterPageProvider` **throws `GenericPagesFailedException`** on a generic
  pages failure (recorded as a failed download); it returns `null` only for non-config-backed
  apis (which then use the legacy download path).

What each endpoint must produce (verb → domain result, via the `fields` mappings):

| Endpoint | Feeds | Minimum output |
|---|---|---|
| `home` | Home feed (paginated) | list of items: `item.title`, `item.url`, `item.cover`; optional rating/genres/recentChapters |
| `featured` | Home "featured" carousel | list of items (cover + title is enough) |
| `search` | Search (this source; also the all-sources fan-out) | same item shape as `home`; use `{queryEncoded}` (raw `{query}` is rejected by the validator) |
| `details` | Manga details screen + library refresh | `detail.title`, `detail.cover`, `detail.description`, `detail.status`, `detail.genres`, and chapters (`detail.chapters` inline, or a separate `chapters` endpoint) with `chapter.name`/`chapter.number`/`chapter.url`/`chapter.date` |
| `pages` | Reader + downloads | ordered image URLs (`page.image`, optional `page.order`) |
| `chapters` (optional) | Two-request details | replaces the inline chapter list; if this second request fails, the whole `details` fails (deliberate — a Success with silently-empty chapters is the forbidden failure mode) |

## 5. Validation rules — and why one bad stanza is catastrophic

`DefaultSourceConfigValidator` (`:sources:engine`) checks, per document:

- `schemaVersion == 1` (anything else rejects the document immediately);
- per stanza: non-blank **unique** `api`; non-blank `language`; `baseUrl` starting with `http`;
  `engine` ∈ {`generic`, `legacy`, `kotlin:*`}; `siteState` ∈ {`WORKING`, `UNDER_MAINTENANCE`,
  `STOPPED`, `ADULT_18_PLUS`}; `lifecycle` ∈ {`active`, `disabled`, `removed`};
  `previousHosts`/`previousImageHosts`/`trustedHosts` entries are **bare hosts** (no scheme, no
  path, no port);
- generic stanzas additionally: known `pagination.type` (currently only `page-number`); at least a
  `home` or `featured` endpoint; endpoint `url` non-blank, method/format whitelisted (`json`,
  `html`, `script-json`), no raw `{query}`; every transform `fn`, `dateStrategy`, and
  `imageStrategy` name must be compiled into `DefaultStrategyRegistry` (the image set is
  intentionally EMPTY — any `imageStrategy` reference is rejected).

**Acceptance is all-or-nothing.** `RemoteSourceConfigManager` drops the ENTIRE document if any
stanza has any error. For the bundled document that means degrading to an empty document — **every
generic source disappears at once** (Home shows its error pane; the catalog sync's guard prevents
DB damage). Unknown JSON fields are silently ignored (`ignoreUnknownKeys`), so typo'd *field
names* don't fail validation — they just don't do anything. Two safety nets exist:

- **Build time**: `ConfigBackedSourceCompletenessTest` (+ `LegacyStanzaCompletenessTest`) in
  `:composeApp` commonTest — parses the bundled JSON, runs the shipping validator, verifies every
  `CONFIG_BACKED_APIS` entry is registry-reachable and defines all required endpoints. Runs in CI
  (`:composeApp:desktopTest`).
- **Runtime**: the manager's `onDocumentRejected` hook logs every rejection with per-stanza
  reasons (tag `SourceConfig`), and `App.kt` logs a startup alarm if the active document contains
  zero generic stanzas.

## 6. How to test a new source

```bash
# 1. The config gates (parse + validate + registry reachability + endpoint completeness):
./gradlew :composeApp:desktopTest --tests "me.manga.kira.sources.runtime.ConfigBackedSourceCompletenessTest" \
                                  --tests "me.manga.kira.sources.runtime.LegacyStanzaCompletenessTest" --offline

# 2. Your parity test + everything else in the sources runtime:
./gradlew :composeApp:desktopTest --offline

# 3. If you added an engine capability:
./gradlew :sources:engine:desktopTest --offline

# 4. The standard compile gate before committing:
./gradlew :composeApp:compileAndroidMain :composeApp:compileKotlinDesktop :composeApp:compileKotlinIosSimulatorArm64 --offline
```

Manual, on a device/emulator (`SourcesScreen` → enable the new source):

- **Home**: tab appears, feed loads, pagination (scroll past page 1), featured row.
- **Search**: this source alone + the all-sources fan-out (fan-out only covers config-backed
  sources).
- **Details**: cover, description, status, genres, full chapter list, chapter dates.
- **Reader**: open a chapter online; page order; page count.
- **Downloads**: download a chapter, then read it offline (airplane mode) — verifies the
  `pages` headers are right and the CBZ pipeline works.
- **Fresh install AND upgrade**: run once on a wiped app (first seed path — is the source visible
  after enabling?) and once over an existing install (catalog-sync-asserts-truth path).
- **If you changed a domain** (`baseUrl` + `previousHosts`): verify a previously-saved manga from
  that source still opens and its cover still loads after the upgrade (the alias sweep rewrote
  stored URLs).
- **If the source is Cloudflare-protected**: first load will 403 → the WebView solver should
  open; after solving, retry succeeds (captured headers now in DataStore).

## 7. How to edit an existing source safely

**Safe (routine) changes** — take effect on the next release, no data impact:

- selectors / JSON paths / transforms in `fields`;
- endpoint URL templates, filters, pagination params;
- `displayName`, `siteState` (UI badge), `priority`;
- **`baseUrl` host move — WITH the ritual**: set the new `baseUrl`, and **append the old host to
  `previousHosts`** (and old image host to `previousImageHosts` if `imageBase` moved).
  `previousHosts` is append-only history. On next launch the catalog sync (a) asserts the new
  baseUrl into the `sources` row and bumps `baseVersion`, (b) rewrites the host of every stored
  `saved_manga.url`, chapter url, history url, and notification url from the old host(s) to the
  new one (`SourceUrlMigrator` — host swap only, paths preserved), and (c) because the old host
  is *declared*, a user's hand-configured mirror host (one NOT in the declared set) is left
  alone. Without the `previousHosts` entry you still get (a)+(b) for the current row host, but
  mirror protection is off and older stored hosts are never swept.

**Risky changes — think twice, then follow the rules:**

- **Changing the api**: never (§3).
- **Removing an endpoint**: instant user-visible breakage of that verb (§4); the completeness
  test will fail the build for the four required ones — that's by design.
- **Changing `lifecycle`**: this is the kill switch (§8) — `disabled` force-disables the source
  on *every* launch (a user's re-enable lasts one session); `removed` deletes its catalog row.
- **Deleting a stanza**: don't (§8) — the sources row is orphaned (force-disabled but never
  cleaned), the alias sweep stops running for it, and the ghost-gate test breaks the build
  anyway if the enum entry remains.
- **Editing a generic stanza's selectors without re-running the parity test**: a stale selector
  that still returns 200-with-empty is the forbidden wrong-but-Success mode.

## 8. How to retire a source

The policy (in order — never skip to deletion):

1. **First release: `"lifecycle": "disabled"`.** The catalog sync force-disables the row every
   launch; the source disappears from Home/search but its row (user toggle, mirror URL,
   siteState) is kept for saved-entry reads. Reversible by shipping `active` again.
2. **Later release (optional): `"lifecycle": "removed"`.** The sync deletes the `sources` row
   (including the user's toggle and mirror URL) and skips re-seed + alias sweep. The stanza stays
   in the document as a tombstone — **never silently delete a shipped stanza**.
3. **Never rename or reuse the api** (§3).

What survives, forever, in either state (verified in code — there is no orphan cleanup, by
design):

| Data | Fate when a source is disabled/removed |
|---|---|
| Library entries (`saved_manga`) | Kept and listed; open from cache (Details renders from Room, zero network); deletable; only a manual refresh errors ("Unknown source api=…") |
| Downloaded chapters | Fully readable offline forever — files are keyed by DB `mangaId`/`chapterId`, the reader's offline path never consults the source |
| Read state / bookmarks / resume page | Untouched (`saved_chapters` flags + settings-store resume keyed by chapter URL) |
| History | Rows kept; downloaded chapters open; non-downloaded chapters error typed |
| Backups | Export/import never touch the sources table; a backup of a dead-api manga imports fine as an orphan row (usable offline, refreshable never) |
| Captured headers (DataStore) | Orphaned but harmless; no GC |
| Library refresh | Skips the manga (strict lookup — dead apis count as failed, and can never blank a stored cover: `LibraryRepositoryCoverGuardTest`) |

## 9. Troubleshooting

| Symptom | Likely cause → what to check |
|---|---|
| **Source doesn't appear in the UI** | (a) not enabled — sources seed disabled by default; enable it in the Sources screen, or ship `"enabled": true` for the first-seed default; (b) api missing from `CONFIG_BACKED_APIS` (stanza alone isn't enough); (c) stanza engine isn't `generic`; (d) the WHOLE document got rejected — see the last row |
| **Tab shows but Home/featured fails** | wrong `home`/`featured` endpoint url/format/root; check Kermit + the parity test fixtures; Cloudflare → see below |
| **Search fails or returns nothing** | missing `search` endpoint (`Validation.Required("endpoint:search")` — no fallback!); raw `{query}` instead of `{queryEncoded}`; site expects POST (set `method`) |
| **Details fail** | `details` endpoint or `detail.*` fields wrong; if a separate `chapters` endpoint exists, ITS failure fails all of details (deliberate) |
| **Pages/download fail** | `pages` endpoint / `page.image` path stale; downloads for config-backed sources throw `GenericPagesFailedException` (failed-chapter row) — check the recorded error message; wrong/missing per-page headers → 403 on the CDN |
| **Cloudflare / 403s** | the engine detects the challenge and signals the WebView solver; the solved `cf_clearance` is stored per-api and merged only when `usesCapturedHeaders: true` (check it isn't `false`); the cookie is per-host — a domain move invalidates it until re-solved |
| **Validates locally but returns empty results** | wrong-but-Success: selectors return no nodes / JSON `root` path misses. Validation can't catch this — only a parity test with real captured fixtures can. Re-capture fixtures; the site probably changed markup |
| **"Unknown source api=…" errors** | a library/history row references an api that no longer resolves (renamed api, or a source removed while its enum entry was also dropped). The data is orphaned — this is exactly why apis are immutable (§3) |
| **Blank home / every generic source gone** | the bundled document was rejected wholesale (one bad stanza!). Logcat tag `SourceConfig` has the per-stanza reasons; `ConfigBackedSourceCompletenessTest` reproduces it at build time. Home now shows an error pane instead of silent blank |

## 10. Examples

**Minimal valid generic source** (JSON API site; every required endpoint; default-enabled):

```jsonc
{
  "api": "Example",                    // permanent identity — never rename (§3)
  "language": "(EN)",
  "displayName": "Example Manga",      // UI label — free to change
  "baseUrl": "https://api.example.com",
  "imageBase": "https://cdn.example.com",
  "engine": "generic",
  "enabled": true,                     // default-enabled on first seed (omit = starts disabled)
  "usesCapturedHeaders": false,        // clean API, no Cloudflare captures needed
  "pagination": { "type": "page-number", "param": "page", "start": 1 },
  "endpoints": {
    "home":     { "url": "{baseUrl}/api/latest?page={page}",  "format": "json", "root": "items" },
    "featured": { "url": "{baseUrl}/api/popular?page={page}", "format": "json", "root": "items" },
    "search":   { "url": "{baseUrl}/api/search?q={queryEncoded}", "format": "json", "root": "items" },
    "details":  { "url": "{itemUrl}",    "format": "json" },
    "pages":    { "url": "{chapterUrl}", "format": "json", "root": "pages" }
  },
  "fields": {
    "item.title":   { "path": "title" },
    "item.url":     { "template": "{baseUrl}/api/manga/{id}", "vars": { "id": "id" } },
    "item.cover":   { "path": "cover" },
    "detail.title":       { "path": "title" },
    "detail.cover":       { "path": "cover" },
    "detail.description": { "path": "summary", "transform": [ { "fn": "clean-html" } ] },
    "detail.status":      { "path": "status", "transform": [ { "fn": "default", "args": { "value": "Unknown" } } ] },
    "detail.genres":      { "listPath": "genres[*].name" },
    "detail.chapters":    { "listPath": "chapters" },
    "chapter.name":   { "path": "name" },
    "chapter.number": { "path": "number", "transform": [ { "fn": "format-number" }, { "fn": "prepend", "args": { "value": "Chapter " } } ] },
    "chapter.url":    { "template": "{baseUrl}/api/chapter/{id}", "vars": { "id": "id" } },
    "chapter.date":   { "path": "createdAt", "dateStrategy": "iso" },
    "page.image": { "path": "url" },
    "page.order": { "path": "order" }
  }
}
```

…plus `"Example"` added to `CONFIG_BACKED_APIS`, a `MangaSource` enum entry (owner coordination,
§2.9), and an `ExamplePilotParityTest`. The real 12 stanzas in `BundledSourcesConfig.kt` are the
best reference — Azora is the cleanest JSON-API example, the Madara-family sources the HTML ones.

**Source after a domain move** (`previousHosts` carries the history, append-only):

```jsonc
{
  "api": "Example",
  "baseUrl": "https://api.example-new.com",       // ← the move
  "previousHosts": ["api.example.com"],            // ← old host, appended (bare host — no scheme!)
  "imageBase": "https://cdn.example-new.com",
  "previousImageHosts": ["cdn.example.com"],
  // …rest unchanged
}
```

**Disabled source** (step 1 of retirement — reversible):

```jsonc
{ "api": "Example", "language": "(EN)", "baseUrl": "https://api.example.com",
  "engine": "generic", "lifecycle": "disabled", /* …rest of the stanza stays intact… */ }
```

**Removed source** (step 2 — the stanza stays as a tombstone; metadata-only is fine now):

```jsonc
{ "api": "Example", "language": "(EN)", "baseUrl": "https://api.example.com",
  "engine": "legacy", "lifecycle": "removed" }
```

**What NOT to do — the api rename.** This is the one change with permanent, invisible damage:

```jsonc
// ❌ NEVER: "renaming" a source
// before:  { "api": "Example",       ... }
// after:   { "api": "Example Manga", ... }   // ← this is a NEW source, not a rename
```

Every library entry, history row, backup, and captured header still says `"Example"`. They now
point at nothing: refresh fails, online reading fails, and no code will ever fix them up. The
old `sources` row lingers force-disabled. If the display name must change, change `displayName`.
If the site truly relaunched as something else, ship the old api as `lifecycle:"disabled"` →
`"removed"` and add the new site as a genuinely new source — accepting that users re-add their
library entries (or migrate via backup export/import, which keeps working offline either way).

---

*Invariants recap: api strings are immutable once shipped · retirement is `disabled` → `removed`,
never silent stanza deletion · `previousHosts` is append-only · validation is all-or-nothing
(build gate: `ConfigBackedSourceCompletenessTest`) · generic sources have no legacy fallback —
every required endpoint must exist and be parity-tested.*
