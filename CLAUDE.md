# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Android-приложение «Курьер Живи» для курьеров доставки (Kotlin, Jetpack Compose, Material 3, Room, WorkManager). Одно-модульный проект (`:app`), один экран-Activity, русскоязычный UI. `applicationId = com.komodist.kurerzhivi`, а `namespace`/пакет кода — `com.example.myapplication` (не путать).

Подробности по слоям — в `CLAUDE.md` внутри `model/`, `logic/`, `data/`, `ui/`, `work/` (путь: `app/src/main/java/com/example/myapplication/`).

## Команды

```
.\gradlew.bat :app:compileDebugKotlin                      # быстрая проверка компиляции
.\gradlew.bat :app:assembleDebug                           # debug APK
.\gradlew.bat :app:assembleRelease                         # release с R8 (без подписи; ключ — ITOG/key.jks, вне git)
.\gradlew.bat :app:testDebugUnitTest                       # юнит-тесты (app/src/test/.../LogicTest.kt)
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.myapplication.AnalyticsTest"   # один класс
.\gradlew.bat :app:testDebugUnitTest --tests "*.AnalyticsTest.weekComparison"            # один тест
```

Особенности окружения (Windows, путь проекта и профиль пользователя содержат кириллицу):
- **Тестовый процесс Gradle не стартует** из-за кириллицы в путях — и в `GRADLE_USER_HOME`, и в пути проекта (короткие имена вида `8523~1` не помогают: Gradle разворачивает их обратно). Рабочий способ: скопировать проект в ASCII-папку и запускать тесты там: `robocopy . C:\Users\Public\kz_proj /E /XD build .gradle .idea .kotlin ITOG release`, затем в `C:\Users\Public\kz_proj`: `$env:GRADLE_USER_HOME="C:\Users\Public\kurer_gradle"; .\gradlew.bat :app:testDebugUnitTest --no-configuration-cache`. Кэш `C:\Users\Public\kurer_gradle` уже скачан. Компиляция и сборка APK работают из исходной папки.
- На телефоне (Xiaomi) `adb shell input` игнорируется, пока не включено «Отладка по USB (настройки безопасности)»; установка APK и скриншоты работают.
- В копии проекта без кэша иногда падает configuration cache (`processDebugNavigationResources`) — добавьте `--no-configuration-cache`.
- Room работает через KSP (плагин `com.google.devtools.ksp`); для первой сборки нужна сеть (`--offline` не сработает, пока плагин не скачан).
- Устройство/эмулятор через CLI не используется — запуск делается из Android Studio.

## Архитектура (общая картина)

Все файлы лежат в **одном Kotlin-пакете** `com.example.myapplication`, подпапки — только группировка. Импортировать между слоями ничего не нужно.

Поток данных: `Network/Settings/Room → CourierViewModel (один StateFlow<CourierUiState>) → Compose-экраны`. Экраны без собственного состояния бизнес-данных; все действия идут через методы ViewModel.

