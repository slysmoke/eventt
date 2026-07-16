# Nostr-флоу P2P Market: обзор архитектуры, уязвимости и рекомендации

Ревью `core:nostr` (`NostrEventFactory`, `OrderRepository`, `ReservationService`,
`NostrRelayManager`, `QuartzGateway`, DAO) от 2026-07-16. Три конкретные уязвимости
в текущей реализации — в разделе 4.

---

## 1. Как флоу устроен сейчас (по коду)

**Объявления (ордера).** Используется правильный примитив — *addressable events*
(бывший NIP-33, теперь часть NIP-01): кастомный `kind: 30735` из диапазона
30000–39999, где `d`-тег = `order_uuid`. Комбинация `(kind, pubkey, d)` — это
«адрес»: реле обязано хранить только событие с максимальным `created_at` по этому
адресу. Опубликованное событие выглядит так:

```json
{
  "kind": 30735,
  "pubkey": "<hex-pubkey продавца>",
  "created_at": 1752650000,
  "tags": [
    ["d", "3f2b9c1e-...-uuid"],
    ["t", "eventt-p2pmarket"],
    ["t", "side:sell"],
    ["t", "type:34"],
    ["t", "region:10000002"],
    ["expiration", "1753859600"],
    ["price", "5.5"],
    ["qty_total", "1000000"],
    ["qty_remaining", "750000"],
    ["min_lot", "100000"],
    ["min_lot_unit", "units"],
    ["trader_char", "Dmitri Winston"],
    ["trader_char_id", "2112345678"]
  ],
  "content": "",
  "id": "...", "sig": "..."
}
```

- **Обновление статуса** = републикация с тем же `d` и свежим `created_at`
  (`NostrEventFactory.republishOrder`). «Зарезервировано» = уменьшенный
  `qty_remaining`, «продано/отменено» = `qty_remaining: 0`, продление = новый
  `expiration`. Отдельных статусных событий нет — и это правильно.
- **Несколько товаров от одного продавца** — уже поддержано: один товар = один
  `d`-uuid, реле хранит по одному последнему событию на каждый `d`.
- **Резервирование** идёт через NIP-17 DM: JSON-пейлоады
  `reservation_request/response/cancel` → `ChatMessageEvent` (kind 14, никогда не
  публикуется в открытую) → seal (kind 13, NIP-44) → gift wrap (kind 1059, NIP-59)
  с эфемерным ключом. Локальный SQLite — source of truth, потому что реле не
  гарантируют реплей старых DM.
- **Подписки**: `{kinds:[30735], #t:["eventt-p2pmarket"]}` на ордера,
  `{kinds:[1059], #p:[myPubkey]}` на DM, `{kinds:[7733], #p:[myPubkey]}` на receipts.
- **Фильтрация просрочки** — на клиенте: `queryActive` берёт только
  `expiration > now AND qty_remaining > 0`, плюс есть
  `DELETE FROM nostr_orders WHERE expiration <= now`.

---

## 2. Kinds: стоит ли переходить на NIP-15 (30017/30018)?

**Короткий ответ: нет, оставаться на своём 30735.**

- **NIP-15** (`30017` stall / `30018` product) спроектирован под классический
  e-commerce: stall с валютами и shipping-зонами, product с картинками и
  категориями, checkout-машина из type 0–3 сообщений поверх **NIP-04** DM
  (устаревшего). Его семантика (доставка, фиат/BTC) не ложится на EVE-ордера
  (регион, type_id, min_lot, ISK, qty_remaining как протокольное состояние).
  Переход дал бы интероп с Plebeian-style клиентами, которым эти ордера всё равно
  бесполезны, ценой втискивания своей модели в чужой JSON-контракт. NIP-15 к тому
  же полуживой — экосистема ушла в NIP-99.
