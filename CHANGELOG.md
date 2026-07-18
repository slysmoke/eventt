
## Unreleased


### Other

- Generate categorized release notes with git-cliff
## v1.0.12 - 2026-07-18


### Added

- Add CodeQL analysis workflow configuration
- Add Dependabot config for Gradle and GitHub Actions updates
- Add junit-platform-launcher to fix JUnit 6 test discovery
- Add junit-platform-launcher to fix test discovery on Gradle 9
- Add search, sortable columns, and pagination to Wallet transactions/journal
- Add Top Buyers & Sellers table to Dashboard
- Add sortable columns to Orders History/Inventory, Total to Sell
- Add inventory-value calculator to EVE Trade Calc overlay

### Changed

- Pin Kotlin JVM toolchain to 21 for all subprojects
- Switch java-kotlin CodeQL analysis to build-mode: none
- Pin nix devShell to gradle_9, refresh flake.lock
- Migrate window icon off deprecated classpath painterResource
- Unify relist fee accounting into FIFO cost basis
- Rework Contracts: character/corp scoping, refresh cooldown, badge
- Improve Inter-Region analysis: swap/presets, item detail dialog, fee model fixes

### Fixed

- Fix runtime regression: junixsocket-core exclude broke Wayland hotkeys
- Fix Portal hotkey signal matching: resolve well-known name to unique name
- Fix orphaned corporations left behind after character deletion
- Fix corp wallet transactions/journal leaking into personal view

### Removed

- Disable java-kotlin CodeQL analysis until Kotlin 2.4.10 is supported
- Exclude POM-only junixsocket-core from the shadow-jar classpath
- Remove dead null-safety code left over from OkHttp 5's non-null body
- Skip ESI calls to routes ESI itself reports as degraded
- Drop the verify job from the release workflow
## v1.0.11 - 2026-07-16


### Changed

- Trim SSO scope request to only scopes EsiClient actually uses

### Fixed

- Fix ktlint spacing violation in SsoAuthManager
## v1.0.10 - 2026-07-16


### Added

- Add selectable themes and fonts, fix light-theme readability

### Fixed

- Fix intermittent SSO token failures from unencoded form values

### Other

- Make trade overlay follow the selected theme and font
## v1.0.9 - 2026-07-16


### Fixed

- Fix missing jdk.httpserver module in packaged runtime
## v1.0.8 - 2026-07-16


### Added

- Add app screenshots (dashboard, market browser, orders, analysis)

### Other

- Dashboard: stop calling cash flow P&L; chart now shows daily realized P&L
- Hotkey: survive X11 grab conflicts instead of dying with the JVM
- P2P Market: harden the Nostr flow — security, relays, presence, notifications
- Orders: competition detector — who's fighting you for the top of the book
- Analysis: Margin is now NET everywhere, plus a separate ROI column
- Wallet: realized P&L chart and an honest margin card
- Orders: competition tooltip + app-wide background market watch
- Wallet profitable days by realized P&L, beaten-count nav badge, wiki refresh
- Cache: wire in the expired-row purge and vacuum, deflate big ESI payloads
- Settings: drop the 6-month EveRef history option, default to 1 month
- Orders: sell-order Best Margin nets the relist fee
- Update macOS version in release workflow
- Update macOS version in release workflow
## v1.0.7 - 2026-07-15


### Added

- Add landing page deployed to GitHub Pages via Actions

### Other

- Split MarketAnalysisScreen into per-concern files
- Split OrdersScreen into per-concern files
- Overlay: copy back beat price for copied orders and book imports
- PLEX: treat order book as the global market it is
- Dashboard: top winners/losers instead of alerts, combined all-entities mode
- Dashboard: daily P&L bar chart over the last 30 days
- Inventory: show how long capital has been stuck in each item
- Overlay: price-check any item by copying its name
- Surface swallowed errors: AppLog journal with topbar indicator
- Orders: beaten-only hotkey mode, stable cycle position, tray alerts
- Notify: fall back to notify-send when there is no system tray
- Orders: selecting a row repositions the Ctrl+Z cycle to that order
- docs: add user wiki (Russian)
- docs: English wiki + Russian versions of wiki and README, fix stale docs
- Assets: refresh replaces the snapshot instead of only upserting
- Show real item icons from images.evetech.net instead of placeholders
- Dashboard: persist the Combine all switch across restarts
- History: subtract an order's relist fees from its shown profit
- Settings: configurable global hotkeys (Ctrl + chosen letter)
- Hotkeys: capture any Ctrl/Alt/Shift+letter combo by pressing it
- Packaging: proper .desktop metadata for the .deb
- Site: denser starfield with real night-sky twinkle
- Site: self-heal the starfield canvas size every frame
- Site: landing page moved to the slysmoke.github.io user repo

