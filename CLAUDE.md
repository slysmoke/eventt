# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

EVE Trader is a desktop trading application for EVE Online, built with Jetpack Compose for Desktop and Kotlin/JVM. It interfaces with the EVE Online ESI (REST) API, using OAuth2 SSO for authentication.

## Build & Run Commands

```bash
./gradlew build              # Compile and test all modules
./gradlew run                # Run the desktop app locally
./gradlew test               # Run all unit tests
./gradlew :module:test       # Run tests for a specific module (e.g. :core:database:test)
./gradlew package            # Create platform-specific distributable (Dmg/Msi/Deb)
./gradlew createDistributable
```

Targets JVM 21, Kotlin 1.9.22. No linter configured; code style is `kotlin.code.style=official`.

## Architecture

### Module Structure

Two layers of modules under `core/` and `features/`, plus `ui/`:

- **`core/`**: model, database, http, cache, auth, esi, queue, staticdata
- **`features/`**: characters, market, assets, wallet, orders, dashboard, alerts, industry, contracts, watchlist
- **`ui/`**: theme (Material 3 + EVE color palette), common (shared Compose components)

### Dependency Flow

```
Main.kt → DatabaseManager.initialize() → EveTraderApp (Compose)
    ↓
:features:* → :core:{model, database, auth, esi, cache, queue}
:core:esi   → :core:{auth, cache, http, queue, database}
:core:auth  → :core:{database, http, model}
```

### Entry Point

`Main.kt` (package `org.eve.trader`):
1. Initializes SQLite with WAL mode pragmas in a synchronized block — **this must happen before the UI starts**
2. Opens a 1200×800 Compose desktop Window
3. Renders `EveTraderApp`, which provides tab-based navigation to the 10 feature screens

### Key Patterns

**SQLite concurrency**: All database access runs on `Dispatchers.IO.limitedParallelism(1)` to prevent `SQLITE_BUSY`. The DB file lives at `~/.eve-trader/eve_trader.db`. Core services use Kotlin `object` singletons rather than Koin injection (despite the Koin dependency being declared).

**Caching**: Three-state model (FRESH/STALE/MISS) based on ESI `Expires` headers. Stale cache is served immediately while a background refresh fires async. Default TTL is 5 minutes when no header is present.

**Request queue**: `StateFlow<List<QueuedRequest>>` tracks all in-flight ESI calls with status (QUEUED / IN_PROGRESS / COMPLETED / FAILED). A UI dialog surfaces overall request progress.

**OAuth2 flow**: An embedded Ktor server on `localhost:8000` handles the SSO callback. `Desktop.browse()` opens the browser; the auth flow blocks on a `Lock` until the authorization code arrives.

**Threading**: OkHttp calls on `Dispatchers.Default`, DB on the single-threaded IO dispatcher above, UI updates marshaled via the Swing coroutine dispatcher.

### Dependency Versions

All library versions are centralized in `gradle/libs.versions.toml`. Update versions there, not in individual build files.
