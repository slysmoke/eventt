# EVE Night Trade Tools — Development Plan

## Phase 1: Foundation (Core Infrastructure)

### 1.1 Project Restructuring
- [ ] Restructure into multi-module Gradle project
- [ ] Set up dependency injection (Koin or manual)
- [ ] Configure `build.gradle.kts` for all modules
- [ ] Add `.gitignore` for `.db`, `tokens.*`, secrets

### 1.2 Database Layer (SQLite)
- [ ] Add SQLite dependency (`sqlite-jdbc` or `SQLDelight`)
- [ ] Create database schema:
  - `characters` (id, name, refresh_token, access_token, token_expiry, corporation_id)
  - `corporations` (id, name)
  - `esi_cache` (endpoint, params_hash, data, expires_at)
  - `orders` (id, character_id/corporation_id, type_id, station_id, price, volume, is_buy, local_buy_price, margin)
  - `transactions` (id, character_id/corporation_id, date, type_id, quantity, price, client_id, is_buy)
  - `journal` (id, character_id/corporation_id, date, amount, reason, ref_type)
  - `assets` (id, character_id/corporation_id, type_id, location_id, quantity, flagged)
  - `market_history` (type_id, station_id/region_id, date, average, volume)
  - `static_types` (type_id, name, group_id, volume, packaged_volume)
  - `static_groups` (group_id, name, category_id)
  - `static_stations` (station_id, name, system_id, region_id)
- [ ] Create DAO interfaces for each table
- [ ] Implement database singleton

### 1.3 HTTP Client (OkHttp + HTTP/2)
- [ ] Configure OkHttp with HTTP/2
- [ ] Connection pooling settings
- [ ] Interceptor for ESI auth header injection
- [ ] Retry interceptor (exponential backoff)
- [ ] Rate limiting interceptor (max 20 req/s)

### 1.4 EVE SSO / OAuth2 Auth
- [ ] Embedded HTTP server on `localhost:8000` for callback
- [ ] Open browser to ESI login URL
- [ ] Handle OAuth2 callback → extract auth code
- [ ] Exchange code for access/refresh tokens
- [ ] Auto-refresh tokens before expiry
- [ ] Store tokens in database
- [ ] Support multiple characters
- [ ] Support corporation association

### 1.5 ESI API Client
- [ ] Generate or write ESI endpoint models (kotlinx.serialization)
- [ ] Base ESI client class with:
  - `get<T>(endpoint, params)` → `T`
  - Automatic `Expires` header parsing
  - Cache lookup before request
  - Background refresh for stale cache
- [ ] Key endpoints to implement:
  - `GET /characters/{id}/` — character info
  - `GET /corporations/{id}/` — corporation info
  - `GET /characters/{id}/assets/` — character assets
  - `GET /corporations/{id}/assets/` — corporation assets
  - `GET /characters/{id}/orders/` — character orders
  - `GET /characters/{id}/wallet/journal/` — character journal
  - `GET /characters/{id}/wallet/transactions/` — character transactions
  - `GET /corporations/{id}/orders/` — corporation orders
  - `GET /corporations/{id}/wallets/` — corporation wallet
  - `GET /corporations/{id}/wallets/{division}/journal/` — corp journal
  - `GET /corporations/{id}/wallets/{division}/transactions/` — corp transactions
  - `GET /markets/{structure_id}/orders/` — structure market orders
  - `GET /markets/{region_id}/history/` — market history
  - `GET /markets/{region_id}/orders/` — region orders
  - `GET /universe/types/{type_id}/` — type info
  - `GET /universe/groups/{group_id}/` — group info
  - `GET /universe/stations/{station_id}/` — station info
  - `GET /search/` — search (structures, types, etc.)

