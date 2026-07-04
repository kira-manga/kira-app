# UI Migration Report — Phase 10

> Mandatory output per Phase 10 of `MIGRATION_PROMPT.md`. Documents the planned migration of the Compose UI layer from Android-only `androidx.compose.*` to Compose Multiplatform.

## Source UI surface

| Bucket | Count |
|---|---|
| `@Composable`-annotated files | 148 |
| Distinct `@Composable` functions | 296 |
| Screen-level entries | ~24 (one per feature ViewModel) |
| Shared component library | `presentation/common/componants/*` (~25 files: buttons, app bars, chips, dialogs, lists, scroll, toast, images, titles, sources, auto-sized text) |
| Theme | `theme/Color.kt`, `theme/Theme.kt`, `theme/Type.kt` |
| Navigation host | `navigation/NavGraphV2.kt` |
| Resources | 12 locale bundles, drawables, fonts, raw, xml, layout |

## Migration target

Compose Multiplatform `1.11.0` (locked in `gradle/libs.versions.toml`). Plugin: `org.jetbrains.compose` applied to `:composeApp` module.

## Cross-platform vs Android-only UI

Most composables are platform-agnostic and move directly to `composeApp/src/commonMain/`. A small set wraps Android-only widgets and must be expressed via `expect @Composable` declarations with platform actuals:

| Composable / area | Strategy | Phase |
|---|---|---|
| `presentation/features/library/*`, `library_details/*`, `home/*`, `details/*`, `history/*`, `settings/*`, `repo_settings/*`, `statistics/*`, `language/*`, `notifications/*`, `refresh/*`, `whatsnew/*`, `about/*`, `complaint/*`, `onboarding/*`, `crash/*`, `admin/*` | Move to `composeApp/commonMain` verbatim. Compose-MP supports all Material 3 + Foundation APIs they use. | Phase 10 batches 10.1 → 10.16 |
| `presentation/features/reader/ui/reading_modes/ZoomableImage.kt` | `expect @Composable fun ZoomableImage(url, headers, modifier)` in commonMain. Android `actual` wraps `me.saket.telephoto:zoomable-image-coil3`. iOS/Desktop `actual` wraps `net.engawapg.lib:zoomable` + Coil 3 AsyncImage. | Phase 10 batch 10.5 |
| `presentation/features/webview/*` | `expect @Composable fun WebViewHost(...)` in commonMain. Android `actual` wraps `android.webkit.WebView`. iOS/Desktop `actual` shows a stub message — real `WKWebView` / JCEF integration deferred. | Phase 10 batch 10.10 + Phase 8 |
| `ad_mob/bannars/BannerAdView.kt`, `ad_mob/native_ads/NativeAdListItem.kt` | `expect @Composable fun BannerAd(...)`, `expect @Composable fun NativeAdItem(...)` in commonMain. Android `actual` wraps Google AdMob. iOS/Desktop `actual` returns `Spacer(Modifier)` (no-op). | Phase 10 batch 10.13 |
| `firebase_cores/common/rememberFirebaseAnalytics.kt` | Android-only composable. Stays in `app/` or in `composeApp/androidMain` if used elsewhere. | Phase 10 / Phase 11 |
| `presentation/common/componants/images/BlurredImageCoil.kt` | Uses Coil 3 + a custom Coil `Transformation`. Compose-MP-portable via `coil-compose:3.4.0` (already locked). | Phase 10 batch 10.6 |
| Reader components using `org.aomedia.avif.android` AVIF decoder | Decoder is Android-only (`androidMain`-only). On non-Android, Coil falls back to default decoders (JPEG/PNG). Android-only feature documented per Section 28's UI Preservation Rule. | Phase 10 batch 10.4 |

## Resource migration

Source has `app/src/main/res/` with:

