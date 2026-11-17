package com.example.myapplication

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.*
import kotlinx.coroutines.*

// ============================================================================
// ВКЛАДКА 4: ПРОГНОЗ И НОВОСТИ
// ============================================================================

@Composable
fun NewsForecastPage(uiState: CourierUiState) {
    val uriHandler = LocalUriHandler.current
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Прогноз на 3 дня", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary) }
        items(uiState.forecast, key = { it.dateStr }) { day ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(day.dateStr, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("${day.weather.emoji} ${day.weather.title}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    }
                    Text("${day.minTemp}° ... ${day.maxTemp}°", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        item {
            Text("Сводка новостей региона", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
            Text("В расчете балла учитываются только свежие события (за последние 12 ч.).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(uiState.latestNews, key = { it.id.toString() + it.title }) { news ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(if (news.isExtreme) Icons.Rounded.Warning else Icons.Rounded.Article, contentDescription = null, tint = if (news.isExtreme) Color.Red else MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(news.source, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (news.ageText.isNotEmpty()) Text(if (news.isFresh) news.ageText else "${news.ageText} (архив)", fontSize = 11.sp, color = if (news.isFresh) MaterialTheme.colorScheme.primary else Color.Gray)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(news.title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                    if (news.url.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        SuggestionChip(onClick = { uriHandler.openUri(news.url) }, label = { Text("Открыть в браузере") }, icon = { Icon(Icons.Rounded.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp)) })
                    }
                }
            }
        }
    }
}
