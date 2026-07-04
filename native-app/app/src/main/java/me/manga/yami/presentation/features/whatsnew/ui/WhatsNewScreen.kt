package me.manga.yamiapk.presentation.features.whatsnew.ui

import AutoSubtitleText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.manga.yamiapk.R
import me.manga.yamiapk.presentation.features.whatsnew.model.WhatsNewFeature
import me.manga.yamiapk.presentation.features.whatsnew.model.MediaType
import me.manga.yamiapk.presentation.features.whatsnew.ui.components.FeatureCard
import me.manga.yamiapk.presentation.features.whatsnew.ui.components.FullscreenMediaViewer
import me.manga.yamiapk.presentation.features.whatsnew.ui.components.NavigationButtons
import me.manga.yamiapk.presentation.features.whatsnew.ui.components.PageIndicators
import me.manga.yamiapk.presentation.features.whatsnew.ui.components.WhatsNewHeader

@Composable
fun WhatsNewScreen(
    onDismiss: () -> Unit,
    features: List<WhatsNewFeature>
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val isTablet = configuration.screenWidthDp >= 600

    val pagerState = rememberPagerState(pageCount = { features.size })
    val coroutineScope = rememberCoroutineScope()
    val currentPage by remember { derivedStateOf { pagerState.currentPage } }

    var fullscreenMediaState by remember { mutableStateOf<FullscreenMediaState?>(null) }

    val horizontalPadding = if (isTablet) 32.dp else 16.dp
    val cardPadding = when {
        isTablet -> 32.dp
        isLandscape -> 16.dp
        else -> 24.dp
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            WhatsNewHeader(
                horizontalPadding = horizontalPadding,
                onDismiss = onDismiss
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                beyondViewportPageCount = 1,
                pageSpacing = 0.dp
            ) { page ->
                FeatureCard(
                    feature = features[page],
                    isActive = page == currentPage,
                    showSmallVideo = fullscreenMediaState == null,
                    isTablet = isTablet,
                    isLandscape = isLandscape,
                    cardPadding = cardPadding,
                    onMediaClick = { mediaType, imageRes, imageUrl, videoUrl ->
                        fullscreenMediaState = FullscreenMediaState(
                            mediaType = mediaType,
                            imageRes = imageRes,
                            imageUrl = imageUrl,
                            videoUrl = videoUrl
                        )
                    }
                )
            }

            PageIndicators(
                totalPages = features.size,
                currentPage = currentPage
            )

            NavigationButtons(
                currentPage = currentPage,
                totalPages = features.size,
                horizontalPadding = horizontalPadding,
                onPrevious = {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(currentPage - 1)
                    }
                },
                onNext = {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(currentPage + 1)
                    }
                },
                onDismiss = onDismiss
            )
        }

        fullscreenMediaState?.let { state ->
            FullscreenMediaViewer(
                mediaType = state.mediaType,
                imageRes = state.imageRes,
                imageUrl = state.imageUrl,
                videoUrl = state.videoUrl,
                onDismiss = { fullscreenMediaState = null }
            )
        }
    }
}

data class FullscreenMediaState(
    val mediaType: MediaType,
    val imageRes: Int? = null,
    val imageUrl: String? = null,
    val videoUrl: String? = null
)