### 1.6 Caching Layer
- [ ] Cache manager with TTL tracking
- [ ] Check `Expires` header → calculate freshness
- [ ] Cache states: `FRESH`, `STALE`, `MISS`
- [ ] Serve from cache if `FRESH` or `STALE` (background refresh if stale)
- [ ] Cache stored in `esi_cache` table
- [ ] Cache invalidation on manual refresh

### 1.7 Request Queue & Progress UI
- [ ] Request queue manager (tracks all in-flight requests)
- [ ] Each request: `{ source: "cache"|"server", endpoint, progress, eta }`
- [ ] Progress window/dialog showing:
  - Total requests, completed, in-progress, queued
  - Per-request: endpoint name, source (cache/server), ETA
  - Overall progress bar
- [ ] Flow-based reactive updates

### 1.8 Static Data Import
- [ ] Download SDE (PostgreSQL dump → SQLite import OR direct SQLite SDE)
- [ ] Import tables: `invTypes`, `invGroups`, `invCategories`, `staStations`, `mapRegions`, `mapSolarSystems`
- [ ] Or use ESI `/universe/` endpoints for static data
- [ ] One-time import on first run
- [ ] Update mechanism (SDE updates ~monthly)

### 1.9 Image Server Integration
- [ ] Image URL builder: `https://images.evetech.net/{category}/{id}/{variation}?size={size}`
- [ ] Categories: `types`, `characters`, `corporations`, `alliances`, `factions`
- [ ] Variations: `render`, `icon`, `portrait`, `bust`
- [ ] Async image loading with caching (Compose `AsyncImage` or custom)
- [ ] Memory cache (LRU) + disk cache (SQLite or filesystem)

---

## Phase 2: Features — Core

### 2.1 Character & Corporation Management
- [ ] "Add Character" flow (SSO login)
- [ ] "Add Corporation" flow (link to character with director role)
- [ ] Character list with status (online/offline, last seen)
- [ ] Corporation list with member count
- [ ] Remove/revoke character
- [ ] Switch active character/corporation context

### 2.2 Asset Viewer
- [ ] Fetch character + corporation assets
- [ ] Hierarchical view: Region → System → Station/Location → Items
- [ ] Group by location or by type
- [ ] Show item icons, names, quantities
- [ ] Lookup current prices (from market data or cached)
- [ ] Total asset value per location
- [ ] Filter/search by type name
- [ ] Expandable tree view
- [ ] Price column (fetch from market or use average)

### 2.3 Market Browser
- [ ] Station/region selector
- [ ] Type search (with icons)
- [ ] Order book display:
  - Buy orders (price, volume, location, expiry, range)
  - Sell orders (price, volume, location, expiry, range)
  - Spread calculation
- [ ] Order history (last 30/90/365 days)
- [ ] Price chart (candlestick or line graph)
- [ ] Volume chart
- [ ] Average/median price over time
- [ ] Filter by order type (buy/sell/all)

### 2.4 Market Analysis
- [ ] Single station analysis:
  - Best buy/sell prices
  - Order depth (volume at each price level)
  - Price history trends
  - Volume analysis
- [ ] Trade hub comparison (Jita ↔ Amarr ↔ Dodixie ↔ Rens):
  - Price differences
  - Best buy in A, sell in B
  - Profit after taxes/jump freight
  - Volume availability
- [ ] Profit calculator:
  - Buy price → sell price
  - Sales tax, broker fees
  - Transport cost (if applicable)
  - Net profit margin %

### 2.5 Orders & Margin Tracking
- [ ] Display active orders (character + corporation)
- [ ] Manual entry: "I bought X at price Y"
- [ ] Track local buy price vs current sell price
- [ ] Margin calculation:
  - Gross margin (sell - buy)
  - Net margin (after taxes/fees)
  - Margin %
- [ ] Order status: active, fulfilled, expired, cancelled
- [ ] Profit/loss per order
- [ ] Total open order value

### 2.6 Transactions & Journal
- [ ] Transaction list (character + corporation)
  - Date, type, quantity, price, client, total
  - Buy/sell filter
  - Type icon + name
