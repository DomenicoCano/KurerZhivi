package com.example.myapplication

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ============================================================================
// СОСТОЯНИЕ ЭКРАНА И ОДНОРАЗОВЫЕ СОБЫТИЯ
// ============================================================================

data class CourierUiState(
    val selectedRegion: Region,
    val transportType: TransportType,
    val scoreResult: ScoreResult,
    val detailedWeather: DetailedWeather? = null,
    val forecast: List<DailyForecast> = emptyList(),
    val hourlyTimeline: List<HourlyScoreItem> = emptyList(),
    val latestNews: List<NewsItem> = emptyList(),
    val shifts: List<ShiftEntry> = emptyList(),
    val monthlyGoal: Int = 80000,
    val liveShiftOrders: Int = 0,
    val liveShiftGross: Int = 0,
    val liveShiftTips: Int = 0,
    val shiftStartMs: Long = 0L,
    val shiftPausedAtMs: Long = 0L,
    val shiftPausedTotalMs: Long = 0L,
    val lastOrderPrice: Int = 250,
    val taxRate: Int = 0,
    val autoBackupEnabled: Boolean = false,
    val autoBackupLastMs: Long = 0L,
    val autoBackupError: String? = null,
    val intercoms: List<IntercomNote> = emptyList(),
    val recentRegions: List<Region> = emptyList(),
    val citySearchQuery: String = "",
    val citySearchResults: List<Region> = emptyList(),
    val isSearchingCity: Boolean = false,
    val isLoading: Boolean = false,
    val weatherFromCache: Boolean = false,
    val currentDateStr: String = "",
    val lastUpdatedStr: String = "",
    val currentScreen: Screen = Screen.MAIN,
    val selectedTab: Int = 0,
    val demandSubTab: Int = 0,
    val showRegionDialog: Boolean = false,
    val showNetworkErrorDialog: Boolean = false,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val ambientEnabled: Boolean = true,
    val ambientBrightness: Float = 0.28f,
    val ambientSpeed: Float = 1.0f,
    val ambientRadius: Float = 1500f,
    val notifEnabled: Boolean = false,
    val extremeNotifEnabled: Boolean = false,
    val quietHoursEnabled: Boolean = true,
    val notifHour: Int = 8,
    val notifMinute: Int = 0,
    val notifTone: NotificationTone = NotificationTone.NORMAL,
    val safeSourcesEnabled: Boolean = false,
    val yandexKey: String = ""
)

/** Одноразовые события для UI (снекбары). */
sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    /** Сообщение с кнопкой «Отменить» — действие хранится во ViewModel (см. [CourierViewModel.undoLast]). */
    data class Undo(val text: String) : UiEvent
}

private const val STALE_AFTER_MS = 15 * 60 * 1000L
private const val MIN_RETRY_MS = 3 * 60 * 1000L

class CourierViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsManager(application)
    private val repo = CourierRepository(application, settings)

    private val _uiState = MutableStateFlow(
        CourierUiState(
            selectedRegion = settings.getSelectedRegion(),
            transportType = settings.getTransport(),
            scoreResult = ScoreResult(50, "Загрузка данных...", emptyList(), Color.Gray),
            monthlyGoal = settings.getMonthlyGoal(),
            liveShiftOrders = settings.getLiveOrders(),
            liveShiftGross = settings.getLiveGross(),
            liveShiftTips = settings.getLiveTips(),
            shiftStartMs = settings.getShiftStart(),
            shiftPausedAtMs = settings.getShiftPausedAt(),
            shiftPausedTotalMs = settings.getShiftPausedTotal(),
            lastOrderPrice = settings.getLastOrderPrice(),
            taxRate = settings.getTaxRate(),
            autoBackupEnabled = settings.isAutoBackupEnabled(),
            autoBackupLastMs = settings.getAutoBackupLastMs(),
            autoBackupError = settings.getAutoBackupError(),
            recentRegions = settings.getRecentRegions(),
            themeMode = settings.getTheme(),
            ambientEnabled = settings.isAmbientEnabled(),
            ambientBrightness = settings.getAmbientBrightness(),
            ambientSpeed = settings.getAmbientSpeed(),
            ambientRadius = settings.getAmbientRadius(),
            notifEnabled = settings.getNotifEnabled(),
            extremeNotifEnabled = settings.getExtremeNotifEnabled(),
            quietHoursEnabled = settings.getQuietHours(),
            notifHour = settings.getNotifHour(),
            notifMinute = settings.getNotifMinute(),
            notifTone = settings.getNotifTone(),
            safeSourcesEnabled = settings.getSafeSources(),
            yandexKey = settings.getYandexKey()
        )
    )
    val uiState: StateFlow<CourierUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private var refreshJob: Job? = null
    private var searchJob: Job? = null
    private var undoAction: (suspend () -> Unit)? = null
    private var lastSuccessMs = 0L
    private var lastAttemptMs = 0L
    private var newsErrorShown = false

    init {
        showCachedWeatherInstantly()
        ShiftNotification.sync(getApplication())
        viewModelScope.launch {
            repo.migrateLegacyIfNeeded()
            launch { repo.shifts.collect { list -> _uiState.update { it.copy(shifts = list) } } }
            launch { repo.intercoms.collect { list -> _uiState.update { it.copy(intercoms = list) } } }
        }
        refreshData()
    }

    // ---------- Навигация и диалоги ----------
    fun navigateTo(screen: Screen) { _uiState.update { it.copy(currentScreen = screen) } }
    fun selectTab(tab: Int) { _uiState.update { it.copy(selectedTab = tab.coerceIn(0, 3), currentScreen = Screen.MAIN) } }
    fun selectDemandSubTab(sub: Int) { _uiState.update { it.copy(demandSubTab = sub.coerceIn(0, 2)) } }
    fun showRegionDialog(show: Boolean) { _uiState.update { it.copy(showRegionDialog = show, citySearchQuery = "", citySearchResults = emptyList()) } }
    fun dismissNetworkErrorDialog() { _uiState.update { it.copy(showNetworkErrorDialog = false) } }

    fun tickMinute() {
        val dateStr = SimpleDateFormat("d MMMM", Locale("ru")).format(Date()).replaceFirstChar { c -> c.uppercase() }
        _uiState.update { st ->
            val w = st.detailedWeather
            val newScore = if (w != null && w.weather != Weather.UNKNOWN) {
                calculateScore100(w, st.latestNews, cityCalendar(w), st.transportType)
            } else st.scoreResult
            st.copy(currentDateStr = dateStr, scoreResult = newScore)
        }
    }

    fun onCitySearchQueryChanged(query: String) {
        _uiState.update { it.copy(citySearchQuery = query) }
        searchJob?.cancel()
        if (query.trim().length < 2) { _uiState.update { it.copy(citySearchResults = emptyList(), isSearchingCity = false) }; return }
        searchJob = viewModelScope.launch {
            // 1) Встроенный справочник городов России — сразу и без интернета (работает при белых списках)
            val local = withContext(Dispatchers.Default) { RuCities.search(getApplication(), query) }
            _uiState.update { if (it.citySearchQuery == query) it.copy(citySearchResults = local, isSearchingCity = local.isEmpty()) else it }
            // 2) Онлайн-поиск (весь мир, сёла) — только без белых списков: там геокодер заблокирован
            if (_uiState.value.safeSourcesEnabled) { _uiState.update { it.copy(isSearchingCity = false) }; return@launch }
            delay(350)
            val online = searchCitiesGeocoding(query)
            val localNames = local.map { it.name.substringBefore(" (").lowercase() }.toSet()
            // Онлайн-результаты добавляем, только если такого города нет во встроенном списке
            val extra = online.filter { o -> o.name.substringBefore(" (").substringBefore(",").lowercase() !in localNames }
            _uiState.update { if (it.citySearchQuery == query) it.copy(citySearchResults = (local + extra).take(10), isSearchingCity = false) else it }
        }
    }

    fun onRegionSelected(region: Region) {
        settings.saveRegion(region)
        _uiState.update {
            it.copy(selectedRegion = region, recentRegions = settings.getRecentRegions(), showRegionDialog = false,
                citySearchQuery = "", citySearchResults = emptyList())
        }
        refreshData(manual = true)
    }

    fun setTransportType(type: TransportType) {
        settings.saveTransport(type)
        _uiState.update { st ->
            val w = st.detailedWeather
            val newScore = if (w != null) calculateScore100(w, st.latestNews, cityCalendar(w), type) else st.scoreResult
            st.copy(transportType = type, scoreResult = newScore)
        }
        // Почасовой прогноз считается с учётом транспорта — пересчитываем его
        refreshData()
    }

    // ---------- Текущая смена ----------
    fun addQuickOrder(orderPrice: Int, tips: Int = 0) {
        val price = orderPrice.coerceAtLeast(0)
        val tip = tips.coerceAtLeast(0)
        if (price == 0 && tip == 0) return
        val prevOrders = settings.getLiveOrders()
        val prevGross = settings.getLiveGross()
        val prevTips = settings.getLiveTips()
        val prevStart = settings.getShiftStart()
        val prevPausedAt = settings.getShiftPausedAt()
        val prevPausedTotal = settings.getShiftPausedTotal()
        val prevLastPrice = settings.getLastOrderPrice()
        settings.addLiveOrder(price, tip)
        reloadLive()
        val undoText = when {
            price > 0 && tip > 0 -> "Заказ +$price ₽ (+$tip ₽ чаевые) добавлен"
            price > 0 -> "Заказ +$price ₽ добавлен"
            else -> "Чаевые +$tip ₽ добавлены"
        }
        postUndo(undoText) {
            settings.saveLiveState(prevOrders, prevGross, prevTips, prevStart, prevPausedAt, prevPausedTotal)
            settings.saveLastOrderPrice(prevLastPrice)
            reloadLive()
        }
    }

    /** Запуск таймера смены. */
    fun startShift() {
        if (settings.getShiftStart() != 0L) return
        settings.saveShiftStart(System.currentTimeMillis())
        reloadLive()
    }

    fun toggleShiftPause() {
        settings.toggleShiftPause()
        reloadLive()
    }

    fun resetLiveShift() {
        val s = _uiState.value
        val prevOrders = s.liveShiftOrders; val prevGross = s.liveShiftGross; val prevTips = s.liveShiftTips
        val prevStart = s.shiftStartMs; val prevPausedAt = s.shiftPausedAtMs; val prevPausedTotal = s.shiftPausedTotalMs
        setLive(0, 0)
        postUndo("Текущая смена сброшена") {
            settings.saveLiveState(prevOrders, prevGross, prevTips, prevStart, prevPausedAt, prevPausedTotal)
            reloadLive()
        }
    }

    private fun setLive(orders: Int, gross: Int) {
        settings.saveLiveState(
            orders, gross,
            if (orders == 0) 0 else settings.getLiveTips(),
            if (orders == 0) 0L else settings.getShiftStart(),
            if (orders == 0) 0L else settings.getShiftPausedAt(),
            if (orders == 0) 0L else settings.getShiftPausedTotal()
        )
        reloadLive()
    }

    /** Перечитать текущую смену из настроек (её мог изменить виджет/шторка) и обновить виджет + уведомление. */
    fun reloadLive(updateExternal: Boolean = true) {
        _uiState.update {
            it.copy(
                liveShiftOrders = settings.getLiveOrders(), liveShiftGross = settings.getLiveGross(), liveShiftTips = settings.getLiveTips(),
                shiftStartMs = settings.getShiftStart(), shiftPausedAtMs = settings.getShiftPausedAt(),
                shiftPausedTotalMs = settings.getShiftPausedTotal(), lastOrderPrice = settings.getLastOrderPrice()
            )
        }
        ShiftNotification.sync(getApplication())
        if (updateExternal) CourierWidget.updateAll(getApplication())
    }

    // ---------- Смены ----------
    fun addShift(
        hours: Double, gross: Int, expFuel: Int, expRepair: Int, expOther: Int, km: Double, orders: Int,
        tips: Int = 0, service: String = "", clearLive: Boolean = false,
        chosenDateMs: Long? = null, taxRateOverride: Int? = null
    ) {
        val nowMs = System.currentTimeMillis()
        val id = if (chosenDateMs != null) {
            val nowCal = Calendar.getInstance().apply { timeInMillis = nowMs }
            val chosenCal = Calendar.getInstance().apply { timeInMillis = chosenDateMs }
            val sameDay = nowCal.get(Calendar.YEAR) == chosenCal.get(Calendar.YEAR) &&
                nowCal.get(Calendar.DAY_OF_YEAR) == chosenCal.get(Calendar.DAY_OF_YEAR)
            if (sameDay) nowMs else {
                chosenCal.apply {
                    set(Calendar.HOUR_OF_DAY, 22); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis + (nowMs % 60_000L)
            }
        } else nowMs
        val dateStr = SimpleDateFormat("dd.MM (E)", Locale("ru")).format(Date(id))
        val w = _uiState.value.detailedWeather?.weather?.takeIf { it != Weather.UNKNOWN && hours > 0.0 }?.name ?: ""
        val rate = taxRateOverride ?: _uiState.value.taxRate
        val entry = ShiftEntry(
            id, dateStr, hours, gross, orders, km, expFuel, expRepair, expOther, w,
            tips = tips.coerceIn(0, gross), tax = taxFor(gross, rate), service = service.trim()
        )
        viewModelScope.launch { repo.addShift(entry) }
        if (clearLive) {
            val s = _uiState.value
            val prevOrders = s.liveShiftOrders; val prevGross = s.liveShiftGross; val prevTips = s.liveShiftTips
            val prevStart = s.shiftStartMs; val prevPausedAt = s.shiftPausedAtMs; val prevPausedTotal = s.shiftPausedTotalMs
            setLive(0, 0)
            postUndo("Смена $dateStr сохранена") {
                repo.deleteShift(entry.id)
                settings.saveLiveState(prevOrders, prevGross, prevTips, prevStart, prevPausedAt, prevPausedTotal)
                reloadLive()
            }
        } else {
            val label = if (entry.isExpenseOnly) "Расход $dateStr сохранён" else "Смена $dateStr добавлена"
            postUndo(label) { repo.deleteShift(entry.id) }
        }
    }

    /** Редактирование смены: id, дата и погода сохраняются, налог считается по выбранной в диалоге ставке. */
    fun updateShift(
        old: ShiftEntry, hours: Double, gross: Int, expFuel: Int, expRepair: Int, expOther: Int,
        km: Double, orders: Int, tips: Int, service: String, taxRatePct: Int
    ) {
        val updated = old.copy(
            hours = hours, gross = gross, expFuel = expFuel, expRepair = expRepair, expOther = expOther, km = km, orders = orders,
            tips = tips.coerceIn(0, gross), tax = taxFor(gross, taxRatePct), service = service.trim()
        )
        viewModelScope.launch { repo.updateShift(updated) }
        postUndo("Смена ${old.dateStr} изменена") { repo.updateShift(old) }
    }

    fun deleteShift(shift: ShiftEntry) {
        viewModelScope.launch { repo.deleteShift(shift.id) }
        postUndo("Смена ${shift.dateStr} удалена") { repo.addShift(shift) }
    }

    fun setMonthlyGoal(goal: Int) {
        settings.saveMonthlyGoal(goal)
        _uiState.update { it.copy(monthlyGoal = goal) }
    }

    // ---------- Домофоны ----------
    fun addIntercom(address: String, code: String, comment: String, category: String, pinned: Boolean = false) {
        val note = IntercomNote(System.currentTimeMillis(), address.trim(), code.trim(), comment.trim(), category, pinned)
        viewModelScope.launch { repo.addIntercom(note) }
    }

    fun updateIntercom(old: IntercomNote, address: String, code: String, comment: String, category: String, pinned: Boolean) {
        val updated = old.copy(address = address.trim(), code = code.trim(), comment = comment.trim(), category = category, pinned = pinned)
        viewModelScope.launch { repo.updateIntercom(updated) }
        postUndo("Запись «${updated.address.take(30)}» изменена") { repo.updateIntercom(old) }
    }

    fun toggleIntercomPin(note: IntercomNote) {
        viewModelScope.launch { repo.updateIntercom(note.copy(pinned = !note.pinned)) }
    }

    fun deleteIntercom(note: IntercomNote) {
        viewModelScope.launch { repo.deleteIntercom(note.id) }
        postUndo("Запись «${note.address.take(30)}» удалена") { repo.addIntercom(note) }
    }

    // ---------- Отмена последнего действия ----------
    private fun postUndo(text: String, action: suspend () -> Unit) {
        undoAction = action
        _events.tryEmit(UiEvent.Undo(text))
    }

    fun undoLast() {
        val action = undoAction ?: return
        undoAction = null
        viewModelScope.launch { action() }
    }

    private fun toast(text: String) { _events.tryEmit(UiEvent.Message(text)) }

    // ---------- Бэкап в файл ----------
    fun exportBackupTo(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val json = Backup.export(settings, repo.allShifts(), repo.allIntercoms())
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }
                toast("Бэкап сохранён в файл")
            } catch (_: Exception) {
                toast("Не удалось сохранить бэкап")
            }
        }
    }

    fun importBackupFrom(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    require(bytes.size < 10_000_000) { "Файл слишком большой" }
                    Backup.parse(String(bytes, Charsets.UTF_8)) // полная проверка до записи
                }
                Backup.applySettings(data, settings)
                repo.replaceAll(data.shifts, data.intercoms)
                _uiState.update {
                    it.copy(
                        taxRate = settings.getTaxRate(), liveShiftTips = settings.getLiveTips(),
                        shiftStartMs = settings.getShiftStart(), shiftPausedAtMs = settings.getShiftPausedAt(),
                        shiftPausedTotalMs = settings.getShiftPausedTotal(), lastOrderPrice = settings.getLastOrderPrice(),
                        selectedRegion = settings.getSelectedRegion(), recentRegions = settings.getRecentRegions(),
                        transportType = settings.getTransport(), themeMode = settings.getTheme(),
                        ambientEnabled = settings.isAmbientEnabled(), ambientBrightness = settings.getAmbientBrightness(),
                        ambientSpeed = settings.getAmbientSpeed(), ambientRadius = settings.getAmbientRadius(),
                        monthlyGoal = settings.getMonthlyGoal(), liveShiftOrders = settings.getLiveOrders(),
                        liveShiftGross = settings.getLiveGross(), safeSourcesEnabled = settings.getSafeSources(),
                        yandexKey = settings.getYandexKey(),
                        notifEnabled = settings.getNotifEnabled(), extremeNotifEnabled = settings.getExtremeNotifEnabled(),
                        quietHoursEnabled = settings.getQuietHours(), notifHour = settings.getNotifHour(),
                        notifMinute = settings.getNotifMinute(), notifTone = settings.getNotifTone()
                    )
                }
                val appCtx = context.applicationContext
                if (settings.getNotifEnabled()) DailyWorker.scheduleDaily(appCtx, settings.getNotifHour(), settings.getNotifMinute())
                else WorkManager.getInstance(appCtx).cancelAllWorkByTag("DAILY_WORK")
                if (settings.getExtremeNotifEnabled()) ExtremeWorker.scheduleExtreme(appCtx)
                else WorkManager.getInstance(appCtx).cancelAllWorkByTag("EXTREME_WORK")
                reloadLive()
                refreshData(manual = true)
                toast("Данные успешно восстановлены!")
            } catch (_: Exception) {
                toast("Файл не является корректным бэкапом — данные не изменены")
            }
        }
    }

    // ---------- Загрузка данных ----------
    private fun showCachedWeatherInstantly() {
        val region = settings.getSelectedRegion()
        val cached = settings.loadWeatherCache(region.id) ?: return
        applyWeather(cached, news = emptyList(), fromCache = true, updatedAtMs = cached.fetchedAtMs, hourly = freshHourly(cached.hourlyTimeline))
    }

    private fun freshHourly(list: List<HourlyScoreItem>): List<HourlyScoreItem> {
        val limit = System.currentTimeMillis() - 3_600_000L
        return list.filter { it.timeMs >= limit }
    }

    private fun applyWeather(data: WeatherData, news: List<NewsItem>, fromCache: Boolean, updatedAtMs: Long, hourly: List<HourlyScoreItem>) {
        val transport = _uiState.value.transportType
        val now = Date(updatedAtMs)
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        _uiState.update {
            it.copy(
                scoreResult = calculateScore100(data.current, news, cityCalendar(data.current), transport),
                detailedWeather = data.current,
                forecast = data.forecast,
                hourlyTimeline = hourly,
                latestNews = if (news.isNotEmpty()) news else it.latestNews,
                weatherFromCache = fromCache,
                currentDateStr = SimpleDateFormat("d MMMM", Locale("ru")).format(Date()).replaceFirstChar { c -> c.uppercase() },
                lastUpdatedStr = if (fromCache) "Нет связи • данные от $timeStr" else "Обновлено: $timeStr"
            )
        }
        CourierWidget.updateAll(getApplication())
    }

    /** Автообновление: вызывается при возврате в приложение и раз в минуту, пока оно открыто. */
    fun refreshIfStale() {
        val now = System.currentTimeMillis()
        if (_uiState.value.isLoading) return
        if (now - lastSuccessMs > STALE_AFTER_MS && now - lastAttemptMs > MIN_RETRY_MS) refreshData()
    }

    fun refreshData(manual: Boolean = false) {
        // Отменяем предыдущую загрузку, чтобы устаревший ответ не перезаписал свежий
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            lastAttemptMs = System.currentTimeMillis()
            _uiState.update { it.copy(isLoading = true) }
            val region = _uiState.value.selectedRegion
            val transport = _uiState.value.transportType
            val yandexKey = _uiState.value.yandexKey
            val safe = _uiState.value.safeSourcesEnabled
            val (fresh, newsPair) = coroutineScope {
                val wDef = async { fetchExpandedWeather(region.lat, region.lon, yandexKey, transport, safe, region.id) }
                val nDef = async {
                    try { fetchLocalNews(region, safe) to false }
                    catch (_: Exception) {
                        listOf(
                            NewsItem(1, "Сетевая ошибка. Не удалось загрузить свежие новости.", "Система", "", false, "Сейчас", false),
                            NewsItem(2, "Расчет выполняется по погодным и календарным данным.", "Система", "", false, "Сейчас", false)
                        ) to true
                    }
                }
                wDef.await() to nDef.await()
            }

            val gotWeather = fresh.current.weather != Weather.UNKNOWN
            if (gotWeather) {
                settings.saveWeatherCache(fresh, region.id)
                lastSuccessMs = System.currentTimeMillis()
                applyWeather(fresh, newsPair.first, fromCache = false, updatedAtMs = fresh.fetchedAtMs, hourly = fresh.hourlyTimeline)
            } else {
                val cached = settings.loadWeatherCache(region.id)
                if (cached != null) {
                    applyWeather(cached, newsPair.first, fromCache = true, updatedAtMs = cached.fetchedAtMs, hourly = freshHourly(cached.hourlyTimeline))
                } else {
                    applyWeather(fresh, newsPair.first, fromCache = false, updatedAtMs = System.currentTimeMillis(), hourly = emptyList())
                }
            }

            // Диалог про ошибку новостей — только при ручном обновлении или один раз за запуск
            val showDialog = newsPair.second && (manual || !newsErrorShown)
            if (showDialog) newsErrorShown = true
            _uiState.update { it.copy(isLoading = false, showNetworkErrorDialog = showDialog) }
        }
    }

    // ---------- Настройки ----------
    fun setTaxRate(rate: Int) { settings.saveTaxRate(rate); _uiState.update { it.copy(taxRate = rate) } }

    // ---------- Экспорт в CSV и автобэкап ----------
    fun exportCsvTo(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val csv = shiftsToCsv(repo.allShifts())
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                }
                toast("Таблица смен сохранена")
            } catch (_: Exception) { toast("Не удалось сохранить таблицу") }
        }
    }

    /** Включение автобэкапа: [treeUri] — папка, выбранная пользователем (доступ уже закреплён). */
    fun enableAutoBackup(context: Context, treeUri: Uri) {
        settings.saveAutoBackup(true, treeUri.toString())
        AutoBackupWorker.schedule(context)
        _uiState.update { it.copy(autoBackupEnabled = true) }
        backupNow(context)
    }

    fun disableAutoBackup(context: Context) {
        settings.saveAutoBackup(false, settings.getAutoBackupUri())
        AutoBackupWorker.cancel(context)
        _uiState.update { it.copy(autoBackupEnabled = false) }
    }

    fun backupNow(context: Context) {
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) { AutoBackupWorker.writeBackup(context.applicationContext) }
            _uiState.update { it.copy(autoBackupLastMs = settings.getAutoBackupLastMs(), autoBackupError = settings.getAutoBackupError()) }
            toast(if (err == null) "Автобэкап сохранён" else "Автобэкап не удался: $err")
        }
    }

    fun setTheme(theme: AppThemeMode) { settings.saveTheme(theme); _uiState.update { it.copy(themeMode = theme) } }
    fun setAmbient(enabled: Boolean) { settings.saveAmbientEnabled(enabled); _uiState.update { it.copy(ambientEnabled = enabled) } }
    fun setAmbientBrightness(value: Float) { settings.saveAmbientBrightness(value); _uiState.update { it.copy(ambientBrightness = value) } }
    fun setAmbientSpeed(value: Float) { settings.saveAmbientSpeed(value); _uiState.update { it.copy(ambientSpeed = value) } }
    fun setAmbientRadius(value: Float) { settings.saveAmbientRadius(value); _uiState.update { it.copy(ambientRadius = value) } }

    fun setTone(tone: NotificationTone) { settings.saveNotifTone(tone); _uiState.update { it.copy(notifTone = tone) } }
    fun setQuietHours(enabled: Boolean) { settings.saveQuietHours(enabled); _uiState.update { it.copy(quietHoursEnabled = enabled) } }
    fun setSafeSources(enabled: Boolean) { settings.saveSafeSources(enabled); _uiState.update { it.copy(safeSourcesEnabled = enabled) }; refreshData() }
    fun setYandexKey(key: String) { settings.saveYandexKey(key); _uiState.update { it.copy(yandexKey = key) }; refreshData() }

    fun setDailyNotif(enabled: Boolean, context: Context) {
        settings.saveNotifEnabled(enabled); _uiState.update { it.copy(notifEnabled = enabled) }
        if (enabled) DailyWorker.scheduleDaily(context, _uiState.value.notifHour, _uiState.value.notifMinute)
        else WorkManager.getInstance(context).cancelAllWorkByTag("DAILY_WORK")
    }

    fun setExtremeNotif(enabled: Boolean, context: Context) {
        settings.saveExtremeNotifEnabled(enabled); _uiState.update { it.copy(extremeNotifEnabled = enabled) }
        if (enabled) ExtremeWorker.scheduleExtreme(context) else WorkManager.getInstance(context).cancelAllWorkByTag("EXTREME_WORK")
    }

    fun setNotifTime(hour: Int, minute: Int, context: Context) {
        settings.saveNotifTime(hour, minute); _uiState.update { it.copy(notifHour = hour, notifMinute = minute) }
        if (_uiState.value.notifEnabled) DailyWorker.scheduleDaily(context, hour, minute)
    }
}
