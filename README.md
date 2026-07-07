# <img src="app/icons/icon.png" width="40" valign="middle" alt="App icon"> EVE Night Trade Tools

A desktop trading toolkit for [EVE Online](https://www.eveonline.com/), built with Kotlin/JVM and Jetpack Compose for Desktop. It talks to CCP's ESI API (OAuth2 SSO) to pull your characters' orders, wallet, assets, and contracts, and layers market analysis, alerts, and a FIFO cost-basis/P&L engine on top.

## Features

- **Dashboard** — at-a-glance summary across your tracked characters
- **Characters** — manage linked EVE characters via SSO
- **Market** — browse regional market orders and price history
- **Analysis** — trade-hub comparison and market analysis tools
- **Assets** — inventory viewer across stations/structures
- **Wallet** — transaction journal and P&L
- **Orders** — active buy/sell orders with margin and market-comparison columns, order history, and a FIFO-costed inventory view
- **Watchlist** — track items with sparklines
- **Alerts** — price alerts with in-app notifications
- **Contracts** — contract tracker
- **Settings** — tax rates, data source, and app preferences

A global hotkey (Ctrl+Z) cycles through your queued orders, opens the in-game market window, and copies an overbid/undercut price to your clipboard.

## Requirements

- JDK 21
- ~2 GB free disk for the EVE static data import on first run

No API keys or configuration needed — the ESI OAuth client ID is bundled; you just log in with your EVE account on first launch.

## Auth & data storage

Login uses EVE SSO's OAuth2 Authorization Code flow with PKCE — no client secret is embedded in the app. Character access/refresh tokens are encrypted at rest (AES-256-GCM) with a local, per-machine key, both stored under `~/.eve-trader/`.

## Building & running

```bash
./gradlew build              # Compile and test all modules
./gradlew run                # Run the desktop app locally
./gradlew test               # Run all unit tests
./gradlew :module:test       # Run tests for a specific module (e.g. :core:database:test)
```

Targets JVM 21 / Kotlin 1.9.22. No linter configured; code style is `kotlin.code.style=official`.

## Packaging

```bash
./gradlew createDistributable   # Portable app-image directory (used for the zip release asset)
./gradlew packageDeb            # .deb installer (Linux)
./gradlew packageMsi            # .msi installer (Windows, per-user install)
./gradlew packageDmg            # .dmg installer (macOS)
./gradlew shadowJar             # Runnable fat jar (java -jar app/build/libs/eventt-<version>.jar)
```

The fat jar is **not** cross-platform despite the format — it bundles whichever OS's Skiko/Compose native rendering libs were present at build time (`compose.desktop.currentOs`). CI builds one per OS, released as `eventt-linux.jar` / `eventt-windows.jar` / `eventt-macos.jar`; grab the one matching your OS, same as the zip or installer.

A NixOS package is also available via `flake.nix` (run `./gradlew createDistributable` first).

## Releases & auto-update

Pushing a tag matching `vX.Y.Z` triggers `.github/workflows/release.yml`, which builds a zip, native installer, and fat jar for Linux/Windows/macOS and publishes them as a GitHub Release. The workflow can also be run manually via `workflow_dispatch` (from the Actions tab) to test the build without cutting a real release.

The app checks the repo's latest GitHub Release on startup and offers a one-click update banner if a newer version is available (see `github.repo` in `gradle.properties`). Self-update works for the portable zip, the `.dmg` install, the per-user `.msi` install, and the fat jar; a system-wide package install (e.g. `.deb` installed via `apt`) can't self-update and will point you to the release page instead.

## Architecture

Modules are organized under `core/`, `features/`, and `ui/`:

- **`core/`**: model, database, http, cache, auth, esi, queue, staticdata, image, everef
- **`features/`**: characters, market, assets, wallet, orders, dashboard, alerts, contracts, watchlist, settings, overlay
- **`ui/`**: theme (Material 3 + EVE color palette), common (shared Compose components)

```
Main.kt → DatabaseManager.initialize() → EventtApp (Compose)
    ↓
:features:* → :core:{model, database, auth, esi, cache, queue}
:core:esi   → :core:{auth, cache, http, queue, database}
:core:auth  → :core:{database, http, model}
```

See `CLAUDE.md` for a deeper dive into caching, the request queue, threading, and other internal patterns.
