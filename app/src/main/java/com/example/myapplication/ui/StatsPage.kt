package com.example.myapplication

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// ============================================================================
// ВКЛАДКА 5: СТАТИСТИКА И УМНЫЙ КАЛЕНДАРЬ
// ============================================================================

@Composable
fun StatsCalendarPage(uiState: CourierUiState) {
    val sdfDisplay = remember { SimpleDateFormat("d MMM, E", Locale("ru")) }
    // Календарь считается один раз за день, а не при каждой перерисовке
    val today = remember(uiState.currentDateStr) { Calendar.getInstance().get(Calendar.DAY_OF_YEAR) }
    val calendarDays = remember(today) {
        (0 until 7).map { offset ->
            val itemCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }
            Triple(itemCal, checkSmartPayday(itemCal), checkHolidayFactor(itemCal))
        }
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { MyAnalyticsSection(uiState.shifts, uiState.currentDateStr) }
        item {
            Text("Календарь спроса (Ближайшие 7 дней)", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
            Text("С учетом переноса выплат ЗП по ТК РФ и праздников", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(7, key = { it }) { offset ->
            val (itemCal, payday, holiday) = calendarDays[offset]
            val dow = itemCal.get(Calendar.DAY_OF_WEEK)
            val isoDow = if (dow == Calendar.SUNDAY) 7 else dow - 1

            var fill = when (isoDow) { 6 -> 0.90f; 5 -> 0.80f; 7 -> 0.78f; 4 -> 0.60f; else -> 0.45f }
            val tags = mutableListOf(when (isoDow) { 6 -> "Суббота (Максимум)"; 5 -> "Пятница"; 7 -> "Воскресенье"; 4 -> "Четверг (Рост)"; else -> "Будни" })
            if (payday.isPayday) { fill = minOf(1.0f, fill + 0.18f); tags.add("💰 День ЗП") }
            if (holiday != null) { fill = minOf(1.0f, fill + 0.22f); tags.add("🎉 Праздник") }
            val color = if (fill >= 0.82f) Color(0xFF00E676) else if (fill >= 0.58f) Color(0xFFFFD600) else Color.Gray

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(sdfDisplay.format(itemCal.time), modifier = Modifier.width(105.dp), fontWeight = if (offset == 0) FontWeight.Bold else FontWeight.Normal, color = MaterialTheme.colorScheme.onBackground)
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    LinearProgressIndicator(progress = { fill }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape), color = color, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                    Text(tags.joinToString(" • "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("💰 Зарплатный буст по ТК РФ", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF00E676))
                    Text("Если 5, 10, 15, 20 или 25 число выпадает на выходной, выплаты приходят в пятницу. В такие дни вечерний спрос максимальный.", modifier = Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        item {
            Text("Основано на исследовании сервиса «Чиббис» (анализ 990 000 заказов из 200 городов).", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
        }
    }
}
