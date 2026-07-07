# Test coverage plan

Tracks what's actually tested vs not, module by module. Update the status column as work lands —
this file is the source of truth for "what's done," not the conversation that produced it.

## Stack

- **JUnit 5 (Jupiter)** — test runner (`useJUnitPlatform()`, configured project-wide in the root `build.gradle.kts`)
- **MockK** — mocking, chosen over Mockito specifically because this codebase leans on `object`
  singletons (`EsiClient`, `SsoAuthManager`, `DatabaseManager`, ...) that Mockito can't fake without
  extra ceremony; MockK's `mockkObject()` handles them directly
- **Kotest assertions** (`kotest-assertions-core`) — used standalone for `shouldBe`-style matchers,
  not the full Kotest test framework/runner
- **kotlinx-coroutines-test** — `runTest` for suspend functions
- **Turbine** — `flow.test { }` for `StateFlow`/`Flow` emissions
- **OkHttp MockWebServer** — fake HTTP server for the ESI/HTTP layer (`core:http` only so far;
  add to a module's `build.gradle.kts` as `testImplementation(libs.okhttp.mockwebserver)` if needed elsewhere)

All of the above are already wired into every subproject via the root `build.gradle.kts`
(`pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { dependencies { testImplementation(...) } }`) —
new modules get them for free, no per-module setup needed except MockWebServer.

## Run it

```bash
./gradlew test              # every module
./gradlew :core:cache:test  # one module
```

## Conventions (read before writing a new test)

- **Never let `DatabaseManager` self-initialize against the real DB.** It lazily opens
  `~/.eve-trader/eve_trader.db` on first use if nothing has called `initialize()` yet — which, on a
  dev machine that's actually used the app, is real production data. Any test that touches a DAO
  or `EsiCacheManager` must call `DatabaseManager.initialize(":memory:")` in a `@BeforeAll` first.
  (This bit us once already while writing `EsiCacheManagerTest` — the fix landed, but treat it as a
  standing landmine, not a one-off.)
- **Singleton `object`s need manual reset.** `RequestQueueManager`, `AlertMonitor`, `PendingOrdersQueue`,
  etc. hold process-wide state with no per-test isolation. Reset it in `@AfterEach` (see
  `RequestQueueManagerTest.resetQueue()`), or tests will pass/fail depending on execution order.
- **Prefer constructor injection over statics for anything a test needs to control** — see
  `EsiThrottleInterceptor`'s `legacy420BackoffMs`/`rateLimitCooldownMs` constructor params (added
  specifically so tests don't have to sleep 60 real seconds to exercise the 420 path) and its
  `cooldownUntilMs` being an instance field, not a `companion object` one (so two test instances
  don't share cooldown state). Retrofit this pattern when a class you're testing resists isolation.
- **Some private helper functions need to become `internal`** before they're reachable from a test
  in the same module (JVM `private` isn't visible to the test source set; `internal` is). This is a
  visibility change only — no behavior change — call it out in the PR/commit when you do it.

## Status

### Tier 1 — pure logic (highest value, cheapest to test — do these first)

| Module | Target | Status | Notes |
|---|---|---|---|
| `core/cache` | `EsiCacheManager` | ✅ Done | `parseExpiresHeader`/`parseCacheControl` + `get`/`save` FRESH/STALE/MISS round-trip via in-memory DB |
| `core/http` | `EsiThrottleInterceptor` | ✅ Done | 429/420/5xx retry, error-limit cooldown, 4xx passthrough, exhausted-retries give-up, via MockWebServer |
| `core/queue` | `RequestQueueManager` | ✅ Done | enqueue/markInProgress/complete/clearCompleted/overallProgress, via Turbine + StateFlow |
| `core/database` | `TokenCrypto` | ✅ Done | `keyFile` changed from `by lazy` to an overridable `internal var` (key is now re-read per call instead of cached, so reassigning it can't leave a stale key). Covers round-trip, IV randomness, garbage/bogus ciphertext returning null instead of throwing, cross-key-file decrypt failure, and first-use key generation |
| `core/auth` | `SsoAuthManager` (`generateCodeVerifier`/`codeChallenge`/`parseQueryString`) | ✅ Done | Bumped `private` → `internal`. `codeChallenge` verified against the RFC 7636 Appendix B worked example, not just round-tripped |
| `app` | `UpdateChecker` (`isNewer`) | ✅ Done | Bumped `private` → `internal`. Covers major/minor/patch ordering, missing trailing segments, and non-numeric segments being dropped rather than failing |
| `features/orders` | `OrdersScreen` (`computeMarginPct`/`computeBestMarginPct`/`historyPnl`) | ✅ Done | Bumped `private` → `internal` (`MarketComparison` too, to construct it in tests). Covers the tax/fee math and `historyPnl`'s FIFO-match vs. avg-cost-basis-fallback vs. null-null branches |
| `features/orders` | `CostBasisService` (FIFO cost basis) | ✅ Done | FIFO across lots, oversell handling, `avgCostBasisForType` fallback, `pnlForOrder` date/qty matching — `WalletDao` faked via `mockkObject` |
| `features/overlay` | `ClipboardParser` | ✅ Done | `parse()` covered (sell/buy row shapes, malformed input, field fallbacks); `readClipboard()` is a thin AWT passthrough, not tested |
| `core/staticdata` | `JumpGraphService` | 🟡 Partial | `bfsDistances` covered (chains, branching shortest-path, unreachable nodes, cycles) via `mockkObject(StaticDataDao)`; `ensureRegionGraph` (the ESI-fetching half) not tested — Tier 3 candidate, not pure |

### Tier 2 — coroutines / Flow state managers

| Module | Target | Status | Notes |
|---|---|---|---|
| `features/alerts` | `AlertMonitor` | ⬜ Not started | Polling loop + `triggered` StateFlow |
| `core/everef` | `EveRefService` | ⬜ Not started | Sync state flow + file diffing logic |
| `core/staticdata` | `StaticDataImporter` | ⬜ Not started | Import state flow |
| `core/staticdata` | `CitadelService` | ⬜ Not started | Sync state flow |
| `features/market` | `StationTradingQueue`, `InterRegionQueue` | ⬜ Not started | Queue state management, same shape as `PendingOrdersQueue` |
| `features/orders` | `PendingOrdersQueue` | ⬜ Not started | |

### Tier 3 — HTTP/ESI layer (MockWebServer)

| Module | Target | Status | Notes |
|---|---|---|---|
| `core/esi` | `EsiClient` | ✅ Done | `esiBaseUrl` bumped from `private const` to `internal var` so tests can point it at MockWebServer. Covers fresh-cache hit avoiding a second request, stale-cache conditional request + 304 refresh, 401 refresh-and-retry, network failure falling back to stale cached data, and pagination (fetch-all-pages + the merged result being served from cache on a second call) |

### Tier 4 — database DAOs (in-memory SQLite via `DatabaseManager.initialize(":memory:")`)

| Module | Target | Status |
|---|---|---|
| `core/database` | `CharacterDao` | ⬜ Not started |
| `core/database` | `WalletDao` | ⬜ Not started |
| `core/database` | `AssetDao` | ⬜ Not started |
| `core/database` | `MarketDao` | ⬜ Not started |
| `core/database` | `ContractDao` | ⬜ Not started |
| `core/database` | `TrackedOrderDao` | ⬜ Not started |
| `core/database` | `WatchlistDao` | ⬜ Not started |
| `core/database` | `AlertDao` | ⬜ Not started |
| `core/database` | `OrderHistoryDao` | ⬜ Not started |
| `core/database` | `StaticDataDao` | ⬜ Not started |
| `core/database` | `EsiCacheDao` | ⬜ Not started | (already implicitly covered by `EsiCacheManagerTest`, but no dedicated tests of its own SQL) |
| `core/database` | `AppState` | ⬜ Not started |

### Tier 5 — Compose UI screens (lowest priority — likely last, possibly never)

All `*Screen.kt` files across `features/*` and `app/ui/EventtApp.kt`. Needs a Compose UI test
harness (`ui-test-junit4` or the desktop equivalent) to do properly; much higher effort and lower
ROI than the tiers above for a trading tool where the value is in the calculations, not the widgets.
Not started, not scheduled.

### Out of scope / no meaningful logic to test

- `core:model` (`Models.kt`) — plain data classes, nothing to assert beyond what the compiler already guarantees
- `ui:theme` — color/typography constants
- `core:image` (`EveImageServer`) — embedded image proxy; revisit if it grows real logic beyond request forwarding

## Suggested order

1. ~~`CostBasisService` (Tier 1) — highest value target in the app, no blockers~~ done
2. ~~`ClipboardParser` + `JumpGraphService` (Tier 1) — quick wins, no blockers~~ done (`JumpGraphService` partially — see note above)
3. ~~Bump the four `private → internal` blockers (SsoAuthManager, UpdateChecker, OrdersScreen helpers) and test those~~ done
4. ~~`TokenCrypto` refactor + test~~ done
5. ~~`EsiClient` (Tier 3) — do this once Tier 1 is solid, since it exercises cache/auth/http together~~ done
6. Tier 2 state managers
7. Tier 4 DAOs
8. Tier 5 only if/when there's an appetite for UI testing infrastructure
