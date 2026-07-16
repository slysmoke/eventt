# EVE Night Trade Tools — Wiki

English | [Русский](WIKI.ru.md)

A desktop trading toolkit for EVE Online: market analysis, order management, P&L accounting, and an in-game overlay calculator. Kotlin/JVM + Compose Desktop; all data comes from the official ESI API via EVE SSO.

---

## Quick start

1. Launch the app and add a character on the **Characters** screen — a browser opens with EVE SSO; after login the token is stored locally.
2. Pick a character (or corporation) in the sidebar — every screen operates in the selected context.
3. In **Settings**, set your fees (Sales Tax, Broker Fee, Advanced Broker Relations skill) — every P&L figure and beat price is computed from them.
4. For in-game integration, point Settings at your EVE **Marketlogs** folder (where EVE writes market exports) — this enables automatic order-book import into the overlay.

**Where data lives:** the SQLite database and tokens sit in the OS's standard app-data directory, under `eventt` (legacy `~/.eve-trader` and `~/.eventt` are migrated automatically on first launch).

---

## Global hotkeys

These work even while the EVE client has focus.

| Hotkey | Action |
|---|---|
| **Ctrl+Z** | Next item of the active queue: on the Orders screen — cycles your own orders, on Analysis — the buy queue (Station Trading or Inter-Region, per active tab) |
| **Ctrl+M** | Open/close the Trade Calc overlay at the cursor |

---

## Screens

### Dashboard

Summary for the selected character/corporation:

- **KPIs**: wallet balance, asset value, 30-day cash flow.
- **Cash Flow** — money flow from the wallet journal (today / 7 days / 30-day income and expenses). Deliberately not called P&L: buying stock shows up as a big negative here even when the trade is profitable.
- **Realized P&L (FIFO)** — profit counted only once a lot is actually sold, fees included. This is the real P&L.
- **Daily Realized P&L — 30d** — realized profit per sell day as bars around a zero baseline; hovering shows the day and amount. Bars sum to the "Realized 30 days" card.
- **Top Items — Realized 30d** — up to 5 most profitable and 5 most losing items over 30 days.
- **Recent Transactions** — latest trades.

The **Combine all** switch in the header aggregates everything across all characters and corporations at once (balances are summed per entity; ESI sync is skipped in this mode — it uses the locally accumulated data).

### Characters

