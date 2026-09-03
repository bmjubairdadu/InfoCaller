# InfoCaller

**InfoCaller** is a comprehensive Android application for caller identification and contact intelligence. It integrates multiple lookup providers to provide enriched information about callers, including names, photos, social profiles, and reputation metrics.

## Features

- **Real-time Caller ID**: Identify unknown callers as they ring.
- **Enriched Contacts**: Automatically sync and update contact information with data from WhatsApp, Truecaller, and public sources.
- **Parallel Lookup Engine**: Executes multiple intelligence providers concurrently for fast results.
- **SIM Management**: Branded SIM picker and operator logo automation via Brandfetch.
- **Smart Dialer**: Fully interactive dialer with T9 search and clipboard integration.
- **Privacy-First**: Offline-first architecture with a local Room database and privacy-safe shared intelligence registry.
- **Theme Support**: Adaptive Light/Dark mode with InfoCaller brand identity.

## Technical Details

- **Package**: `com.infocaller.app` · `compileSdk/targetSdk 34` · `minSdk 26`
- **Architecture**: MVVM, Jetpack Compose, Coroutines, Room
- **OSINT**: Truecaller (truecallerjs `search5-noneu` + `bulk`), Eyecon, WhatsApp Apify (educational), DuckDuckGo/Bing free dorks, GitHub, Sherlock 40 + WhatsMyName, Holehe email, PhoneInfoga, NID DB 115k indexed, Brandfetch SIM logos
- **Build**: Gradle 9 + Kotlin DSL · R8 minify (`proguard-rules.pro`)

## One-by-One Enrichment (Anti-Block)

All contacts are processed **one by one** with throttling to avoid API blocks:
- `EnrichmentWorker` enqueues all unknown numbers, then dequeues **LIMIT 1** every cycle.
- `ContinuousEnrichmentEngine.processNextOneByOne()` enforces **MIN_INTERVAL 3.5s (≈17/min)** + **daily soft cap 800** (prefs `enrichment_limits`).
- `ScanningService` polls with backoff; priority scans (user search) skip the queue immediately.
- Per-provider exponential backoff capped at 24h + jitter if a provider rate-limits.

## API Keys — What You Must Provide (professional setup)

All keys go in `local.properties` (never commit). App also works without them via free fallbacks.

| Key | Required? | Where to get | What it unlocks |
|---|---|---|---|
| `truecaller.client.secret` | **Recommended** (login still works without) | Truecaller account (Onboarding OTP flow uses `lvc22mp3l1sfv6ujg83rd17btt` fallback) | Truecaller search (primary caller ID) — users OTP-verify in app and cloud secret auto-creates as `truecaller_token` |
| `apify.token.1` / `apify.token.2` | Optional | apify.com | WhatsApp/Telegram profile via Apify actor (backend `POST /api/v1/lookup/phone`) |
| `backend.api.key` | Optional (if you run your backend) | You generate | Secures your Node relay (`backend/.env INFOCALLER_API_KEY`) |
| `numlookup.api.key` | Optional | numlookupapi.com | Carrier/line-type enrichment |
| `coreclaw.api.key` | Optional | coreclaw.com | Business OSINT |
| `brandfetch.client.id` | Optional (has fallback) | brandfetch.io | Operator logos |

Free providers (no key needed): DuckDuckGo/Bing/Google dorks, GitHub, Sherlock/WhatsMyName OSINT, Disposable check, IP geocoding, local NID DB (115k), Dark Web surface.

## Build Instructions

1. Clone:
   ```bash
   git clone https://github.com/bmjubairdadu/InfoCaller.git
   ```
2. Open in Android Studio.
3. Copy `local.properties.example` → `local.properties` and fill only what you need (see table above).
4. Build:
   ```bash
   ./gradlew assembleDebug
   ```

## Download

The latest **Debug APK** is available in the [GitHub Releases](https://github.com/bmjubairdadu/InfoCaller/releases) section.

---
*This is the official source repository for the InfoCaller application.*
