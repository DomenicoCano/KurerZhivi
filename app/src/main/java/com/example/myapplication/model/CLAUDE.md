# model/

`Models.kt` — все data-классы и enum-ы: погода (`Weather`, `DetailedWeather`, `WeatherData`), транспорт, регион, новости, результат балла, `AppThemeMode`/`NotificationTone`/`Screen`, а также **Room-сущности** `ShiftEntry` и `IntercomNote`.

- `ShiftEntry.expenses/net/perHour/avgCheck/perKm` — вычисляемые свойства без поля в БД. `net = gross − расходы − tax`; `tips` уже входят в `gross`; `tax` считается при сохранении/редактировании по ставке из настроек (`taxFor`).
- Любое изменение сущностей = миграция Room + правки в `data/Backup.kt` и `data/Repository.kt` (`parseShiftsJson`).
- `HourlyScoreItem.timeMs` нужен кэшу прогноза, чтобы отбрасывать прошедшие часы.
