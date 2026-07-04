package me.manga.kira.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Central semantic icon map for the Yami design system (Phase 11.ui.UP-2, Option A).
 *
 * Screens reference these by intent (e.g. [Back], [Refresh]) instead of importing raw `Icons.*`
 * vectors directly. This keeps the action→glyph mapping in one place, lets the whole app retheme
 * its iconography from a single file, and replaces the interim text-glyph placeholders
 * (`←`, `‹`, `›`, `⋯`, `↻`, `↓`, `↗`, `♥`/`♡`, `✓`, `🔖`, `🗑`, `⏰`/`🕒`) that the rework had
 * been using while `:ui` was kept off the material-icons dependency.
 *
 * Auto-mirrored entries ([Back], [PrevChapter], [NextChapter]) flip correctly under RTL (the
 * primary locale is Arabic), matching the legacy app's `Icons.AutoMirrored.*` usage.
 */
object KiraIcons {
    /** Navigate up / back. Auto-mirrored for RTL. */
    val Back: ImageVector = Icons.AutoMirrored.Filled.ArrowBack

    /** Previous chapter chevron. Auto-mirrored for RTL. */
    val PrevChapter: ImageVector = Icons.AutoMirrored.Filled.NavigateBefore

    /** Next chapter chevron. Auto-mirrored for RTL. */
    val NextChapter: ImageVector = Icons.AutoMirrored.Filled.NavigateNext

    /** Overflow / more-actions menu. */
    val Overflow: ImageVector = Icons.Filled.MoreVert

    /** Refresh / reload. */
    val Refresh: ImageVector = Icons.Filled.Refresh

    /** Downloads. */
    val Download: ImageVector = Icons.Filled.Download

    /** Open in external browser / WebView. Auto-mirrored for RTL. */
    val OpenInWebView: ImageVector = Icons.AutoMirrored.Filled.OpenInNew

    /** Library membership — filled heart when in library. */
    val FavoriteFilled: ImageVector = Icons.Filled.Favorite

    /** Library membership — outline heart when not in library. */
    val FavoriteOutline: ImageVector = Icons.Filled.FavoriteBorder

    /** Selection / confirmation check. */
    val Check: ImageVector = Icons.Filled.Check

    /** Bookmark (used on the library card bookmark-count badge; filled = bookmarked). */
    val Bookmark: ImageVector = Icons.Filled.Bookmark

    /** Bookmark — outline (not bookmarked) variant for the reader top-bar toggle. */
    val BookmarkOutline: ImageVector = Icons.Outlined.BookmarkBorder

    /** Delete / remove. */
    val Delete: ImageVector = Icons.Filled.Delete

    /** "Watching now" — active (filled clock). */
    val WatchingNowOn: ImageVector = Icons.Filled.WatchLater

    /** "Watching now" — inactive (outline clock). */
    val WatchingNowOff: ImageVector = Icons.Filled.Schedule

    /** Sort direction — ascending. */
    val SortAscending: ImageVector = Icons.Filled.ArrowUpward

    /** Sort direction — descending. */
    val SortDescending: ImageVector = Icons.Filled.ArrowDownward

    /** Empty-state placeholder (outline inbox) — used by the shared [KiraEmptyState]. */
    val Empty: ImageVector = Icons.Outlined.Inbox

    /** Error-state placeholder (outline error) — used by the shared [KiraErrorState]. */
    val Error: ImageVector = Icons.Outlined.ErrorOutline

    /**
     * Broken / failed image placeholder glyph. [KiraCoverImage] now renders native's flat
     * `error @ 0.24` fill on a cover-load failure (no glyph) to match the native `MangaCard`, so this
     * is currently unused by the cover component but kept available for any future glyph-style error.
     */
    val BrokenImage: ImageVector = Icons.Outlined.BrokenImage

    /** Play affordance — WhatsNew video poster overlay (GAP-WN-01). */
    val Play: ImageVector = Icons.Filled.PlayArrow

    /** Filter/sort/display options entry point (sliders) — opens the Library options bottom sheet. */
    val Tune: ImageVector = Icons.Filled.Tune

    /** Search action (Home top bar → opens the search overlay). */
    val Search: ImageVector = Icons.Filled.Search

    /** Close / dismiss (search field clear, overlay close). */
    val Close: ImageVector = Icons.Filled.Close

    /** Grid layout — shown when the feed is in list mode (tap → switch to grid). */
    val GridView: ImageVector = Icons.Filled.GridView

    /** List layout — shown when the feed is in grid mode (tap → switch to list). Auto-mirrored for RTL. */
    val ViewList: ImageVector = Icons.AutoMirrored.Filled.ViewList

    /** Edit (the Home tab strip's "edit sources" trailing action). */
    val Edit: ImageVector = Icons.Filled.Edit

    /** Share (the reader top-bar "share current page" action). */
    val Share: ImageVector = Icons.Filled.Share

    /**
     * Read/unread toggle — the per-chapter "mark read" eye affordance on the Details chapter list
     * (GAP-LIB-02, native `RemoveRedEye` parity). Tinted by the caller: primary when the chapter is
     * already read, onSurfaceVariant when unread.
     */
    val MarkRead: ImageVector = Icons.Filled.RemoveRedEye
}
