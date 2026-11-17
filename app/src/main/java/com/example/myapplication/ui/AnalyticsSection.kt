package com.example.myapplication

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

// ============================================================================
// МОЯ АНАЛИТИКА: заработки по дням, неделя к неделе, лучшие дни, расходы и км
// ============================================================================

@Composable
fun MyAnalyticsSection(shifts: List<ShiftEntry>, currentDateStr: String = "") {
    val days = remember(shifts, currentDateStr) { dailyNet(shifts, 14) }
    val weeks = remember(shifts, currentDateStr) { weekVsPrevious(shifts) }
    val week = weeks.first
    val prevWeek = weeks.second
    val weekdays = remember(shifts) { weekdayStats(shifts) }
    val month = remember(shifts, currentDateStr) { totalsOf(shiftsOfMonth(shifts)) }
    val services = remember(shifts) { serviceStats(shifts) }
    val cardColors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Моя аналитика", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
        PersonalInsightsCard(shifts)
        if (shifts.isEmpty()) {
            Text(
                "Сохраняй смены на вкладке «Смена» — здесь появятся графики, лучшие дни недели и учёт расходов.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // --- Чистый доход по дням (14 дней) ---
            ElevatedCard(modifier = Modifier.fillMaxWidth(), colors = cardColors) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Чистыми по дням (14 дней)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(10.dp))
                    val maxAbsNet = (days.maxOfOrNull { kotlin.math.abs(it.net) } ?: 0).coerceAtLeast(1)
                    Row(
                        modifier = Modifier.fillMaxWidth().height(96.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        days.forEach { d ->
                            Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                                when {
                                    d.net > 0 -> {
                                        Box(
                                            modifier = Modifier.fillMaxWidth()
                                                .fillMaxHeight((d.net / maxAbsNet.toFloat()).coerceIn(0.05f, 1f))
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFF00E676))
                                        )
                                    }
                                    d.net < 0 -> {
                                        Box(
                                            modifier = Modifier.fillMaxWidth()
                                                .fillMaxHeight((kotlin.math.abs(d.net) / maxAbsNet.toFloat()).coerceIn(0.06f, 1f))
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFFFF5252))
                                        )
                                    }
                                    else -> {
                                        Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                                    }
                                }
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        days.forEach { d ->
                            Text(
                                d.label, modifier = Modifier.weight(1f), fontSize = 8.sp, textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    val delta = week.net - prevWeek.net
                    val deltaText = when {
                        prevWeek.net == 0 && week.net == 0 -> "За 7 дней смен нет"
                        prevWeek.net == 0 -> "За 7 дней: ${week.net} ₽"
                        else -> "За 7 дней: ${week.net} ₽ (${if (delta >= 0) "+" else ""}$delta ₽ к прошлой неделе)"
                    }
                    Text(
                        deltaText, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        color = if (delta >= 0) Color(0xFF00E676) else Color(0xFFFF5252)
                    )
                }
            }

            // --- Лучшие дни недели по ₽/ч ---
            if (weekdays.isNotEmpty()) {
                ElevatedCard(modifier = Modifier.fillMaxWidth(), colors = cardColors) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Мои лучшие дни (чистыми ₽/ч)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(8.dp))
                        val top = weekdays.first().avgPerHour.coerceAtLeast(1)
                        weekdays.forEach { st ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(st.title, modifier = Modifier.width(110.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                                LinearProgressIndicator(
                                    progress = { (st.avgPerHour / top.toFloat()).coerceIn(0.03f, 1f) },
                                    modifier = Modifier.weight(1f).height(8.dp).clip(CircleShape),
                                    color = Color(0xFFFFD600), trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                                Text(
                                    "${st.avgPerHour}", modifier = Modifier.width(52.dp).padding(start = 8.dp),
                                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End
                                )
                            }
                        }
                        Text(
                            "По ${weekdays.sumOf { it.shifts }} сменам. Чем больше смен, тем точнее.",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }

            // --- Сравнение сервисов (агрегаторов) ---
            if (services.isNotEmpty()) {
                ElevatedCard(modifier = Modifier.fillMaxWidth(), colors = cardColors) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Где выгоднее работать (чистыми ₽/ч)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(8.dp))
                        val top = services.first().perHour.coerceAtLeast(1)
                        services.forEach { st ->
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(st.service, modifier = Modifier.width(110.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                                    LinearProgressIndicator(
                                        progress = { (st.perHour / top.toFloat()).coerceIn(0.03f, 1f) },
                                        modifier = Modifier.weight(1f).height(8.dp).clip(CircleShape),
                                        color = Color(0xFF40C4FF), trackColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    Text(
                                        "${st.perHour}", modifier = Modifier.width(52.dp).padding(start = 8.dp),
                                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End
                                    )
                                }
                                Text(
                                    "${st.shifts} см. • чек ${st.avgCheck} ₽" + (if (st.tipsShare > 0) " • чаевые ${st.tipsShare}% дохода" else ""),
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (services.size == 1) Text(
                            "Указывай сервис при сохранении смены — появится сравнение.",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }

            // --- Расходы и пробег за месяц ---
            if (month.shifts > 0 || month.expenses > 0) {
                ElevatedCard(modifier = Modifier.fillMaxWidth(), colors = cardColors) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Расходы и пробег за месяц", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Грязными ${month.gross} ₽ → чистыми ${month.net} ₽ (расходы ${month.expenses} ₽" + (if (month.tax > 0) ", налог ${month.tax} ₽)" else ")"),
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Топливо/зарядка ${month.expFuel} ₽ • Ремонт/ТО ${month.expRepair} ₽ • Прочее ${month.expOther} ₽",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp)
                        )
                        if (month.km > 0) {
                            val km = String.format(Locale.US, "%.0f", month.km)
                            val perKm = String.format(Locale.US, "%.1f", month.perKm)
                            val cost = String.format(Locale.US, "%.1f", month.costPerKm)
                            Text(
                                "Пробег $km км • $perKm ₽ чистыми за км • транспорт обходится в $cost ₽/км",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    }
}
