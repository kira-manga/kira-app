package me.manga.yamiapk.presentation.features.library_details.ui.components

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import me.manga.yamiapk.R
import me.manga.yamiapk.data.local.entity.SavedMangaEntity
import me.manga.yamiapk.di.coli.getImageLoader


@Composable
 fun CoverImage(imageUrl: String, contentDescription: String) {

    AsyncImage(

        model = ImageRequest.Builder(LocalContext.current)
            .data(imageUrl)
            .crossfade(true)
            .build(),
        contentDescription = contentDescription,
        imageLoader = getImageLoader(),
        modifier = Modifier
            .size(200.dp, 250.dp)
            .clip(RoundedCornerShape(8.dp)),
        contentScale = ContentScale.Crop
    )
}


    @Composable
     fun TitleSection(title: String) {
        val clipboardManager = LocalClipboardManager.current
        val context = LocalContext.current

        Text(
            text = title,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            fontSize = 20.sp,
                    modifier = Modifier
                    .combinedClickable(
                    onClick = { /* if you want a normal click */ },
                        onLongClick = {
                            clipboardManager.setText(AnnotatedString(title))

                            Toast.makeText(
                                context,
                                context.getString(R.string.title_copied),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
        )
        )
    }

@Composable
 fun InfoSection(manga: SavedMangaEntity) {
    Text(
        text = "${manga.api} ${manga.language} - ${manga.status}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        textAlign = TextAlign.Center
    )
}