- **NIP-99** (`30402`, classified listings) — ближе по духу («объявление» с
  `price`, `status: active/sold`, `published_at`), и если хочется хоть какой-то
  интероп, можно позаимствовать его конвенцию тега статуса:
  `["status", "active" | "sold"]` вместо/вдобавок к `qty_remaining = 0`. Но полный
  переход на 30402 тоже не даёт ничего: чужие клиенты покажут ордер как
  бессмысленное объявление без DM-протокола.
- Текущий выбор — addressable кастомный kind + `#t`-тег для дискавери + NIP-40 —
  это ровно то, что рекомендует протокол для доменных приложений. Всё желаемое
  (обновляемость, несколько товаров, авто-протухание) уже покрыто.

Единственное дополнение — явный тег статуса для читаемости и дешёвой фильтрации на
реле (`#t`-фильтром можно будет запрашивать только активные):

```kotlin
// в buildTags(): вместо неявного qty_remaining==0
.add(arrayOf("t", if (qtyRemaining > 0) "status:active" else "status:closed"))
```

---

## 3. Удаление: NIP-40, клиентская фильтрация, kind 5

Три уровня, от надёжного к необязательному:

**Уровень 1 — замещение (гарантировано протоколом).** Для addressable-событий
«latest `created_at` wins» — это **обязательное** поведение NIP-01, его
поддерживает любое реле. `cancelOrder` (републикация с `qty_remaining: 0`) — это
tombstone, который *вытесняет* старую версию везде. Это надёжнее любого kind 5.
Это уже сделано — это главный механизм, остальное — гигиена.

**Уровень 2 — NIP-40 `expiration`.** Реле с поддержкой NIP-40 перестают отдавать
событие после метки и могут удалить его физически. Реле без поддержки будут
отдавать его вечно — поэтому клиентская фильтрация **обязательна**, и она уже есть
в двух местах (SQL `expiration > ?` и `deleteExpired`). Одна дырка:
`parseOrderEvent` требует наличие `expiration` (хорошо — событие без него
отбрасывается), но не проверяет его при *приёме*. Стоит отбрасывать уже протухшие
события прямо в подписке, чтобы реле-игнорщик NIP-40 не заливал мёртвые ордера в БД:

```kotlin
// NostrRelayManager, в onEvent ордер-подписки:
val parsed = NostrEventFactory.parseOrderEvent(event) ?: return
if (parsed.expiration <= System.currentTimeMillis() / 1000) return  // NIP-40 на клиенте
```

Вторая мелочь: `republishOrder(renew=false)` сохраняет старый `expiration` —
значит tombstone отмены живёт до исходного дедлайна и сам исчезнет. Это правильное
поведение, не трогать (если укоротить expiration у tombstone, реле, удалившее его
раньше чужих кэшей, воскресит «активную» копию у клиентов, которые не видели
отмену).

**Уровень 3 — kind 5 (NIP-09), ручное удаление.** Для addressable-событий deletion
request ссылается не на id, а на **адрес** через `a`-тег, плюс `k`-тег:

```json
{
  "kind": 5,
  "pubkey": "<pubkey продавца>",
  "tags": [
    ["a", "30735:<pubkey>:3f2b9c1e-...-uuid"],
    ["k", "30735"]
  ],
  "content": "order cancelled"
}
```

Важно: по спеке `a`-тег в kind 5 просит удалить **все версии до `created_at`
самого kind 5** — и реле **MAY** это исполнить, гарантий нет. Поэтому правильный
порядок ручного удаления: **сначала tombstone-републикация (qty_remaining=0),
потом kind 5 как best-effort зачистка**. Никогда не полагаться на kind 5 один —
клиент, подключённый к реле, которое его проигнорировало, увидит ордер живым.

```kotlin
// NostrEventFactory
fun buildDeletionEvent(signer: NostrSignerSync, order: ParsedOrder): Event =
    QuartzGateway.signEvent(
        signer,
        System.currentTimeMillis() / 1000,
        5,
        arrayOf(
            arrayOf("a", "$ORDER_KIND:${order.pubkey}:${order.orderUuid}"),
            arrayOf("k", ORDER_KIND.toString()),
        ),
        "",
    )
```

