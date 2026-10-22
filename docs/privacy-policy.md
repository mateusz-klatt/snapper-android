# Snapper Android — Privacy Policy

**Effective date:** 2026-10-16  
**Last updated:** 2026-10-16  
**App:** Snapper for Android (`ch.snapper.android`)  
**Publisher:** Mateusz Klatt  
**Contact:** [mateusz@klatt.ie](mailto:mateusz@klatt.ie)

Canonical policy URL: [snapper.ch/android-privacy-policy.html](https://snapper.ch/android-privacy-policy.html).

## Software and backend responsibilities

Snapper is open-source client software for a backend that you or your operator control. You configure the backend address and use an existing account on that backend. The Android app does not create accounts. The publisher distributes the client software; the app does not itself operate an exchange, brokerage, custody service or investment advisory service.

The backend operator controls the accounts, trading integrations, stored records, access permissions and retention of backend data. This policy describes the Android client's behavior. Your backend operator's policies also apply to data sent to that backend. Configuring a backend does not mean that its data is stored only on your phone.

As of 2026-10-16, the active Google Play internal-testing build is version code 1006 and contains no Firebase SDK. The optional Firebase push behavior described below applies to subsequent builds that include that feature. Build 1006 does not perform that Firebase collection or registration.

## Authentication and account information

The app sends your username and password to your configured backend to sign in. It receives session cookies and account information, including your username, user identifier, role, permissions and an email address when the backend supplies one. Session cookies authenticate later requests, including session refresh and logout.

The app holds the password in memory while the login form is present. It does not save the password to preferences, session files or logs. The username may be retained in Android's saved UI state when the login screen is recreated.

Remembered session cookies are encrypted with AES-GCM using an origin-specific Android Keystore key and stored in private, backup-excluded app storage. Persistent sessions require Android 11 or newer, a secure screen lock and recent device authentication. If these requirements are unavailable, the session remains in memory and a later app launch requires sign-in again. The app does not request device authentication automatically.

## Financial data and commands

The app retrieves wallet identifiers and labels, positions, quantities, prices, profit and loss, order history, alert history, exchange and instrument metadata, historical candles, related instruments and underlying descriptions from your backend. These records are displayed in memory during the app session.

While the app is in the foreground, it can obtain short-lived authentication tickets from your backend and establish an authenticated WebSocket connection to that backend. It sends subscription topics for permitted feeds and the selected market, receives live market updates and uses relevant events to refresh account and operational records. The connection stops when the app goes into the background.

When you have the required permissions, the app sends order submissions and cancellation requests to your configured backend. Requests include the selected wallet, instrument, order details and identifiers used to track or deduplicate the operation. The backend is responsible for processing these commands and any exchange integration. The Android app does not hold exchange API credentials or connect directly to an exchange.

With the corresponding permissions, you can also request position reductions and protective bracket or trailing plans, manage backend processes, and approve or reject AI reviews. The app sends the selected target, command details, relevant operation identifiers and any review rationale you enter to your backend. A process command may change a remote coordinator's desired state rather than immediately change the running process.

With the required permissions, the app also retrieves execution records and fees, venue balances and reconciliation status, trading signals, historical P&L and valuation metadata, accessible desk information, backend health and process status, strategy and backtest records, user-directory information and AI-delegate metadata.

When authorized to manage desk memberships, you can submit an existing viewer's username and the selected desk identifier to your backend.

The app retrieves and updates account-level notification defaults, including alert type, enabled status and minimum priority. These settings are stored by your backend; changing them does not register this Android device for push notifications.

If you export trading signals as CSV, you choose the document destination through Android. The app writes the selected records to that destination. Access and retention then depend on the destination provider and your choices.

## Preferences and local storage

Android DataStore stores the configured backend origin, interface language, selected country/region and financial-color preference. Country/region controls display formatting independently of the interface language; the financial-color preference controls how rising and falling values are displayed. The selected wallet is also communicated through the backend session contract. Account and financial screens are cleared when you sign out or switch backend.

While signed in, the selected interface language is also sent to your configured backend and stored as an account language preference. When you use the system language setting, the app resolves a supported language from your device language preferences, including any Android per-app language override, and sends that resolved language. Your backend may use this preference for descriptions, alerts and notifications. Country/region and financial-color preferences remain independent and are not sent as part of language synchronization. This feature adds no advertising, analytics or other third-party SDK.

The app disables Android cloud backup and device-transfer backup for its data. Signing out removes local session credentials. Clearing the app's storage or uninstalling removes its local preferences and session files; it does not delete your account or records on the backend.

## Connections, permissions and third-party services

Release builds require HTTPS for the configured backend and use Android's certificate trust checks. Debug builds additionally allow selected local development HTTP addresses. The backend receives requests and normal connection metadata, such as the network address from which you connect.

The app requests internet access. On supported Android versions it offers an optional local-network permission for connecting to a backend on your local network. This permission is unnecessary for a public internet backend.

If you explicitly enable push notifications and grant notification permission, the app uses Firebase Cloud Messaging and Firebase Installations with the public project configuration provided by your backend. Firebase receives the installation registration, normal connection metadata and technical app, device, operating-system and SDK metadata used to operate the service; see [Firebase’s data-disclosure documentation](https://firebase.google.com/docs/android/play-data-disclosure). Your backend receives an installation identifier, Firebase installation ID, app version and opaque registration identifiers linked to your account. These identifiers and public configuration are stored locally outside Android backups. Automatic messaging initialization, analytics, notification delegation and delivery-metric export are disabled. Background notices contain only “Open Snapper to view updates”, without account or financial details. Opening a notice requires the existing authenticated session and a fresh permission-checked backend request before showing alert details. Background delivery never opens the authenticated cookie store. Disabling push notifications in the app or signing out blocks local delivery before attempting backend and Firebase cleanup; unavailable cleanup may remain pending.

A previously enabled registration may initialize Firebase again to restore an accepted binding or complete pending cleanup. Revoking Android notification permission stops notification display but does not withdraw the earlier in-app consent or itself request Firebase cleanup. Use Disable in the app or sign out to block local delivery and request cleanup. These requests can remain pending while the backend or Firebase is unavailable.

The app includes no advertising, analytics, crash-reporting or third-party tracking SDK. It does not request access to location, contacts, microphone, camera, photos or health information. It does not contain advertising or in-app purchases.

Opening the privacy-policy link launches your browser. That browser contacts the policy website under its normal settings; this does not send the app's backend credentials to the website. Email and source-code links likewise open the relevant external application or website.

## Retention, deletion and requests

Local preferences remain until changed or removed through Android app-storage controls or uninstall. Session persistence is subject to the security conditions described above; signing out removes the local session.

The backend operator determines how long account information, financial records, commands and server logs are retained. Contact the operator of your configured backend for access, correction, deletion or export requests. The Android client has no self-service account-deletion workflow, and logging out does not delete server data. The publisher cannot delete records on a backend controlled by someone else.

For questions about the Android client or this policy, contact [mateusz@klatt.ie](mailto:mateusz@klatt.ie). Do not include passwords, session tokens or exchange credentials in email or public issues.

## Changes and source code

Material policy changes will be reflected in the last-updated date and published at the canonical policy URL. The Android source and policy history are available in the [Apache-2.0-licensed Snapper Android repository](https://github.com/mateusz-klatt/snapper-android).

The separate [Snapper iOS policy](https://snapper.ch/privacy-policy.html) describes the iOS app.
