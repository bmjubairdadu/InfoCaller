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

- **Package**: `com.infocaller.app`
- **Architecture**: MVVM with Jetpack Compose.
- **Database**: Room for local persistence and caching.
- **Providers**: Apify (WhatsApp/Telegram), Truecaller V2, Google Search, and Phone Metadata.
- **Build System**: Gradle with Kotlin DSL.

## Build Instructions

1. Clone the repository:
   ```bash
   git clone https://github.com/bmjubairdadu/InfoCaller.git
   ```
2. Open in Android Studio.
3. (Optional) Provide API tokens in `local.properties` (e.g., `apify.token`).
4. Build and run:
   ```bash
   ./gradlew assembleDebug
   ```

## Download

The latest **Debug APK** is available in the [GitHub Releases](https://github.com/bmjubairdadu/InfoCaller/releases) section.

---
*This is the official source repository for the InfoCaller application.*
