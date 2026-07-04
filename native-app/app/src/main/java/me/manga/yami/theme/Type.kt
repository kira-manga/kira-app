package me.manga.yamiapk.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import me.manga.yamiapk.R

// Set of Material typography styles to start with
val GellixFontFamily = FontFamily(
    Font(R.font.gellix_regular, weight = FontWeight.Normal),
    Font(R.font.gellix_semibold,  weight = FontWeight.Medium),
    Font(R.font.gellix_bold,    weight = FontWeight.Bold)
)

val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = GellixFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp
    ),
    titleMedium = TextStyle(
        fontFamily = GellixFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp
    ),
    titleSmall = TextStyle(
        fontFamily = GellixFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp
    ),

    // …and so on (or omit and just rely on all the built-in defaults)
)
