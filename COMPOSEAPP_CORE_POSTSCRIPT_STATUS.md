# :composeApp + :core + :platform + :domain/{model,repository} §253 postscript status (as of 2026-05-28)

Survey result from background agent a5a06301ce5089330.

## Summary by module

### :composeApp (~mostly swept)
- App.kt — SWEPT (cluster93)
- Navigation routes — mostly SWEPT (cluster5/8/12-15/18/87-88, etc.)
- Koin modules (`di/`) — PARTIALLY SWEPT (some modules carry postscripts; coverage gap exists)
- WebViewHost, error screens — SWEPT (cluster88, 91-92)

### :core (0/7 UNSWEPT — full gap)
All :core/commonMain files lack postscripts. Candidates for cascade:
- AppResult.kt
- AppError.kt
- Dispatchers contracts
- Base MVI contracts (MviState/MviIntent/MviEffect, MviViewModel)
- Locale facade interfaces
- Result + Either monads
- Misc base abstractions

### :platform (0/32 UNSWEPT — full gap)
All `:platform/commonMain` interfaces + `:platform/androidMain` /
`:platform/iosMain` / `:platform/jvmMain` actuals lack postscripts.
Candidates for cascade:
- ToastShower, IntentLauncher, AppFileSystem, FileSizeFormatter
- LocaleProvider, SecureStorage, SettingsFactory, ConnectivityObserver
- NotificationPresenter, PushTokenProvider, ScreenshotProvider,
  BackgroundJobScheduler
- ImageDecoderRegistry, Base64ImageConverter, DominantColorExtractor
- CbzWriter, CbzReader, CbzSettings, DeviceTierProbe
- InAppReviewClient, AppUpdateClient, ConsentFlowClient, AdProvider
- AnalyticsClient, CrashReporter, RemoteDocStore, ForegroundActivityProvider

### :domain/repository (14/26 swept)
Roughly half of the domain repository INTERFACE definitions carry
postscripts; the rest lack them. Continuing the cascade through the
remaining 12 unswept interface files would round out :domain/repository
parity with :data/repository (which is ~14/27 swept).

### :domain/model (mostly UNSWEPT — wide gap)
Domain ADTs (Manga, Chapter, AppTheme, Language, ReadingMode,
ChapterDownload, Complaint variants, etc.) mostly lack postscripts.
A model-tier cascade wave would catch these.

## Recommendations (in priority order)

1. **:domain/model cascade** — high marginal coverage (~20+ files unswept),
   low per-file cost (ADTs are short).
2. **:domain/repository finish** — 12 files to close the half-swept tier;
   short interfaces, fast per-file.
3. **:core cascade** — 7 files, foundational base contracts (high
   architectural visibility per file).
4. **:platform cascade** — 32 files, but largest payoff per cluster
   (interfaces with 4 actuals each = wide expect/actual surface).
5. **:composeApp Koin module finish** — gap-closure for di/ remaining
   unswept modules.