### Removed

- Remove dead code and consolidate formatIsk into ui:common
## v1.0.6 - 2026-07-14


### Added

- Add CI status and release version badges to README
- Add regression test for medianDailyVolume's calendar-window fix

### Other

- Enhance README with badges for downloads and visits
- Market Analysis: fix median volume to use calendar window, not row count
- ktlint: fix chain-method-continuation formatting in medianDailyVolume
- Wallet/Dashboard: add FIFO cost-basis P&L alongside cash-flow, fix UTC/local date drift
- Orders: track relist fees per order, redesign Sell table columns and sorting
- Orders: fix phantom relist counting across view switches and restarts
- Orders: show total relist fees of active orders in the summary bar
- Analysis: align filter bar controls and pin primary action right
- Analysis: bulk-fetch whole region order book past 1000 candidate types

### Removed

- Remove watchlist feature and related code
## v1.0.5 - 2026-07-11


### Fixed

- Fix stale Kotlin version (1.9.22 -> 2.4.0) in README and CLAUDE.md

### Other

- P2P Market: remove leftover TEMP debug logging from NostrRelayManager
- Update README: Trade Calc, Ctrl+M hotkey, ktlint/detekt, license
- Stop tracking local planning/instruction docs (PLAN.md, TESTING_PLAN.md, CLAUDE.md)
- Ignore PLAN.md, TESTING_PLAN.md, CLAUDE.md
- Stop tracking Qwen Code CLI config (.qwen/, QWEN.md)
- Update README to remove CLAUDE.md reference
## v1.0.4 - 2026-07-11


### Added

- Add QWEN.md and PLAN.md with project context and development plan
- Add comprehensive OAuth2 logging for debugging auth flow
- Add Market Analysis screen with station and inter-region trading tabs
- Add GitHub Actions: build + smoke test on Linux / Windows / macOS
- Add versioning, auto-updater, and release CI pipeline
- Add Settings screen, EveRef bulk history source, and Analysis improvements
- Add citadel/player-structure support and fix wallet journal display
- Add global character selector and per-character tax settings
- Add station picker to Analysis screen (Station Trading + Inter-Region)
- Add NPC station support: fix SDE import and station picker UX
- Add row numbers, row selection and copy-to-clipboard in Analysis
- Add order history tab, Dashboard ESI refresh, and market accuracy fixes
- Add trade overlay window: clipboard parser + profit/margin calc
- Add Nix flake for reproducible dev shell
- Add FIFO cost basis tracking and P&L to Orders screen
- Add ESI cooldown timer to Orders and Wallet refresh buttons
- Add overbid indicator, market comparison, and in-game hotkey for orders
- Add global hotkey (Ctrl+Shift+Space) to cycle orders from background
- Add NixOS package via flake.nix + fix java.sql module in distributable
- Add citadel-aware order matching, Ctrl+Z hotkey queues, and inventory pricing fixes
- Add Margin/Best Margin columns to Orders, drop Group/Range/Min Qty
- Add native installers, jar release, and working self-update
- Add README and set version to 0.0.1
- Add app icon: NTT monogram, blue palette
- Add ktlint + detekt, gate CI/release on tests and lint passing
- Add a Stop button to cancel a running Analysis pass
- Add corporation trading support; fix Orders hotkey-reset and unclear buttons
- Add Tools page: Cargo Splitter and Sell Pricing
- Add per-member corp views: character picker, order issuer filter, safe buy-to-sell
- Add EVE Marketlogs import: instant Orders refresh + Overlay order-book feed
- Add P2P Market: Nostr-based off-market order board (Phase 0 + Phase 1)
- Add MIT license

### Changed