- [ ] Journal list
  - Date, amount, reason, ref_type, first/second party
  - Filter by type (market transaction, broker fee, etc.)
- [ ] Profit/Loss chart:
  - Cumulative P&L over time
  - Daily/weekly/monthly aggregation
  - Buy vs sell breakdown
  - Corporation vs character
- [ ] Top traded items
- [ ] Top clients (by volume)
- [ ] Export to CSV

---

## Phase 3: Features — Advanced

### 3.1 Dashboard / Summary View (PROMOTED)
- [ ] Total asset value (all characters + corps)
- [ ] Active order summary (total value, buy/sell split)
- [ ] Today's P&L (realized + unrealized)
- [ ] Pending skill training (quick glance)
- [ ] Quick market glance (watchlist types with sparklines)
- [ ] Recent transactions (last 10)
- [ ] Active contracts summary
- [ ] Price alerts overview (triggered alerts)
- [ ] Industry job status (running jobs, completion ETA)
- [ ] Layout: grid of cards, customizable/reorderable

### 3.2 Price Alerts (PROMOTED)
- [ ] Set alert when type reaches price X (buy or sell)
- [ ] Alert conditions: above, below, % change
- [ ] Background polling / periodic check (configurable interval)
- [ ] Desktop notification on trigger (system tray)
- [ ] Alert history log
- [ ] Enable/disable individual alerts
- [ ] Alert groups (by character, by type, etc.)
- [ ] Sound notification option

### 3.3 Industry / Manufacturing Cost Calculator (PROMOTED)
- [ ] Select item to manufacture (type browser or search)
- [ ] Auto-populate Bill of Materials (from SDE industryBlueprints)
- [ ] Material costs (from current market buy orders at selected station)
- [ ] Facility cost selection (refinery, manufacturing facility)
- [ ] ME (Material Efficiency) impact on material quantities
- [ ] PE (Production Efficiency) impact on time/cost
- [ ] Team/worker slot costs (if applicable)
- [ ] Install cost (facility tax)
- [ ] Total manufacturing cost per unit
- [ ] Compare vs current market sell price
- [ ] Profit margin per unit and total batch
- [ ] ROI % calculation
- [ ] Save manufacturing templates
- [ ] Compare multiple items side-by-side

### 3.4 Contract Tracker (PROMOTED)
- [ ] View character contracts
- [ ] View corporation contracts
- [ ] Contract types: item exchange, courier, auction, bid
- [ ] Contract status: outstanding, in_progress, finished, cancelled, rejected, failed
- [ ] Contract details: items, collateral, reward, expiry
- [ ] Accept/fulfill contracts from UI
- [ ] Profit/loss on completed contracts
- [ ] Courier contract: route display, reward vs risk
- [ ] Contract history with filtering
- [ ] Search by type or counterparty

### 3.5 Personal Watchlist with Sparkline Charts (PROMOTED)
- [ ] Add/remove types from watchlist (search by name or ESI search)
- [ ] Group watchlist by category (ships, modules, minerals, etc.)
- [ ] Current best buy/sell price at selected station
- [ ] Mini sparkline charts (7d / 30d price trend)
- [ ] Price change % (24h, 7d, 30d) with color coding (green/red)
- [ ] Volume traded (24h)
- [ ] Margin column (spread %)
- [ ] Quick actions: open in market browser, set price alert
- [ ] Multiple watchlists (e.g., "Minerals", "Ships", "Modules")
- [ ] Drag-to-reorder items
- [ ] Persist watchlist in database

### 3.6 Trade Route Finder
- [ ] Find profitable routes between stations
- [ ] Factor in: buy/sell prices, taxes, transport
- [ ] Filter by budget, cargo capacity
- [ ] Show best N routes

### 3.7 Wallet Overview
- [ ] Character wallet balance
- [ ] Corporation wallet balance (per division)
- [ ] Balance history chart
- [ ] Daily income/expense summary

