package com.gpo.pilipala.ui.theme

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.core.util.Images

/** True when the resolved theme is dark (after the user's theme-mode override). */
val LocalPiliDark = staticCompositionLocalOf { true }

@Composable
fun piliIsDark(): Boolean {
    val mode by AppSettings.themeMode.state.collectAsState()
    return when (mode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
}

/**
 * App theme — Yoin's design language: M3 Expressive motion scheme, Google Sans
 * Flex type scale, continuous-corner shapes, and a color scheme that is
 * (a) the system dynamic palette by default, (b) a SPEC_2025 expressive scheme
 * from the user's seed otherwise. Every token change washes with the
 * effects spring (no hard cuts).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PiliTheme(
    seedOverride: Int? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = piliIsDark()
    val dynamic by AppSettings.dynamicColor.state.collectAsState()
    val seed by AppSettings.seedColor.state.collectAsState()

    val target: ColorScheme = remember(context, dark, dynamic, seed, seedOverride) {
        when {
            seedOverride != null -> ExpressiveColorSchemeFactory.fromSeed(seedOverride, dark)
            dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            else -> ExpressiveColorSchemeFactory.fromSeed(seed, dark)
        }
    }
    val motionScheme = MotionScheme.expressive()
    val colorScheme = animateColorScheme(
        targetColorScheme = target,
        darkTheme = dark,
        motionScheme = motionScheme,
    )
    CompositionLocalProvider(LocalYoinColors provides colorScheme, LocalPiliDark provides dark) {
        MaterialTheme(
            colorScheme = colorScheme,
            motionScheme = motionScheme,
            shapes = YoinShapes,
            typography = YoinTypography,
            content = content,
        )
    }
}

val LocalYoinColors = staticCompositionLocalOf<ColorScheme?> { null }

/**
 * Re-themes [content] from a cover image (Yoin's cover-driven palette), used
 * by video / bangumi / live detail pages. Falls back to the app theme until
 * the seed is extracted, then washes over with the effects spring.
 */
@Composable
fun CoverTheme(
    coverUrl: String?,
    enabled: Boolean = AppSettings.coverColor.state.collectAsState().value,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var seed by remember(coverUrl) { mutableStateOf<Int?>(null) }
    LaunchedEffect(coverUrl, enabled) {
        if (!enabled || coverUrl.isNullOrBlank()) {
            seed = null
            return@LaunchedEffect
        }
        val request = ImageRequest.Builder(context)
            .data(Images.thumb(coverUrl, 200))
            .allowHardware(false)
            .size(CoverSeedExtractor.BitmapSizePx)
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        val bitmap: Bitmap? = (result as? SuccessResult)?.image?.toBitmap()
        seed = CoverSeedExtractor.extractSeedArgb(bitmap)
    }
    PiliTheme(seedOverride = seed, content = content)
}
