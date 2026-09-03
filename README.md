# InfoCaller

**InfoCaller** — Caller identification for Android. When someone calls, see their name, photo, location and carrier using on-device intelligence and optional cloud lookup.

## Features

- **Caller ID on incoming call** — Full-screen incoming call screen with name, photo, location and block option.
- **Smart Dialer** — T9 search and dialpad.
- **Contacts & Recents** — Unified list with brand SIM logos (Brandfetch).
- **Privacy first** — Local Room database caching, no contact upload.

## Build

```bash
git clone https://github.com/bmjubairdadu/InfoCaller.git
cd InfoCaller
# put keys in local.properties (see below)
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Configuration

All keys go in `local.properties` (never committed):

```
apify.token.1=apify_api_xxx
apify.token.2=apify_api_xxx
brandfetch.client.id=xxx
truecaller.client.secret=lvc22mp3l1sfv6ujg83rd17btt
sdk.dir=/path/to/Android/Sdk
```

Keys are optional; free providers work without them.

## Download

Releases → https://github.com/bmjubairdadu/InfoCaller/releases — download `app-debug.apk`.

## License

Private source — all rights reserved.
