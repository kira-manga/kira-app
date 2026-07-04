package me.manga.yamiapk.presentation.features.whatsnew.ui.components


import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.launch
import me.manga.yamiapk.R

@Composable
fun ImageCarousel(
    imageResList: List<Int>,
    mediaSize: Dp = 220.dp,
    onImageClick: (Int) -> Unit = {}
) {
    var selectedImageIndex by remember { mutableIntStateOf(0) }
    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Image(
            painter = painterResource(id = imageResList[selectedImageIndex]),
            contentDescription = stringResource(R.string.feature_image),
            modifier = Modifier
                .size(mediaSize)
                .clip(RoundedCornerShape(16.dp))
                .clickable { onImageClick(imageResList[selectedImageIndex]) },
            contentScale = ContentScale.Fit
        )

        if (imageResList.size > 1) {
            LazyRow(
                state = lazyListState,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                itemsIndexed(
                    items = imageResList,
                    key = { _, item -> item }
                ) { index, imageRes ->
                    val isSelected = index == selectedImageIndex

                    Image(
                        painter = painterResource(id = imageRes),
                        contentDescription = stringResource(R.string.thumbnail, index + 1),
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                selectedImageIndex = index
                                coroutineScope.launch {
                                    lazyListState.animateScrollToItem(index)
                                }
                            }
                            .then(
                                if (isSelected) {
                                    Modifier.border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                } else Modifier
                            )
                            .padding(if (isSelected) 2.dp else 0.dp),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}

@Composable
fun ImageUrlsCarousel(
    imageUrlList: List<String>,
    mediaSize: Dp = 220.dp,
    onImageClick: (String) -> Unit = {}
) {
    if (imageUrlList.isEmpty()) return

    var selectedImageIndex by rememberSaveable { mutableIntStateOf(0) }
    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageUrlList[selectedImageIndex])
                .crossfade(true)
                .build(),
            contentDescription = "Feature image",
            modifier = Modifier
                .size(mediaSize)
                .clip(RoundedCornerShape(16.dp))
                .clickable { onImageClick(imageUrlList[selectedImageIndex]) },
            contentScale = ContentScale.Fit,
            loading = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        strokeWidth = 3.dp
                    )
                }
            },
            error = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Failed to load",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        )

        if (imageUrlList.size > 1) {
            LazyRow(
                state = lazyListState,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                itemsIndexed(
                    items = imageUrlList,
                    key = { _, url -> url }
                ) { index, url ->
                    val isSelected = index == selectedImageIndex

                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .then(
                                if (isSelected) {
                                    Modifier
                                        .border(
                                            width = 2.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .padding(2.dp)
                                } else Modifier
                            )
                            .clickable {
                                selectedImageIndex = index
                                coroutineScope.launch {
                                    lazyListState.animateScrollToItem(index)
                                }
                            }
                    ) {
                        SubcomposeAsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(url)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Thumbnail ${index + 1}",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                            loading = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SingleImage(
    imageRes: Int,
    title: String,
    mediaSize: Dp = 220.dp,
    onImageClick: () -> Unit = {}
) {
    Image(
        painter = painterResource(id = imageRes),
        contentDescription = title,
        modifier = Modifier
            .size(mediaSize)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onImageClick() },
        contentScale = ContentScale.Fit
    )
}

@Composable
fun SingleUrlImage(
    imageUrl: String,
    title: String,
    mediaSize: Dp = 220.dp,
    onImageUrlClick: () -> Unit = {}
) {
    SubcomposeAsyncImage(
        model = imageUrl,
        contentDescription = title,
        modifier = Modifier
            .size(mediaSize)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onImageUrlClick() },
        contentScale = ContentScale.Fit,
        loading = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    strokeWidth = 3.dp
                )
            }
        },
        error = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Failed to load",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    )
}

@Composable
fun ImagePlaceholder(
    title: String,
    mediaSize: Dp = 220.dp
) {
    Box(
        modifier = Modifier
            .size(mediaSize)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title.take(2).uppercase(),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold
        )
    }
}