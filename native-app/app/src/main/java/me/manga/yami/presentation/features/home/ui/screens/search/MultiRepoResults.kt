    package me.manga.yamiapk.presentation.features.home.ui.screens.search
    import android.content.Context
    import android.util.Log
    import androidx.compose.foundation.background
    import androidx.compose.foundation.clickable
    import androidx.compose.foundation.layout.Arrangement
    import androidx.compose.foundation.layout.Box
    import androidx.compose.foundation.layout.Column
    import androidx.compose.foundation.layout.PaddingValues
    import androidx.compose.foundation.layout.fillMaxSize
    import androidx.compose.foundation.layout.fillMaxWidth
    import androidx.compose.foundation.layout.height
    import androidx.compose.foundation.layout.padding
    import androidx.compose.foundation.layout.width
    import androidx.compose.foundation.lazy.LazyColumn
    import androidx.compose.foundation.lazy.LazyRow
    import androidx.compose.foundation.lazy.items
    import androidx.compose.foundation.shape.RoundedCornerShape
    import androidx.compose.material3.Card
    import androidx.compose.material3.CardDefaults
    import androidx.compose.material3.CircularProgressIndicator
    import androidx.compose.material3.MaterialTheme
    import androidx.compose.material3.Text
    import androidx.compose.runtime.Composable
    import androidx.compose.runtime.rememberCoroutineScope
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.graphics.Color
    import androidx.compose.ui.layout.ContentScale
    import androidx.compose.ui.platform.LocalContext
    import androidx.compose.ui.text.style.TextOverflow
    import androidx.compose.ui.tooling.preview.Preview
    import androidx.compose.ui.unit.dp
    import coil3.compose.AsyncImage
    import coil3.request.ImageRequest
    import kotlinx.coroutines.launch
    import me.manga.yamiapk.ad_mob.bannars.BannerAdView
    import me.manga.yamiapk.core.states.State
    import me.manga.yamiapk.domain.model.MangaItem
    import me.manga.yamiapk.presentation.features.home.data.ApiTitle
    import me.manga.yamiapk.theme.YamiMangaTheme


    @Composable
    fun MultiRepoResults(
        multiSearchState: Map<String, State<List<MangaItem>>>,
        savedTitles: Set<ApiTitle>,
        onMangaClick: (String, String, String,Boolean) -> Unit,
        buildImageRequest: (Context, String, String) -> ImageRequest,
        onRepoChange:suspend (String) -> Unit

    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // 🔒 FIXED BANNER
            BannerAdView()

            // 🔄 SCROLLABLE CONTENT
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                multiSearchState.forEach { (apiName, state) ->
                    item {
                        RepoSection(
                            apiName = apiName,
                            state = state,
                            savedTitles = savedTitles,
                            onMangaClick = onMangaClick,
                            buildImageRequest = buildImageRequest,
                            onRepoChange = onRepoChange
                        )
                    }
                }
            }
        }

    }


    @Composable
    fun RepoSection(
        apiName: String,
        state: State<List<MangaItem>>,
        savedTitles: Set<ApiTitle>,
        onMangaClick: (String, String, String,Boolean) -> Unit,
        buildImageRequest: (Context, String, String) -> ImageRequest,
        onRepoChange: suspend (String) -> Unit

    ) {
        Text(
            text = apiName,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        when (state) {
            is State.Loading -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
            is State.Error -> {
                Text(
                    text = "Error: ${state.message}",
                    color = MaterialTheme.colorScheme.error
                )
            }
            is State.Success -> {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    items(state.data) { manga ->
                        MultiSearchItem(
                            manga = manga,
                            savedTitles = savedTitles,

                            onMangaClick = onMangaClick,
                            buildImageRequest = buildImageRequest,
                            onRepoChange = onRepoChange
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun MultiSearchItem(
        manga: MangaItem,
        savedTitles: Set<ApiTitle>,
        onMangaClick: (String, String, String,Boolean) -> Unit,
        buildImageRequest: (Context, String, String) -> ImageRequest,
        onRepoChange: suspend (String) -> Unit
    ) {
        val context = LocalContext.current

        val coroutineScope = rememberCoroutineScope()
        Card(
            modifier = Modifier
                .width(140.dp)
                .height(200.dp)
                .clickable {
                    coroutineScope.launch {
//
//                        onRepoChange(manga.api)

                        onMangaClick(manga.url, manga.api, manga.title,savedTitles.contains(ApiTitle(manga.api,manga.title)))
                    }

                },
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Box {
                AsyncImage(
                    model = buildImageRequest(context, manga.imageUrl, manga.api),
                    contentDescription = manga.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // **Always** overlay the title in top-left
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(bottomEnd = 4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = manga.title,
                        style = MaterialTheme.typography.bodySmall.copy(color = Color.White),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Bookmark icon at top-right if saved

            }
        }
    }
    @Preview(showBackground = true, widthDp = 360, heightDp = 640)
    @Composable
    fun MultiRepoResultsPreview() {
        // Sample image‐request builder that does nothing in Preview
        val fakeImageRequest: (Context, String, String) -> ImageRequest = { ctx, url, api ->
            ImageRequest.Builder(ctx).data(url).build()
        }

        // Make a few dummy MangaItems
        val sampleList = listOf(
            MangaItem(
                api = "Repo A", title = "Manga Alpha", url = "", imageUrl = "",
                language = "ar",
                rating = 0,
                chapters = listOf(),
                genres = listOf()
            ),
            MangaItem(api = "Repo A", title = "Manga Beta",  url = "", imageUrl = "",
                language = "ar",
                rating = 0,
                chapters = listOf(),
                genres = listOf()),

            MangaItem(api = "Repo A", title = "Manga Beta",  url = "", imageUrl = "",
                language = "ar",
                rating = 0,
                chapters = listOf(),
                genres = listOf()),
            MangaItem(api = "Repo A", title = "Manga Beta",  url = "", imageUrl = "",
                language = "ar",
                rating = 0,
                chapters = listOf(),
                genres = listOf()),
            MangaItem(api = "Repo A", title = "Manga Beta",  url = "", imageUrl = "",
                language = "ar",
                rating = 0,
                chapters = listOf(),
                genres = listOf()),
            MangaItem(api = "Repo A", title = "Manga Beta",  url = "", imageUrl = "",
                language = "ar",
                rating = 0,
                chapters = listOf(),
                genres = listOf()),
            MangaItem(api = "Repo A", title = "Manga Beta",  url = "", imageUrl = "",
                language = "ar",
                rating = 0,
                chapters = listOf(),
                genres = listOf()),
            MangaItem(api = "Repo A", title = "Manga Gamma", url = "", imageUrl = "",
                language = "ar",
                rating = 0,
                chapters = listOf(),
                genres = listOf())
        )

        // Build a fake multi‐repo state map
        val multiSearchState: Map<String, State<List<MangaItem>>> = mapOf(
            // Simulate a repo still loading
            "Repo Loading" to State.
            Loading,

            // Simulate a repo that failed
            "Repo Error" to State.Loading,
//            Error(code = 404, message = "Not Found"),

            // Simulate a repo that succeeded
            "Repo Success" to State.Loading,
//            Success(sampleList)

            "Repo Error1" to State.Loading,

            "Repo Error2" to State.Loading,

            "Repo Error3" to State.Loading,

            )

        // No saved titles in preview
        val savedTitles = emptySet<ApiTitle>()

        YamiMangaTheme(darkTheme = true)  {
            MultiRepoResults(
                multiSearchState = multiSearchState,
                savedTitles = savedTitles,
                onMangaClick     = { api, title, chapters,f  -> /* no-op */ },
                buildImageRequest= fakeImageRequest,
                onRepoChange = {}
            )
        }
    }