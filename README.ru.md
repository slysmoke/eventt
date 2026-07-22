[![CI](https://github.com/slysmoke/eventt/actions/workflows/build.yml/badge.svg)](https://github.com/slysmoke/eventt/actions/workflows/build.yml)
![GitHub Release](https://img.shields.io/github/v/release/slysmoke/eventt)
![GitHub Downloads (all assets, all releases)](https://img.shields.io/github/downloads/slysmoke/eventt/total)
![GitHub Downloads (all assets, latest release)](https://img.shields.io/github/downloads/slysmoke/eventt/latest/total)
![GitHub Release Date](https://img.shields.io/github/release-date/slysmoke/eventt)
[![Visits Badge](https://badges.pufler.dev/visits/slysmoke/eventt)](https://badges.pufler.dev)

# <img src="app/icons/icon.png" width="40" valign="middle" alt="App icon"> EVE Night Trade Tools

[English](README.md) | Русский · **[Вики](docs/WIKI.ru.md)** ([EN](docs/WIKI.md))

Десктопный набор инструментов для торговли в [EVE Online](https://www.eveonline.com/) на Kotlin/JVM и Jetpack Compose for Desktop. Работает через ESI API (OAuth2 SSO): подтягивает ордера, кошелёк, ассеты и контракты твоих персонажей, а поверх — анализ рынка, алерты и FIFO-движок себестоимости и P&L.

## Возможности

- **Dashboard** — KPI по кошельку/ассетам/P&L, график дневного P&L за 30 дней, топ прибыльных и убыточных товаров по реализованному FIFO P&L, режим «Combine all» с агрегацией по всем персонажам и корпорациям
- **Characters** — подключение персонажей через SSO; работа от имени персонажа или корпорации
- **Market** — стакан ордеров и история цен по регионам (PLEX — через его глобальный рынок)
- **Analysis** — сканеры возможностей станционной и межрегиональной торговли с хоткей-очередями закупки
- **Assets** — просмотр имущества по станциям/структурам
- **Wallet** — журнал транзакций и P&L
- **Orders** — активные ордера со сравнением с рынком, подсветкой перебитых и системными уведомлениями, учётом relist-комиссий, историей ордеров и FIFO-инвентарём с возрастом стока
- **Alerts** — прайс-алерты с уведомлениями в приложении
- **Contracts** — трекер контрактов
- **Tools** — деление груза на партии (с пушем фитов в игру) и прайсер, привязанный к станции, где задокован персонаж
- **P2P Market** — прямая OTC-торговля между игроками поверх [Nostr](https://nostr.com/): публикация ордеров, переговоры в зашифрованных DM, расчёт в игре вне ESI-рынка
- **Trade Calc** — компактный always-on-top оверлей: берёт цены SELL/BUY из скопированной строки ордера, экспорта ордербука EVE или скопированного названия предмета (запрос в Jita); сразу кладёт цену перебития обратно в буфер и показывает комиссии, маржу и реальные суммы buy-out/sell-out по проходу стакана
- **Settings** — комиссии, источники данных и настройки приложения

Два глобальных хоткея работают, даже когда фокус в EVE: **Ctrl+Z** циклично проходит по очереди ордеров, открывает окно рынка в игре и копирует цену перебития в буфер; **Ctrl+M** открывает оверлей Trade Calc у курсора.

Подробное описание всех экранов и сценариев — в **[вики](docs/WIKI.ru.md)**.

## Требования

- JDK 21
- ~2 ГБ свободного места для импорта статических данных EVE при первом запуске

API-ключи и настройка не нужны — client ID для ESI OAuth встроен; при первом запуске просто логинишься своим аккаунтом EVE.

### Установка JDK 21

Нужен только для сборки из исходников или запуска fat jar (`java -jar`) — установщики `.dmg`/`.msi`/`.deb` несут собственную среду выполнения.

- **macOS**: скачай Temurin 21 `.pkg` под свой чип (x64 или aarch64) с [adoptium.net](https://adoptium.net/) и установи — Homebrew не нужен (если есть `brew`: `brew install openjdk@21`)
- **Linux**: `sudo apt install openjdk-21-jdk` (Debian/Ubuntu) или `sudo dnf install java-21-openjdk-devel` (Fedora)
- **Windows**: скачай Temurin 21 `.msi` с [adoptium.net](https://adoptium.net/) и установи (или `winget install EclipseAdoptium.Temurin.21.JDK`)

Проверь `java -version` — должно быть `21`.

### Установка на macOS (неподписанная сборка)

Сборка для macOS не подписана Apple Developer ID и не нотаризована (нет платного сертификата), поэтому Gatekeeper блокирует первый запуск с предупреждением «app is damaged» или «unidentified developer». После установки `.dmg` один раз сними карантин:

```bash
xattr -cr /Applications/eventt.app
```

Для macOS публикуются две отдельные сборки — `eventt-macos-x64.dmg` для Intel и `eventt-macos-arm64.dmg` для Apple Silicon (M1/M2/M3/M4); универсального бинарника нет — бери под свой чип (меню Apple → Об этом Mac).

## Авторизация и хранение данных

Логин идёт через OAuth2 Authorization Code flow с PKCE — client secret в приложение не зашит. Access/refresh-токены персонажей шифруются на диске (AES-256-GCM) локальным ключом машины; всё хранится в стандартной пользовательской папке данных ОС (`%APPDATA%\eventt` на Windows, `~/Library/Application Support/eventt` на macOS, `$XDG_DATA_HOME/eventt` или `~/.local/share/eventt` на Linux). Старые установки использовали `~/.eve-trader/` или `~/.eventt/` — данные оттуда подхватываются автоматически при первом запуске после обновления.

## P2P Market

Внебиржевая торговля между игроками, построенная на [Nostr](https://nostr.com/) вместо центрального сервера: ордера — это адресуемые события NIP-33, публикуемые на несколько публичных реле (настраиваются в Settings), а переговоры идут в зашифрованных DM по NIP-17. Каждый персонаж EVE получает собственную подпись автоматически — при первом открытии вкладки P2P Market с этим персонажем; больше настраивать нечего.

## Сборка и запуск

```bash
./gradlew build              # Компиляция и тесты всех модулей
./gradlew run                # Локальный запуск десктоп-приложения
./gradlew test               # Все юнит-тесты
./gradlew :module:test       # Тесты одного модуля (например :core:database:test)
```

Цели: JVM 21 / Kotlin 2.4.0, стиль `kotlin.code.style=official`. Линтинг — ktlint и detekt:

```bash
./gradlew ktlintCheck         # Проверка стиля (ktlintFormat — автопочинка)
./gradlew detekt              # Статический анализ
```

Оба входят в `./gradlew check` и гейтят CI.

## Пакетирование

```bash
./gradlew createDistributable   # Портативная папка-образ (идёт в zip-релиз)
./gradlew packageDeb            # .deb (Linux)
./gradlew packageMsi            # .msi (Windows, установка per-user)
./gradlew packageDmg            # .dmg (macOS)
./gradlew shadowJar             # Запускаемый fat jar (java -jar app/build/libs/eventt-<version>.jar)
```

Fat jar **не** кроссплатформенный, несмотря на формат — в него попадают нативные библиотеки рендеринга Skiko/Compose той ОС/архитектуры, на которой шла сборка (`compose.desktop.currentOs`). CI собирает по одному на ОС (для macOS — на архитектуру): `eventt-linux.jar` / `eventt-windows.jar` / `eventt-macos-x64.jar` / `eventt-macos-arm64.jar` — бери свой, как и с zip или установщиком.

Есть и пакет для NixOS через `flake.nix` (сначала `./gradlew createDistributable`).

## Релизы и автообновление

Пуш тега `vX.Y.Z` запускает `.github/workflows/release.yml`: собираются zip, нативный установщик и fat jar для Linux/Windows/macOS и публикуются как GitHub Release. Workflow можно запустить и вручную (`workflow_dispatch` во вкладке Actions), чтобы проверить сборку без настоящего релиза.

При старте приложение проверяет последний GitHub Release и показывает баннер обновления в один клик (см. `github.repo` в `gradle.properties`). Самообновление работает для портативного zip, `.dmg`, per-user `.msi` и fat jar; системная установка (например `.deb` через `apt`) самообновляться не может — приложение отправит на страницу релиза.

## Архитектура

Модули организованы в `core/`, `features/` и `ui/`:

- **`core/`**: model, database, http, cache, auth, esi, queue, staticdata, image, everef, marketlogs, nostr
- **`features/`**: characters, market, assets, wallet, orders, dashboard, alerts, contracts, settings, overlay, tools, p2pmarket
- **`ui/`**: theme (Material 3 + палитра EVE), common (общие Compose-компоненты)

```
Main.kt → DatabaseManager.initialize() → EventtApp (Compose)
    ↓
:features:* → :core:{model, database, auth, esi, cache, queue}
:core:esi   → :core:{auth, cache, http, queue, database}
:core:auth  → :core:{database, http, model}
```

## Лицензия

[MIT](LICENSE)
