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
| `core/staticdata` | `JumpGraphService` | ✅ Done | `bfsDistances` covered via `mockkObject(StaticDataDao)`. `ensureRegionGraph` covered separately (real in-memory `StaticDataDao`, only `EsiClient` mocked): fetches+records edges, skips already-fetched systems, a no-stargates system still gets marked fetched, and a failed ESI call leaves that one system unfetched while still reporting progress and not blocking siblings |

### Tier 2 — coroutines / Flow state managers

| Module | Target | Status | Notes |
|---|---|---|---|
| `features/alerts` | `AlertMonitor` | ✅ Done | `checkAlerts` bumped `private` → `internal`. Covers grouping/one-ESI-call-per-type, above/below firing, default-region fallback, a failed DB read and a failed ESI call both being swallowed without crashing or blocking other groups, and accumulation across repeated polls. `AlertDao`/`EsiClient` faked via `mockkObject` |
| `core/everef` | `EveRefService` | ✅ Done | `parseLine`/`parseFileDate` (pure) plus `fetchYearIndex`/`downloadAndParse` (bumped to `internal`, `baseUrl` bumped from `private const` to `internal var` for MockWebServer, same pattern as `EsiClient.esiBaseUrl`). Covers the index-JSON parse (incl. skipping entries missing name/url, non-2xx, malformed JSON) and the BZip2-CSV download path (decompress → save as `everef`-sourced history → mark downloaded; throws on non-2xx or an unrecognized header). `sync()` itself is not covered — it bakes in `LocalDate.now()` directly, so a deterministic test would need date injection too; not worth it for an outer function that's just wiring these two together |
| `core/staticdata` | `StaticDataImporter` | ✅ Done | Refactored its 7 `parseXLine` functions from mutating shared private lists to returning the parsed model (nullable) — the list-append (and, for types, the progress-counter/setState side effect) moved to the `downloadAndParse` call site. Pure functions now, bumped to `internal`. Covers well-formed rows, missing-field defaults, and missing-required-field → null for all 7 (types, groups, categories, market groups, regions, systems, NPC stations) |
| `core/staticdata` | `CitadelService` | ✅ Done | `parse` bumped `private` → `internal`. Covers a well-formed entry, missing optional fields defaulting rather than dropping the entry, a non-numeric key being skipped, a missing name being skipped, and multiple entries / empty object |
| `features/market` | `StationTradingQueue`, `InterRegionQueue` | ✅ Done | Two-phase (PRICE/VOLUME) cursor cycling with `copyVolume` on/off, wraparound, empty-queue no-op, `clear`, and each queue's `priceToSet` math (competitive-bid sigfig step vs. as-is price). Each file has its own `private` (file-scoped, not `internal`) copy of `eveSigFigStep`/`formatEveSigFigPrice` — tried bumping one to `internal` and it collided with the other file's identically-named private one (same package), so both stay `private` and are only exercised indirectly via `priceToSet` |
| `features/orders` | `PendingOrdersQueue` | ✅ Done | `eveSigFigStep`/`formatEveSigFigPrice` (already `internal`) covered directly: 4-sigfig/tenth/cent precision, non-positive-price floor, decimal-count formatting. Queue: sort order (beaten-first then alphabetical), cursor cycling + wraparound via `processNext`, `clear`, and `PendingOrder.priceToSet`'s beaten-vs-not-beaten branching |

### Tier 3 — HTTP/ESI layer (MockWebServer)

| Module | Target | Status | Notes |
|---|---|---|---|
| `core/esi` | `EsiClient` | ✅ Done | `esiBaseUrl` bumped from `private const` to `internal var` so tests can point it at MockWebServer. Covers fresh-cache hit avoiding a second request, stale-cache conditional request + 304 refresh, 401 refresh-and-retry, network failure falling back to stale cached data, and pagination (fetch-all-pages + the merged result being served from cache on a second call) |

### Tier 4 — database DAOs (in-memory SQLite via `DatabaseManager.initialize(":memory:")`)

