# NIP-поддержка дефолтных релеев (NIP-11)

Снято 2026-07-16 запросом `GET https://<relay>` с `Accept: application/nostr+json`.

| Релей | Софт | NIP-09 (kind 5) | NIP-40 (expiration) | NIP-42 (auth) | Прочее важное |
|---|---|---|---|---|---|
| nos.lol | strfry 1.1.0 | ✅ | ✅ | ❌ | max_limit 500, max 20 подписок |
| nostr.wine | nostr.wine 0.3.3 | ✅ | ✅ | ✅ | ⚠️ **payment_required + restricted_writes** — запись только после оплаты (admission 18 888 sats); NIP-50 search |
| relay.primal.net | strfry 1.0.3 | ✅ | ✅ | ❌ | max_limit 500, max 20 подписок |
| relay.snort.social | memlay 0.2.0 | ❌ | ❌ | ❌ | ⚠️ **in-memory релей, supported_nips = [1, 11]** — не хранит долгоживущие события, не годится для 2-недельных ордеров |
| relay.nostr.info | strfry 1.0.4 | ✅ | ✅ | ❌ | max 10 подписок (у нас их 7 — впритык) |
| relay.wellorder.net | nostr-rs-relay 0.9.0 | ✅ | ✅ | ❌ | старый софт (2022-й список NIP), но 9/40 есть |

Полные `supported_nips`:

- **nos.lol**: 1, 2, 4, 9, 11, 28, 40, 45, 70, 77
- **nostr.wine**: 1, 2, 4, 9, 11, 40, 42, 50, 70
- **relay.primal.net**: 1, 2, 4, 9, 11, 22, 28, 40, 70, 77
- **relay.snort.social**: 1, 11
- **relay.nostr.info**: 1, 2, 4, 9, 11, 22, 28, 40, 70, 77
- **relay.wellorder.net**: 1, 2, 9, 11, 12, 15, 16, 20, 33, 40

## Статус

Выводы ниже реализованы 2026-07-16: relay.snort.social и nostr.wine заменены в
`DEFAULT_RELAYS` на relay.damus.io и offchain.pub (оба — strfry, NIP-9/40,
бесплатная запись; проверены по NIP-11), существующие установки мигрируются
автоматически (`NostrRelayManager.migrateRetiredDefaultRelays`). Приложение
теперь само запрашивает NIP-11 у релеев (`Nip11Service`), хранит
supported_nips в `nostr_relays` и предупреждает в Settings о релеях с платной
записью или без NIP-40.

## Выводы

1. **NIP-40 и NIP-09 поддерживают 5 из 6 релеев** — авто-протухание ордеров и
   kind-5-удаление будут работать почти везде. Клиентская фильтрация просрочки
   всё равно обязательна (из-за relay.snort.social и любых будущих релеев).
2. **relay.snort.social — плохой кандидат для ордеров**: in-memory, не хранит
   события долговременно, не поддерживает ни expiration, ни deletion. Для
   2-недельных объявлений бесполезен (для эфемерных пингов presence — наоборот,
   идеален).
3. **nostr.wine фактически read-only для нас**: запись платная
   (`restricted_writes: true`). Наши publish туда молча отбрасываются — стоит
   убрать из дефолтов записи или пометить write=false.
4. NIP-13 (PoW) в `supported_nips` никто не декларирует, `min_pow_difficulty: 0`
   у nostr.wine — майнинг PoW для публикации сейчас не нужен.
5. NIP-17/44/59 (DM-стек) релеи не декларируют и не должны — для них gift wrap
   это обычный kind 1059; хранение работает везде, где хранятся обычные события.

## Ревизия 2026-11-17: тест записи

NIP-11 врёт: `supported_nips` и отсутствие `restricted_writes` не гарантируют,
что релей примет наши события (whitelist kinds, WoT-фильтры, регистрация).
Теперь кандидат проверяется реальной записью: одноразовый ключ, свой `t`-тег,
`expiration` +120 с, 4 события (ордер 30735, gift wrap 1059 с `created_at`
−2 дня, чат 42, presence 30315), затем чтение обратно по `ids`.

| Релей | 30735 | 1059 | 42 | 30315 | Итог |
|---|---|---|---|---|---|
| relay.primal.net | ✅ | ✅ | ✅ | ✅ | дефолт |
| offchain.pub | ✅ | ✅ | ❌ WoT | ✅ | дефолт; чат только с ключей из их web of trust |
| relay.damus.io | — | — | — | — | дефолт; WebSocket отдаёт 503 (NIP-11 жив) |
| **nostr.21crypto.ch** | ✅ | ✅ | ✅ | ✅ | новый дефолт (strfry 1.1.0, 200 подписок) |
| **nostr.azzamo.net** | ✅ | ✅ | ✅ | ✅ | новый дефолт (strfry 1.0.4, 100 подписок) |
| **relay.angor.io** | ✅ | ✅ | ✅ | ✅ | новый дефолт (strfry 1.1.3, 20 подписок) |
| nostr.stakey.net, nostr.sathoarder.com, nostr-01.yakihonne.com | ✅ | ✅ | ✅ | ✅ | запасные |
| nostr.bitcoiner.social | ✅ | ✅ | ❌ WoT | ✅ | — |
| relay.nos.social, relay.coinos.io | ❌ | ✅ | ❌ | ❌ | whitelist kinds |
| relay.westernbtc.com | ❌ | ✅ | ❌ | ❌ | нужна регистрация |
| relay.nostr.info | ❌ | ❌ | ❌ | ❌ | «not accepted by this relay» — убран |
| nos.lol, relay.wellorder.net | — | — | — | — | лежат (таймаут / 503) — убраны |

Миграция: nos.lol → nostr.21crypto.ch, relay.wellorder.net → nostr.azzamo.net,
relay.nostr.info → relay.angor.io (`RETIRED_DEFAULT_RELAYS`).

Требования к релею: NIP-9/40/11, бесплатная запись всех наших kinds,
`max_subscriptions` ≥ 10 (у нас 7 REQ на релей), `max_limit` ≥ 500 (история
чата), сообщение ≥ 64 KB, нет `created_at_lower_limit` уже 2–3 суток
(NIP-59 сдвигает `created_at` gift wrap до 2 суток назад).
