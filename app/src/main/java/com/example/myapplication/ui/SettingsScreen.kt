package com.example.myapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// ============================================================================
// НАСТРОЙКИ (С ПОЛЗУНКАМИ ЖИВОГО ГРАДИЕНТА), БЭКАП И СЕКРЕТНОЕ МЕНЮ
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: CourierViewModel, uiState: CourierUiState) {
    val context = LocalContext.current
    var hasNotificationPermission by remember {
        mutableStateOf(if (Build.VERSION.SDK_INT >= 33) ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED else true)
    }
    var pendingToggle by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasNotificationPermission = granted
        if (granted) {
            if (pendingToggle == "daily") viewModel.setDailyNotif(true, context)
            if (pendingToggle == "extreme") viewModel.setExtremeNotif(true, context)
        }
        pendingToggle = null
    }

    // Бэкап через системный выбор файла (Storage Access Framework) — разрешения на хранилище не нужны
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.exportBackupTo(context, uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importBackupFrom(context, uri)
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.exportCsvTo(context, uri)
    }
    // Папка для автобэкапа: доступ закрепляется навсегда (переживает перезагрузку)
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                viewModel.enableAutoBackup(context, uri)
            } catch (_: Exception) {
                Toast.makeText(context, "Не удалось получить доступ к папке", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var showDevLogin by remember { mutableStateOf(false) }
    var devPassword by remember { mutableStateOf("") }

    if (showDevLogin) {
        AlertDialog(
            onDismissRequest = { showDevLogin = false },
            title = { Text("Вход разработчика", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                OutlinedTextField(
                    value = devPassword, onValueChange = { devPassword = it },
                    label = { Text("Пароль") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (sha256Hex(devPassword) == DEV_PASSWORD_SHA256) { showDevLogin = false; viewModel.navigateTo(Screen.DEVELOPER) }
                    else Toast.makeText(context, "Неверный пароль", Toast.LENGTH_SHORT).show()
                }) { Text("Войти") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки", color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = { IconButton(onClick = { viewModel.navigateTo(Screen.MAIN) }) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад", tint = MaterialTheme.colorScheme.onSurface) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())) {
            Text("СЕТЬ И ИСТОЧНИКИ", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Белые списки (мобильный интернет без VPN)", fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        "Только российские источники, без ожидания заблокированных: погода — Яндекс Погода (сайт, ключ не нужен) и Рамблер.Погода, " +
                            "новости — РИА, ТАСС, Лента, РБК, РГ, Коммерсантъ, Рамблер, МК, АиФ. Если зарубежные сервисы не отвечают, " +
                            "приложение переключается на российские и без этого режима, но с режимом обновление быстрее.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = uiState.safeSourcesEnabled, onCheckedChange = { viewModel.setSafeSources(it) })
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("ВНЕШНИЙ ВИД И ЖИВОЙ ГРАДИЕНТ", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Живая градиентная подсветка", fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
                    Text("Плавное свечение под цвет текущего балла спроса", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = uiState.ambientEnabled, onCheckedChange = { viewModel.setAmbient(it) })
            }

            if (uiState.ambientEnabled) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Яркость свечения: ${(uiState.ambientBrightness * 100).roundToInt()}%",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Slider(
                            value = uiState.ambientBrightness,
                            onValueChange = { viewModel.setAmbientBrightness(it) },
                            valueRange = 0.05f..0.70f
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (uiState.ambientSpeed <= 0.05f) "Скорость движения: Статично (Пауза)"
                            else "Скорость перемещения: ${String.format(Locale.US, "%.1fx", uiState.ambientSpeed)}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Slider(
                            value = uiState.ambientSpeed,
                            onValueChange = { viewModel.setAmbientSpeed(it) },
                            valueRange = 0.0f..3.0f
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Радиус (размер) ореола: ${uiState.ambientRadius.roundToInt()} px",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Slider(
                            value = uiState.ambientRadius,
                            onValueChange = { viewModel.setAmbientRadius(it) },
                            valueRange = 600f..2600f
                        )
                    }
                }
            }

            listOf(AppThemeMode.SYSTEM to "Как в системе", AppThemeMode.LIGHT to "Светлая тема", AppThemeMode.DARK_AMOLED to "Тёмная (AMOLED)").forEach { (mode, label) ->
                Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.setTheme(mode) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = uiState.themeMode == mode, onClick = { viewModel.setTheme(mode) })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = label, color = MaterialTheme.colorScheme.onBackground)
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("УВЕДОМЛЕНИЯ", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Ежедневный прогноз", fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
                    Text("Присылать утреннюю сводку и час пика", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = uiState.notifEnabled,
                    onCheckedChange = {
                        if (it && !hasNotificationPermission && Build.VERSION.SDK_INT >= 33) { pendingToggle = "daily"; permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                        else viewModel.setDailyNotif(it, context)
                    }
                )
            }
            if (uiState.notifEnabled) {
                var timeMenuExpanded by remember { mutableStateOf(false) }
                Row(modifier = Modifier.fillMaxWidth().clickable { timeMenuExpanded = true }.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Время уведомления", color = MaterialTheme.colorScheme.onBackground)
                    Box {
                        Text(String.format(Locale.getDefault(), "%02d:%02d", uiState.notifHour, uiState.notifMinute), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        DropdownMenu(expanded = timeMenuExpanded, onDismissRequest = { timeMenuExpanded = false }) {
                            listOf(6, 7, 8, 9, 10, 11).forEach { h ->
                                DropdownMenuItem(text = { Text("$h:00", color = MaterialTheme.colorScheme.onSurface) }, onClick = { viewModel.setNotifTime(h, 0, context); timeMenuExpanded = false })
                            }
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Об экстремальном спросе", fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
                    Text("При балле ≥85 или ≤25 (не чаще раза в 3 ч.)", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = uiState.extremeNotifEnabled,
                    onCheckedChange = {
                        if (it && !hasNotificationPermission && Build.VERSION.SDK_INT >= 33) { pendingToggle = "extreme"; permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                        else viewModel.setExtremeNotif(it, context)
                    }
                )
            }
            if (uiState.extremeNotifEnabled) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Тихие часы (23:00–07:00)", fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
                        Text("Ночью экстремальные уведомления не приходят", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = uiState.quietHoursEnabled, onCheckedChange = { viewModel.setQuietHours(it) })
                }
                listOf(NotificationTone.NORMAL to "Обычный (Спокойный)", NotificationTone.MAT to "Жесткий (С матом)").forEach { (tone, label) ->
                    Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.setTone(tone) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = uiState.notifTone == tone, onClick = { viewModel.setTone(tone) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = label, color = MaterialTheme.colorScheme.onBackground)
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("ДЕНЬГИ И НАЛОГ", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
            Text("Налог самозанятого (НПД) вычитается из «чистыми» при сохранении смены.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            listOf(0 to "Не учитывать", 4 to "4% — оплата от физлиц", 6 to "6% — от компаний и ИП").forEach { (rate, label) ->
                Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.setTaxRate(rate) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = uiState.taxRate == rate, onClick = { viewModel.setTaxRate(rate) })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(label, color = MaterialTheme.colorScheme.onBackground)
                }
            }
            OutlinedButton(
                onClick = { csvLauncher.launch("kurer-smeny-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.csv") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            ) { Text("Экспорт смен в таблицу (CSV для Excel)") }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("РЕЗЕРВНОЕ КОПИРОВАНИЕ (БЭКАП)", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp))
            Text("Сохранение смен, домофонов и настроек в файл (JSON) — его можно отправить в облако или мессенджер. Ключ Яндекс.Погоды в файл не попадает.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("kurer-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.json") }, modifier = Modifier.weight(1f)) { Text("Сохранить в файл") }
                Button(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.weight(1f)) { Text("Восстановить из файла") }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Автобэкап раз в неделю", fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
                    val status = when {
                        uiState.autoBackupError != null && uiState.autoBackupEnabled -> "Ошибка: ${uiState.autoBackupError}"
                        uiState.autoBackupLastMs > 0 -> "Последний: ${SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(uiState.autoBackupLastMs))}. Хранятся 5 последних файлов."
                        else -> "В выбранную папку (например, синхронизируемую с облаком)"
                    }
                    Text(status, fontSize = 12.sp, color = if (uiState.autoBackupError != null && uiState.autoBackupEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = uiState.autoBackupEnabled, onCheckedChange = { on -> if (on) folderLauncher.launch(null) else viewModel.disableAutoBackup(context) })
            }
            if (uiState.autoBackupEnabled) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.backupNow(context) }, modifier = Modifier.weight(1f)) { Text("Сделать сейчас") }
                    OutlinedButton(onClick = { folderLauncher.launch(null) }, modifier = Modifier.weight(1f)) { Text("Другая папка") }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            val uriHandler = LocalUriHandler.current
            Text("РАЗРАБОТЧИК", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp))
            Row(modifier = Modifier.fillMaxWidth().clickable { uriHandler.openUri("https://t.me/Komodist") }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Send, contentDescription = null, tint = Color(0xFF2CA5E0), modifier = Modifier.size(32.dp).padding(end = 8.dp))
                Column {
                    Text("Мой Telegram канал", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                    Text("@Komodist", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "Справочник городов России: hflabs/city, CC BY-SA 4.0 • Погода: Open-Meteo, Яндекс Погода, Рамблер.Погода",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            TextButton(onClick = { devPassword = ""; showDevLogin = true }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Секретное меню", color = Color.Gray)
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperScreen(viewModel: CourierViewModel, uiState: CourierUiState) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Инструменты разработчика", color = MaterialTheme.colorScheme.error) },
                navigationIcon = { IconButton(onClick = { viewModel.navigateTo(Screen.SETTINGS) }) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад", tint = MaterialTheme.colorScheme.onSurface) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())) {
            Text("ИНТЕГРАЦИИ API", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("API Ключ 'Яндекс Погода'", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text("Ключ API Погоды (v3, кабинет yandex.ru/pogoda/b2b) или старый ключ «Погода на вашем сайте». Нужен для режима белых списков: Open-Meteo через них не проходит. Если пусто — используется Open-Meteo.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                    var yandexKey by remember { mutableStateOf(uiState.yandexKey) }
                    OutlinedTextField(value = yandexKey, onValueChange = { yandexKey = it }, modifier = Modifier.fillMaxWidth(), label = { Text("API Ключ") }, singleLine = true)
                    Button(onClick = { viewModel.setYandexKey(yandexKey); Toast.makeText(context, "Сохранено", Toast.LENGTH_SHORT).show() }, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) { Text("Применить") }
                }
            }

            Text("ТЕСТ УВЕДОМЛЕНИЙ", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
            Button(onClick = { sendNotification(context, "ТЕСТ", extremeHighNormalMessages.random()) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text("Экстремальный спрос (Обычный)") }
            Button(onClick = { sendNotification(context, "ТЕСТ", extremeHighMatMessages.random()) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("Экстремальный спрос (МАТ)") }
            Button(onClick = { sendNotification(context, "ТЕСТ", extremeLowNormalMessages.random()) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text("Мертвая зона (Обычный)") }
            Button(onClick = { sendNotification(context, "ТЕСТ", extremeLowMatMessages.random()) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("Мертвая зона (МАТ)") }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
