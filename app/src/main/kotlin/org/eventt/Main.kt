package org.eventt

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.eventt.app.generated.resources.Res
import org.eventt.app.generated.resources.icon
import org.eventt.core.cache.EsiCacheManager
import org.eventt.core.database.DatabaseManager
import org.eventt.core.http.EveHttpClient
import org.eventt.core.marketlogs.MarketLogWatcher
import org.eventt.core.model.AppPaths
import org.eventt.core.model.HotkeyBindings
import org.eventt.core.nostr.NostrIdentityService
import org.eventt.core.nostr.NostrRelayManager
import org.eventt.features.contracts.ContractWatchService
import org.eventt.features.orders.LeaderboardPublisher
import org.eventt.features.orders.MarketWatchService
import org.eventt.features.orders.PendingOrdersQueue
import org.eventt.notify.TrayNotifier
import org.eventt.ui.EventtApp
import org.jetbrains.compose.resources.painterResource
import java.io.File
import java.io.RandomAccessFile
import javax.swing.JOptionPane
import kotlin.system.exitProcess

// Held for the process lifetime once acquired; released automatically when the JVM exits.
private var singleInstanceLock: RandomAccessFile? = null

// One SQLite file under WAL mode expects a single writer process — a second instance racing
// the first would risk SQLITE_BUSY/corruption, not just a confusing duplicate window.
private fun acquireSingleInstanceLockOrExit() {
    val lockFile = File(AppPaths.appDataDir, "app.lock")
    val raf = RandomAccessFile(lockFile, "rw")
    if (raf.channel.tryLock() == null) {
        raf.close()
        JOptionPane.showMessageDialog(
            null,
            "EVE Night Trade Tools is already running.",
            "Already running",
            JOptionPane.WARNING_MESSAGE,
        )
        exitProcess(1)
    }
    singleInstanceLock = raf
}

fun main() {
    // ESI requires a User-Agent identifying the app on every request — must be set before
    // any ESI/SSO call, which is why this runs before anything else in main().
    val repoUrl = AppVersion.GITHUB_REPO.takeIf { it.isNotBlank() }?.let { "https://github.com/$it" }
    EveHttpClient.configure(
        "EventNightTradeTools/${AppVersion.NAME}" + (repoUrl?.let { " (+$it)" } ?: ""),
    )

    // Must run before anything touches the DB — a second instance racing the first on the
    // same SQLite file is exactly what this guards against.
    acquireSingleInstanceLockOrExit()

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

    GlobalHotkeyService.start()
    // Settings saves new hotkey letters, then calls this to re-register without a restart.
    HotkeyBindings.applyChange = GlobalHotkeyService::restart
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
    // Opt-in trader leaderboard republish sweep — see LeaderboardPublisher.
    LeaderboardPublisher.start()
    P2pRequestNotifier.start()
    // Keeps the P2P Market active identity following whichever character (or corp's acting
    // character) is selected in the main nav — there's no separate manual picker for it anymore.
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { NostrIdentityService.followAppCharacterSelection() }

    application {
        Window(
            title = "EVE Night Trade Tools",
            state = rememberWindowState(width = 1200.dp, height = 800.dp),
            icon = painterResource(Res.drawable.icon),
            onCloseRequest = {
                GlobalHotkeyService.stop()
                MarketLogWatcher.stop()
                MarketWatchService.stop()
                ContractWatchService.stop()
                LeaderboardPublisher.stop()
                NostrRelayManager.stop()
                exitApplication()
            },
        ) {
            EventtApp()
        }
    }
}
