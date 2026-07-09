package org.eventt.core.database

import org.eventt.core.model.AppPaths
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

object DatabaseManager {
    private const val DEFAULT_DB_NAME = "eve_trader.db"

    @Volatile private var connection: Connection? = null

    @Volatile
    var isInitialized: Boolean = false
        private set

    private val initLock = Any()

    // Single-thread dispatcher for serialized DB access

    fun initialize(dbPath: String? = null) {
        if (isInitialized) return

        synchronized(initLock) {
            if (isInitialized) return@synchronized

            val dbFilePath = dbPath ?: java.io.File(AppPaths.appDataDir, DEFAULT_DB_NAME).absolutePath
            val dir = java.io.File(dbFilePath).parentFile
            dir?.mkdirs()

            // Remove corrupt/empty database files
            val dbFile = java.io.File(dbFilePath)
            if (dbFile.exists() && dbFile.length() == 0L) {
                dbFile.delete()
                // Only safe to delete WAL/SHM when the DB itself is gone
                java.io.File("$dbFilePath-journal").delete()
                java.io.File("$dbFilePath-wal").delete()
                java.io.File("$dbFilePath-shm").delete()
            }

            // Get connection with default settings
            val conn = DriverManager.getConnection("jdbc:sqlite:$dbFilePath")
            connection = conn

            // IMPORTANT: Don't set autoCommit = true before PRAGMAs.
            // The SQLite JDBC driver creates an internal transaction when autoCommit is set,
            // which then conflicts with PRAGMA journal_mode=WAL (causes SQLITE_BUSY).
            // Set autoCommit AFTER all PRAGMAs.
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL")
                stmt.execute("PRAGMA foreign_keys=ON")
                stmt.execute("PRAGMA synchronous=NORMAL")
                stmt.execute("PRAGMA busy_timeout=30000")
                stmt.execute("PRAGMA cache_size=-20000")
                stmt.execute("PRAGMA journal_size_limit=67108864")
                stmt.execute("PRAGMA wal_autocheckpoint=1000")
            }

            // NOW set autoCommit to true
            conn.autoCommit = true

            createTables(conn)
            migrateSchema(conn)
            createIndexes(conn)
            isInitialized = true
        }
    }

    fun getConnection(): Connection {
        if (!isInitialized) initialize()
        return connection ?: error("Database not initialized")
    }

    fun close() {
        synchronized(initLock) {
            connection?.close()
            connection = null
            isInitialized = false
        }
    }

    // ─── Schema Migration ────────────────────────────────────────────────

    private fun migrateSchema(conn: Connection) {
        val migrations =
            listOf(
                "ALTER TABLE static_types ADD COLUMN market_group_id INTEGER",
                "CREATE TABLE IF NOT EXISTS market_groups (market_group_id INTEGER PRIMARY KEY, name TEXT NOT NULL, parent_group_id INTEGER)",
                "ALTER TABLE esi_cache ADD COLUMN etag TEXT",
                "ALTER TABLE esi_cache ADD COLUMN last_modified TEXT",
                "ALTER TABLE market_history ADD COLUMN source TEXT DEFAULT 'esi'",
                "ALTER TABLE order_history ADD COLUMN corporation_id INTEGER",
                "ALTER TABLE order_history ADD COLUMN is_corp INTEGER DEFAULT 0",
            )
        conn.createStatement().use { stmt ->
            migrations.forEach { sql ->
                try {
                    stmt.execute(sql)
                } catch (e: SQLException) {
                    // column/table already exists
                }
            }
        }
    }

    // ─── Table Creation ─────────────────────────────────────────────────

    private fun createTables(conn: Connection) {
        val statements =
            listOf(
                // Characters
                """
                CREATE TABLE IF NOT EXISTS characters (
                    id INTEGER PRIMARY KEY,
                    name TEXT NOT NULL,
                    refresh_token TEXT NOT NULL,
                    access_token TEXT NOT NULL DEFAULT '',
                    token_expiry INTEGER NOT NULL DEFAULT 0,
                    corporation_id INTEGER,
                    corporation_name TEXT,
                    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
                )
                """.trimIndent(),
                // Corporations
                """
                CREATE TABLE IF NOT EXISTS corporations (
                    id INTEGER PRIMARY KEY,
                    name TEXT NOT NULL,
                    ticker TEXT DEFAULT '',
                    alliance_id INTEGER
                )
                """.trimIndent(),
                // ESI Cache
                """
                CREATE TABLE IF NOT EXISTS esi_cache (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    endpoint TEXT NOT NULL,
                    params_hash TEXT NOT NULL,
                    data TEXT NOT NULL,
                    expires_at INTEGER NOT NULL,
                    source TEXT DEFAULT 'server',
                    last_fetched INTEGER DEFAULT (strftime('%s', 'now') * 1000),
                    UNIQUE(endpoint, params_hash)
                )
                """.trimIndent(),
                // Tracked Orders
                """
                CREATE TABLE IF NOT EXISTS tracked_orders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    type_id INTEGER NOT NULL,
                    type_name TEXT DEFAULT '',
                    buy_price REAL NOT NULL,
                    quantity INTEGER NOT NULL,
                    current_sell_price REAL DEFAULT 0.0,
                    station_id INTEGER DEFAULT 0,
                    station_name TEXT DEFAULT '',
                    notes TEXT DEFAULT '',
                    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
                    updated_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
                    character_id INTEGER,
                    corporation_id INTEGER
                )
                """.trimIndent(),
                // Transactions
                """
                CREATE TABLE IF NOT EXISTS transactions (
                    transaction_id INTEGER PRIMARY KEY,
                    date TEXT NOT NULL,
                    type_id INTEGER NOT NULL,
                    type_name TEXT DEFAULT '',
                    quantity INTEGER NOT NULL,
                    unit_price REAL NOT NULL,
                    total REAL NOT NULL,
                    is_buy INTEGER NOT NULL DEFAULT 0,
                    client_id INTEGER DEFAULT 0,
                    client_name TEXT DEFAULT '',
                    location_id INTEGER DEFAULT 0,
                    location_name TEXT DEFAULT '',
                    is_corp INTEGER DEFAULT 0,
                    character_id INTEGER,
                    corporation_id INTEGER
                )
                """.trimIndent(),
                // Journal
                """
                CREATE TABLE IF NOT EXISTS journal (
                    entry_id INTEGER PRIMARY KEY,
                    date TEXT NOT NULL,
                    amount REAL NOT NULL,
                    balance REAL NOT NULL,
                    reason TEXT DEFAULT '',
                    ref_type TEXT DEFAULT '',
                    first_party_id INTEGER DEFAULT 0,
                    first_party_name TEXT DEFAULT '',
                    second_party_id INTEGER DEFAULT 0,
                    second_party_name TEXT DEFAULT '',
                    tax_amount REAL,
                    is_corp INTEGER DEFAULT 0,
                    character_id INTEGER,
                    corporation_id INTEGER,
                    division_id INTEGER
                )
                """.trimIndent(),
                // Assets
                """
                CREATE TABLE IF NOT EXISTS assets (
                    item_id INTEGER PRIMARY KEY,
                    type_id INTEGER NOT NULL,
                    type_name TEXT DEFAULT '',
                    quantity INTEGER NOT NULL,
                    location_id INTEGER NOT NULL,
                    location_name TEXT DEFAULT '',
                    region_id INTEGER DEFAULT 0,
                    region_name TEXT DEFAULT '',
                    system_id INTEGER DEFAULT 0,
                    system_name TEXT DEFAULT '',
                    station_id INTEGER DEFAULT 0,
                    station_name TEXT DEFAULT '',
                    is_singleton INTEGER DEFAULT 1,
                    location_flag TEXT DEFAULT '',
                    estimated_price REAL DEFAULT 0.0,
                    is_corp_asset INTEGER DEFAULT 0,
                    character_id INTEGER,
                    corporation_id INTEGER
                )
                """.trimIndent(),
                // Market History
                """
                CREATE TABLE IF NOT EXISTS market_history (
                    type_id INTEGER NOT NULL,
                    region_id INTEGER NOT NULL,
                    date TEXT NOT NULL,
                    average REAL NOT NULL,
                    volume INTEGER NOT NULL,
                    order_count INTEGER NOT NULL,
                    highest REAL NOT NULL,
                    lowest REAL NOT NULL,
                    PRIMARY KEY (type_id, region_id, date)
                )
                """.trimIndent(),
                // Watchlist
                """
                CREATE TABLE IF NOT EXISTS watchlist (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    type_id INTEGER NOT NULL,
                    type_name TEXT DEFAULT '',
                    watchlist_name TEXT DEFAULT 'Default',
                    station_id INTEGER DEFAULT 0,
                    region_id INTEGER DEFAULT 0,
                    sort_order INTEGER DEFAULT 0
                )
                """.trimIndent(),
                // Watchlist Price Snapshots
                """
                CREATE TABLE IF NOT EXISTS watchlist_prices (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    type_id INTEGER NOT NULL,
                    station_id INTEGER DEFAULT 0,
                    best_buy_price REAL DEFAULT 0.0,
                    best_sell_price REAL DEFAULT 0.0,
                    spread REAL DEFAULT 0.0,
                    spread_percent REAL DEFAULT 0.0,
                    volume_24h INTEGER DEFAULT 0,
                    change_percent_24h REAL DEFAULT 0.0,
                    change_percent_7d REAL DEFAULT 0.0,
                    change_percent_30d REAL DEFAULT 0.0,
                    sparkline_data TEXT DEFAULT '[]',
                    captured_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
                )
                """.trimIndent(),
                // Price Alerts
                """
                CREATE TABLE IF NOT EXISTS price_alerts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    type_id INTEGER NOT NULL,
                    type_name TEXT DEFAULT '',
                    target_price REAL NOT NULL,
                    condition_type TEXT DEFAULT 'below',
                    station_id INTEGER DEFAULT 0,
                    region_id INTEGER DEFAULT 0,
                    order_type TEXT DEFAULT 'sell',
                    enabled INTEGER DEFAULT 1,
                    triggered INTEGER DEFAULT 0,
                    triggered_at INTEGER,
                    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
                    character_id INTEGER
                )
                """.trimIndent(),
                // Contracts
                """
                CREATE TABLE IF NOT EXISTS contracts (
                    contract_id INTEGER PRIMARY KEY,
                    issuer_id INTEGER NOT NULL,
                    issuer_corp_id INTEGER NOT NULL,
                    assignee_id INTEGER DEFAULT 0,
                    acceptor_id INTEGER DEFAULT 0,
                    start_station_id INTEGER DEFAULT 0,
                    end_station_id INTEGER DEFAULT 0,
                    type TEXT DEFAULT 'unknown',
                    status TEXT DEFAULT 'outstanding',
                    title TEXT DEFAULT '',
                    description TEXT DEFAULT '',
                    date_issued TEXT NOT NULL,
                    date_expired TEXT NOT NULL,
                    date_accepted TEXT,
                    date_completed TEXT,
                    num_days INTEGER DEFAULT 0,
                    price REAL DEFAULT 0.0,
                    reward REAL DEFAULT 0.0,
                    collateral REAL DEFAULT 0.0,
                    buyout REAL DEFAULT 0.0,
                    for_corp INTEGER DEFAULT 0,
                    is_corp INTEGER DEFAULT 0,
                    character_id INTEGER,
                    corporation_id INTEGER
                )
                """.trimIndent(),
                // Contract Items
                """
                CREATE TABLE IF NOT EXISTS contract_items (
                    contract_id INTEGER NOT NULL,
                    record_id INTEGER NOT NULL,
                    type_id INTEGER NOT NULL,
                    type_name TEXT DEFAULT '',
                    quantity INTEGER NOT NULL,
                    raw_quantity INTEGER NOT NULL,
                    is_included INTEGER DEFAULT 1,
                    is_singleton INTEGER DEFAULT 1,
                    estimated_price REAL DEFAULT 0.0,
                    PRIMARY KEY (contract_id, record_id),
                    FOREIGN KEY (contract_id) REFERENCES contracts(contract_id) ON DELETE CASCADE
                )
                """.trimIndent(),
                // Static Types (from SDE)
                """
                CREATE TABLE IF NOT EXISTS static_types (
                    type_id INTEGER PRIMARY KEY,
                    name TEXT NOT NULL,
                    group_id INTEGER NOT NULL,
                    category_id INTEGER NOT NULL,
                    volume REAL DEFAULT 0.0,
                    packaged_volume REAL DEFAULT 0.0,
                    portion_size INTEGER DEFAULT 1,
                    description TEXT DEFAULT '',
                    icon_id INTEGER,
                    published INTEGER DEFAULT 0
                )
                """.trimIndent(),
                // Static Groups
                """
                CREATE TABLE IF NOT EXISTS static_groups (
                    group_id INTEGER PRIMARY KEY,
                    name TEXT NOT NULL,
                    category_id INTEGER NOT NULL
                )
                """.trimIndent(),
                // Market Groups (for market tree navigation)
                """
                CREATE TABLE IF NOT EXISTS market_groups (
                    market_group_id INTEGER PRIMARY KEY,
                    name TEXT NOT NULL,
                    parent_group_id INTEGER
                )
                """.trimIndent(),
                // Static Categories
                """
                CREATE TABLE IF NOT EXISTS static_categories (
                    category_id INTEGER PRIMARY KEY,
                    name TEXT NOT NULL
                )
                """.trimIndent(),
                // Static Stations
                """
                CREATE TABLE IF NOT EXISTS static_stations (
                    station_id INTEGER PRIMARY KEY,
                    name TEXT NOT NULL,
                    system_id INTEGER NOT NULL,
                    system_name TEXT DEFAULT '',
                    region_id INTEGER NOT NULL,
                    region_name TEXT DEFAULT '',
                    type_id INTEGER DEFAULT 0
                )
                """.trimIndent(),
                // Static Systems
                """
                CREATE TABLE IF NOT EXISTS static_systems (
                    system_id INTEGER PRIMARY KEY,
                    name TEXT NOT NULL,
                    region_id INTEGER NOT NULL
                )
                """.trimIndent(),
                // Static Regions
                """
                CREATE TABLE IF NOT EXISTS static_regions (
                    region_id INTEGER PRIMARY KEY,
                    name TEXT NOT NULL
                )
                """.trimIndent(),
                // Stargate connectivity graph (undirected edges), used to compute jump distance
                // between systems for order-range reachability in station trading analysis.
                """
                CREATE TABLE IF NOT EXISTS static_system_jumps (
                    system_id INTEGER NOT NULL,
                    neighbor_system_id INTEGER NOT NULL,
                    PRIMARY KEY (system_id, neighbor_system_id)
                )
                """.trimIndent(),
                // Marks systems whose stargates have already been fetched from ESI, so the jump
                // graph is only built once per system (topology never changes).
                """
                CREATE TABLE IF NOT EXISTS static_system_jumps_fetched (
                    system_id INTEGER PRIMARY KEY
                )
                """.trimIndent(),
                // Settings
                """
                CREATE TABLE IF NOT EXISTS settings (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """.trimIndent(),
                // EveRef bulk download tracking
                """
                CREATE TABLE IF NOT EXISTS everef_downloads (
                    date TEXT PRIMARY KEY,
                    file_name TEXT NOT NULL,
                    size INTEGER NOT NULL,
                    downloaded_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
                )
                """.trimIndent(),
                // Completed / expired / cancelled order history from ESI
                """
                CREATE TABLE IF NOT EXISTS order_history (
                    order_id INTEGER PRIMARY KEY,
                    type_id INTEGER NOT NULL,
                    type_name TEXT DEFAULT '',
                    location_id INTEGER DEFAULT 0,
                    station_name TEXT DEFAULT '',
                    price REAL NOT NULL,
                    volume_total INTEGER NOT NULL,
                    volume_remaining INTEGER NOT NULL,
                    is_buy_order INTEGER NOT NULL DEFAULT 0,
                    duration INTEGER NOT NULL DEFAULT 90,
                    issued TEXT NOT NULL,
                    range TEXT DEFAULT 'station',
                    min_volume INTEGER DEFAULT 1,
                    state TEXT DEFAULT 'expired',
                    character_id INTEGER
                )
                """.trimIndent(),
                // Currently active orders — a full snapshot per character/corp, replaced wholesale
                // on every ESI/Marketlogs-file refresh (not append-only like order_history), so the
                // Orders screen has something to show instantly on startup before the next live fetch.
                """
                CREATE TABLE IF NOT EXISTS active_orders (
                    order_id INTEGER PRIMARY KEY,
                    type_id INTEGER NOT NULL,
                    type_name TEXT DEFAULT '',
                    location_id INTEGER DEFAULT 0,
                    region_id INTEGER DEFAULT 0,
                    station_name TEXT DEFAULT '',
                    price REAL NOT NULL,
                    volume_total INTEGER NOT NULL,
                    volume_remaining INTEGER NOT NULL,
                    is_buy_order INTEGER NOT NULL DEFAULT 0,
                    duration INTEGER NOT NULL DEFAULT 90,
                    issued TEXT NOT NULL,
                    state TEXT DEFAULT 'active',
                    issued_by_char_id INTEGER,
                    character_id INTEGER,
                    corporation_id INTEGER
                )
                """.trimIndent(),
                // P2P Market (Nostr) — local Nostr identities. encrypted_privkey is opaque
                // ciphertext from NostrKeyCrypto; the raw key never touches this table.
                """
                CREATE TABLE IF NOT EXISTS nostr_identity (
                    pubkey TEXT PRIMARY KEY,
                    encrypted_privkey TEXT NOT NULL,
                    label TEXT DEFAULT '',
                    is_active INTEGER DEFAULT 0,
                    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
                )
                """.trimIndent(),
                // P2P Market (Nostr) — configurable relay list, seeded with defaults on first run.
                """
                CREATE TABLE IF NOT EXISTS nostr_relays (
                    url TEXT PRIMARY KEY,
                    enabled INTEGER DEFAULT 1,
                    read INTEGER DEFAULT 1,
                    write INTEGER DEFAULT 1,
                    sort_order INTEGER DEFAULT 0,
                    last_connected_at INTEGER,
                    last_status TEXT DEFAULT 'unknown',
                    last_error TEXT
                )
                """.trimIndent(),
                // P2P Market (Nostr) — local mirror of kind-30735 addressable order events (own +
                // others). (order_uuid, pubkey) is the NIP-33 addressable coordinate; upserts keep
                // only the highest-seen created_at per coordinate, matching relay replace semantics.
                """
                CREATE TABLE IF NOT EXISTS nostr_orders (
                    order_uuid TEXT NOT NULL,
                    pubkey TEXT NOT NULL,
                    event_id TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    side TEXT NOT NULL,
                    type_id INTEGER NOT NULL,
                    region_id INTEGER NOT NULL,
                    price REAL NOT NULL,
                    qty_total INTEGER NOT NULL,
                    qty_remaining INTEGER NOT NULL,
                    min_lot INTEGER NOT NULL,
                    min_lot_unit TEXT NOT NULL,
                    trader_char TEXT DEFAULT '',
                    expiration INTEGER NOT NULL,
                    raw_event_json TEXT NOT NULL,
                    is_mine INTEGER DEFAULT 0,
                    first_seen_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
                    updated_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
                    PRIMARY KEY (order_uuid, pubkey)
                )
                """.trimIndent(),
            )

        // using conn parameter
        conn.createStatement().use { stmt ->
            statements.forEach { sql ->
                try {
                    stmt.execute(sql)
                } catch (e: SQLException) {
                    println("Warning: Failed to execute statement: ${e.message}")
                }
            }
        }
    }

    private fun createIndexes(conn: Connection) {
        val indexes =
            listOf(
                "CREATE INDEX IF NOT EXISTS idx_esi_cache_lookup ON esi_cache(endpoint, params_hash)",
                "CREATE INDEX IF NOT EXISTS idx_tracked_orders_character ON tracked_orders(character_id)",
                "CREATE INDEX IF NOT EXISTS idx_tracked_orders_corp ON tracked_orders(corporation_id)",
                "CREATE INDEX IF NOT EXISTS idx_transactions_date ON transactions(date)",
                "CREATE INDEX IF NOT EXISTS idx_transactions_type ON transactions(type_id)",
                "CREATE INDEX IF NOT EXISTS idx_transactions_character ON transactions(character_id)",
                "CREATE INDEX IF NOT EXISTS idx_transactions_corporation ON transactions(corporation_id)",
                "CREATE INDEX IF NOT EXISTS idx_journal_date ON journal(date)",
                "CREATE INDEX IF NOT EXISTS idx_journal_character ON journal(character_id)",
                "CREATE INDEX IF NOT EXISTS idx_journal_corporation ON journal(corporation_id)",
                "CREATE INDEX IF NOT EXISTS idx_assets_location ON assets(location_id)",
                "CREATE INDEX IF NOT EXISTS idx_assets_type ON assets(type_id)",
                "CREATE INDEX IF NOT EXISTS idx_assets_character ON assets(character_id)",
                "CREATE INDEX IF NOT EXISTS idx_assets_corporation ON assets(corporation_id)",
                "CREATE INDEX IF NOT EXISTS idx_market_history_source_date ON market_history(source, date)",
                "CREATE INDEX IF NOT EXISTS idx_watchlist_type ON watchlist(type_id)",
                "CREATE INDEX IF NOT EXISTS idx_watchlist_name ON watchlist(watchlist_name)",
                "CREATE INDEX IF NOT EXISTS idx_watchlist_prices_lookup ON watchlist_prices(type_id, station_id)",
                "CREATE INDEX IF NOT EXISTS idx_watchlist_prices_time ON watchlist_prices(captured_at)",
                "CREATE INDEX IF NOT EXISTS idx_price_alerts_type ON price_alerts(type_id)",
                "CREATE INDEX IF NOT EXISTS idx_price_alerts_enabled ON price_alerts(enabled)",
                "CREATE INDEX IF NOT EXISTS idx_contracts_status ON contracts(status)",
                "CREATE INDEX IF NOT EXISTS idx_contracts_character ON contracts(character_id)",
                "CREATE INDEX IF NOT EXISTS idx_contracts_corporation ON contracts(corporation_id)",
                "CREATE INDEX IF NOT EXISTS idx_static_types_name ON static_types(name)",
                "CREATE INDEX IF NOT EXISTS idx_static_types_group ON static_types(group_id)",
                "CREATE INDEX IF NOT EXISTS idx_static_stations_region ON static_stations(region_id)",
                "CREATE INDEX IF NOT EXISTS idx_static_types_market ON static_types(market_group_id) WHERE market_group_id IS NOT NULL",
                "CREATE INDEX IF NOT EXISTS idx_market_groups_parent ON market_groups(parent_group_id)",
                "CREATE INDEX IF NOT EXISTS idx_market_groups_name ON market_groups(name)",
                "CREATE INDEX IF NOT EXISTS idx_order_history_character ON order_history(character_id, is_buy_order)",
                "CREATE INDEX IF NOT EXISTS idx_order_history_issued ON order_history(issued)",
                "CREATE INDEX IF NOT EXISTS idx_order_history_corp ON order_history(corporation_id)",
                "CREATE INDEX IF NOT EXISTS idx_active_orders_character ON active_orders(character_id)",
                "CREATE INDEX IF NOT EXISTS idx_active_orders_corp ON active_orders(corporation_id)",
                "CREATE INDEX IF NOT EXISTS idx_nostr_orders_lookup ON nostr_orders(type_id, region_id, side)",
                "CREATE INDEX IF NOT EXISTS idx_nostr_orders_expiration ON nostr_orders(expiration)",
                "CREATE INDEX IF NOT EXISTS idx_nostr_orders_mine ON nostr_orders(is_mine)",
            )

        // using conn parameter
        conn.createStatement().use { stmt ->
            indexes.forEach { sql ->
                try {
                    stmt.execute(sql)
                } catch (e: SQLException) {
                    println("Warning: Failed to create index: ${e.message}")
                }
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    fun <T> transaction(block: Connection.() -> T): T =
        synchronized(this) {
            val conn = getConnection()
            val wasAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val result = conn.block()
                conn.commit()
                result
            } catch (e: Exception) {
                try {
                    conn.rollback()
                } catch (_: Exception) {
                }
                throw e
            } finally {
                conn.autoCommit = wasAutoCommit
            }
        }

    private const val LAST_VACUUM_SETTING = "db.last_vacuum_millis"

    // VACUUM rewrites the whole file and needs exclusive access (can't run inside a
    // transaction), so it's only worth paying for when there's a meaningful amount of free
    // space to reclaim — checked via the freelist ratio rather than running unconditionally —
    // and not more often than minInterval, so a ratio that hovers right at the threshold
    // doesn't trigger a rewrite on every single startup.
    fun vacuumIfNeeded(
        minFreeRatio: Double = 0.20,
        minInterval: Long = 7 * 24 * 60 * 60 * 1000L,
    ) {
        synchronized(this) {
            val conn = getConnection()
            val (freePages, totalPages) =
                conn.createStatement().use { stmt ->
                    val free =
                        stmt.executeQuery("PRAGMA freelist_count").use {
                            it.next()
                            it.getLong(1)
                        }
                    val total =
                        stmt.executeQuery("PRAGMA page_count").use {
                            it.next()
                            it.getLong(1)
                        }
                    free to total
                }
            if (totalPages == 0L || freePages.toDouble() / totalPages < minFreeRatio) return
            val lastVacuum = StaticDataDao.getSetting(LAST_VACUUM_SETTING)?.toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - lastVacuum < minInterval) return

            conn.createStatement().use { it.execute("VACUUM") }
            StaticDataDao.setSetting(LAST_VACUUM_SETTING, System.currentTimeMillis().toString())
        }
    }
}
