package com.example.myapplication

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

private fun levelColor(l: DemandLevel): Color = when (l) {
    DemandLevel.LOW -> Color(0xFF9E9E9E)
    DemandLevel.MEDIUM -> Color(0xFFFFD600)
    DemandLevel.HIGH -> Color(0xFF69F0AE)
    DemandLevel.PEAK -> Color(0xFF00E676)
}

/** Суточный ритм спроса: будни и выходные отличаются. У каждого интервала указано, на чём он основан. */
@Composable
fun DemandRhythmCard(weather: DetailedWeather?, refreshKey: String, shifts: List<ShiftEntry>) {
    val now = remember(refreshKey, weather) { cityCalendar(weather) }
    val slots = remember(refreshKey) { dailyRhythm(now) }
    val current = currentRhythmIndex(slots, now.get(Calendar.HOUR_OF_DAY))
    val weekend = isWeekendDay(now)
    val weatherNote = weather?.let { weatherRhythmNote(it.weather) } ?: ""
    val personalHint = remember(refreshKey, shifts) { personalHintNow(shifts, now) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                if (weekend) "Ритм спроса сегодня (выходной)" else "Ритм спроса сегодня (будни)",
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
            )
            val accuracy = remember(shifts) { accuracyLabel(shifts) }
            Text(accuracy, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (personalHint != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(personalHint, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E676))
            }
            Spacer(modifier = Modifier.height(10.dp))
            slots.forEachIndexed { i, slot ->
                val isNow = i == current
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isNow) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(modifier = Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(levelColor(slot.level)))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${slot.range} • ${slot.title} — ${slot.level.title}${if (isNow) "  ← сейчас" else ""}",
                            fontSize = 13.sp, fontWeight = if (isNow) FontWeight.Bold else FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(slot.note, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Основа: ${slot.basis}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                    }
                }
            }
            if (weatherNote.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(weatherNote, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Это ориентир, а не измерение для вашего города: оценки без ссылки на исследование — разумные предположения. Точнее всего покажет статистика ваших смен.",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
