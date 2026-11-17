package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

// ============================================================================
// ВКЛАДКА 2: СМЕНА И ФИНАНСЫ — цель, таймер с паузой, «+ Заказ», расходы, история
// ============================================================================

/** Сервисы для быстрого выбора; к ним добавляются уже использованные пользователем. */
private val DEFAULT_SERVICES = listOf("Яндекс Еда", "Самокат", "Купер", "Магнит", "Достависта")

private fun money(v: Double) = if (v == v.toInt().toDouble()) v.toInt().toString() else String.format(Locale.US, "%.1f", v)

private fun sanitizeDecimal(s: String): String {
    val normalized = s.replace(',', '.')
    val sb = StringBuilder()
    var dotSeen = false
    for (c in normalized) {
        if (c.isDigit()) sb.append(c)
        else if (c == '.' && !dotSeen) { sb.append('.'); dotSeen = true }
    }
    return sb.toString().take(6)
}

private fun sameCalendarDay(aMs: Long, bMs: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = aMs }
    val cb = Calendar.getInstance().apply { timeInMillis = bMs }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

private fun utcDateToLocalNoon(utcMillis: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 12, 0, 0)
    }.timeInMillis
}

@Composable
fun FinancePage(viewModel: CourierViewModel, uiState: CourierUiState) {
    val context = LocalContext.current
    var shiftDialog by remember { mutableStateOf<ShiftDialogMode?>(null) }
    var showQuickOrderDialog by remember { mutableStateOf(false) }
    var showGoalDialog by remember { mutableStateOf(false) }

    var askedNotifOnce by remember { mutableStateOf(false) }
    var pendingActionAfterPerm by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        pendingActionAfterPerm?.invoke()
        pendingActionAfterPerm = null
    }
    fun runWithShiftNotificationPerm(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !askedNotifOnce &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotifOnce = true
            pendingActionAfterPerm = action
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    // Тик раз в 15 с, когда таймер смены активен и не на паузе
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(uiState.shiftStartMs, uiState.shiftPausedAtMs) {
        nowMs = System.currentTimeMillis()
        if (uiState.shiftStartMs > 0L && uiState.shiftPausedAtMs == 0L) {
            while (true) {
                delay(15_000)
                nowMs = System.currentTimeMillis()
            }
        }
    }

    // Цель — на МЕСЯЦ: смены текущего месяца + текущая смена (за вычетом налога)
    val monthTotals = remember(uiState.shifts, uiState.currentDateStr) { totalsOf(shiftsOfMonth(uiState.shifts)) }
    val liveNet = uiState.liveShiftGross - taxFor(uiState.liveShiftGross, uiState.taxRate)
    val totalNet = monthTotals.net + liveNet
    val totalOrders = monthTotals.orders + uiState.liveShiftOrders
    val goalProgress = (totalNet.toFloat() / uiState.monthlyGoal.coerceAtLeast(1)).coerceIn(0f, 1f)
    val remainingToGoal = (uiState.monthlyGoal - totalNet).coerceAtLeast(0)
    val neededPerDay = remember(uiState.monthlyGoal, totalNet, uiState.currentDateStr) { neededPerDay(uiState.monthlyGoal, totalNet) }
    val forecast = remember(totalNet, monthTotals.shifts, uiState.liveShiftOrders, uiState.currentDateStr) {
        smartMonthForecast(totalNet, monthTotals.shifts + if (uiState.liveShiftOrders > 0) 1 else 0)
    }
    val services = remember(uiState.shifts) { (uiState.shifts.map { it.service.trim() }.filter { it.isNotBlank() } + DEFAULT_SERVICES).distinct().take(8) }

    if (showQuickOrderDialog) QuickOrderDialog(uiState.lastOrderPrice, onDismiss = { showQuickOrderDialog = false }) { sum, tips ->
        showQuickOrderDialog = false
        if (uiState.shiftStartMs == 0L) {
            runWithShiftNotificationPerm { viewModel.addQuickOrder(sum, tips) }
        } else {
            viewModel.addQuickOrder(sum, tips)
        }
    }

    shiftDialog?.let { mode ->
        ShiftDialog(mode, uiState, services, nowMs, onDismiss = { shiftDialog = null }) { h, g, fuel, repair, other, km, orders, tips, service, chosenDateMs, taxRatePct ->
            when (mode) {
                is ShiftDialogMode.Edit -> viewModel.updateShift(mode.shift, h, g, fuel, repair, other, km, orders, tips, service, taxRatePct)
                is ShiftDialogMode.New -> viewModel.addShift(
                    h, g, fuel, repair, other, km, orders, tips, service,
                    clearLive = mode.fromLive, chosenDateMs = chosenDateMs, taxRateOverride = taxRatePct
                )
                is ShiftDialogMode.Expense -> viewModel.addShift(
                    0.0, 0, fuel, repair, other, km, 0, 0, service,
                    clearLive = false, chosenDateMs = chosenDateMs, taxRateOverride = 0
                )
            }
            shiftDialog = null
        }
    }

    if (showGoalDialog) {
        var goalInput by remember { mutableStateOf(uiState.monthlyGoal.toString()) }
        AlertDialog(
            onDismissRequest = { showGoalDialog = false },
            title = { Text("Цель на месяц (₽)") },
            text = { OutlinedTextField(value = goalInput, onValueChange = { goalInput = it.filter(Char::isDigit).take(7) }, label = { Text("Сумма чистыми") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true) },
            confirmButton = { Button(onClick = { goalInput.toIntOrNull()?.let { viewModel.setMonthlyGoal(it) }; showGoalDialog = false }) { Text("ОК") } }
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Финансовая цель", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { showGoalDialog = true }) { Text("Изменить цель") }
                    }
                    Text("$totalNet ₽ из ${uiState.monthlyGoal} ₽ (${(goalProgress * 100).roundToInt()}%)", fontWeight = FontWeight.Black, fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { goalProgress }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape), color = Color(0xFF00E676), trackColor = MaterialTheme.colorScheme.surfaceVariant)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        if (remainingToGoal > 0) "До цели на месяц осталось $remainingToGoal ₽ (нужно делать по $neededPerDay ₽ в день)"
                        else "🎉 Месячная финансовая цель полностью выполнена!",
                        fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        color = if (remainingToGoal > 0) MaterialTheme.colorScheme.onSurface else Color(0xFF00E676)
                    )
                    if (forecast != null) {
                        val onTrack = forecast >= uiState.monthlyGoal
                        Text(
                            "📈 При текущем темпе за месяц выйдет ≈ $forecast ₽" + if (onTrack) " — цель будет выполнена" else " — не хватит ${uiState.monthlyGoal - forecast} ₽",
                            fontSize = 13.sp, color = if (onTrack) Color(0xFF00E676) else Color(0xFFFFAB40), modifier = Modifier.padding(top = 4.dp)
                        )
                    } else if (totalNet > 0) {
                        Text(
                            "📈 Прогноз за месяц появится после 2+ смен или с 3-го числа месяца",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Чистыми в час: ${monthTotals.perHour} ₽/ч", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Заказов: $totalOrders • Часов: ${String.format(Locale.US, "%.1f", monthTotals.hours)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (monthTotals.tips > 0 || monthTotals.tax > 0) {
                        Text(
                            listOfNotNull(
                                if (monthTotals.tips > 0) "Чаевые за месяц: ${monthTotals.tips} ₽" else null,
                                if (monthTotals.tax > 0) "Налог: ${monthTotals.tax} ₽" else null
                            ).joinToString(" • "),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }

        // Текущая смена: таймер, пауза, «+ Заказ», завершение
        item {
            val isPaused = uiState.shiftStartMs > 0L && uiState.shiftPausedAtMs > 0L
            val activeMs = activeShiftElapsedMs(uiState.shiftStartMs, uiState.shiftPausedAtMs, uiState.shiftPausedTotalMs, nowMs)
            val pausedDurationMs = if (uiState.shiftStartMs > 0L) ((nowMs - uiState.shiftStartMs) - activeMs).coerceAtLeast(0L) else 0L

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Текущая смена", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            if (uiState.shiftStartMs > 0L) {
                                val perHour = if (activeMs >= 15 * 60_000L && liveNet > 0) (liveNet / (activeMs / 3_600_000.0)).roundToInt() else null
                                if (isPaused) {
                                    Text(
                                        "⏸ На паузе • активно ${formatShiftDuration(activeMs)} (пауза ${formatShiftDuration(pausedDurationMs)})",
                                        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFFFAB40)
                                    )
                                } else {
                                    Text(
                                        "⏱ На линии ${formatShiftDuration(activeMs)}" + (perHour?.let { " • ≈ $it ₽/ч" } ?: ""),
                                        fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF00E676)
                                    )
                                }
                            } else {
                                Text("Смена не начата — таймер запустится сам с первым заказом", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val liveAvg = if (uiState.liveShiftOrders > 0) uiState.liveShiftGross / uiState.liveShiftOrders else 0
                            val tipsPart = if (uiState.liveShiftTips > 0) " • чаевые ${uiState.liveShiftTips} ₽" else ""
                            Text("Заказов: ${uiState.liveShiftOrders} • Сумма: ${uiState.liveShiftGross} ₽ (чек $liveAvg ₽)$tipsPart", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (uiState.shiftStartMs > 0L) {
                                Text(
                                    "Закреплено в шторке уведомлений — заказы можно добавлять не выходя из навигатора",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f), modifier = Modifier.padding(top = 3.dp)
                                )
                            }
                        }
                        if (uiState.liveShiftOrders > 0 || uiState.shiftStartMs > 0) {
                            IconButton(onClick = { viewModel.resetLiveShift() }) { Icon(Icons.Rounded.RestartAlt, contentDescription = "Сбросить смену", tint = Color.Gray) }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    if (uiState.shiftStartMs == 0L && uiState.liveShiftOrders == 0) {
                        OutlinedButton(onClick = { runWithShiftNotificationPerm { viewModel.startShift() } }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Начать смену (таймер)")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    } else if (uiState.shiftStartMs > 0L) {
                        OutlinedButton(onClick = { viewModel.toggleShiftPause() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isPaused) "Продолжить смену" else "Пауза (перерыв / обед)")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showQuickOrderDialog = true }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853))) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Заказ", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        OutlinedButton(onClick = { shiftDialog = ShiftDialogMode.New(fromLive = true) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Check, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Завершить")
                        }
                    }
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("История смен", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = MaterialTheme.colorScheme.onBackground)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { shiftDialog = ShiftDialogMode.Expense }) { Text("+ Расход") }
                    TextButton(onClick = { shiftDialog = ShiftDialogMode.New(fromLive = false) }) { Text("+ Смена") }
                }
            }
        }

        if (uiState.shifts.isEmpty()) {
            item { Text("История смен пуста. Нажимай «Заказ» во время работы и «Завершить» после — так считается чистый доход в час.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp)) }
        } else {
            items(uiState.shifts, key = { it.id }) { s ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.clickable { shiftDialog = ShiftDialogMode.Edit(s) }) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            val servicePart = if (s.service.isNotBlank()) " • ${s.service}" else ""
                            if (s.isExpenseOnly) {
                                Text("${s.dateStr} • Внесменовый расход$servicePart", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Text("Расход: ${s.net} ₽", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            } else {
                                Text("${s.dateStr} • ${money(s.hours)} ч. (${s.orders} зак.)$servicePart", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                val netColor = if (s.net >= 0) Color(0xFF00E676) else Color(0xFFFF5252)
                                val netSign = if (s.net > 0) "+" else ""
                                Text("Чистыми: $netSign${s.net} ₽ (${s.perHour} ₽/ч)", color = netColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                val tipsPart = if (s.tips > 0) " (чаевые ${s.tips})" else ""
                                Text("Грязными: ${s.gross} ₽$tipsPart • Средний чек: ${s.avgCheck} ₽", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (s.km > 0 || s.expenses > 0 || s.tax > 0) {
                                val kmPart = if (s.km > 0) "Пробег: ${String.format(Locale.US, "%.1f", s.km)} км (${String.format(Locale.US, "%.1f", s.perKm)} ₽/км) • " else ""
                                val taxPart = if (s.tax > 0) " • Налог: ${s.tax}" else ""
                                Text("${kmPart}Топл./зарядка: ${s.expFuel} • Ремонт: ${s.expRepair} • Прочее: ${s.expOther}$taxPart ₽", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Column {
                            IconButton(onClick = { shiftDialog = ShiftDialogMode.Edit(s) }) { Icon(Icons.Rounded.Edit, contentDescription = "Изменить", tint = Color.Gray) }
                            IconButton(onClick = { viewModel.deleteShift(s) }) { Icon(Icons.Rounded.Delete, contentDescription = "Удалить", tint = Color.Gray) }
                        }
                    }
                }
            }
        }
    }
}

sealed interface ShiftDialogMode {
    data class New(val fromLive: Boolean) : ShiftDialogMode
    data class Edit(val shift: ShiftEntry) : ShiftDialogMode
    data object Expense : ShiftDialogMode
}

@Composable
private fun QuickOrderDialog(lastPrice: Int, onDismiss: () -> Unit, onAdd: (Int, Int) -> Unit) {
    var sumStr by remember { mutableStateOf(lastPrice.toString()) }
    var tipsStr by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("+ Заказ в текущую смену") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = sumStr, onValueChange = { sumStr = it.filter(Char::isDigit).take(6) }, label = { Text("Оплата за заказ (₽, 0 если только чаевые)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(180, 250, 350, 500).forEach { p -> SuggestionChip(onClick = { sumStr = p.toString() }, label = { Text("$p") }) }
                }
                OutlinedTextField(value = tipsStr, onValueChange = { tipsStr = it.filter(Char::isDigit).take(5) }, label = { Text("Чаевые (₽), если были") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(50, 100, 200).forEach { p -> SuggestionChip(onClick = { tipsStr = p.toString() }, label = { Text("+$p") }) }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val sum = sumStr.toIntOrNull() ?: 0
                val tips = tipsStr.toIntOrNull() ?: 0
                if (sum > 0 || tips > 0) onAdd(sum, tips)
            }) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ShiftDialog(
    mode: ShiftDialogMode, uiState: CourierUiState, services: List<String>, nowMs: Long, onDismiss: () -> Unit,
    onSave: (hours: Double, gross: Int, fuel: Int, repair: Int, other: Int, km: Double, orders: Int, tips: Int, service: String, chosenDateMs: Long?, taxRatePct: Int) -> Unit
) {
    val edit = (mode as? ShiftDialogMode.Edit)?.shift
    val fromLive = (mode as? ShiftDialogMode.New)?.fromLive == true
    val isExpense = mode is ShiftDialogMode.Expense || (edit != null && edit.isExpenseOnly)
    fun num(v: Int) = if (v > 0) v.toString() else ""
    val liveHours = if (fromLive) activeElapsedHours(uiState.shiftStartMs, uiState.shiftPausedAtMs, uiState.shiftPausedTotalMs, nowMs) else 0.0

    val startedEarlierDay = fromLive && uiState.shiftStartMs > 0L && !sameCalendarDay(uiState.shiftStartMs, nowMs)
    var chosenDateMs by remember {
        mutableStateOf(
            when {
                edit != null -> edit.id
                startedEarlierDay -> uiState.shiftStartMs
                else -> nowMs
            }
        )
    }
    var showDatePicker by remember { mutableStateOf(false) }

    var grossStr by remember { mutableStateOf(edit?.gross?.toString() ?: if (fromLive) num(uiState.liveShiftGross) else "") }
    var tipsStr by remember { mutableStateOf(edit?.let { num(it.tips) } ?: if (fromLive) num(uiState.liveShiftTips) else "") }
    var serviceStr by remember { mutableStateOf(edit?.service ?: if (isExpense) "" else (uiState.shifts.firstOrNull { !it.isExpenseOnly }?.service ?: "")) }
    var fuelStr by remember { mutableStateOf(edit?.let { num(it.expFuel) } ?: "") }
    var repairStr by remember { mutableStateOf(edit?.let { num(it.expRepair) } ?: "") }
    var otherStr by remember { mutableStateOf(edit?.let { num(it.expOther) } ?: "") }
    var kmStr by remember { mutableStateOf(edit?.km?.takeIf { it > 0 }?.let { money(it) } ?: "") }
    var hoursStr by remember { mutableStateOf(edit?.let { money(it.hours) } ?: if (liveHours > 0) money(liveHours) else "8") }
    var ordersStr by remember { mutableStateOf(edit?.orders?.toString() ?: if (fromLive && uiState.liveShiftOrders > 0) uiState.liveShiftOrders.toString() else "") }
    var selectedTaxRate by remember {
        mutableIntStateOf(
            if (edit != null) {
                if (edit.gross > 0 && edit.tax > 0) listOf(0, 4, 6).minBy { abs(taxFor(edit.gross, it) - edit.tax) } else 0
            } else uiState.taxRate
        )
    }

    val gross = if (isExpense) 0 else (grossStr.toIntOrNull() ?: 0)
    val tax = if (isExpense) 0 else taxFor(gross, selectedTaxRate)
    val expenses = (fuelStr.toIntOrNull() ?: 0) + (repairStr.toIntOrNull() ?: 0) + (otherStr.toIntOrNull() ?: 0)
    val parsedHours = hoursStr.toDoubleOrNull()
    val hoursValid = isExpense || (parsedHours != null && parsedHours in 0.1..24.0)
    val numberField = KeyboardOptions(keyboardType = KeyboardType.Number)
    val decimalField = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    val yesterdayMs = remember(nowMs) { nowMs - 86_400_000L }
    val shortDateFmt = remember { SimpleDateFormat("dd.MM", Locale("ru")) }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = chosenDateMs)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { chosenDateMs = utcDateToLocalNoon(it) }
                    showDatePicker = false
                }) { Text("Выбрать") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Отмена") } }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    edit != null && isExpense -> "Изменить расход ${edit.dateStr}"
                    edit != null -> "Изменить смену ${edit.dateStr}"
                    isExpense -> "Внесменовый расход"
                    else -> "Сохранить смену в историю"
                }
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (edit == null) {
                    Text("Дата (${shortDateFmt.format(Date(chosenDateMs))})", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (startedEarlierDay) {
                            FilterChip(
                                selected = sameCalendarDay(chosenDateMs, uiState.shiftStartMs),
                                onClick = { chosenDateMs = uiState.shiftStartMs },
                                label = { Text("Старт (${shortDateFmt.format(Date(uiState.shiftStartMs))})") }
                            )
                        }
                        FilterChip(
                            selected = sameCalendarDay(chosenDateMs, nowMs),
                            onClick = { chosenDateMs = nowMs },
                            label = { Text("Сегодня") }
                        )
                        if (!startedEarlierDay) {
                            FilterChip(
                                selected = sameCalendarDay(chosenDateMs, yesterdayMs),
                                onClick = { chosenDateMs = yesterdayMs },
                                label = { Text("Вчера") }
                            )
                        }
                        FilterChip(
                            selected = !sameCalendarDay(chosenDateMs, nowMs) && !sameCalendarDay(chosenDateMs, yesterdayMs) && (!startedEarlierDay || !sameCalendarDay(chosenDateMs, uiState.shiftStartMs)),
                            onClick = { showDatePicker = true },
                            label = { Text("Дата...") }
                        )
                    }
                }
                if (!isExpense) {
                    Text("Сервис", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        services.forEach { name ->
                            FilterChip(selected = serviceStr == name, onClick = { serviceStr = if (serviceStr == name) "" else name }, label = { Text(name) })
                        }
                    }
                    OutlinedTextField(value = serviceStr, onValueChange = { serviceStr = it.take(30) }, label = { Text("Или свой вариант") }, singleLine = true)
                    OutlinedTextField(value = grossStr, onValueChange = { grossStr = it.filter(Char::isDigit).take(7) }, label = { Text("Заработано грязными, с чаевыми (₽)") }, keyboardOptions = numberField, singleLine = true)
                    OutlinedTextField(value = tipsStr, onValueChange = { tipsStr = it.filter(Char::isDigit).take(6) }, label = { Text("из них чаевые (₽)") }, keyboardOptions = numberField, singleLine = true)
                    OutlinedTextField(
                        value = hoursStr,
                        onValueChange = { hoursStr = sanitizeDecimal(it).take(5) },
                        label = { Text(if (liveHours > 0) "Часов на линии (по таймеру)" else "Часов на линии (напр. 8.5)") },
                        isError = hoursStr.isNotEmpty() && !hoursValid,
                        supportingText = if (hoursStr.isNotEmpty() && !hoursValid) {
                            { Text("Укажите от 0.1 до 24 часов") }
                        } else null,
                        keyboardOptions = decimalField,
                        singleLine = true
                    )
                    OutlinedTextField(value = ordersStr, onValueChange = { ordersStr = it.filter(Char::isDigit).take(4) }, label = { Text("Количество заказов") }, keyboardOptions = numberField, singleLine = true)
                    Text("Налог на эту смену", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0, 4, 6).forEach { r ->
                            FilterChip(selected = selectedTaxRate == r, onClick = { selectedTaxRate = r }, label = { Text("$r%") })
                        }
                    }
                } else {
                    OutlinedTextField(value = serviceStr, onValueChange = { serviceStr = it.take(30) }, label = { Text("Назначение (напр. ТО, цепь, связь)") }, singleLine = true)
                }
                OutlinedTextField(value = fuelStr, onValueChange = { fuelStr = it.filter(Char::isDigit).take(6) }, label = { Text("Расход: топливо / зарядка (₽)") }, keyboardOptions = numberField, singleLine = true)
                OutlinedTextField(value = repairStr, onValueChange = { repairStr = it.filter(Char::isDigit).take(6) }, label = { Text("Расход: ремонт / ТО (₽)") }, keyboardOptions = numberField, singleLine = true)
                OutlinedTextField(value = otherStr, onValueChange = { otherStr = it.filter(Char::isDigit).take(6) }, label = { Text("Расход: еда, связь, прочее (₽)") }, keyboardOptions = numberField, singleLine = true)
                OutlinedTextField(value = kmStr, onValueChange = { kmStr = sanitizeDecimal(it) }, label = { Text("Пробег за смену, км (необязательно)") }, keyboardOptions = decimalField, singleLine = true)
                if (isExpense && expenses > 0) {
                    Text("Итого расход: -$expenses ₽", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFFF5252))
                } else if (!isExpense && gross > 0) {
                    val taxText = if (selectedTaxRate > 0) "налог $selectedTaxRate%: $tax ₽ • " else ""
                    val netPreview = gross - expenses - tax
                    Text(
                        "$taxText" + "Чистыми: $netPreview ₽",
                        fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        color = if (netPreview >= 0) Color(0xFF00E676) else Color(0xFFFF5252)
                    )
                }
            }
        },
        confirmButton = {
            val canSave = if (isExpense) expenses > 0 else (gross > 0 && hoursValid)
            Button(
                enabled = canSave,
                onClick = {
                    val h = if (isExpense) 0.0 else (parsedHours ?: 1.0).coerceIn(0.1, 24.0)
                    onSave(
                        h, gross, fuelStr.toIntOrNull() ?: 0, repairStr.toIntOrNull() ?: 0, otherStr.toIntOrNull() ?: 0,
                        kmStr.toDoubleOrNull() ?: 0.0, if (isExpense) 0 else (ordersStr.toIntOrNull() ?: 0),
                        if (isExpense) 0 else (tipsStr.toIntOrNull() ?: 0), serviceStr,
                        if (edit == null) chosenDateMs else null, selectedTaxRate
                    )
                }
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
