package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ============================================================================
// MAIN ACTIVITY (подсветка и тема — в ui/AppChrome.kt, экраны — в ui/*.kt)
// ============================================================================

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<CourierViewModel>()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tab = intent.getIntExtra(EXTRA_OPEN_TAB, -1)
        if (tab >= 0) viewModel.selectTab(tab)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialTab = intent?.getIntExtra(EXTRA_OPEN_TAB, -1) ?: -1
        if (initialTab >= 0) viewModel.selectTab(initialTab)

        // Автообновление: при возврате в приложение и раз в минуту, пока оно на экране
        // (само обновление происходит только если данные старше 15 минут)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    viewModel.reloadLive(updateExternal = false) // заказ мог быть добавлен с виджета/шторки
                    viewModel.tickMinute()
                    viewModel.refreshIfStale()
                    delay(60_000)
                }
            }
        }

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            // Иконки статус-бара/навигации должны следовать теме приложения, а не системной
            val isDarkTheme = when (uiState.themeMode) {
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK_AMOLED -> true
            }
            DisposableEffect(isDarkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { isDarkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF),
                        android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)
                    ) { isDarkTheme }
                )
                onDispose {}
            }
            // Снекбары (в т.ч. «Отменить») показываются поверх любого экрана
            val snackbar = remember { SnackbarHostState() }
            LaunchedEffect(Unit) {
                viewModel.events.collect { e ->
                    launch {
                        snackbar.currentSnackbarData?.dismiss()
                        when (e) {
                            is UiEvent.Message -> snackbar.showSnackbar(e.text)
                            is UiEvent.Undo -> {
                                val r = snackbar.showSnackbar(e.text, actionLabel = "Отменить", duration = SnackbarDuration.Long)
                                if (r == SnackbarResult.ActionPerformed) viewModel.undoLast()
                            }
                        }
                    }
                }
            }
            DynamicAppTheme(uiState.themeMode) {
                LiveAmbientBackground(uiState = uiState) {
                    when (uiState.currentScreen) {
                        Screen.MAIN -> MainScreen(viewModel, uiState)
                        Screen.SETTINGS -> { BackHandler { viewModel.navigateTo(Screen.MAIN) }; SettingsScreen(viewModel, uiState) }
                        Screen.DEVELOPER -> { BackHandler { viewModel.navigateTo(Screen.SETTINGS) }; DeveloperScreen(viewModel, uiState) }
                    }

                    SnackbarHost(
                        snackbar,
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = if (uiState.currentScreen == Screen.MAIN) 88.dp else 8.dp)
                    )

                    if (uiState.showNetworkErrorDialog) {
                        AlertDialog(
                            onDismissRequest = { viewModel.dismissNetworkErrorDialog() },
                            title = { Text("Новости не загрузились", color = MaterialTheme.colorScheme.onSurface) },
                            text = {
                                Text(
                                    if (uiState.safeSourcesEnabled) "Российские ленты (РИА, ТАСС, Лента, РБК) не ответили. Календарь спроса продолжает работать."
                                    else "Не ответили ни Google News, ни российские ленты. Если мобильный интернет работает по «белым спискам», включите этот режим в настройках.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            confirmButton = {
                                if (uiState.safeSourcesEnabled) TextButton(onClick = { viewModel.dismissNetworkErrorDialog() }) { Text("Понятно") }
                                else TextButton(onClick = { viewModel.setSafeSources(true); viewModel.dismissNetworkErrorDialog() }) { Text("Включить белые списки") }
                            },
                            dismissButton = {
                                if (!uiState.safeSourcesEnabled) TextButton(onClick = { viewModel.dismissNetworkErrorDialog() }) { Text("Позже") }
                            }
                        )
                    }
                }
            }
        }
    }
}