- `MainActivity` → `DynamicAppTheme` → `LiveAmbientBackground` (20 FPS) → один из экранов `Screen.MAIN/SETTINGS/DEVELOPER` (навигация — поле `currentScreen` в состоянии, не Navigation Compose). `MainScreen` — нижний `NavigationBar` с 4 вкладками («Спрос», «Смена», «Адреса», «Статистика»); внутри «Спрос» — переключатель «Обзор» / «Прогноз и новости» / «Экипировка».
- **Погода**: `fetchExpandedWeather` берёт Яндекс.Погоду (если задан ключ) как основной «текущий» источник и всегда Open-Meteo для почасовки/прогноза/давления/заката; при полном отказе возвращает `Weather.UNKNOWN`, тогда ViewModel подставляет кэш (`SettingsManager.loadWeatherCache`, привязан к id города).
- **Балл спроса** (`calculateScore100`, 0–100) — сумма факторов: погода, давление, час (в поясе выбранного города `cityCalendar`), день недели, праздники/ЗП, новости, профиль транспорта. Чистая функция; тот же расчёт используется для почасового таймлайна и в фоновых воркерах.
- **Хранение**: смены и домофоны — Room (`courier.db`, `exportSchema=false`, версия 4; 1→2 добавила `shifts.weather`, 2→3 — `tips`, `tax`, `service`, 3→4 — `intercoms.pinned`); всё остальное — SharedPreferences `courier_settings` (`courier_secrets` для `yandex_key`) через `SettingsManager` (в т.ч. текущая смена, таймер и паузы). Бэкап — JSON-файл (`Backup`, формат v4; читает старые форматы).
- **Фон и шторка**: `DailyWorker` (одноразовый, перепланирует себя) и `ExtremeWorker` (периодический, 30 мин, тихие часы 23–07) в `work/Workers.kt`; `AutoBackupWorker` (раз в 7 дней, папка через SAF) в `work/AutoBackup.kt`; `ShiftNotification` + `ShiftActionReceiver` в `work/ShiftNotification.kt` — закреплённое уведомление активной смены с быстрым добавлением заказа/чаевых и паузой.
- **Виджет** `CourierWidget` (RemoteViews, `ui/CourierWidget.kt`, `res/layout/widget_courier.xml`): балл из кэша погоды, текущая смена с `Chronometer` (учитывает паузу), кнопка «+ N ₽» добавляет заказ через `SettingsManager.addLiveOrder`. ViewModel перечитывает смену раз в минуту (`reloadLive`).
- **Белые списки** (мобильная сеть РФ без VPN): Open-Meteo, Google, Bing недоступны; доступны Яндекс, РИА, ТАСС, Лента, РБК. Новости в этом режиме — из российских RSS (`fetchRuFeedsNews` + `matchesCityInHeadline`), при отказе Google они подключаются автоматически. Погода — API Яндекса (если есть ключ) → страница Яндекс Погоды → Рамблер.Погода (`fetchRuWeather`); в режиме белых списков Open-Meteo не опрашивается вовсе.
- **Плавность**: `HorizontalPager` с `beyondViewportPageCount = 1`; подсветка фона (20 FPS) и контент — в отдельных `graphicsLayer`. Тяжёлые расчёты в composable — только в `remember`. Debug-сборка Compose заметно медленнее release — плавность оценивайте на release.
- **Отмена действий**: ViewModel хранит один `undoAction` и шлёт `UiEvent.Undo`; снекбар живёт на уровне `MainActivity`, а не экранов.
- **Debug vs Release**: `debug` имеет `applicationIdSuffix = ".debug"` и имя «Курьер Живи (Тест)», поэтому ставится рядом с релизным приложением на смартфон и не затрагивает пользовательские данные.

## Неочевидные правила

- Смена идентифицируется `ShiftEntry.id = System.currentTimeMillis()`; по нему же определяются месяц/неделя/день недели (`logic/Analytics.kt`). Не заменяйте id на автогенерируемый без добавления поля даты.
- Изменение схемы Room (поля `ShiftEntry`/`IntercomNote`) требует повышения `version` и `Migration` — иначе данные пользователей пропадут/приложение упадёт. Новые поля смены нужно добавить также в `Backup` и `parseShiftsJson`.
- Время из Open-Meteo приходит в поясе **города** (`utc_offset_seconds`), остальной код использует `Calendar.getInstance()` устройства. Не сравнивайте их напрямую.
- Release собирается с R8 (`isMinifyEnabled=true`); при добавлении рефлексивно создаваемых классов проверьте `app/proguard-rules.pro` и соберите `assembleRelease`.
- Пароль секретного меню хранится как SHA-256 (`DEV_PASSWORD_SHA256`); Яндекс-ключ задаётся в этом меню и не экспортируется в новый бэкап.
- `ITOG/` (keystore) и `app/release/` исключены из git — не добавляйте.
