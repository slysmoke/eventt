# EVE Night Trade Tools — Project Context

## Project Overview
Desktop trading application for **EVE Online** built with **Kotlin** and **Jetpack Compose for Desktop**.

## Tech Stack
- **Language**: Kotlin (JVM)
- **UI**: Jetpack Compose Desktop + Material 3
- **Build**: Gradle (Kotlin DSL)
- **Database**: SQLite
- **HTTP**: OkHttp (HTTP/2 support) + Kotlin Coroutines
- **JSON**: kotlinx.serialization
- **Auth**: EVE Online SSO (OAuth 2.0 / ESI)
- **Charts**: (TBD — likely a Compose-compatible charting library)
- **Image loading**: EVE Image Server (renders, icons, portraits, etc.)

## EVE Online ESI Details

### Client Credentials
```json
{
  "name": "eventt",
  "description": "Eve Night Trade Tools",
  "clientId": "9bacf8234c4b41888f00b084413868c0",
  "clientSecret": "eat_1NcbJbALfafoObI921w8HMpbUPKjEosp9_4NSjgX",
  "callbackUrl": "http://localhost:8000/callback",
  "scopes": [ /* see scopes in project */ ]
}
```

### Key ESI Concepts
- **ESI endpoints** cover both character and corporation data
- **Expires header** on each response — use for cache TTL
- **Rate limits**: ~20 req/s (varies by endpoint)
- **HTTP/2**: supported by ESI
- **Static data**: SDE (Static Data Export) — downloadable, infrequently changing
- **Image Server**: `https://images.evetech.net/` — renders, icons, portraits, etc.

### ESI Base URL
```
https://esi.evetech.net/latest/
```

## Architecture Decisions

### Caching Layer
- Every ESI response has `Expires` header → calculate TTL
- Cache stored in SQLite (freshness check before requesting)
- Cache states: `FRESH` (serve from cache), `STALE` (serve but refresh), `MISS` (fetch)
- Background refresh for stale data (don't block UI)

### HTTP Layer
- OkHttp with HTTP/2 enabled
- Connection pooling
- Request queue with progress tracking
- Retry on 4xx/5xx with exponential backoff

### Database (SQLite)
- Characters table
- Corporations table
- ESI cache tables (per endpoint)
- Orders (local tracking — buy price, margins)
- Transactions & journal entries (profit/loss)
- Assets (character + corporation)
- Market history (local cache)

### Async Model
- Kotlin Coroutines + `async/await`
- Parallel requests for independent endpoints
- Flow for reactive UI updates

## Sensitive Data
- Client secret in config (not committed to public repos)
- Access/refresh tokens stored securely (SQLite encrypted if possible)
- `.gitignore` must exclude `*.db`, `*.env`, `tokens.*`

## Modules (Planned)
- `app` — main desktop entry point
- `core:esi` — ESI API client
- `core:cache` — caching layer
- `core:database` — SQLite + DAOs
- `core:http` — HTTP client (OkHttp)
- `core:auth` — OAuth2 / SSO flow
- `features:market` — market analysis & browser
- `features:assets` — asset viewer
- `features:wallet` — transactions, journal, P&L
- `features:orders` — order tracking & margins
- `ui:common` — shared Compose components
- `ui:theme` — EVE Online themed design system
