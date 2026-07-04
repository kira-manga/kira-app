package me.manga.yamiapk.presentation.common.componants.images

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transformations
import me.manga.yamiapk.core.blur.BlurTransformation

@Composable
fun BlurredImageCoil(url: String, modifier: Modifier = Modifier,contentScale: ContentScale = ContentScale.Fit) {
    val context = LocalContext.current

    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(url)
            .transformations(BlurTransformation(context, radius = 25f, sampling = 8f))
            .build(),
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale
    )
}
