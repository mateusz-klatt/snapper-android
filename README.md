# Snapper Android

[![Android](https://github.com/mateusz-klatt/snapper-android/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/mateusz-klatt/snapper-android/actions/workflows/ci.yml)

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=mateusz-klatt_snapper-android&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=mateusz-klatt_snapper-android)

Native Android client for the [Snapper](https://github.com/mateusz-klatt/snapper) trading platform, built with Kotlin, Jetpack Compose and Material 3. It connects directly to the same self-hosted backend as the [SwiftUI iOS client](https://github.com/mateusz-klatt/snapper-ios).

## Current application

- Sign in, restore a remembered session, refresh expired cookies and sign out.
- Configure a backend origin before login; switching backend removes the previous session and displayed account data.
- Select a wallet through the backend session contract and request wallet-scoped positions and orders.
- View current positions, recent orders, alert history and historical candle charts.
- Review and submit manual orders for a selected wallet using execution-capable instruments.
- Confirm cancellation of eligible open orders.
- Show only the screens allowed by the session's effective permissions.
- English and Polish interfaces, with light and dark themes.
- Exact decimal quantities, prices and P&L; chart drawing converts coordinates separately.

The app displays REST snapshots, refreshed on foreground entry or with Refresh. Orders and alerts show up to 100 recent records; positions are the current backend snapshot. Market charts request 100 candles. Other iOS management screens, WebSocket streaming and Android push notifications remain unavailable.

The existing backend's device registration and delivery are APNs-only. FCM requires an Android provider contract and delivery integration before an Android device token can be registered; this app does not submit FCM tokens to the iOS endpoint.

## Manual orders

Order entry requires a selected wallet and `create:orders` plus `read:market_data`. The wallet determines paper or live mode. Review buy/sell market, limit, stop or stop-limit orders before confirming; leverage and reduce-only are optional. The exchange and instrument lists come from the backend, and observation-only instruments cannot be selected for trading.

Cancellation requires `cancel:orders`, an eligible open order in the selected wallet and its execution plan. Acceptance means cancellation was requested, not that the venue has cancelled the order. After an uncertain submission, check status; an explicit creation retry retains the same reviewed payload and idempotency key. Dismissing the dialog does not discard that uncertain intent, and a later rejected retry does not resolve it. Accepted commands remain accepted if the subsequent snapshot refresh fails.

## Open in Android Studio

Open this directory as a project in a current stable Android Studio. Install Android SDK Platform 37.0 and Build Tools 36.0.0 from SDK Manager, then let Gradle sync. Android 8.0 (API 26) or newer is required to run the app.

The project pins AGP 9.4.1, Gradle 9.8.1 and Kotlin 2.4.21. Gradle runs on JDK 17 or a compatible newer JDK; Android Studio can supply the JDK. `local.properties` and signing credentials are not committed.

Library and plugin versions are declared in `gradle/libs.versions.toml`; the early Kotlin buildscript bootstrap in `build.gradle.kts` must retain the same Kotlin pin. Gradle verifies dependency artifacts and metadata against the committed SHA-256 checksums in `gradle/verification-metadata.xml`.

## Build and validate

With a JDK and Android SDK available:

```bash
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. The Android workflow also publishes a `snapper-debug` artifact. Debug builds use application ID `ch.snapper.android.debug`; release builds use `ch.snapper.android` and require your own signing configuration for distribution.

See [Android releases](RELEASING.md) for the manual release workflow, unsigned bundle builds, signed bundles verified against an upload-certificate pin, and separately configured Google Play publication.

Run the offline Compose interaction tests on a connected emulator or device:

```bash
./gradlew :app:connectedDebugAndroidTest
```

CI runs these device tests on an Android 17 emulator with hardware acceleration, including local network permission denial, grant and activity recreation after a language change. A fresh temporary screen-lock credential on the disposable emulator enables authenticated Keystore tests. CI combines JVM and device execution data into one JaCoCo report using all application classes, without coverage exclusions. The analysis receives the actual application and test compile classpaths, including Android SDK classes. After the tests, it analyzes all owned source files with SonarQube Cloud and requires the project quality gate to pass. The analysis uses the repository’s `SONAR_TOKEN` secret; automatic analysis must stay disabled so CI supplies the analysis.

To generate the same combined coverage report locally with a connected device:

```bash
./gradlew :app:combinedDebugCoverageReport
```

This runs both test suites and writes `app/build/reports/jacoco/combinedDebugCoverageReport/combinedDebugCoverageReport.xml`. The report task requires execution data from both suites.

## Connect to a backend

Set the server origin in the login screen, for example `https://snapper.example`. Enter an origin only: no `/api` suffix, credentials, query or fragment. Release builds accept HTTPS only and use the platform certificate trust configuration.

On Android 17 (API 37) or newer, a backend on your local network requires Android’s local network permission. Tap **Allow local network** beside the backend settings before signing in. This action is optional for public internet backends and never prompts automatically. If Android stops showing the permission prompt after denial, **Open app settings** lets you grant access; returning to the app refreshes the permission state. See [Android’s local network permission guidance](https://developer.android.com/privacy-and-security/local-network-permission). HTTPS and certificate requirements still apply.

Debug builds additionally allow HTTP for `localhost`, `127.0.0.1`, `::1` and the Android emulator host bridge `10.0.2.2`. The debug default is `http://10.0.2.2:8000`; release builds require the user to configure an HTTPS origin.

For an Android emulator, start a development backend on your computer at port 8000 and use the debug default. For a Samsung or another physical phone, enable USB debugging, connect it and authorize the computer, then use ADB reverse:

```bash
adb devices
adb reverse tcp:8000 tcp:8000
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Select `http://127.0.0.1:8000` in the debug app. If multiple devices are connected, add `-s DEVICE_SERIAL` to each ADB command. No production credentials or sample live accounts are included.

### Remembered sessions

Remembered sessions require Android 11 or newer, a secure screen lock, and device authentication with a PIN, pattern, password or strong biometric within the last five minutes. The app does not open an authentication prompt automatically. When these conditions are unavailable, the current session stays in memory and restarting the app requires signing in again.

Session encryption keys are specific to the backend origin and require device authentication. Signing out removes the key before deleting its encrypted file. The earlier shared key and stored sessions are retired on upgrade, requiring one new sign-in.

## Design

### Privacy and publication

Snapper distributes open-source client software for a backend controlled by you or your
operator. It does not itself operate an exchange, brokerage, custody or investment
advisory service. An existing backend account is required.

The [Android privacy policy](docs/privacy-policy.md) describes authentication, local
storage, financial requests and backend-operator retention/deletion responsibilities.
The in-app link opens `https://snapper.ch/android-privacy-policy.html`; the separate iOS
policy remains at its existing URL. Before store submission, verify that the Android URL
serves this policy publicly rather than a login page, SPA fallback or the iOS policy.

Reviewed listing assets and their synthetic-data provenance are indexed in
[store/visuals/README.md](store/visuals/README.md). See [RELEASING.md](RELEASING.md) for
signing and Play submission requirements.

### Implementation

- A single application module with Compose screens, a ViewModel/StateFlow UI state, and injected session and settings dependencies.
- Retrofit, OkHttp and kotlinx.serialization use the backend's typed `payload` envelopes, cookie authentication and URL-scoped CSRF headers.
- Session rotations and wallet operations are serialized. Authenticated reads can refresh expired credentials and retry once; order mutations are never replayed automatically. Backend, session, wallet and permission changes discard stale account data and order drafts.
- Persistent cookies use AES-GCM with a device-authenticated, origin-specific Android Keystore key and private backup-excluded storage. DataStore contains only the backend and language preferences.
- Backend and network failures are visible; an unavailable resource is not silently shown as an empty successful result.
- Room, WorkManager, Hilt and Firebase are added only when an implemented feature needs them.

Brand assets and existing translations are reused from the MIT-licensed iOS project. This repository is also licensed under [MIT](LICENSE), copyright 2026 Mateusz Klatt.
