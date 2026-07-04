package me.manga.yamiapk.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
data class ChapterImage(
   val image: String,
    val index:Int
) : Parcelable

