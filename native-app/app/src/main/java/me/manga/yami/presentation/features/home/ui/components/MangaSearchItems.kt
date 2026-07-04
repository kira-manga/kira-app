package me.manga.yamiapk.presentation.features.home.ui.components

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import me.manga.yamiapk.ad_mob.bannars.BannerAdView
import me.manga.yamiapk.ad_mob.util.ListEntryWithAd
import me.manga.yamiapk.di.coli.getImageLoader
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.presentation.features.home.data.ApiTitle

@Composable
fun MangaSearchItems(
    items:  List<MangaItem>,
    savedTitles: Set<ApiTitle>,
    onMangaSearchClick: (String, String,String, Boolean) -> Unit,
    buildImageRequest:(Context, String, String) -> ImageRequest

) {
    Column(modifier = Modifier.fillMaxSize()) {
        BannerAdView()

        // The grid of manga items
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items) { manga ->
                            SearchItems(
                                manga = manga,
                                savedTitles = savedTitles,
                                onMangaClick = onMangaSearchClick,
                                buildImageRequest = buildImageRequest
                            )





            }
        }

    }
}

@Composable
fun SearchItems(
    manga: MangaItem,
    savedTitles: Set<ApiTitle>,
    onMangaClick: ( String,String, String,Boolean) -> Unit,
    buildImageRequest:(Context, String, String) -> ImageRequest

) {

    val context = LocalContext.current

    Card(
        modifier = Modifier
            .padding(8.dp)
            .fillMaxWidth()
            .clickable { onMangaClick(manga.url, manga.api,manga.title,savedTitles.contains(ApiTitle(manga.api,manga.title))) },
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .height(250.dp)
        ) {
            AsyncImage(
                model =buildImageRequest(context,manga.imageUrl,manga.api),
                contentDescription = manga.title,
                contentScale = ContentScale.FillBounds,
                imageLoader = getImageLoader(),
                modifier = Modifier.fillMaxSize()
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                Spacer(modifier = Modifier.weight(1f))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .background(Color.Black.copy(alpha = 0.3f))
                    )
                    Text(
                        text = manga.title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .background(Color.Transparent)
                            .align(Alignment.Center)
                            .padding(vertical = 1.dp, horizontal = 4.dp)
                    )
                }
            }
        }
    }
}