# data/

- `Network.kt` — геокодинг и погода (Open-Meteo; Яндекс.Погода v3 GraphQL `X-Yandex-Weather-Key` с откатом на старый v2/informers; если Open-Meteo недоступен, а Яндекс ответил — всё строится из Яндекса через `YandexWeather.toWeatherData`), RSS-новости (Google News; российские ленты РИА/ТАСС/Лента/РБК при «белых списках» или при отказе Google, фильтр по `cityStem`). На тарифе «Тестовый» нет `feelsLike`/`uvIndex` — не добавляйте их в запрос.
- Без ключа и при белых списках погода берётся со страницы `yandex.ru/pogoda?lat&lon` (`parseYandexPogodaHtml` — по тексту для скринридеров вида «22:00: 0°, малооблачно, Ощущается как -5°.», а не по хэшированным CSS-классам), затем Рамблер.Погода (неофициальный JSON, только 5 встроенных городов, `RAMBLER_PATHS`). Порядок: `fetchRuWeather`. Если Яндекс сменит вёрстку — парсер вернёт null, проверка в `RuWeatherParsingTest`. Все вызовы — `HttpURLConnection` на `Dispatchers.IO`; ошибки глотаются и превращаются в запасные значения (`Weather.UNKNOWN`, исключение для новостей — его ловит ViewModel).
- `SettingsManager.kt` — SharedPreferences, кэш прогноза (JSON, с id города), список недавних городов, флаг миграции в Room.
- `AppDatabase.kt` / `Repository.kt` — Room (DAO-интерфейсы и БД в одном файле) и `CourierRepository`; здесь же разовая миграция старого JSON из SharedPreferences и функции разбора `parseShiftsJson`/`parseIntercomsJson`.
- `Backup.kt` — формат бэкапа v2. `Backup.parse` полностью проверяет данные **до** записи (битый файл не должен затирать текущие); ключ Яндекса не экспортируется, но читается из старых бэкапов.

В юнит-тестах `org.json` реальный (зависимость `org.json:json`), в `android.jar` он заглушка.