В `OrderRepository.cancelOrder`: после `setRemainingQty(order, 0)` опубликовать ещё
и deletion. Клиентская сторона приёма kind 5: при получении — удалить локальную
строку, если `created_at` удаления новее `created_at` ордера и pubkey совпадает.

---

## 4. Уязвимости текущего DM-флоу резервирования

Три **конкретные находки в коде**, плюс общие риски.

### 4.1 `handleIncomingDm` не проверяет отправителя ответов и отмен — спуфинг

`ReservationService.kt:245-262`: `reservation_response` и `reservation_cancel`
применяются к БД **по одному только `tradeId`**, без проверки, что `fromPubkey` —
это реальный контрагент сделки. `tradeId` — UUID, ходивший только внутри
шифрованных DM, так что угадать его извне трудно, но: любой, кто узнал tradeId
(лог, скриншот, второй девайс, компрометация одной стороны), может прислать
фейковый `"accepted": true` покупателю (тот поедет в Jita к несуществующей сделке)
или `reservation_cancel` и снести чужую резервацию. Исправление — одна проверка:

```kotlin
"reservation_response" -> {
    val resp = ... ?: return null
    val res = withContext(Dispatchers.IO) { NostrReservationDao.getByTradeId(resp.tradeId) } ?: return null
    if (res.sellerPubkey != fromPubkey) return null  // ответ может прислать только продавец
    ...
}
"reservation_cancel" -> {
    ...
    if (res.buyerPubkey != fromPubkey) return null   // отменить может только покупатель
    ...
}
```

Смежное: убедиться (тестом на Quartz), что `unsealOrNull` сверяет
`rumor.pubkey == seal.pubkey` — это требование NIP-17; если библиотека этого не
делает, `unwrapped.pubKey` можно подделать, и тогда проверка выше тоже обходится.
Тест добавить в `QuartzSpikeTest`.

### 4.2 Oversell в `respond()` — двойная продажа одного товара

`ReservationService.kt:127-147`: при accept `reservedQty = reservation.qty` без
проверки `order.qtyRemaining >= reservation.qty`, а декремент —
`coerceAtLeast(0)`. Сценарий: осталось 100 единиц, два покупателя одновременно
просят по 100, продавец жмёт accept обоим — оба получают DM «зарезервировано 100»,
qty уходит в 0 молча. Это и есть double-spend, и он закрывается на стороне
единственного арбитра (продавца):

```kotlin
if (accept && order.qtyRemaining < reservation.qty) return false  // или авто-decline с причиной
```

Архитектурно модель «продавец — единственный, кто может подписать новую версию
ордера» уже race-free на уровне протокола; дыра чисто в клиентской логике accept.

### 4.3 Спам фейковыми резервами: входящие запросы не валидируются

`handleIncomingDm` для `reservation_request` вставляет запись **на любой пейлоад
от любого pubkey** — включая запросы на несуществующие ордера, чужие ордера и qty,
нарушающий min_lot. Бот, знающий pubkey продавца (он публичен — стоит на каждом
ордере), может залить Incoming Requests тысячами гифт-врапов. Дешёвая оборона в
порядке ценности:

```kotlin
"reservation_request" -> {
    val req = ... ?: return null
    val myIdentity = NostrIdentityService.getIdentityByPubkey(req.orderPubkey) ?: return null // ордер должен быть моим
    val order = withContext(Dispatchers.IO) {
        NostrOrderDao.getByCoordinate(req.orderId, req.orderPubkey)
    } ?: return null                                            // ордер должен существовать
    if (req.qty <= 0 || req.qty > order.qtyRemaining) return null
    if (order.minLotUnit == "units" && req.qty < order.minLot) return null
    // ponytail: считаем pending-запросы от fromPubkey и молча дропаем сверх N — WoT-скоринг добавить, когда появится реальный спам
    ...
}
```