Add characters via EVE SSO, manage tokens, and pick the active context — a character or a corporation (a corporation acts through one of its characters' tokens).

### Market

- **Browser** — find an item via the market-group tree or by name; regional order book and price history. PLEX is special-cased: it trades in a single global virtual region (ID 19000001), not per-region.
- Price alerts can be created from here as well.

### Analysis

Two opportunity scanners:

- **Station Trading** — finds items with a healthy margin between buy and sell orders at one station. Filters: region/station, item category, margin, traded volume, volume modifier. Results support drag-select and feed the Ctrl+Z queue: the first press opens the item's market window in-game and copies the buy price, the second copies the quantity to buy.
- **Inter-Region** — cross-region hauling: five trade types (Sell→Buy instant, Sell→Sell Order, Buy Order→Buy, Buy→Sell, and Safe Buy→Sell with an "unattractive to outbid" source price). Computes shipping cost per m³, real profit by walking the order book (not just the single best price), the 7-day trend, and the deviation from the weekly average.

**Margin is net everywhere**: after broker fees and sales tax (Inter-Region also nets out shipping), relative to the sell price — the same figure the Trade Calc overlay shows, and what the "Margin %" filter compares against. The separate **ROI** column is the same net profit relative to the capital outlaid (buy price, plus shipping for Inter-Region).

With many candidates (>1000) the scanner switches to a bulk fetch of the whole region's order book — faster than thousands of per-type requests.

### Orders

Your active orders and everything around them. Tabs: **Sell / Buy / History / Inventory**.

- **Beaten orders**: the app compares your price against the best competing one (sell — at the same station, buy — region-wide) and highlights beaten orders in orange; the header shows an "N beaten" counter. A background watcher covers **every** character's orders as each order book's ESI cache expires — notifications and the beaten counter on the sidebar's Orders item stay live even while you're on another tab or switched to another character.
- **Competition**: a per-order verdict built from a week of order-book snapshots (one per ~5-min ESI tick): **Calm / Contested / Bot war**, with time-on-top %, distinct rivals, and median survival before being undercut. The bot verdict comes from coverage, not speed — near-instant re-undercuts spread across 16+ hours of the day is not a human sleep schedule. Hover the cell for a plain-words breakdown of every number.
- **The Ctrl+Z queue**: cycles through your orders (beaten first), opens the market window in-game, and puts the beat price on the clipboard (±1 tick on EVE's 4-significant-figures grid). The cycle position is keyed to the order and survives data refreshes. The **only beaten** toggle restricts the cycle to beaten orders. Clicking an order row repositions the cycle onto it.
- **Notifications**: when an order becomes beaten — a system notification (tray, falling back to notify-send on Linux). Toggle with the bell next to Refresh Prices.
- **Relist fees**: the app notices price changes on your orders via the public order book and tallies the modification fees paid, honoring the Advanced Broker Relations skill.
- **Inventory** — FIFO stock: average cost basis (buy broker fee included), current sell price, profit/unit, realized P&L, and an **Age** column — how many days the oldest lot has been sitting (>30 days is highlighted).

### Wallet

Wallet journal and transactions with daily breakdowns and operation types. Works for corporation wallets too (per division).

The **P&L** tab separates two things that are often confused: **Cash Flow** cards (wallet in minus wallet out — restock days look hugely negative here, by design) and **Realized P&L (FIFO)** cards — actual profit at the moment of sale. **Avg Margin (realized)** is total FIFO profit minus relist fees on completed orders, relative to the cost of the units sold; **Profitable Days** counts days that closed with positive realized profit out of days that had any sales at all. The chart plots daily realized profit, mirroring the Dashboard.

### Assets

Character/corporation assets by location with estimated value.

### Alerts

Price alerts: above/below a price (buy or sell side), checked against Jita (PLEX — against the global market). Triggered alerts show as in-app banners.

### Contracts

Contract tracker: item exchange / courier / auction, statuses, contents value.

### P2P Market

Direct player-to-player trading over the **Nostr** protocol (decentralized relays, no central server): publish orders, make requests, handle incoming requests and reservations. Each order carries a savings badge relative to the regional market price (PLEX is compared to the global market).

- **Online status**: every trader's presence is visible in Browse and on requests (`● Online` / `○ Seen … ago`), heartbeated while their app runs. The header shows how many people are running the app right now — counted via an anonymous per-install key, so it counts people, not characters, and doesn't link anyone's characters together.
- **Requests reach you anywhere**: incoming buy requests arrive (and notify via the system tray) even for orders posted by a character other than the currently selected one; bursts are coalesced into a single "N new requests" popup.
- Relays are checked against their published capabilities (NIP-11); Settings warns about relays that can't store orders or silently reject writes. Outgoing events are queued persistently and retried until at least one relay confirms them.

### Tools

- **Pricing** — a pricer: item list in → prices out, results copyable to the clipboard.
- **Cargo Splitter** — splits a purchase into hold-sized batches; batches can be pushed into the game as fittings via ESI.

### Settings

Character fees (Sales Tax / Broker Fee / relist skill), Marketlogs folder, SDE static-data import, EveRef price sync, P2P Market settings, database maintenance.

---

## Trade Calc overlay (Ctrl+M)

A compact always-on-top window over the game. Price sources:

1. **Copying an order row in-game** (Ctrl+C on a market row) — the overlay parses it (regular items and PLEX), detects buy/sell, and **immediately writes the beat price back to the clipboard**: one tick below for sell, one tick above for buy. Paste it into the order dialog and you're on top.
2. **Order-book export** from EVE's market window (a file in Marketlogs) — the overlay picks up the whole book automatically: best prices, Buy out / Sell out walls (walked order-by-order), the top-5% average. The **Auto-copy: OFF / SELL− / BUY+** selector chooses which beat price is auto-copied on import. For PLEX the whole book is used (global market, no station filter).
3. **Copying an item name** from anywhere (chat, a contract) — if the text exactly matches a market type name, the overlay pulls the Jita 4-4 book (badge "jita").

Under each price sits a clickable `beat …` line: re-copies the beat price if the clipboard got overwritten. Profit and margin are computed with the selected character's fees. All prices follow EVE's 4-significant-figures grid.

---

## How it works inside

- **ESI cache**: three-state (FRESH / STALE / MISS) driven by the `Expires` header; a stale response is served immediately while the refresh runs in the background. All request progress is visible via the sync icon in the top bar.
- **Error journal**: ESI and load failures aren't swallowed — an orange top-bar icon opens the list of recent errors (time, source, message).
- **Updates**: the app checks GitHub releases and shows a one-click update banner.
- **Marketlogs watcher**: the folder is polled periodically; a recognized export is imported and deleted.
- **Background market watch**: an app-lifetime sweeper refreshes the order books of every character's active orders as their ESI caches expire — competition history, relist detection, and beaten-order notifications keep working without the Orders screen open. Between ESI ticks, responses come from the local cache, so this costs no extra requests.
- **Database**: SQLite (WAL); all access is serialized through a single-threaded dispatcher — no concurrent `SQLITE_BUSY`.

---

## Typical workflows

**An evening of station trading**
Analysis → Station Trading → scan the region → select candidates → in-game: Ctrl+Z (price) → place the buy → Ctrl+Z (quantity) → … → later, Orders shows who got beaten → Ctrl+Z in only-beaten mode takes the lead back.

**A cross-region haul**
Analysis → Inter-Region → pick the trade type and regions → sort by Net Profit → Tools → Cargo Splitter for your hold → push the fittings into the game.

**A quick price check**
Ctrl+M → copy an item name or an order row → prices, walls, and the beat price are on screen (and on the clipboard).