### 3.8 Skills & Clones
- [ ] Current skill queue
- [ ] Skill completion ETA
- [ ] Clone state (jump clones, implants)
- [ ] (Less trading-focused but useful for alts)

---

## Phase 4: Polish & Optimization

### 4.1 Performance
- [ ] Lazy loading for large lists
- [ ] Pagination for orders/transactions
- [ ] Background data sync (configurable interval)
- [ ] Database indexes on frequently queried columns
- [ ] Compose performance: avoid recomposition loops

### 4.2 UX Polish
- [ ] Keyboard shortcuts
- [ ] Column sorting in tables
- [ ] Column resizing
- [ ] Export data (CSV, JSON)
- [ ] Settings panel (sync interval, cache duration, etc.)
- [ ] Dark/light theme (already implemented)

### 4.3 Error Handling
- [ ] Graceful ESI error handling
- [ ] User-friendly error messages
- [ ] Retry mechanisms
- [ ] Offline mode (serve from cache)

---

## Suggested Implementation Order

1. **Phase 1** (Week 1-3): Database + HTTP client + ESI base client + caching + request queue
2. **Phase 1.5** (Week 4-5): OAuth2/SSO auth flow + character/corporation management + static data import
3. **Phase 2.1-2.2** (Week 6-7): Asset viewer + image server integration
4. **Phase 2.3-2.4** (Week 8-10): Market browser + price charts + market analysis + trade hub comparison
5. **Phase 2.5-2.6** (Week 11-13): Orders & margin tracking + transactions, journal, P&L charts
6. **Phase 3.5** (Week 14): Personal watchlist with sparklines (depends on market data)
7. **Phase 3.2** (Week 15): Price alerts (depends on watchlist + market data)
8. **Phase 3.1** (Week 16): Dashboard / summary view (aggregates all previous features)
9. **Phase 3.4** (Week 17): Contract tracker
10. **Phase 3.3** (Week 18-19): Industry / manufacturing cost calculator
11. **Phase 4** (Week 20+): Polish, performance, error handling, offline mode

---

## Key Dependencies to Add

```kotlin
// build.gradle.kts or module-level
dependencies {
    // HTTP
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.+")
    
    // SQLite
    implementation("org.xerial:sqlite-jdbc:3.45.+")
    // OR SQLDelight
    implementation("app.cash.sqldelight:sqlite-driver:2.0.+")
    implementation("app.cash.sqldelight:coroutines-extensions:2.0.+")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.+")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.8.+")
    
    // Charts (for price charts + sparklines)
    // Option A: Vico (Compose Multiplatform charts)
    implementation("com.patrykandpatrick.vico:compose:2.0.+")
    // Option B: Custom Canvas drawing (more control, more work)
    // Option C: XChart (JVM, embed in Compose via Swing interop)
    
    // Image loading
    // - Custom OkHttp + ImageBitmap loader with LRU cache
    // - Or coil3 (Compose Multiplatform) if compatible
    
    // Embedded server for OAuth callback
    // Option A: Ktor server (lightweight)
    implementation("io.ktor:ktor-server-core:2.3.+")
    implementation("io.ktor:ktor-server-netty:2.3.+")
    // Option B: Bare Java HTTP server (simpler, less deps)
    
    // System tray / desktop notifications
    // - Java Desktop API (built-in)
    // - SystemTray for tray icon
    
    // Dependency injection (optional but recommended)
    implementation("io.insert-koin:koin-core:3.5.+")
}
```

---

## Notes

- ESI rate limits: ~20 req/s global, per-endpoint limits vary
- Corp endpoints require director/CEO role
- Some endpoints are paginated (use `?page=` parameter)
- ESI spec changes — check `https://esi.evetech.net/ui/` for latest
- Static data exports update ~monthly on CCP's FTP
- Image server: `https://images.evetech.net/` — no auth needed
