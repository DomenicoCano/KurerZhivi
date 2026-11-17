# ui/

- `CourierViewModel.kt` — единственная ViewModel и `CourierUiState`. Обновляйте состояние через `_uiState.update { }`. `refreshData()` отменяет предыдущую загрузку (`refreshJob`); `refreshIfStale()` вызывается из `MainActivity` раз в минуту. Диалог ошибки новостей — только при `manual = true` или один раз за запуск.
- `AppChrome.kt` — тема, живая градиентная подсветка (фаза читается в `drawBehind`, не в композиции — не переносите чтение `phase` в тело composable).
- `FinancePage.kt` — таймер смены, «+ Заказ» с чаевыми, общий диалог смены `ShiftDialog` (новая/завершение/редактирование). `CourierWidget.kt` — виджет рабочего стола.
- `MainScreen.kt` — шапка, 6 вкладок, диалог выбора города. Остальные файлы — по одной вкладке/экрану: `DashboardPage`, `FinancePage`, `IntercomPage`, `NewsForecastPage`, `StatsPage` (+ `AnalyticsSection`), `GearPage`, `SettingsScreen` (включая `DeveloperScreen`).
- Новый экран: добавьте значение в `Screen` и ветку в `MainActivity`; новая вкладка — в `tabs` и `when (page)` в `MainScreen`.
- Удаления делайте через ViewModel с `postUndo(...)` — снекбар «Отменить» общий для всех экранов.
- Числовые поля ввода фильтруются `filter(Char::isDigit)`; тексты UI пока написаны прямо в коде (не в `strings.xml`).
