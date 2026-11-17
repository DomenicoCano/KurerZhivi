package com.example.myapplication

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================================
// ВКЛАДКА 3: БАЗА ДОМОФОНОВ, ШЛАГБАУМОВ И ПРОБЛЕМНЫХ ТОЧЕК
// ============================================================================

private val INTERCOM_CATEGORIES = listOf(
    "Домофон / ЖК",
    "Калитка / Шлагбаум",
    "Ресторан (задержки)",
    "Злой консьерж / Охрана"
)

@Composable
fun IntercomPage(viewModel: CourierViewModel, uiState: CourierUiState) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf<String?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<IntercomNote?>(null) }

    val hasPinned = remember(uiState.intercoms) { uiState.intercoms.any { it.pinned } }
    val filtered = remember(uiState.intercoms, search, categoryFilter) {
        uiState.intercoms.filter { item ->
            val catOk = when (categoryFilter) {
                null -> true
                "__PINNED__" -> item.pinned
                else -> item.category == categoryFilter
            }
            catOk && matchesIntercomQuery(item, search)
        }
    }

    if (showDialog) {
        IntercomDialog(
            editing = editingNote,
            onDismiss = { showDialog = false; editingNote = null },
            onSave = { addr, code, comm, cat, pinned ->
                val cur = editingNote
                if (cur != null) viewModel.updateIntercom(cur, addr, code, comm, cat, pinned)
                else viewModel.addIntercom(addr, code, comm, cat, pinned)
                showDialog = false
                editingNote = null
            }
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        label = { Text("Адрес, ЖК или код") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        trailingIcon = if (search.isNotEmpty()) {
                            { IconButton(onClick = { search = "" }) { Icon(Icons.Rounded.Clear, contentDescription = "Очистить") } }
                        } else null,
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    FilledIconButton(
                        onClick = { editingNote = null; showDialog = true },
                        modifier = Modifier.size(52.dp)
                    ) { Icon(Icons.Rounded.Add, contentDescription = "Добавить") }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = categoryFilter == null,
                            onClick = { categoryFilter = null },
                            label = { Text("Все", fontSize = 12.sp) }
                        )
                    }
                    if (hasPinned) {
                        item {
                            FilterChip(
                                selected = categoryFilter == "__PINNED__",
                                onClick = { categoryFilter = if (categoryFilter == "__PINNED__") null else "__PINNED__" },
                                label = { Text("📌 Избранные", fontSize = 12.sp) }
                            )
                        }
                    }
                    items(INTERCOM_CATEGORIES) { cat ->
                        FilterChip(
                            selected = categoryFilter == cat,
                            onClick = { categoryFilter = if (categoryFilter == cat) null else cat },
                            label = { Text(cat, fontSize = 12.sp) }
                        )
                    }
                }
            }
        }
        if (uiState.intercoms.isEmpty()) {
            item {
                Text(
                    "Записей пока нет. Сохраняй коды домофонов, калиток, входы в ЖК со двора и проблемные рестораны с долгим ожиданием.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp)
                )
            }
        } else if (filtered.isEmpty()) {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Ничего не найдено по запросу",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { search = ""; categoryFilter = null }) { Text("Сбросить фильтр") }
                }
            }
        } else {
            items(filtered, key = { it.id }) { item ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (item.code.isNotEmpty()) {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("code", item.code))
                            Toast.makeText(context, "Код ${item.code} скопирован", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp).fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    (if (item.pinned) "📌 " else "") + item.category,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(item.address, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                                if (item.code.isNotEmpty()) {
                                    Text("Код: ${item.code}", fontWeight = FontWeight.Black, fontSize = 18.sp, color = Color(0xFF00E676), modifier = Modifier.padding(top = 2.dp))
                                }
                                if (item.comment.isNotEmpty()) {
                                    Text(item.comment, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            IconButton(onClick = { viewModel.toggleIntercomPin(item) }) {
                                Icon(
                                    if (item.pinned) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                    contentDescription = if (item.pinned) "Открепить" else "Закрепить",
                                    tint = if (item.pinned) Color(0xFFFFD600) else Color.Gray
                                )
                            }
                            IconButton(onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(item.address)))
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Приложение карт не найдено", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Rounded.Place, contentDescription = "Открыть на карте", tint = Color.Gray)
                            }
                            IconButton(onClick = { editingNote = item; showDialog = true }) {
                                Icon(Icons.Rounded.Edit, contentDescription = "Изменить", tint = Color.Gray)
                            }
                            IconButton(onClick = { viewModel.deleteIntercom(item) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Удалить", tint = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntercomDialog(
    editing: IntercomNote?,
    onDismiss: () -> Unit,
    onSave: (address: String, code: String, comment: String, category: String, pinned: Boolean) -> Unit
) {
    var addr by remember { mutableStateOf(editing?.address ?: "") }
    var code by remember { mutableStateOf(editing?.code ?: "") }
    var comm by remember { mutableStateOf(editing?.comment ?: "") }
    var selectedCat by remember { mutableStateOf(editing?.category ?: INTERCOM_CATEGORIES.first()) }
    var pinned by remember { mutableStateOf(editing?.pinned ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing != null) "Изменить запись" else "Новый адрес / проблемная точка") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Категория точки:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(INTERCOM_CATEGORIES) { cat ->
                        FilterChip(selected = selectedCat == cat, onClick = { selectedCat = cat }, label = { Text(cat, fontSize = 11.sp) })
                    }
                }
                OutlinedTextField(value = addr, onValueChange = { addr = it }, label = { Text("Улица, дом, ЖК или Ресторан/ТЦ") }, singleLine = true)
                OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Код домофона / калитки (напр. #1234)") }, singleLine = true)
                OutlinedTextField(value = comm, onValueChange = { comm = it }, label = { Text("Подсказка (вход со двора, ждут 25 мин и т.д.)") })
                Row(modifier = Modifier.fillMaxWidth().clickable { pinned = !pinned }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = pinned, onCheckedChange = { pinned = it })
                    Text("Закрепить вверху списка", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = addr.isNotBlank(),
                onClick = { onSave(addr, code, comm, selectedCat, pinned) }
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