Дальше по нарастающей, когда (если) спам станет реальным: NIP-13 PoW на
гифт-врапах запросов (вся обвязка уже есть — `ORDER_POW_DIFFICULTY` просто
выключен), затем web-of-trust (принимать запросы только от pubkey с
follow-графом/репутацией из kind-7733 receipts — `ReputationAggregator` уже
наполовину это умеет).

### 4.4 NIP-04 vs NIP-17 — уже правильно

NIP-04 **не используется**, и не надо: NIP-04 — это AES-256-CBC без аутентификации
(податлив к ciphertext-malleability), с ECDH shared secret без HKDF, и главное —
**метаданные голые**: kind 4 несёт настоящий `pubkey` отправителя, `p`-тег
получателя и точный `created_at`, то есть любое реле видит весь граф «кто с кем
торгует и когда». Стек NIP-17/44/59 закрывает всё это: ChaCha20 + HMAC-SHA256
(аутентифицированно), внешний wrap подписан одноразовым ключом (отправитель
скрыт), `created_at` врапа рандомизирован до −2 дней (тайминг скрыт), виден только
получатель. Единственная оставшаяся утечка — сам факт «этому pubkey кто-то пишет»;
это потолок модели без спец-реле (NIP-42 auth-реле, где kind 1059 отдаётся только
владельцу `p`).

### 4.5 Ещё два «узких места» не про крипту

- **`holdUntil` — чисто декларативный**: истёкший hold не возвращает qty
  автоматически; продавец должен вручную нажать release. Забыл — товар висит
  замороженным. Дешёвый фикс: при `queryActive`/открытии экрана — авто-`release`
  для `accepted`-резерваций с `holdUntil < now`.
- **`publish()` — fire-and-forget без подтверждений**: OK-ответы реле не
  отслеживаются, так что «опубликовано» реально значит «отправлено в сокет, если
  он был открыт». Для tombstone отмены это значит: отмена может не дойти ни до
  одного реле, а UI покажет её успешной. Минимум — слушать OK и ретраить
  неподтверждённые события при реконнекте (outbox-таблица).

---

## 5. Онлайн/офлайн статус

Два рабочих варианта; рекомендация — **NIP-38** как основной и эфемерный пинг
только внутри открытой сделки.

### Вариант A — NIP-38 (`kind: 30315` + NIP-40), «жирный» presence

`30315` — тоже addressable, `d` = тип статуса. Трюк: публикуется heartbeat каждые
N минут с `expiration = now + 2N`; «онлайн» = у пира есть неистёкший статус. Реле
хранит ровно одно событие на pubkey (замещение по `d`), NIP-40 гасит его после
ухода, а на реле без NIP-40 клиент фильтрует по тому же `expiration`-тегу сам —
симметрично ордерам.

```json
{
  "kind": 30315,
  "pubkey": "<мой pubkey>",
  "created_at": 1752650000,
  "tags": [
    ["d", "general"],
    ["expiration", "1752650600"]
  ],
  "content": "trading in The Forge"
}
```

```kotlin
// core:nostr — presence в стиле NostrEventFactory
const val STATUS_KIND = 30315
private const val HEARTBEAT_SECONDS = 300L

object PresenceService {
    fun buildHeartbeat(signer: NostrSignerSync): Event {
        val now = System.currentTimeMillis() / 1000
        return QuartzGateway.signEvent(
            signer, now, STATUS_KIND,
            TagArrayBuilder<Event>()
                .add(DTag("general").toTagArray())
                .expiration(now + 2 * HEARTBEAT_SECONDS)   // умирает через 2 пропущенных пинга
                .build(),
            "online",
        )
    }

    // пир онлайн = его 30315 ещё не истёк
    fun isOnline(statusEvent: Event): Boolean =
        (statusEvent.expiration() ?: 0) > System.currentTimeMillis() / 1000
}

// в NostrRelayManager.connect(): heartbeat-луп + подписка
s.launch {
    val signer = QuartzGateway.signerFor(identity.keyPair)
    while (true) {
        publish(PresenceService.buildHeartbeat(signer))
        kotlinx.coroutines.delay(HEARTBEAT_SECONDS * 1000)
    }
}
// подписка на статусы контрагентов активных сделок:
// Filter(kinds=[30315], authors=[...pubkeys из nostr_reservations...])
```

