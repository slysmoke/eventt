# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**eventt** — кроссплатформенный Flutter-клиент для торговли в EVE Online под **Linux / Windows / macOS**. Аналог Evernus, написанный с нуля на Flutter. Код Evernus (`Evernus/`) присутствует только как референс — не используется, не собирается, не интегрируется.

## Development Workflow

### TDD — обязательно

**Сначала тест, потом реализация.**
1. Написать failing test
2. Написать минимальную реализацию, чтобы тест прошёл
3. Рефакторинг

### Dev Environment

```bash
nix-shell          # войти в окружение (flutter, ninja, pkg-config, libsecret, gtk3, sysprof, glib)
```

### Flutter Commands

```bash
flutter pub get                          # установить зависимости
flutter analyze                          # линтер
flutter test                             # все тесты
flutter test test/path/to/test.dart      # один тест
flutter test --name "test name"          # тест по имени
flutter run -d linux                     # запуск на Linux
flutter build linux                      # сборка Linux
flutter build windows                    # сборка Windows
flutter build macos                      # сборка macOS
```

> **Важно:** тесты с базой данных (drift) требуют `libsqlite3.so`. В `nix-shell` это выставляется автоматически через `LD_LIBRARY_PATH` в `shell.nix`. Вне nix-shell тесты с DB упадут с ошибкой `libsqlite3.so not found`.

После изменения схемы базы — регенерировать код:
```bash
flutter pub run build_runner build --delete-conflicting-outputs
```

## Architecture

Слоистая архитектура. Зависимости идут только вниз: UI → Domain → Data.

```
lib/
├── features/
│   ├── dashboard/
│   ├── market_browser/
│   ├── market_analysis/
│   ├── margin_tool/
│   ├── orders/
│   ├── assets/
│   ├── transactions/
│   └── journal/
│       └── <feature>/
│           ├── data/          # репозитории, datasource, DTO
│           ├── domain/        # entities, use cases, интерфейсы репозиториев
│           └── presentation/  # виджеты, Riverpod providers/notifiers
├── core/
│   ├── esi/           # ESI HTTP-клиент + кеширование
│   ├── auth/          # OAuth 2.0 (EVE SSO)
│   ├── database/      # SQLite (drift)
│   ├── sde/           # SDE updater + доступ к статической базе
│   ├── hotkeys/       # глобальные хоткеи
│   └── updater/       # авто-обновление через GitHub Releases API
test/                  # зеркалит структуру lib/
```

## Технологический стек

| Слой | Библиотека |
|---|---|
| State management | **Riverpod** (`flutter_riverpod`, `riverpod_annotation`) |
| База данных | **drift** (SQLite, type-safe, поддерживает изоляты) |
| HTTP | **dio** |
| Auth | **flutter_appauth** (OAuth 2.0 + PKCE) |
| Защищённое хранилище | **flutter_secure_storage** (токены) |
| Глобальные хоткеи | **hotkey_manager** |
| Авто-обновление | GitHub Releases API + **dio** для скачивания |
| Версия приложения | **package_info_plus** |
| Кодогенерация | **build_runner**, **riverpod_generator**, **drift_dev** |

## Активный персонаж

Выбор персонажа глобальный для всего приложения. Влияет на:
- Какие ордера показываются в My Orders
- Чьи налоги и broker fee используются в Margin Tool
- Через чей аккаунт открывается предмет в игре (`POST /ui/openwindow/marketdetails/`)

Dashboard — исключение: показывает **агрегированную статистику по всем персонажам** сразу.

Хранить выбранного персонажа в Riverpod:
```dart
final activeCharacterProvider = NotifierProvider<ActiveCharacterNotifier, Character?>();
```

## Ключевые технические требования

### База данных пользователя (`core/database/`)
- Создаётся автоматически при первом запуске (`onCreate` → `m.createAll()`)
- При обновлении приложения drift сравнивает `schemaVersion` в коде с версией в файле базы и запускает `onUpgrade` — данные сохраняются
- Каждое изменение схемы: увеличить `schemaVersion`, добавить шаг в `onUpgrade`, написать тест миграции
- Снапшоты схем хранить в `drift_schemas/` — они коммитятся в репо и используются в тестах миграций
- Пример структуры миграций:
```dart
@override
int get schemaVersion => 2;

@override
MigrationStrategy get migration => MigrationStrategy(
  onCreate: (m) => m.createAll(),
  onUpgrade: (m, from, to) async {
    if (from < 2) await m.addColumn(orders, orders.brokerFee);
    if (from < 3) await m.createTable(journal);
    // накапливается с каждой версией
  },
);
```
- Цепочка `if (from < N)` покрывает любой прыжок версий (пользователь не обновлял год → пройдут все шаги по порядку)

