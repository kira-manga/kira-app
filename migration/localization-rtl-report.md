# Localization & RTL Report — Phase 10 / Section 43

> Mandatory output per `MIGRATION_PROMPT.md` Section 43.

## Locale set preserved

Source ships 12 locale bundles (default English + 10 translated + dark-theme). All preserved into composeResources:

| Locale | Direction | Source file count | Status |
|---|---|---|---|
| `values/` (default English) | LTR | 6 files (strings, colors, dimens, themes, arrays, ids) | preserved |
| `values-ar/` (Arabic) | **RTL** | strings.xml | preserved |
| `values-de/` (German) | LTR | strings.xml | preserved |
| `values-es/` (Spanish) | LTR | strings.xml | preserved |
| `values-fr/` (French) | LTR | strings.xml | preserved |
| `values-in/` (Indonesian) | LTR | strings.xml | preserved |
| `values-it/` (Italian) | LTR | strings.xml | preserved |
| `values-ja/` (Japanese) | LTR | strings.xml | preserved |
| `values-pt/` (Portuguese) | LTR | strings.xml | preserved |
| `values-ru/` (Russian) | LTR | strings.xml | preserved |
| `values-tr/` (Turkish) | LTR | strings.xml | preserved |
| `values-night/` | (theme) | colors / themes overrides | preserved |
| `values-v26/` | (API-level system feature) | themes overrides | preserved Android-only |

## RTL preservation

- Source manifest declares `android:supportsRtl="true"`.
- Compose Multiplatform inherits the `LocalLayoutDirection.current` mechanism — automatic on every target.
- Source uses `LayoutDirection.Ltr.calculateStartPadding(...)` explicitly in a few list scrollers (`presentation/common/componants/scroll/LazyVerticalScrollerWithScrollBar.kt`) — preserved verbatim.
- Source uses `androidx.core:core-i18n:1.0.0` for some i18n helpers (will check Phase 10 audit; if used only for bidirectional text formatting, the KMP-portable replacement is `CharDirectionality` from `kotlin.text` or just delegating to `LocalLayoutDirection`).

## Formatting

- **Date/time**: source uses Android `DateFormat` + Java `DateTimeFormatter`. Migration replaces with `kotlinx-datetime`'s `LocalDateTime.Format { … }`. Output strings remain culture-appropriate via `TimeZone.currentSystemDefault()` + locale-aware pluralization in compose-resources (`pluralStringResource(Res.plurals.time_minutes_ago, count, count)`).
- **Numbers**: source has no custom number formatting; default Kotlin number-to-string is used. Preserved.
- **Currency**: not present in source.

## Hardcoded text

- Source has zero hardcoded user-visible strings outside of `strings.xml` files based on Phase 1 grep — every `Text("...")` references a string resource.
- One minor exception: the App-shell stub in `composeApp/src/commonMain/.../App.kt` has the hardcoded string `"Yami KMP — stub. Replace in Phase 10/11."` — this is the migration stub and gets replaced in Phase 10.

## RTL audit per screen

After Phase 10 batches land, this section will list each migrated screen + whether RTL was tested:

```
### <feature>/<file>.kt
- LTR rendering: ✅
- RTL rendering (Arabic locale): ✅
- Mirror-anchored components verified (back button, drawer side, …): yes/no
```

## Status

| Item | Status |
|---|---|
| 12 locale bundles preserved | ✅ Phase 3 (XMLs copied; Phase 10 moves to composeResources) |
| `supportsRtl="true"` preserved in manifest | ✅ |
| RTL helpers preserved | ✅ |
| Date/time formatting locale-aware via compose-resources | ⏳ Phase 10 |
| Per-screen RTL audit | ⏳ Phase 10 + Phase 14 manual test (run app in Arabic) |
