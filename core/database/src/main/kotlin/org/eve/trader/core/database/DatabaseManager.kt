package org.eve.trader.core.database

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant

object DatabaseManager {

    private const val DEFAULT_DB_NAME = "eve_trader.db"
    @Volatile private var connection: Connection? = null

    @Volatile
    var isInitialized: Boolean = false
        private set

    private val initLock = Any()

    // Single-thread dispatcher for serialized DB access
    private val dbDispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1)

    fun initialize(dbPath: String? = null) {
        if (isInitialized) return

        synchronized(initLock) {
            if (isInitialized) return@synchronized

            val dbFilePath = dbPath ?: "${System.getProperty("user.home")}/.eve-trader/$DEFAULT_DB_NAME"
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
        return connection ?: throw IllegalStateException("Database not initialized")
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
        val migrations = listOf(
            "ALTER TABLE static_types ADD COLUMN market_group_id INTEGER",
            "CREATE TABLE IF NOT EXISTS market_groups (market_group_id INTEGER PRIMARY KEY, name TEXT NOT NULL, parent_group_id INTEGER)",
        )
        conn.createStatement().use { stmt ->
            migrations.forEach { sql ->
                try { stmt.execute(sql) } catch (e: SQLException) { /* column/table already exists */ }
            }
        }
    }

    // ─── Table Creation ─────────────────────────────────────────────────

    private fun createTables(conn: Connection) {
        val statements = listOf(
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

            // Manufacturing Templates
            """
            CREATE TABLE IF NOT EXISTS manufacturing_templates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                blueprint_type_id INTEGER NOT NULL,
                blueprint_type_name TEXT DEFAULT '',
                quantity INTEGER DEFAULT 1,
                material_efficiency INTEGER DEFAULT 0,
                time_efficiency INTEGER DEFAULT 100,
                facility_id INTEGER DEFAULT 0,
                facility_name TEXT DEFAULT '',
                station_id INTEGER DEFAULT 0,
                station_name TEXT DEFAULT '',
                run_cost REAL DEFAULT 0.0,
                install_tax REAL DEFAULT 0.0
            )
            """.trimIndent(),

            // Manufacturing Materials
            """
            CREATE TABLE IF NOT EXISTS manufacturing_materials (
                template_id INTEGER NOT NULL,
                type_id INTEGER NOT NULL,
                type_name TEXT DEFAULT '',
                required_quantity REAL NOT NULL,
                estimated_price REAL DEFAULT 0.0,
                total_cost REAL DEFAULT 0.0,
                PRIMARY KEY (template_id, type_id),
                FOREIGN KEY (template_id) REFERENCES manufacturing_templates(id) ON DELETE CASCADE
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

            // Settings
            """
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
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
        val indexes = listOf(
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
            "CREATE INDEX IF NOT EXISTS idx_market_history_lookup ON market_history(type_id, region_id, date)",
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

    fun <T> transaction(block: Connection.() -> T): T {
        val conn = getConnection()
        val wasAutoCommit = conn.autoCommit
        conn.autoCommit = false
        return try {
            val result = conn.block()
            conn.commit()
            result
        } catch (e: Exception) {
            try { conn.rollback() } catch (_: Exception) {}
            throw e
        } finally {
            conn.autoCommit = wasAutoCommit
        }
    }
}