| Resource type | Migration |
|---|---|
| `values/strings.xml` + 11 locale bundles (`values-ar`, `-de`, `-es`, `-fr`, `-in`, `-it`, `-ja`, `-pt`, `-ru`, `-tr`) + `values-night/` | Move to `composeApp/src/commonMain/composeResources/values/strings.xml` + `composeResources/values-<locale>/strings.xml`. Access via `stringResource(Res.string.<key>)`. Composable resources auto-resolve locale on every target. |
| `values-v26/` | Android-only (system feature). Stays in `app/src/main/res/values-v26/`. |
| `drawable/` | Vector drawables (XML) → move to `composeResources/drawable/`. Bitmap drawables (PNG, JPG, WebP) → move to `composeResources/drawable/`. Access via `painterResource(Res.drawable.<name>)`. |
| `mipmap-*/` | Android launcher icons. Stays in `app/src/main/res/mipmap-*/`. |
| `font/` | Move to `composeResources/font/`. Access via `Font(Res.font.<name>)`. |
| `raw/` | Move to `composeResources/files/`. Access via `Res.readBytes("files/<name>")`. |
| `xml/` (file_paths, network_security_config, data_extraction_rules, backup_rules) | Android-only. Stays in `app/src/main/res/xml/`. |
| `layout/` | XML layouts for ViewBinding. **Audit pending** — most Compose code shouldn't reference XML layouts. Any that does is Android-only and stays. |

## Display-name extensions deferred from Phase 4

Two source enums had Android-Context-bound display-name methods that were dropped during Phase 4 move:

- `ComplaintType.getDisplayName(context: Context): String` — used `R.string.error_in_the_app` etc.
- `ComplaintStatus.getDisplayName(context: Context): String` — used `R.string.status_open` etc.

Phase 10 replaces these with compose-resources extension functions in commonMain:

```kotlin
@Composable
fun ComplaintType.displayName(): String = stringResource(when (this) {
    TECHNICAL  -> Res.string.error_in_the_app
    LANGUAGES  -> Res.string.add_languages
    ...
})

@Composable
fun ComplaintStatus.displayName(): String = stringResource(when (this) {
    OPEN -> Res.string.status_open
    ...
})
```

Identical user-visible behavior; locale-aware on every platform via `compose.resources`.

## Theme

Source `theme/Color.kt` + `Theme.kt` + `Type.kt` use Material 3 + Material 2 (mixed) APIs that are all available in Compose Multiplatform 1.11.0. Move to `composeApp/src/commonMain/kotlin/me/manga/yamiapk/theme/`. Light/dark detection on each platform uses CMP's `isSystemInDarkTheme()`.

## Other deferred display-bound extension that lived in Phase 4 source

- `core/util/date/Date.kt` — had Android Context + `R.plurals.time_minutes_ago` etc. for `LocalDate.toRelativeString(context, now)` and `LocalDateTime.timeAgo(context)`. Phase 10 rewrites these as compose-resources lookups using `pluralStringResource(Res.plurals.<key>, count, count)`.
- `core/util/Plus18memes.kt` — `R.drawable.anti_horny_*` lists. Phase 10 moves the drawables to `composeResources/drawable/` and rewrites the object to return `Res.drawable.anti_horny_2` etc.

## Behavior parity preservation

Per `MIGRATION_PROMPT.md` Section 28 ("Compose UI Preservation and Cleanup Rule"):
- Same layout, colors, typography, spacing, component sizes.
- Same navigation flow, button behavior, form behavior.
- Same loading/error/empty states.
- Same animations (verified per composable during Phase 10 batches).
- Same accessibility modifiers (`contentDescription`, `semantics`, etc. — never removed).
- Same previews preserved or documented in this report if removed.

## Status

| Item | Status |
|---|---|
| Compose-MP `1.11.0` plugin applied to `:composeApp` | ✅ Phase 3 |
| Material 3 + Material + Foundation + Animation deps in commonMain | ✅ Phase 3 |
| `compose.resources` configured in `composeApp/build.gradle.kts` with corrected `packageOfResClass = "me.manga.kira.composeapp.generated.resources"` | ✅ (fixed in commit `90f1a85`) |
| Theme files moved | ⏳ Phase 10 batch 10.1 |
| 19 navigation routes moved | ⏳ Phase 9 batch 9.1 (Phase 10 prerequisite) |
| 148 composable files moved | ⏳ Phase 10 batches 10.2 → 10.16 |
| Resources moved (12 locales + drawables + fonts + raw) | ⏳ Phase 10 batch 10.17 |
| Display-name extensions for `ComplaintType` / `ComplaintStatus` / `Date` / `Plus18memes` | ⏳ Phase 10 batch 10.18 |
| Android-only composables wrapped in `expect @Composable` (`ZoomableImage`, `WebViewHost`, `BannerAd`, `NativeAdItem`) | ⏳ Phase 10 / Phase 8 |
| Accessibility audit per migrated screen | ⏳ Phase 10 (one row per screen in `accessibility-report.md`) |
| Localization/RTL audit | ⏳ Phase 10 (`localization-rtl-report.md`) |