- Improve Market Browser, Wallet UI and auth fixes
- Rework Orders screen and fix Wallet transactions display
- Improve Orders screen: volume progress bar, Group column, Order Age, summary bar
- Improve Inter-Region analysis: fee calculation, vol/day, Net×Vol, citadel names
- Migrate SSO auth to PKCE, encrypt tokens at rest

### Fixed

- Fix database initialization and auth error handling
- Fix SQLITE_BUSY race condition on database initialization
- Fix SQLITE_BUSY: Initialize DB in main() before any UI starts
- Fix StackOverflowError in DatabaseManager
- Fix Market Browser: auto-import SDE on first open + use searchMarketTypes
- Fix SDE JSONL ZIP importer: stream was being consumed by forEachLine
- Fix Watchlist prices + add delete + Market Browser integration
- Fix repeat analysis + cache correctness + category-scoped history fetch
- Fix deprecated icon warnings in Wallet and Orders screens
- Fix ESI cache: ETag conditional requests + in-memory L1 layer
- Fix asset valuation: fetch prices from /markets/prices/ instead of missing field
- Fix PLEX region override and swap swapped Buy/Sell columns in Station Trading
- Fix Analysis inner tabs losing state on switch
- Fix Compose state writes on IO thread and industry compiler warnings
- Fix SQLite concurrent transaction crash and compiler warnings
- Fix RequestQueueManager thread-safety: synchronize all mutating methods
- Fix Watchlist prices, add current price to alert dialog, implement alert monitoring
- Fix missing station and client names for old wallet transactions
- Fix market history chart — show oldest→newest left to right
- Fix stuck QUEUED requests and simplify ESI request dialog
- Fix Orders column alignment: all columns left-aligned
- Fix Dashboard P&L/Income/Expenses: compute from transactions not journal
- Fix ESI refresh timer: include datasource param in expiry lookup
- Fix History P&L: add fallback cost basis and correct margin formula
- Fix crash on startup: catch UnsatisfiedLinkError from JNativeHook
- Fix P&L always showing — in order history
- Fix remaining xorg deprecated refs in shellHook LD_LIBRARY_PATH
- Fix Windows app-image path: no bin/ subfolder, unlike Linux/macOS
- Fix self-update jar-path resolution for bare relative launches
- Fix m³/unit for ships: resolve packaged volume from ESI, not the bulk SDE
- Fix macOS release build: GitHub retired the free Intel (x86_64) hosted runner
- Fix Marketlogs startup wipe destroying unprocessed exports; other small fixes

### Other

