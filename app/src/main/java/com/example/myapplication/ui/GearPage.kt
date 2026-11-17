package com.example.myapplication

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.*
import kotlinx.coroutines.*
import kotlin.math.roundToInt

// ============================================================================
// ВКЛАДКА 6: ЭКИПИРОВКА, ПРОФИЛЬ ТРАНСПОРТА, ТАЙМЕР ТЕМНОТЫ И АКБ
// ============================================================================

@Composable
fun GearPage(viewModel: CourierViewModel, uiState: CourierUiState) {
    val weather = uiState.detailedWeather
    if (weather == null) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    val transport = uiState.transportType
    val recs = remember(weather, transport) { getGearRecommendations(weather, transport) }
    val batteryDrop = remember(weather) { calculateBatteryDropPercent(weather) }
    val kcalShift = remember(weather, transport) { calculateCaloriesPerShift(weather, transport, 8) }
    val pressInfo = remember(weather) { analyzeAtmosphericPressure(weather.pressureMmHg, weather.pressureDeltaMmHg) }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Твой транспорт", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TransportType.values().forEach { type ->
                    FilterChip(
                        selected = uiState.transportType == type,
                        onClick = { viewModel.setTransportType(type) },
                        label = { Text("${type.emoji} ${type.title}", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Плашка Таймера темноты на экране Экипировки
        if (weather.darknessBannerTitle.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.WbTwilight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(weather.darknessBannerTitle, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(weather.darknessHint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
                    }
                }
            }
        }

        // Наглядный виджет просадки АКБ / калорий / расхода по выбранному транспорту
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    when (uiState.transportType) {
                        TransportType.EBIKE -> {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("⚡ Реальная емкость Li-ion АКБ", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text("Потеря: -$batteryDrop%", fontWeight = FontWeight.Bold, color = if (batteryDrop >= 25) Color(0xFFFF5252) else Color(0xFF00E676))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { ((100 - batteryDrop) / 100f).coerceIn(0.1f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape),
                                color = if (batteryDrop >= 30) Color(0xFFFF5252) else if (batteryDrop >= 15) Color(0xFFFFD600) else Color(0xFF00E676),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Доступно ~${100 - batteryDrop}% запаса хода при ${weather.temp}°C и ветре ${weather.windSpeed} м/с.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TransportType.WALK, TransportType.BIKE -> {
                            Text("${uiState.transportType.emoji} Нагрузка за смену (8 ч.): ~$kcalShift ккал", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Не забывай восполнять углеводы и воду каждые 2 часа работы.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TransportType.CAR -> {
                            Text("🚗 Профиль Авто: Пробки, Прогревы и ЖК", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Учитывается трафик в часы пик и повышенный расход топлива на прогревы при низких температурах.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(recs.third, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                }
            }
        }

        item {
            Text("Детальная метеосводка (${weather.source})", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WeatherTile(modifier = Modifier.weight(1f), "Температура", "${weather.temp}°C", Icons.Rounded.Thermostat)
                WeatherTile(modifier = Modifier.weight(1f), "Ощущается", "${weather.feelsLike}°C", Icons.Rounded.EmojiPeople)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WeatherTile(modifier = Modifier.weight(1f), "Ветер (Порывы)", "${weather.windSpeed} (${weather.windGusts.roundToInt()}) м/с", Icons.Rounded.Air)
                WeatherTile(modifier = Modifier.weight(1f), "Закат / Рассвет", "${weather.sunsetStr} / ${weather.sunriseStr}", Icons.Rounded.WbSunny)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WeatherTile(modifier = Modifier.weight(1f), "Давление", "${weather.pressureMmHg} мм рт. ст.", Icons.Rounded.Speed)
                WeatherTile(modifier = Modifier.weight(1f), "Влажность / Статус", "${weather.humidity}% • ${weather.weather.emoji}", Icons.Rounded.WaterDrop)
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.MonitorHeart, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Влияние давления на заказы", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Text("${pressInfo.statusTitle}\n\n${pressInfo.wellBeingVerdict}", modifier = Modifier.padding(top = 8.dp), lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Checkroom, contentDescription = null, tint = MaterialTheme.colorScheme.primary); Spacer(modifier = Modifier.width(8.dp)); Text("Одежда и экипировка", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) }
                    Text(recs.first, modifier = Modifier.padding(top = 8.dp), lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Restaurant, contentDescription = null, tint = MaterialTheme.colorScheme.primary); Spacer(modifier = Modifier.width(8.dp)); Text("Питание и гаджеты", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) }
                    Text(recs.second, modifier = Modifier.padding(top = 8.dp), lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
fun WeatherTile(modifier: Modifier, title: String, value: String, icon: ImageVector) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurface)
            Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