| Module | Target | Status | Notes |
|---|---|---|---|
| `core/database` | `CharacterDao` | ✅ Done | Insert/getById/getAll (sorted, INSERT OR REPLACE), updateToken vs. updateRefreshToken touching only their own column, token encryption transparent round-trip, null `corporationId` staying null (not 0), delete. Uses a `@TempDir`-backed `TokenCrypto.keyFile`, same as `TokenCryptoTest` |
| `core/database` | `WalletDao` | ✅ Done | Transactions (ascending date order, per-character scoping, partial `updateTransactionNames`, limit/offset paging), journal + `getWalletSummary` (latest balance, daily income/expense/net split), `getTransactionBreakdown` (buy=expense/sell=income, `since` filtering) |
| `core/database` | `AssetDao` | ✅ Done | upsert (INSERT OR REPLACE) + bulkUpsert, character- vs. corp-scoped queries, `getTotalValue` aggregation (incl. zero for no rows), scoped deletes |
| `core/database` | `MarketDao` | ✅ Done | `insertHistory`'s default source, ordering, per-source filtering (`getHistoryBySource`), `deleteEveRefBeforeDate` only touching `everef` rows before the cutoff, `days` limit |
| `core/database` | `ContractDao` | ✅ Done | upsert/bulkUpsert, ordering by `date_issued`, `getByStatus` scoped to a character, contract items round-trip |
| `core/database` | `TrackedOrderDao` | ✅ Done | Generated-id insert (asserted relatively, not as a hardcoded literal — ids aren't reset between test methods since SQLite `AUTOINCREMENT` never reuses them), update, `updateSellPrice` touching only that column, character/corp scoping, delete |
| `core/database` | `WatchlistDao` | ✅ Done | Entries (sortOrder ordering, per-list grouping, delete), price snapshots (`getLatestPrice` — note: `captured_at` is second-granularity, so the test sleeps 1.1s between inserts to get an unambiguous "latest"), sparkline round-trip, `getPriceHistory` scoped by type+station |
| `core/database` | `AlertDao` | ✅ Done | Generated-id insert, `getEnabled` filtering, update, `setEnabled`, `markTriggered` (before/after state), delete |
| `core/database` | `OrderHistoryDao` | ✅ Done | Empty-list no-op, batch upsert + INSERT OR REPLACE, `issued`-descending order, `isBuyOrder` filter, `limit`, per-character scoping |
| `core/database` | `StaticDataDao` | ✅ Done | The biggest DAO — types (search ranking: exact > prefix > substring, published-only, by-group), groups (incl. the type→group name JOIN), market groups (top-level/children/type lookups), stations (incl. the citadel-id-threshold count), regions, systems, the jump graph (bidirectional edge insert, adjacency read), and settings (incl. per-character tax/broker-fee defaults and overrides not leaking across characters) |
| `core/database` | `EsiCacheDao` | ✅ Done | save/get round-trip, hash-based miss on different params, `computeHash`'s order-independence and null-vs-empty-map equivalence, `refreshExpiry`'s `COALESCE` behavior (null etag/lastModified keep the existing value), `isFresh`, `deleteExpired`, `clearAll` |
| `core/database` | `AppState` | ✅ Done | `init()`'s three-way branch (no chars → null, saved id still valid → use it, saved id gone → first character), `selectCharacter` persisting the setting, `refreshCharacters` re-evaluating after characters are added/removed |

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
2. ~~`ClipboardParser` + `JumpGraphService` (Tier 1) — quick wins, no blockers~~ done
3. ~~Bump the four `private → internal` blockers (SsoAuthManager, UpdateChecker, OrdersScreen helpers) and test those~~ done
4. ~~`TokenCrypto` refactor + test~~ done
5. ~~`EsiClient` (Tier 3) — do this once Tier 1 is solid, since it exercises cache/auth/http together~~ done
6. ~~Tier 2 state managers~~ done
7. ~~Tier 4 DAOs~~ done — all 12
8. Tier 5 only if/when there's an appetite for UI testing infrastructure
