package com.example.myapplication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Короткая строка о точности данных: приблизительные (общие) или уточнённые смены пользователя. */
fun accuracyLabel(shifts: List<ShiftEntry>): String = accuracyLabel(personalProfile(shifts))

fun accuracyLabel(p: PersonalProfile): String {
    return if (p.enough) "Точность: уточнено по вашим ${p.totalShifts} сменам"
    else if (p.totalShifts == 0) "Точность: приблизительно (общие данные). Сохраняйте смены — цифры станут вашими"
    else "Точность: приблизительно. Ещё ${p.shiftsToGo} смен(ы) — и появятся ваши данные"
}

@Composable
private fun InsightRow(title: String, items: List<Insight>) {
    if (items.isEmpty()) return
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
    items.forEachIndexed { i, it ->
        val sign = if (it.deltaPct >= 0) "+" else ""
        val color = when {
            it.deltaPct >= 5 -> Color(0xFF00E676)
            it.deltaPct <= -5 -> Color(0xFFFF5252)
            else -> MaterialTheme.colorScheme.onSurface
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (i == 0 && items.size > 1) "★ ${it.label}" else it.label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text("${it.perHour} ₽/ч  ($sign${it.deltaPct}%, ${it.shifts} см.)", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color)
        }
    }
}

/** Персональные закономерности: реальные ₽/ч по времени суток, типу дня и погоде. */
@Composable
fun PersonalInsightsCard(shifts: List<ShiftEntry>) {
    val profile = remember(shifts) { personalProfile(shifts) }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Ваши закономерности", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(accuracyLabel(profile), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            if (!profile.enough) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Пока в приложении только приблизительные ориентиры по общим данным. Когда наберётся $MIN_SHIFTS_TOTAL смен, " +
                        "здесь появятся ваши реальные ₽/ч: в какое время суток, в какие дни и в какую погоду вы зарабатываете больше.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                Text("Ваш средний чистый доход: ${profile.overallPerHour} ₽/ч", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 6.dp))
                InsightRow("По времени суток", profile.byDayPart)
                InsightRow("По типу дня", profile.byDayType)
                InsightRow("По погоде", profile.byWeather)
                if (profile.byDayPart.isEmpty() && profile.byDayType.isEmpty() && profile.byWeather.isEmpty()) {
                    Text("Смен в каждой группе пока меньше $MIN_SHIFTS_GROUP — выводы были бы случайными.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
                Text(
                    "Показываются группы от $MIN_SHIFTS_GROUP смен. Время смены берётся по её середине, погода — на момент сохранения смены, " +
                        "поэтому это тенденции, а не точные причины. Процент — к вашему среднему.",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