Плюсы: работает, даже если стороны онлайн не одновременно («был в сети 5 минут
назад» бесплатно из `created_at`), хранится на реле, ложится на существующую
replaceable+expiration механику один в один. Минус — приватность: presence
публичен, любой наблюдатель строит график активности по pubkey. Смягчение: слать
heartbeat только пока открыта вкладка P2P Market, и не слать вовсе, если нет
активных сделок/ордеров.

### Вариант B — эфемерные события (kind 20000–29999), пинг внутри сделки

Реле по NIP-01 транслируют эфемерные события подписчикам и **не хранят** их.
Годится для «печатает…»/live-присутствия в открытом чате сделки, где обе стороны
и так онлайн:

```json
{
  "kind": 20735,
  "pubkey": "<мой pubkey>",
  "created_at": 1752650000,
  "tags": [
    ["p", "<pubkey контрагента>"],
    ["trade", "<tradeId>"]
  ],
  "content": ""
}
```

```kotlin
const val PING_KIND = 20735 // эфемерный диапазон — реле не хранят

// отправитель: пинг каждые 30с, пока экран сделки открыт
// приёмник: подписка Filter(kinds=[20735], tags={"p" to listOf(myPubkey)}),
// пир «онлайн», если последний пинг моложе 90с:
class PeerPresence {
    private val lastSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun onPing(event: Event) { lastSeen[event.pubKey] = System.currentTimeMillis() / 1000 }
    fun isOnline(pubkey: String) = (lastSeen[pubkey] ?: 0) > System.currentTimeMillis() / 1000 - 90
}
```

Минусы: нулевая история (оба должны быть онлайн одновременно), `trade`-тег и
`p`-тег видны реле открытым текстом (утечка «эти двое сейчас в сделке» — если это
критично, пинг можно гнать тем же gift-wrap-каналом, но это дороже).

**Рекомендация:** NIP-38 для глобального индикатора в списках запросов/ордеров
(переиспользует всю существующую механику), эфемерный пинг добавлять только если
появится реальный чат сделки с потребностью в live-индикации.

---

## 6. Сводка архитектурных советов

Что уже правильно и трогать не надо: addressable kind 30735 вместо NIP-15;
tombstone-републикация как основной механизм отмены; NIP-17/44/59 вместо NIP-04;
локальная БД как source of truth для DM-хендшейка; клиентская фильтрация
expiration.

Что сделать, по приоритету:

1. **Безопасность (пункты 4.1–4.3)** — проверка `fromPubkey` против контрагента
   сделки, guard от oversell в `respond()`, валидация входящих
   `reservation_request` против локального ордера. Это маленькие диффы и реальные
   дыры.
2. **Drop просроченных событий при приёме** в подписке (одна строка,
   п.3-уровень-2).
3. **Kind 5 после tombstone** при ручной отмене — best-effort гигиена реле.
4. **Авто-release просроченных hold'ов** — убирает замороженный qty у забывчивого
   продавца.
5. **Outbox с ретраем по OK-ответам реле** — иначе «отменено» может быть локальной
   иллюзией.
6. **NIP-38 presence** — когда дойдут руки до UI-индикатора; эфемерные пинги
   отложить до появления чата.

Пропущено сознательно: WoT-скоринг и PoW на DM (добавлять, когда появится
наблюдаемый спам — обвязка PoW уже готова), миграция на NIP-99 (интероп не даёт
ценности этому домену).
