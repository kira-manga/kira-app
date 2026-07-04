package me.manga.yamiapk.di.coli

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CoilEntryPoint {
    fun imageLoader(): ImageLoader
}

@Composable
fun getImageLoader(): ImageLoader {
    val context = LocalContext.current.applicationContext
    val entryPoint = EntryPointAccessors.fromApplication(
        context,
        CoilEntryPoint::class.java
    )
    return entryPoint.imageLoader()
}