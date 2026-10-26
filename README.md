# Snapper Android

[![Android](https://github.com/mateusz-klatt/snapper-android/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/mateusz-klatt/snapper-android/actions/workflows/ci.yml)

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=mateusz-klatt_snapper-android&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=mateusz-klatt_snapper-android)

Native Android client for the [Snapper](https://github.com/mateusz-klatt/snapper) trading platform, built with Kotlin, Jetpack Compose and Material 3. It connects directly to the same self-hosted backend as the [SwiftUI iOS client](https://github.com/mateusz-klatt/snapper-ios).

## Current application

- Sign in, restore a remembered session, refresh expired cookies and sign out.
- Configure a backend origin before login; switching backend removes the previous session and displayed account data.
- Select a wallet through the backend session contract and request wallet-scoped positions and orders.
- View current positions, open/recent orders, fills with fees, expandable alert details and historical candle charts.
- Search the backend market catalog without trading permissions or a selected wallet; choose 1m, 5m, 15m, 1h, 4h or 1d candles.
- Receive authenticated foreground market updates and refresh authorized record feeds after backend events.
- Inspect related instruments and underlying descriptions; navigate through a freshly validated exchange/instrument catalog. View cached hourly price metrics with their observation time; 24-hour metrics require a fresh, complete hourly window.
- Inspect accessible desks and, with explicit membership permissions, attach an existing viewer.
- Read permission-gated health/status, strategy, backtest, user and AI-delegate inventories. Backtest rows translate status labels and format UTC calendar periods using the selected country, and display initial cash in USD with two decimal places; the underlying decimal value remains unchanged.
- Edit account-level notification defaults independently of Android push registration.
- Inspect profile email/role and app version; confirm sign-out or backend changes before clearing the session.
- Filter trading signals by strategy and export the selected rows as CSV to an Android document destination.
- Inspect venue balances, positions and reconciliation with explicit observation/freshness status.
- Request a wallet-scoped P&L timeline with window, granularity and valuation-currency controls, withholding reasons and valuation provenance. Net, realized and unrealized series share one chart, value scale, time axis and legend; missing values remain gaps.
- Review and submit manual orders for a selected wallet using execution-capable instruments.
- Confirm cancellation of eligible open orders.
- Review close/reduce orders and attach bracket or percentage trailing protection to a position.
- Inspect process summaries and confirm authorized local or remote process controls.
- Inspect AI-review audit/pending feeds and confirm delegated approve/reject decisions with a rationale.
- Show only the screens allowed by the session's effective permissions.
- Support the `auditor` role through effective permissions: the user directory
  accepts `read:users`, while account-language synchronization, push registration
  and notification-default changes require `manage:self_account`. Read-only
  accounts can change the local interface language and inspect preferences.
- Select from 45 language catalogs, including Arabic, Hebrew and Persian RTL layouts, with light and dark themes. Additional catalogs reuse reviewed iOS translations; missing reviewed text remains English.
- Choose a country/region independently of the interface language for numeric and record-timestamp formatting, and select automatic or explicit financial-direction colors. Automatic colors use red for rising values in China, Hong Kong, Japan and South Korea; other supported countries use green. Error and health-status colors retain their severity meaning.
- Exact decimal quantities, prices and P&L; chart drawing converts coordinates separately.

The app combines REST snapshots with authenticated WebSocket updates while in the foreground. Market frames update the selected chart; authorized order, signal, AI-review, process, strategy and system events trigger fresh REST reads. Live connections stop in the background and reconnect on foreground entry. Refresh remains available. Orders, fills and alerts fetch up to 100 recent records; the Recent orders segment displays the newest 50. Positions are the current backend snapshot. Market charts request 100 candles and display price/time axes and a grid. System feeds, fills, signals and P&L refresh independently, with their own errors. Venue accounts poll every five seconds only while their screen is active and the app is in the foreground; stale observation or polling evidence cannot be labelled authoritative.

Configured-pair statistics show correlation, cointegration and cold/warm status; selecting a peer revalidates the market catalog. Selected-chart cache diagnostics report available versus expected candles independently of hourly metrics, with separate retry controls. Warmth reports the requested row count, not data freshness. Cache probes accompany chart snapshots and explicit retries, rather than each live frame.

Optional Android push registration is available when the configured backend advertises a supported Firebase project. After explicit consent and notification permission, the app registers with Firebase Messaging in installation-ID mode and binds that registration to the authenticated backend account. Background notices contain only “Open Snapper to view updates”; opening one requires a fresh authorized alert read before any details appear. Uncertain registration is reconciled by an explicit read, never an automatic repeated registration. Switching Firebase project options requires an app restart. Live delivery requires an operator-configured project and has not been validated by the offline tests.

The interface language also synchronizes to the configured backend account preference so backend descriptions can use the same language. System language follows Android's per-app language override when configured, otherwise resolving the first supported device language, with English as the fallback; country/region formatting remains independent. Initial authenticated navigation waits for the language attempt to settle. Settings shows synchronization, confirmation or an uncertain result and offers an explicit retry. A failed attempt preserves the local interface choice and never retries automatically.

Builds package every supported translation catalog, with English as the default. Native CI and release builds run `bash scripts/check-packaged-locales.sh` against the built APK to detect missing catalogs; device tests also compare visible labels with independent reviewed translations.

Record timestamps use country formatting and the current device time zone with an explicit UTC offset. Presentation formatting leaves domain timestamps and API queries unchanged; CSV retains its canonical UTC millisecond format. Candle axis endpoints use the same country and device-time-zone formatter with explicit UTC offsets, distinguishing repeated times during daylight-saving transitions. P&L axis labels include dates or offsets when needed to distinguish midnight and daylight-saving transitions. Dense P&L series preserve bucket extrema, endpoints and missing-value gaps when reduced for drawing. Series colors and line patterns identify net, realized and unrealized values independently of the financial-direction preference.

Existing APNs registration and delivery remain unchanged. Android uses the separate `/api/devices/android` FID contract and runtime configuration supplied by the backend; absent or unavailable configuration leaves push disabled. The app never submits a Firebase installation ID to the legacy iOS registration endpoint. Active Google Play internal-testing build 1006 contains no Firebase SDK; publication of the optional push feature still requires real project and device-lifecycle validation.

## Manual orders

Order entry requires a selected wallet and `create:orders` plus `read:market_data`. The wallet determines paper or live mode. Review buy/sell market, limit, stop or stop-limit orders before confirming; leverage and reduce-only are optional. The exchange and instrument lists come from the backend, and observation-only instruments cannot be selected for trading.

Cancellation requires `cancel:orders`, an eligible open order in the selected wallet and its execution plan. Acceptance means cancellation was requested, not that the venue has cancelled the order. After an uncertain submission, check status; an explicit creation retry retains the same reviewed payload and idempotency key. Dismissing the dialog does not discard that uncertain intent, and a later rejected retry does not resolve it. Accepted commands remain accepted if the subsequent snapshot refresh fails.

## Position and administrative actions

Position actions require a selected wallet, `read:positions`, `read:market_data` and `create:orders`. Close/reduce submits an opposite-side reduce-only market order with an exact quantity. Protection additionally requires the current position cycle and entry price. Bracket protection accepts stop loss, take profit or both; trailing protection uses percentage distance and an optional minimum locked profit. Every action is reviewed and revalidates the current position, wallet mode and execution capability before dispatch. An uncertain request retains its original payload and operation key across dismissal and explicit retry.

Process summaries require `read:system_status`; configuration requires `read:processes`. Controls additionally require `manage:processes` and a fresh matching configuration. Strategy/backtest processes and templates are excluded. Local restart may stop successfully and fail before starting; remote controls save desired state for the owning coordinator. A saved intent is distinct from runtime convergence. A lost command acknowledgement remains unknown; refreshing status cannot prove that the command was applied.

AI audits require `read:ai_reviews`. Pending reviews require a delegate identity and `read:signals`; decisions additionally require `submit:ai_review_decision`. Approve/reject requires explicit confirmation. After an uncertain submission, a fresh status read permits an explicit retry of the same decision and rationale, including when the review has left the pending list. Only the backend's matching receipt or terminal refusal resolves that uncertainty; there is no automatic decision replay.

## Open in Android Studio

Open this directory as a project in a current stable Android Studio. Install Android SDK Platform 37.0 and Build Tools 36.0.0 from SDK Manager, then let Gradle sync. Android 8.0 (API 26) or newer is required to run the app.

The project pins AGP 9.4.1, Gradle 9.8.2 and Kotlin 2.4.21. Gradle runs on JDK 17 or a compatible newer JDK; Android Studio can supply the JDK. `local.properties` and signing credentials are not committed.

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
- Session rotations and wallet operations are serialized. Authenticated reads can refresh expired credentials and retry once; mutations are never replayed automatically. An uncertain desk attachment remains blocked until the account scope changes; notification defaults can be reconciled through an explicit read before another write. Backend, session, wallet, primary-operator, delegate and permission changes discard stale account data and order drafts. Confirmed commands retain their original actor scope, including while queued behind another session operation.
- Persistent cookies use AES-GCM with a device-authenticated, origin-specific Android Keystore key and private backup-excluded storage. DataStore contains the backend origin, interface language, country/region and financial-color preferences. Country and color changes do not change the backend session or translation language.
- Backend and network failures are visible; an unavailable resource is not silently shown as an empty successful result.
- Firebase Messaging 26.0.1, Firebase Common 23.0.0 and Firebase Installations 20.0.0 support optional push delivery. Firebase initializes for an explicitly enabled registration, restoration of an accepted binding, or pending cleanup of a previously enabled registration; analytics, automatic messaging initialization, notification delegation and delivery-metric export are disabled. Background delivery does not access session cookies. Logout blocks local delivery before best-effort server and SDK cleanup; failed cleanup remains pending. The service-component block protects cold restarts when binding storage cannot be cleared; simultaneous operating-system component-state and storage failure cannot provide that persistence guarantee.

Brand assets and existing translations are reused from the Apache-2.0-licensed
iOS project. This repository is licensed under the
[Apache License, Version 2.0](LICENSE), copyright 2026 Mateusz Klatt. See
[NOTICE](NOTICE) for attribution. Third-party components retain their own licenses.

Revisions through the `last-mit` tag were released under MIT. Subsequent original
work is licensed under Apache-2.0; earlier MIT grants remain valid.
