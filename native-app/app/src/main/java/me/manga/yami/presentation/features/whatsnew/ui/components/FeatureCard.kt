package me.manga.yamiapk.presentation.features.whatsnew.ui.components

import AutoSubtitleText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.manga.yamiapk.presentation.features.whatsnew.model.MediaType
import me.manga.yamiapk.presentation.features.whatsnew.model.WhatsNewFeature

@Composable
fun FeatureCard(
    feature: WhatsNewFeature,
    isActive: Boolean,
    showSmallVideo: Boolean = true,
    isTablet: Boolean = false,
    isLandscape: Boolean = false,
    cardPadding: Dp = 24.dp,
    onMediaClick: (MediaType, Int?, String?, String?) -> Unit = { _, _, _, _ -> }
) {
    val mediaSize = when {
        isTablet && isLandscape -> 300.dp
        isTablet -> 280.dp
        isLandscape -> 180.dp
        else -> 220.dp
    }

    val maxCardHeight = if (isLandscape) 0.85f else 0.75f

    // Key to force recomposition when switching pages
    val cardKey = remember(isActive) { Any() }

    AnimatedVisibility(
        visible = isActive,
        enter = slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = tween(250)
        ) + fadeIn(animationSpec = tween(250)),
        exit = slideOutHorizontally(
            targetOffsetX = { -it },
            animationSpec = tween(250)
        ) + fadeOut(animationSpec = tween(250))
    ) {
        key(cardKey) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(maxCardHeight)
                    .padding(horizontal = cardPadding, vertical = 8.dp),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(cardPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    FeatureMedia(
                        feature = feature,
                        mediaSize = mediaSize,
                        showSmallVideo = showSmallVideo,
                        isActive = isActive,
                        onMediaClick = onMediaClick
                    )

                    AutoSubtitleText(
                        text = feature.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (isTablet) 32.sp else 26.sp,
                        maxSize = if (isTablet) 36.sp else 32.sp,
                        minSize = 20.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2
                    )

                    AutoSubtitleText(
                        text = feature.description,
                        fontSize = if (isTablet) 18.sp else 15.sp,
                        maxSize = if (isTablet) 20.sp else 18.sp,
                        minSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 15
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureMedia(
    feature: WhatsNewFeature,
    mediaSize: Dp,
    isActive: Boolean,
    showSmallVideo: Boolean,
    onMediaClick: (MediaType, Int?, String?, String?) -> Unit
) {
    when (feature.mediaType) {
        MediaType.IMAGE -> {
            when {
                feature.imageResList.isNotEmpty() -> {
                    ImageCarousel(
                        imageResList = feature.imageResList,
                        mediaSize = mediaSize,
                        onImageClick = { imageRes ->
                            onMediaClick(MediaType.IMAGE, imageRes, null, null)
                        }
                    )
                }
                feature.imageUrlList.isNotEmpty() -> {
                    ImageUrlsCarousel(
                        imageUrlList = feature.imageUrlList,
                        mediaSize = mediaSize,
                        onImageClick = { imageUrl ->
                            onMediaClick(MediaType.IMAGE, null, imageUrl, null)
                        }
                    )
                }
                feature.imageRes != null -> {
                    SingleImage(
                        imageRes = feature.imageRes,
                        title = feature.title,
                        mediaSize = mediaSize,
                        onImageClick = {
                            onMediaClick(MediaType.IMAGE, feature.imageRes, null, null)
                        }
                    )
                }
                feature.imageUrl != null -> {
                    SingleUrlImage(
                        imageUrl = feature.imageUrl,
                        title = feature.title,
                        mediaSize = mediaSize,
                        onImageUrlClick = {
                            onMediaClick(MediaType.IMAGE, null, feature.imageUrl, null)
                        }
                    )
                }
                else -> {
                    ImagePlaceholder(title = feature.title, mediaSize = mediaSize)
                }
            }
        }
        MediaType.VIDEO -> {
            if (feature.videoUrl != null && showSmallVideo) {
                SafeVideoPlayer(
                    videoUrl = feature.videoUrl,
                    isActive = isActive,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(mediaSize * 0.75f),
                    onFullscreenClick = {
                        onMediaClick(MediaType.VIDEO, null, null, feature.videoUrl)
                    }
                )
            } else {
                VideoPlaceholder(mediaSize = mediaSize)
            }
        }
    }
}