- Initial commit
- Phase 1: Complete core infrastructure implementation
- Phase 2+3: Complete all feature screens - BUILD SUCCESSFUL
- Implement SDE JSONL ZIP importer for static game data
- Complete Market Browser rewrite with market group tree navigation
- Market Analysis: any-region picker + category/subcategory filter
- Market Analysis: trade type selector + fix region picker UX
- Rewrite MarketAnalysisScreen: horizontal filter bar + persistent settings
- Redesign Analysis filter bar: chip dropdowns + label-above layout
- Auto-refresh expired tokens on Characters screen + retry 401 in ESI client
- Untrack Gradle and build artifacts already covered by .gitignore
- Preserve screen state across navigation by keeping screens in composition
- Per-type ESI requests in Station Trading analysis with progressive results
- Import packagedVolume from SDE — was silently 0.0 causing volume fallback
- Refactor Inter-Region analysis to per-type parallel fetching
- Auto-sync EveRef on startup and remove manual SDE button
- Speed up EveRef sync: one transaction per file, buffered BZip2 reads
- Analysis: load broker fee and sales tax from character settings
- Show EveRef sync progress banner; don't block startup
- Replace redundant market_history index with (source, date) for fast EveRef cleanup
- Rewrite Dashboard: wallet balance, asset value, P&L, recent transactions, triggered alerts
- Replace click-toggle with drag-to-select in Analysis results
- Show all journal entries in Wallet: remove row limit
- Show full Gradle version banner in dev shell greeting
- Suppress SLF4J startup warning by adding slf4j-nop runtime dep
- Apply sales tax and broker fee to profit/margin calculations
- Upgrade Kotlin 1.9.22 → 2.0.21 and Compose 1.6.0 → 1.7.3
- Change global hotkey from Ctrl+Shift+Space to Ctrl+Z
- flake.nix: add xorg.libXt to devShell for JNativeHook
- Highlight the order currently open in EVE via the global hotkey
- Sync wallet transactions on orders refresh; add P&L recalculate button
- Show estimated cost/profit in Sell tab when wallet data is stale
- Replace Calculate icon with Autorenew for P&L recalculate button
- EVE sigfig pricing + tab-filtered hotkey queue
- Update flake.nix xorg package names to non-deprecated forms
- Make wallet P&L tab actually readable
- Replace JNativeHook with per-platform native hotkey backends
- Rename package and product to EVE Night Trade Tools (org.eventt)
- Finish eventt rename: update leftover eve_trader token prefix
- Track gradle-wrapper.jar so CI can run ./gradlew
- Set version to 1.0.0
- Implement ESI best-practices and rate-limiting guidance
- Document that the release jar is per-OS, not cross-platform
- Put app icon inline with the README title, not floated above it
- Make the codebase pass ktlint + detekt, tune both for this project
- Set up test infrastructure (JUnit5 + MockK + Kotest + Turbine + MockWebServer)
- Test CostBasisService: FIFO cost basis, oversell, avgCostBasisForType, pnlForOrder
- Test ClipboardParser and JumpGraphService.bfsDistances
- Test SsoAuthManager PKCE helpers, UpdateChecker.isNewer, OrdersScreen margin math
- Make TokenCrypto's key path overridable and test it
- Test EsiClient: caching, conditional requests, 401 retry, pagination
- Test Tier 2 state managers: AlertMonitor, EveRefService, CitadelService, market queues
- Test all 12 core:database DAOs (Tier 4)
- Close remaining test-plan gaps: StaticDataImporter, EveRefService network, ensureRegionGraph
- Center app branding in the top bar, unify Dashboard/Wallet P&L math
- Inter-Region: compute real buy quantity from the live order book, not vol/day
- Stop the ESI Requests dialog from jittering; animate/color its button; ease rate-limit pressure
- Orders history: derive real fill status instead of trusting ESI's state field
- Auto-scroll hotkey lists so the active row stays in view
- Clarify Station Trading's "Orders" column header
- Auto-sync player structures on startup; drop the 1-year history option
- Orders: periodically re-check competing prices instead of only once on load
- Actually run ESI cache cleanup — it existed but was never called
- Auto-VACUUM on startup, but only when it's actually worth it
- Move app data to per-OS app-data directories, migrating existing installs
- Split macOS release into x64/arm64 builds; document JDK setup and unsigned-app install
- Persist active orders locally and stop ESI's own cache from reverting them
- Market Analysis: fix Safe Buy->Sell pricing, add skip-existing-orders, redo filter bar layout
- Market Analysis: redesign filter bar for visual consistency
- Tools: fix Cargo Splitter valuation, make Sell Pricing margin fee-aware
- P2P Market Phase 2: post/renew/cancel own orders
- P2P Market Phase 3: NIP-17 DM reservation handshake
- P2P Market Phase 4: receipts + reputation
- P2P Market Phase 5: polish — relay health, error surfacing, expiry cues, tests
- Confirm ORDER_KIND/RECEIPT_KIND don't collide with the live NIPs registry
- P2P Market: per-character identity, item autocomplete, price suggestion, sortable tables
- P2P Market: enrich My Requests and Inbox rows with item/price/region details
- P2P Market: fix accept/decline across characters, add in-game info links, table UI everywhere
- P2P Market: fix reservation delivery/status bugs, add PoW+rate limiting, UX pass
- P2P Market: split Incoming Requests into its own tab, fix badge/UI bugs
- P2P Market: pricing model rework, buy/sell clarity, fresh-post visibility, README
- Market Analysis: fix Inter-Region bugs, add margin sanity-check tooling
- Extract shared EVE price formatting and JSON name-parsing helpers
- Orders/Pricing: scope sell prices to station; PLEX parsing; overlay hotkey
- Trade Calc: real depth-walked buy/sell wall totals + top-5% avg price

### Removed

- Remove the Industry feature — unused and never wired to real data

