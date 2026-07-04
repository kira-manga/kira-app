package me.manga.yamiapk.presentation.features.home.ui.components

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ScrollableTabRow
import androidx.compose.material.Tab
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.manga.yamiapk.R
import me.manga.yamiapk.sources_repositry.BaseMangaRepository
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import coil3.request.ImageRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.SearchType
import me.manga.yamiapk.presentation.features.library.ui.components.AnimatedNew
import me.manga.yamiapk.presentation.features.repo_settings.data.Source

@Composable
fun SourcesTabs(
    tabs: List<BaseMangaRepository>,
    activeTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    onEditTabs: () -> Unit,
    isNewSource: Boolean,
    editLabelText: String = stringResource(R.string.new_badge)// Customizable text
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1) The scrollable tabs take up all available space
        ScrollableTabRow(
            modifier = Modifier
                .weight(1f),
            selectedTabIndex = activeTabIndex,
            backgroundColor = Color.Transparent,
            edgePadding = 16.dp,
            indicator = {}, // no underline
            divider = {}
        ) {
            tabs.forEachIndexed { index, repo ->

                Log.i("sdgkjfdgdfgfdgdfgdfgdsfgdsfg", "$index =========== ${repo.API}=============${repo.BASE_URL}")
                val selected = index == activeTabIndex
                val bgColor = if (selected)
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                else
                    Color.Transparent

                val textColor = if (selected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

                Tab(
                    selected = selected,

                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(bgColor)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    onClick = { onTabSelected(index) },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val safeRes = if (repo.ICON != 0) repo.ICON else R.drawable.team_x

                        Icon(
                            painter = painterResource(safeRes),
                            contentDescription = "repo.API",
                            tint = if(selected) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = repo.API,
                            color = textColor,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
                        )
                    }
                }
            }
        }

        // 2) Edit button with optional text label on top
        Box(
            modifier = Modifier.padding(end = 8.dp),
            contentAlignment = Alignment.Center
        ) {
//            Column(
//                horizontalAlignment = Alignment.CenterHorizontally,
//                verticalArrangement = Arrangement.Center
//            ) {
                // Conditional text above the icon


                IconButton(
                    onClick = onEditTabs,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit_sur),
                        contentDescription = "Edit tabs",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            if (isNewSource) {

                AnimatedNew(Modifier.height(24.dp).width(24.dp).padding(bottom = 0.dp).align(Alignment.TopCenter))

            }
        }
    }
}

// Minimal fake implementation for previewing only.
// Implements required members with simple defaults — none of these are used by the preview UI.
class FakeRepo(
    override val API: String,
    override val ICON: Int = 0,
    override val LANGUAGE: String = "en"
) : BaseMangaRepository() {

    override val BASE_URL: String = "https://example.com"
    override val URL_VERSION: Int = 1
    override var baseUrl: String = BASE_URL

    override var imgBaseUrl: String = BASE_URL
    override var imgUrlVersion: Int = 1

    override val PRIORITY: Int = 0
    override val blackListGenres: Set<String> = emptySet()
    override val sortTypes: Set<String> = emptySet()
    override val allGenres: Set<String> = emptySet()
    override val defaultHeaders: Map<String, String> = emptyMap()

    override suspend fun fetchSearchDataF(searchType: SearchType): Flow<State<List<MangaItem>>> = flow { }
    override fun fetchMangaHomeF(query: String): Flow<State<MutableList<MangaItem>>> = flow { }
    override suspend fun fetchMangaChaptersF(query: String): Flow<State<MangaInfo>> = flow { }
    override fun fetchChapterDataF(url: String): Flow<State<List<String>>> = flow { }
    override fun fetchMoreManga(page: Int, currentItems: List<MangaItem>?): Flow<State<List<MangaItem>>> = flow { }
    override suspend fun fetchPopularManga(baseUrl: String): Flow<State<List<PopularManga>>> = flow { }

    override fun buildImageRequest(context: Context, url: String, screenWidthPx: Int): ImageRequest =
        ImageRequest.Builder(context).data(url).build()

    override fun buildItemsImageRequest(context: Context, url: String, screenWidthPx: Int): ImageRequest =
        ImageRequest.Builder(context).data(url).build()

    override suspend fun refreshHeaders(newHeaders: Map<String, String>) { /* no-op for preview */ }
    override suspend fun getBaseUrl(): String = baseUrl
}

@Preview(showBackground = true, widthDp = 360)
@Composable
fun SourcesTabsPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            // sample repos (ICON = 0 will fall back to R.drawable.team_x in your SourcesTabs)
            val sample = listOf(
                FakeRepo(API = "MangaDex", ICON = 0),
                FakeRepo(API = "MangaPark", ICON = 0),
                FakeRepo(API = "Manganelo", ICON = 0),
                FakeRepo(API = "Local", ICON = R.drawable.team_x)
            )

            var active by remember { mutableStateOf(0) }

            SourcesTabs(
                tabs = sample,
                activeTabIndex = active,
                onTabSelected = { active = it },
                onEditTabs = { /* show edit sheet in real app */ },
                isNewSource = true
            )
        }
    }
}