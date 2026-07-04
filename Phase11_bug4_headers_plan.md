# Bug 4 — Saved headers not reliably applied to requests

## Symptom (assumed)
Headers saved correctly from WebView (via `WebViewViewModel.saveHeaders` → `repo.refreshHeaders` → `DataStoreHelper.saveHeadersForApi`), but not reloaded into `_cachedHeaders` before every request path on app restart. Affected repos: any that overrides `initSite()` to hydrate headers but where the calling ViewModel doesn't call `initSite()` first.

## Root cause
The current architecture relies on ViewModels to call `repo.initSite()` before fetching:
- `MangaViewModel.loadHome` ✅
- `HomeViewModel.fetchSearchDataF` ✅
- `LibraryDetailsViewModel` ✅
- `CoroutineDownloadRepositoryImpl` ✅
- `MangaViewModel.getPopularManga` ❌ (missing)
- `MangaViewModel.getMoreManga` ❌ (missing)
- `MangaViewModel.startSearch` ❌ (missing)
- Reader/chapter pages flow ❌ (missing)
- `MangamelloPlusRepository` does NOT override `initSite()` — even if called, headers don't reload from DataStore.

## Fix strategy
Centralize hydration in `BaseManga.fetchDataWithHeaders` (the helper every `fetchMangaHomeF` / `fetchPopularManga` / `fetchMangaChaptersF` / `fetchChapterDataF` / `*Search` goes through). This guarantees that the first call into any source request path hydrates headers from DataStore, regardless of which ViewModel called it.

Plus a session-long `siteInitialized` flag to avoid 100+ redundant DataStore reads per session.

Plus add the missing `MangamelloPlus.initSite()` override.

Plus add Kermit `[Headers]` logging at save / load / pre-request boundaries.

## Files touched (commit batch A, ≤5)
1. `shared/.../sources_repositry/common/BaseManga.kt` — add `siteInitialized` flag, `ensureSiteInitialized()` method, call it from `fetchDataWithHeaders`, log header state pre-request.
2. `shared/.../sources_repositry/ar/mangamelloplus/MangamelloPlusRepository.kt` — add `initSite()` override (was missing).
3. `shared/.../core/storage/DataStoreHelper.kt` — Kermit logging in `saveHeadersForApi` and `getHeadersForApi`.

## Flows covered after fix
- Home fetch (`MangaViewModel.loadHome` → `fetchMangaHomeF` → `fetchDataWithHeaders`)
- Popular fetch (`MangaViewModel.getPopularManga` → `fetchPopularManga` → `fetchDataWithHeaders`)
- Load more (`MangaViewModel.getMoreManga` → `fetchMoreManga` → `fetchMangaHome` → `fetchDataWithHeaders`)
- Single-source search (`MangaViewModel.startSearch` → `fetchSearchDataF` → `*Search` → `fetchDataWithHeaders`)
- Multi-source search (`HomeViewModel` → `fetchSearchDataF` → `fetchDataWithHeaders`; also has explicit `initSite()` for safety)
- Manga details (`LibraryDetailsViewModel` → `fetchMangaChaptersF` → `fetchDataWithHeaders`)
- Chapter pages (`fetchChapterDataF` → `fetchDataWithHeaders`)
- Downloads (`CoroutineDownloadRepositoryImpl` → `fetchChapterDataF` → `fetchDataWithHeaders`)

## Not covered (out of scope for this bug)
- Repos that override `initSite()` to do MORE than just header hydration (e.g. base URL refresh). The centralized fix still calls polymorphic `initSite()`, so any extra work runs too — just once per session instead of per-request.
- Direct `api.get(...)` calls outside `fetchDataWithHeaders` blocks (e.g. inside helper methods of concrete repos). Those still need `defaultHeaders` to be populated; the flag ensures hydration happened before the helper is reached, as long as something else fired a `fetchDataWithHeaders` first.
