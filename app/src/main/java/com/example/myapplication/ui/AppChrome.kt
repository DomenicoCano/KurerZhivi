package com.example.myapplication

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun LiveAmbientBackground(uiState: CourierUiState, content: @Composable BoxScope.() -> Unit) {
    var phase by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(uiState.ambientEnabled, uiState.ambientSpeed, uiState.showRegionDialog, uiState.showNetworkErrorDialog) {
        if (!uiState.ambientEnabled || uiState.ambientSpeed <= 0.05f || uiState.showRegionDialog || uiState.showNetworkErrorDialog) return@LaunchedEffect
        var lastMs = android.os.SystemClock.uptimeMillis()
        while (isActive) {
            delay(50L) // ~20 FPS: плавное фоновое дыхание без расхода батареи на 120 Гц экранах
            val nowMs = android.os.SystemClock.uptimeMillis()
            val dtSec = ((nowMs - lastMs) / 1000f).coerceIn(0f, 0.25f)
            lastMs = nowMs
            // Период 2π*20 кратен периодам всех множителей (1, 0.75, 0.6, 0.85) — без скачка при сбросе фазы
            phase = (phase + dtSec * uiState.ambientSpeed * 1.15f) % (2f * Math.PI.toFloat() * 20f)
        }
    }

    val bgColor = MaterialTheme.colorScheme.background
    val glowColor = uiState.scoreResult.color

    Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
        if (uiState.ambientEnabled) {
            val alphaMain = uiState.ambientBrightness.coerceIn(0.05f, 0.75f)
            val alphaSecond = (alphaMain * 0.55f).coerceIn(0.03f, 0.50f)
            val radiusMain = uiState.ambientRadius.coerceIn(600f, 2600f)

            // phase читается только в фазе рисования — анимация не вызывает рекомпозицию контента.
            // Координаты заданы долями размера экрана (раньше были «сырые» пиксели под ~1080 px).
            // Отдельный графический слой: покадровая перерисовка свечения не перерисовывает содержимое экрана
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer()
                    .drawBehind {
                        val w = size.width
                        val h = size.height
                        val scale = w / 1080f
                        val p = phase
                        val primaryX = w * 0.48f + cos(p) * w * 0.39f
                        val primaryY = h * 0.16f + sin(p * 0.75f) * h * 0.13f
                        val secondaryX = w * 0.48f + sin(p * 0.6f) * w * 0.43f
                        val secondaryY = h * 0.52f + cos(p * 0.85f) * h * 0.16f
                        drawRect(
                            Brush.radialGradient(
                                colors = listOf(glowColor.copy(alpha = alphaMain), Color.Transparent),
                                center = Offset(primaryX, primaryY),
                                radius = radiusMain * scale
                            )
                        )
                        drawRect(
                            Brush.radialGradient(
                                colors = listOf(glowColor.copy(alpha = alphaSecond), Color.Transparent),
                                center = Offset(secondaryX, secondaryY),
                                radius = radiusMain * 0.85f * scale
                            )
                        )
                    }
            )
        }
        // Содержимое — тоже в своём слое, чтобы анимация фона его не задевала
        Box(modifier = Modifier.fillMaxSize().graphicsLayer(), content = content)
    }
}

@Composable
fun DynamicAppTheme(themeMode: AppThemeMode, content: @Composable () -> Unit) {
    val isDark = when (themeMode) { AppThemeMode.SYSTEM -> isSystemInDarkTheme(); AppThemeMode.LIGHT -> false; AppThemeMode.DARK_AMOLED -> true }
    val colorScheme = if (isDark) {
        darkColorScheme(
            background = if (themeMode == AppThemeMode.DARK_AMOLED) Color.Black else Color(0xFF121212),
            surface = if (themeMode == AppThemeMode.DARK_AMOLED) Color(0xFF0D0D0D) else Color(0xFF1E1E1E),
            surfaceVariant = Color(0xFF222222),
            onBackground = Color.White, onSurface = Color.White, onSurfaceVariant = Color(0xFFD0D0D0), primary = Color(0xFF64B5F6)
        )
    } else lightColorScheme()
    MaterialTheme(colorScheme = colorScheme, content = content)
}
