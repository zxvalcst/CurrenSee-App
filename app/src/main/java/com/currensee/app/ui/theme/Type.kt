package com.currensee.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.currensee.app.R

private val Satoshi = FontFamily(
    Font(R.font.satoshi_regular, FontWeight.Normal),
    Font(R.font.satoshi_medium, FontWeight.Medium),
    Font(R.font.satoshi_bold, FontWeight.Bold)
)

val Typography = Typography(
    // Semua peran Material, termasuk Snackbar, menggunakan keluarga yang sama.
    displayLarge = TextStyle(fontFamily = Satoshi, fontWeight = FontWeight.Bold, fontSize = 57.sp, lineHeight = 64.sp),
    displayMedium = TextStyle(fontFamily = Satoshi, fontWeight = FontWeight.Bold, fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall = TextStyle(fontFamily = Satoshi, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineMedium = TextStyle(fontFamily = Satoshi, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
    titleMedium = TextStyle(fontFamily = Satoshi, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 26.sp),
    titleSmall = TextStyle(fontFamily = Satoshi, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontFamily = Satoshi, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    labelMedium = TextStyle(fontFamily = Satoshi, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    labelSmall = TextStyle(fontFamily = Satoshi, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    headlineLarge = TextStyle(
        fontFamily = Satoshi,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        lineHeight = 60.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = Satoshi,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),
    titleLarge = TextStyle(
        fontFamily = Satoshi,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = Satoshi,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 32.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Satoshi,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    labelLarge = TextStyle(
        fontFamily = Satoshi,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 30.sp
    )
)
