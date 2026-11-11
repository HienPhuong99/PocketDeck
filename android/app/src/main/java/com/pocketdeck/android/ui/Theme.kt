package com.pocketdeck.android.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

val SlateBg = Color(0xFF0F172A)
val SlateCard = Color(0xFF1E293B)
val SlateCardElevated = Color(0xFF162033)
val SlateBorder = Color(0xFF334155)
val PrimaryBlue = Color(0xFF2563EB)
val AccentSky = Color(0xFF60A5FA)
val EmeraldOk = Color(0xFF10B981)
val AmberWarn = Color(0xFFF59E0B)
val DangerRed = Color(0xFFEF4444)
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)

private val PocketDeckColorScheme = darkColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1D4ED8),
    onPrimaryContainer = Color.White,
    secondary = AccentSky,
    onSecondary = SlateBg,
    background = SlateBg,
    onBackground = TextPrimary,
    surface = SlateCard,
    onSurface = TextPrimary,
    surfaceVariant = SlateCardElevated,
    onSurfaceVariant = TextSecondary,
    outline = SlateBorder,
    error = DangerRed,
)

val TabularTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontFeatureSettings = "tnum",
)

private val PocketDeckTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

@Composable
fun PocketDeckTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PocketDeckColorScheme,
        typography = PocketDeckTypography,
        content = content,
    )
}

@Composable
fun rememberQrBitmap(content: String, sizePx: Int = 512): ImageBitmap? {
    return remember(content, sizePx) {
        if (content.isBlank()) return@remember null
        runCatching {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1,
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val pixels = IntArray(sizePx * sizePx)
            val black = 0xFF0F172A.toInt()
            val white = 0xFFFFFFFF.toInt()
            for (y in 0 until sizePx) {
                val offset = y * sizePx
                for (x in 0 until sizePx) {
                    pixels[offset + x] = if (matrix.get(x, y)) black else white
                }
            }
            Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888).asImageBitmap()
        }.getOrNull()
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(java.util.Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(java.util.Locale.US, "%.2f GB", gb)
}

fun formatSpeed(bps: Double): String {
    if (bps <= 1.0) return "0 KB/s"
    return "${formatBytes(bps.toLong())}/s"
}

fun formatEta(seconds: Double?): String {
    if (seconds == null || seconds.isNaN() || seconds.isInfinite()) return "--:--"
    val s = seconds.toInt().coerceAtLeast(0)
    val m = s / 60
    val rem = s % 60
    return "%02d:%02d".format(m, rem)
}
