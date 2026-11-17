package com.example.myapplication

import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(viewModel: CourierViewModel, uiState: CourierUiState) {
    val navItems = listOf(
        "Спрос" to Icons.Rounded.Speed,
        "Смена" to Icons.Rounded.AccountBalanceWallet,
        "Адреса" to Icons.Rounded.Key,
        "Статистика" to Icons.Rounded.BarChart
    )
    val pagerState = rememberPagerState(initialPage = uiState.selectedTab, pageCount = { navItems.size })
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(uiState.selectedTab) {
        if (pagerState.currentPage != uiState.selectedTab) {
            pagerState.animateScrollToPage(uiState.selectedTab)
        }
    }
    LaunchedEffect(pagerState.settledPage) {
        if (uiState.selectedTab != pagerState.settledPage) {
            viewModel.selectTab(pagerState.settledPage)
        }
    }

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Часы обновляются на границе минуты, а не каждую секунду
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
            nowMs = System.currentTimeMillis()
        }
    }
    val cityCal = remember(uiState.detailedWeather, nowMs) { cityCalendar(uiState.detailedWeather, nowMs) }
    val cityTimeStr = remember(cityCal) {
        String.format(Locale.getDefault(), "%02d:%02d", cityCal.get(Calendar.HOUR_OF_DAY), cityCal.get(Calendar.MINUTE))
    }
    val cityTzOffset = uiState.detailedWeather?.utcOffsetSeconds
    val deviceOffset = TimeZone.getDefault().getOffset(nowMs) / 1000
    val timePrefix = if (cityTzOffset != null && cityTzOffset != deviceOffset) "Местное $cityTimeStr" else "Сейчас $cityTimeStr"

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { viewModel.showRegionDialog(true) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${uiState.selectedRegion.name} ${uiState.transportType.emoji}",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "$timePrefix • ${uiState.lastUpdatedStr}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshData(manual = true); Toast.makeText(context, "Обновление данных...", Toast.LENGTH_SHORT).show() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Обновить", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { viewModel.navigateTo(Screen.SETTINGS) }) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Настройки", tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
                navItems.forEachIndexed { index, (label, icon) ->
                    val selected = uiState.selectedTab == index
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            viewModel.selectTab(index)
                            coroutineScope.launch { pagerState.animateScrollToPage(index) }
                        },
                        icon = {
                            if (index == 1) {
                                BadgedBox(
                                    badge = {
                                        if (uiState.shiftStartMs > 0L) {
                                            Badge(containerColor = Color(0xFF00E676))
                                        }
                                    }
                                ) {
                                    Icon(icon, contentDescription = label)
                                }
                            } else {
                                Icon(icon, contentDescription = label)
                            }
                        },
                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        }
    ) { innerPadding ->
        // Соседние вкладки строятся заранее — иначе вкладка собирается с нуля прямо во время свайпа
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            verticalAlignment = Alignment.Top,
            beyondViewportPageCount = 1,
            key = { it }
        ) { page ->
            when (page) {
                0 -> DemandHubPage(viewModel, uiState)
                1 -> FinancePage(viewModel, uiState)
                2 -> IntercomPage(viewModel, uiState)
                3 -> StatsCalendarPage(uiState)
            }
        }
    }

    if (uiState.showRegionDialog) RegionSelectionDialog(viewModel, uiState)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DemandHubPage(viewModel: CourierViewModel, uiState: CourierUiState) {
    val subTabs = listOf("Обзор", "Прогноз", "Экипировка")
    Column(modifier = Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            subTabs.forEachIndexed { index, title ->
                SegmentedButton(
                    selected = uiState.demandSubTab == index,
                    onClick = { viewModel.selectDemandSubTab(index) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = subTabs.size),
                    icon = {}
                ) {
                    Text(title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (uiState.demandSubTab) {
                0 -> DashboardPage(viewModel, uiState)
                1 -> NewsForecastPage(uiState)
                else -> GearPage(viewModel, uiState)
            }
        }
    }
}

@Composable
fun RegionSelectionDialog(viewModel: CourierViewModel, uiState: CourierUiState) {
    AlertDialog(
        onDismissRequest = { viewModel.showRegionDialog(false) },
        title = { Text("Выбор города", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = uiState.citySearchQuery,
                    onValueChange = { viewModel.onCitySearchQueryChanged(it) },
                    label = { Text("Поиск города...") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (uiState.isSearchingCity && uiState.citySearchResults.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
                } else if (uiState.citySearchQuery.trim().length >= 2) {
                    if (uiState.citySearchResults.isEmpty()) Text("Город не найден.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    else LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                        items(uiState.citySearchResults) { reg ->
                            Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.onRegionSelected(reg) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(reg.name, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                        items(uiState.recentRegions + availableRegionsList) { reg ->
                            Text(reg.name, modifier = Modifier.fillMaxWidth().clickable { viewModel.onRegionSelected(reg) }.padding(vertical = 12.dp), fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { viewModel.showRegionDialog(false) }) { Text("Закрыть") } }
    )
}
