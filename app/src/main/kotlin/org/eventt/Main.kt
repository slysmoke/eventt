package org.eventt

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.eventt.app.generated.resources.Res
import org.eventt.app.generated.resources.icon
import org.eventt.core.cache.EsiCacheManager
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.DatabaseManager
import org.eventt.core.http.EveHttpClient
import org.eventt.core.marketlogs.MarketLogWatcher
import org.eventt.core.model.AppPaths
import org.eventt.core.model.CharacterModel
import org.eventt.core.model.HotkeyBindings
import org.eventt.core.nostr.NostrIdentityService
import org.eventt.core.nostr.NostrRelayManager
import org.eventt.features.contracts.ContractWatchService
import org.eventt.features.orders.MarketWatchService
import org.eventt.features.orders.PendingOrdersQueue
import org.eventt.notify.TrayNotifier
import org.eventt.ui.EventtApp
import org.jetbrains.compose.resources.painterResource

// Fake ESI character ID (outside EVE's real ID range) used only to give the P2P Market
// test instance (see scripts/run-p2p-test.sh) a character to hang a Nostr identity off of —
// this instance never makes authenticated ESI calls, so there's no real SSO token behind it.
private const val P2P_TEST_CHARACTER_ID = 900_000_001
private const val P2P_TEST_CHARACTER_NAME = "P2P Test Trader"

private fun seedP2pTestIdentityIfNeeded() {
    if (CharacterDao.getById(P2P_TEST_CHARACTER_ID) == null) {
        CharacterDao.insert(
            CharacterModel(
                id = P2P_TEST_CHARACTER_ID,
                name = P2P_TEST_CHARACTER_NAME,
                refreshToken = "",
            ),
        )
    }
    runBlocking {
        NostrIdentityService.ensureIdentityForCharacter(CharacterDao.getById(P2P_TEST_CHARACTER_ID)!!)
    }
}

fun main() {
    // ESI requires a User-Agent identifying the app on every request — must be set before
    // any ESI/SSO call, which is why this runs before anything else in main().
    val repoUrl = AppVersion.GITHUB_REPO.takeIf { it.isNotBlank() }?.let { "https://github.com/$it" }
    EveHttpClient.configure(
        "EventNightTradeTools/${AppVersion.NAME}" + (repoUrl?.let { " (+$it)" } ?: ""),
    )

    // One-time pickup of data from the old ~/.eve-trader / ~/.eventt home-dir locations, before
    // anything (DB, token key) reads/writes the new per-OS app-data directory.
    AppPaths.migrateLegacyData()

    // Initialize database BEFORE UI starts — prevents race conditions
    println("[App] Initializing database...")
    try {
        DatabaseManager.initialize()
        println("[App] Database initialized successfully")
        // Cache hygiene while nothing else touches the DB yet: purge long-expired ESI cache rows,
        // then reclaim file space when enough has accumulated (both are cheap no-ops otherwise).
        // These existed but were never wired in — the measured result was a 710MB database where
        // 90% of esi_cache rows were expired junk. VACUUM can take a while on a bloated file, but
        // it runs at most once a week and only when ≥20% of the file is reclaimable.
        EsiCacheManager.cleanupExpired()
        DatabaseManager.vacuumIfNeeded()
    } catch (e: Exception) {
        println("[App] Database init failed: ${e.stackTraceToString()}")
    }

    val isP2pTestInstance = System.getenv("EVENTT_DATA_DIR") != null
    if (isP2pTestInstance) seedP2pTestIdentityIfNeeded()

    // X11's XGrabKey is exclusive process-wide — the main instance already holds these combos,
    // so a test instance's grab can never succeed (X11HotkeyBackend detects the BadAccess and
    // backs off gracefully); don't even try.
    if (!isP2pTestInstance) {
        GlobalHotkeyService.start()
        // Settings saves new hotkey letters, then calls this to re-register without a restart.
        HotkeyBindings.applyChange = GlobalHotkeyService::restart
    }
    // Beaten-order tray notifications: OrdersScreen detects the transition, this supplies the
    // tray (the icon resource and windowing live in the app module, not features:orders).
    PendingOrdersQueue.notifier = TrayNotifier::notify
    // No separate startup wipe: MarketLogWatcher itself consumes (imports, then deletes) any
    // recognized export already sitting in the folder within its first couple of polls after
    // start() — including one written just before a restart, before it had a chance to be
    // processed. A blind delete-on-startup step used to run here and would destroy exactly that
    // file before the watcher ever saw it — a real data-loss bug, not just theoretical.
    MarketLogWatcher.start()
    // Watches every character's order books app-wide: competition snapshots, relist detection,
    // beaten-order notifications — regardless of which tab/character is active.
    MarketWatchService.start()
    // Opt-in background contract refresh + status-change badge — see ContractWatchService.
    ContractWatchService.start()
    NostrRelayManager.start()
    P2pRequestNotifier.start()
    // Keeps the P2P Market active identity following whichever character (or corp's acting
    // character) is selected in the main nav — there's no separate manual picker for it anymore.
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { NostrIdentityService.followAppCharacterSelection() }

    application {
        Window(
            title = if (isP2pTestInstance) "EVE Night Trade Tools — P2P TEST" else "EVE Night Trade Tools",
            state = rememberWindowState(width = 1200.dp, height = 800.dp),
            icon = painterResource(Res.drawable.icon),
            onCloseRequest = {
                GlobalHotkeyService.stop()
                MarketLogWatcher.stop()
                MarketWatchService.stop()
                ContractWatchService.stop()
                NostrRelayManager.stop()
                exitApplication()
            },
        ) {
            EventtApp()
        }
    }
}
