package org.eventt.features.market

/**
 * Tracks which Market Analysis sub-tab (Station Trading vs Inter-Region) is currently visible.
 *
 * Both sub-tabs stay mounted in the background once visited (same pattern as the top-level app
 * screens), so each can be actively refreshing its own hotkey queue at once. GlobalHotkeyService
 * lives in the app module, which already depends on this one, so it reads this directly rather
 * than the other way around (this module can't depend on app).
 */
object MarketAnalysisRouter {
    /** 0 = Station Trading, 1 = Inter-Region. */
    @Volatile var activeTab: Int = 0
}
