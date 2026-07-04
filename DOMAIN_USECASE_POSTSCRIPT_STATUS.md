# :domain/usecase/ §253 postscript status (as of 2026-05-28, post-cluster132 — FULLY SWEPT 78/78 = 100%)

## Summary
- Total files: 78 (re-verified count: complaint 9 + details 2 + downloads 6 + feedback 2 + history 3 + language 3 + library 25 + reader 8 + settings 3 + sources 4 + statistics 1 + theme 4 + updates 5 + whatsnew 2 + about 1)
- Swept: 78 (postscript present) — +2 from cluster132 :library/ 2-toggle-pair batch (ToggleMangaLiked + ToggleMangaWatchingNow); CLOSES wave-23 cycle (25 files across clusters 127-132); CLOSES library/ subpackage entirely (25/25 SWEPT); CLOSES :domain/usecase/ §253 audit-trail-preservation sweep at 78/78 = 100%
- Unswept: 0 (sweep complete)

## By subpackage

### about/ (1 file)
- GetAppMetadataUseCase.kt — SWEPT (cluster114, Task #570)

### complaint/ (9 files) — FULLY SWEPT (5 admin from cluster123 + 4 user from cluster124)
- AddClosureReasonUseCase.kt — SWEPT (cluster123, Task #579)
- AdminDeleteComplaintUseCase.kt — SWEPT (cluster123, Task #579)
- AdminEditComplaintUseCase.kt — SWEPT (cluster123, Task #579)
- ChangeComplaintStatusUseCase.kt — SWEPT (cluster123, Task #579)
- DeleteComplaintUseCase.kt — SWEPT (cluster124, Task #580)
- EditComplaintUseCase.kt — SWEPT (cluster124, Task #580)
- ObserveAllComplaintsUseCase.kt — SWEPT (cluster123, Task #579)
- ObserveUserComplaintsUseCase.kt — SWEPT (cluster124, Task #580)
- ReplyToComplaintUseCase.kt — SWEPT (cluster124, Task #580)

### details/ (2 files) — FULLY SWEPT
- FetchMangaDetailsUseCase.kt — SWEPT (cluster119, Task #575)
- IsAdultContentUseCase.kt — SWEPT (cluster119, Task #575)

### downloads/ (6 files) — FULLY SWEPT
- CancelDownloadUseCase.kt — SWEPT (cluster121, Task #577)
- CancelRunningDownloadUseCase.kt — SWEPT (cluster121, Task #577)
- DeleteDownloadUseCase.kt — SWEPT (cluster121, Task #577)
- EnqueueDownloadUseCase.kt — SWEPT (cluster121, Task #577)
- ObserveDownloadsUseCase.kt — SWEPT (cluster121, Task #577)
- RetryDownloadUseCase.kt — SWEPT (cluster122, Task #578)

### feedback/ (2 files) — FULLY SWEPT
- SendLanguageRequestUseCase.kt — SWEPT (cluster120, Task #576)
- SubmitFeedbackUseCase.kt — SWEPT (cluster120, Task #576)

### history/ (3 files)
- DeleteAllHistoryUseCase.kt — SWEPT (cluster112, Task #568)
- DeleteHistoryEntryUseCase.kt — SWEPT (cluster112, Task #568)
- ObserveHistoryUseCase.kt — SWEPT (cluster112, Task #568)

### language/ (3 files) — FULLY SWEPT
- GetSupportedLanguagesUseCase.kt — SWEPT (cluster116, Task #572)
- ObserveSelectedLanguageUseCase.kt — SWEPT (cluster116, Task #572)
- SetLanguageUseCase.kt — SWEPT (cluster116, Task #572)

### library/ (25 files) — FULLY SWEPT (wave-23 cycle clusters 127-132)
- BulkRemoveFromLibraryUseCase.kt — SWEPT (cluster127, Task #583)
- ObserveInLibraryUseCase.kt — SWEPT (cluster127, Task #583)
- ObserveLibraryCategoryUseCase.kt — SWEPT (cluster130, Task #586)
- ObserveLibraryDisplayUseCase.kt — SWEPT (cluster130, Task #586)
- ObserveLibraryFilterUseCase.kt — SWEPT (cluster129, Task #585)
- ObserveLibraryGridDensityUseCase.kt — SWEPT (cluster129, Task #585)
- ObserveLibraryLastUpdatedUseCase.kt — SWEPT (cluster130, Task #586)
- ObserveLibraryRefreshUseCase.kt — SWEPT (cluster128, Task #584)
- ObserveLibrarySortDirectionUseCase.kt — SWEPT (cluster128, Task #584)
- ObserveLibrarySortUseCase.kt — SWEPT (cluster128, Task #584)
- ObserveLibraryUseCase.kt — SWEPT (cluster127, Task #583)
- RefreshLibraryUseCase.kt — SWEPT (cluster127, Task #583)
- SetLibraryCategoryUseCase.kt — SWEPT (cluster130, Task #586)
- SetLibraryFilterUseCase.kt — SWEPT (cluster129, Task #585)
- SetLibraryGridDensityUseCase.kt — SWEPT (cluster129, Task #585)
- SetLibraryShowButtonsUseCase.kt — SWEPT (cluster131, Task #587)
- SetLibraryShowCountUseCase.kt — SWEPT (cluster131, Task #587)
- SetLibraryShowDetailsUseCase.kt — SWEPT (cluster131, Task #587)
- SetLibraryShowSourceUseCase.kt — SWEPT (cluster131, Task #587)
- SetLibraryShowTabsUseCase.kt — SWEPT (cluster131, Task #587)
- SetLibrarySortDirectionUseCase.kt — SWEPT (cluster128, Task #584)
- SetLibrarySortUseCase.kt — SWEPT (cluster128, Task #584)
- ToggleInLibraryUseCase.kt — SWEPT (cluster127, Task #583)
- ToggleMangaLikedUseCase.kt — SWEPT (cluster132, Task #588)
- ToggleMangaWatchingNowUseCase.kt — SWEPT (cluster132, Task #588)

### reader/ (8 files) — FULLY SWEPT (5 opener from cluster125 + 3 closer from cluster126)
- EndReadingSessionUseCase.kt — SWEPT (cluster125, Task #581)
- FetchChapterPagesUseCase.kt — SWEPT (cluster126, Task #582)
- ListChaptersUseCase.kt — SWEPT (cluster125, Task #581)
- LoadPagePositionUseCase.kt — SWEPT (cluster125, Task #581)
- ObserveReadingModeUseCase.kt — SWEPT (cluster126, Task #582)
- SavePagePositionUseCase.kt — SWEPT (cluster125, Task #581)
- SetReadingModeUseCase.kt — SWEPT (cluster126, Task #582)
- StartReadingSessionUseCase.kt — SWEPT (cluster125, Task #581)

### settings/ (3 files) — FULLY SWEPT
- ClearCacheUseCase.kt — SWEPT (cluster117, Task #573)
- ObserveSettingsUseCase.kt — SWEPT (cluster117, Task #573)
- UpdateSettingsToggleUseCase.kt — SWEPT (cluster117, Task #573)

### sources/ (4 files) — FULLY SWEPT
- EnableDefaultLanguageSourcesUseCase.kt — SWEPT (cluster118, Task #574)
- ObserveSourcesUseCase.kt — SWEPT (cluster118, Task #574)
- SetLanguageEnabledUseCase.kt — SWEPT (cluster118, Task #574)
- SetSourceEnabledUseCase.kt — SWEPT (cluster118, Task #574)

### statistics/ (1 file)
- ObserveReadingStatisticsUseCase.kt — SWEPT (cluster113, Task #569)

### theme/ (4 files)
- ObserveAppThemeUseCase.kt — SWEPT (cluster115, Task #571)
- ObservePureBlackUseCase.kt — SWEPT (cluster115, Task #571)
- SetAppThemeUseCase.kt — SWEPT (cluster115, Task #571)
- SetPureBlackUseCase.kt — SWEPT (cluster115, Task #571)

### updates/ (5 files)
- DeleteAllUpdatesUseCase.kt — SWEPT (cluster110, Task #566)
- DeleteUpdateEntryUseCase.kt — SWEPT (cluster110, Task #566)
- MarkAllUpdatesAsReadUseCase.kt — SWEPT (cluster110, Task #566)
- MarkUpdateAsReadUseCase.kt — SWEPT (cluster16, Task #472)
- ObserveUpdatesUseCase.kt — SWEPT (cluster110, Task #566)

### whatsnew/ (2 files)
- GetWhatsNewFeaturesUseCase.kt — SWEPT (cluster111, Task #567)
- MarkWhatsNewSeenUseCase.kt — SWEPT (cluster111, Task #567)

## Cluster Index
- cluster16: 1 file (Task #472)
- cluster110: 4 files (Task #566)
- cluster111: 2 files (Task #567)
- cluster112: 3 files (Task #568)
- cluster113: 1 file (Task #569)
- cluster114: 1 file (Task #570)
- cluster115: 4 files (Task #571)
- cluster116: 3 files (Task #572)
- cluster117: 3 files (Task #573)
- cluster118: 4 files (Task #574)
- cluster119: 2 files (Task #575)
- cluster120: 2 files (Task #576)
- cluster121: 5 files (Task #577)
- cluster122: 1 file (Task #578)
- cluster123: 5 files (Task #579)
- cluster124: 4 files (Task #580)
- cluster125: 5 files (Task #581)
- cluster126: 3 files (Task #582)
- cluster127: 5 files (Task #583)
- cluster128: 5 files (Task #584)
- cluster129: 4 files (Task #585)
- cluster130: 4 files (Task #586)
- cluster131: 5 files (Task #587)
- cluster132: 2 files (Task #588) — closes :domain/usecase/ FULLY SWEPT 78/78
