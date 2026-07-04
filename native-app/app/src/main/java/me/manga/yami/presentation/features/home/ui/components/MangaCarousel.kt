package me.manga.yamiapk.presentation.features.home.ui.components

import android.content.Context
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.HorizontalUncontainedCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import me.manga.yamiapk.di.coli.getImageLoader
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.features.home.data.ApiTitle


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaCarousel(
    items: List<PopularManga>,
    savedTitles: Set<ApiTitle>,
    onItemClick: (String, String, String,Boolean) -> Unit,
    buildImageRequest:(Context, String, String) -> ImageRequest

) {
    if (items.isEmpty()) return  // <-- guard against empty list

    val carouselState = rememberCarouselState { items.size }
    val context = LocalContext.current

    HorizontalUncontainedCarousel(
        state = carouselState,
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        itemWidth = 150.dp,
        itemSpacing = 16.dp,
        flingBehavior = CarouselDefaults.singleAdvanceFlingBehavior(
            carouselState,
            snapAnimationSpec = spring()
        )
    ) { page: Int ->


        if (page !in items.indices) return@HorizontalUncontainedCarousel
        val manga = items.getOrNull(page) ?: return@HorizontalUncontainedCarousel

        // Access carouselItemInfo from receiver
        val info = carouselItemDrawInfo // CarouselItemDrawInfo
        val rawScale = if (info.maxSize > 0f) info.size / info.maxSize else 1f

        val targetScale = rawScale.takeIf { it.isFinite() } ?: 1f
        val scale by animateFloatAsState(
            targetValue = targetScale,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
        )

        Card(
            shape = RoundedCornerShape(12.dp),
            elevation = 6.dp,
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clickable { onItemClick(manga.url, manga.api,manga.title,savedTitles.contains(
                    ApiTitle(manga.api,manga.title))) }
        ) {

            AsyncImage(
                model = buildImageRequest(context, manga.imageUrl, manga.api),
                imageLoader = getImageLoader(),

                contentDescription = manga.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