### ESI Client (`core/esi/`)
- Все запросы асинхронные, UI не блокируется
- Кешировать ответы по заголовкам `Cache-Control` / `ETag` / `Expires` из ESI
- Кеш хранить в drift (SQLite)
- При ошибке сети — отдавать stale-кеш, не крашиться
- Тяжёлые вычисления (аналитика, агрегация) — в `compute()` или drift isolate

### OAuth 2.0 (`core/auth/`)
- EVE SSO: authorization code flow + PKCE через `flutter_appauth`
- Токены хранятся в `flutter_secure_storage`
- Авто-обновление access token через refresh token
- Поддержка нескольких персонажей одновременно

### SDE — Static Data Export (`core/sde/`)
Два запроса при старте:
- **Проверка версии**: `GET https://raw.githubusercontent.com/slysmoke/evernus-db/main/latest_version.json` → поле `sdeVersion`
- **Скачивание базы**: `GET https://raw.githubusercontent.com/slysmoke/evernus-db/main/eve.db`

Обновлять только если `sdeVersion` изменилась или база отсутствует. База read-only, хранится отдельно от пользовательских данных.

SDE таблицы (из Evernus): `invTypes`, `invGroups`, `invMarketGroups`, `invMetaTypes`, `invMetaGroups`, `invTypeMaterials`, `mapRegions`, `mapConstellations`, `mapSolarSystems`, `mapSolarSystemJumps`, `mapDenormalize` (только groupID 5 и 15), `staStations`, `industryActivity`, `industryActivityMaterials`, `industryActivityProducts`, `industryActivitySkills`, `ramActivities`.

### Авто-обновление (`core/updater/`)
- При старте: `GET https://api.github.com/repos/<owner>/eventt/releases/latest`
- Сравнить `tag_name` с текущей версией (`package_info_plus`)
- Если новее — показать баннер/диалог, предложить скачать
- Скачать нужный asset по платформе (`linux`, `windows`, `macos`) через `dio`
- На Linux: скачать новый бинарь/AppImage и предложить перезапуститься
- На Windows/macOS: скачать installer и запустить

### Глобальные хоткеи (`core/hotkeys/`)
- Работают когда приложение свёрнуто / неактивно (`hotkey_manager`)
- Для Margin Tool: хоткей читает буфер обмена → считает → копирует цену

## Фичи

### Margin Tool
- Читает цену из буфера обмена (или поле ввода)
- Считает маржу с учётом налогов и broker fee **активного персонажа**
- Показывает цену для выставления ордера #1
- Копирует рассчитанную цену хоткеем (работает когда приложение неактивно)
- Открывает предмет в игре: `POST /ui/openwindow/marketdetails/?type_id=...`

### My Orders
- Ордера только **активного персонажа**
- Для каждого ордера: текущая лучшая цена рынка, маржа, дельта до #1
- Быстрое обновление: копирует новую цену → открывает в игре
- История покупок: по сколько куплены предметы (из транзакций) → реальная прибыль

### Dashboard
- Агрегированная статистика **по всем персонажам**
- Прибыль за день / неделю / месяц
- Затраты: налоги, broker fee, прочие расходы
- Графики P&L по времени

### Market Browser / Market Analysis
- Браузер по рынку (регион → группа → тип)
- Текущие ордера + исторические данные
- Аналитика: SMA, MACD (параметры см. `Evernus/TypeAggregatedDetailsFilterWidget.cpp`)

### Assets, Transactions, Journal
- Assets по всем персонажам с текущей стоимостью
- История транзакций и wallet journal
- Аналитика расходов/доходов

### P2P Market (в планах)
- Децентрализованная торговля между игроками
- Технология не определена

## CI/CD (GitHub Actions)

Собирать при пуше тега `v*.*.*`:
- Linux → AppImage
- Windows → installer (NSIS или Inno Setup)
- macOS → dmg

Артефакты публиковать в GitHub Releases — их подхватывает `core/updater/`.

## Референс (Evernus)

Ключевые файлы для понимания логики (не копировать, только читать):
- `Evernus/ESIOAuth.cpp` — OAuth flow
- `Evernus/MarketOrderDataFetcher.cpp` — получение рыночных данных
- `Evernus/TypeAggregatedDetailsFilterWidget.cpp` — параметры аналитики (SMA, MACD)
- `Evernus/ImportingDataModel.cpp` — расчёт отклонений и аналитика импорта
- `Evernus/EveDatabaseUpdater.cpp` — логика обновления SDE
