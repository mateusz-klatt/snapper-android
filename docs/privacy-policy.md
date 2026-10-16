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

## Authentication and account information

The app sends your username and password to your configured backend to sign in. It receives session cookies and account information, including your username, user identifier, role, permissions and an email address when the backend supplies one. Session cookies authenticate later requests, including session refresh and logout.

The app holds the password in memory while the login form is present. It does not save the password to preferences, session files or logs. The username may be retained in Android's saved UI state when the login screen is recreated.

Remembered session cookies are encrypted with AES-GCM using an origin-specific Android Keystore key and stored in private, backup-excluded app storage. Persistent sessions require Android 11 or newer, a secure screen lock and recent device authentication. If these requirements are unavailable, the session remains in memory and a later app launch requires sign-in again. The app does not request device authentication automatically.

## Financial data and commands

The app retrieves wallet identifiers and labels, positions, quantities, prices, profit and loss, order history, alert history, exchange and instrument metadata, and historical candles from your backend. These records are displayed in memory during the app session.

When you have the required permissions, the app sends order submissions and cancellation requests to your configured backend. Requests include the selected wallet, instrument, order details and identifiers used to track or deduplicate the operation. The backend is responsible for processing these commands and any exchange integration. The Android app does not hold exchange API credentials or connect directly to an exchange.

With the required permissions, the app also retrieves execution records and fees, venue balances and reconciliation status, trading signals, historical P&L and valuation metadata, accessible desk information, backend health and process status, strategy and backtest records, user-directory information and AI-delegate metadata.

When authorized to manage desk memberships, you can submit an existing viewer's username and the selected desk identifier to your backend.

The app retrieves and updates account-level notification defaults, including alert type, enabled status and minimum priority. These settings are stored by your backend; changing them does not register this Android device for push notifications.

If you export trading signals as CSV, you choose the document destination through Android. The app writes the selected records to that destination. Access and retention then depend on the destination provider and your choices.

## Preferences and local storage

Android DataStore stores the configured backend origin and interface language. The selected wallet is also communicated through the backend session contract. Account and financial screens are cleared when you sign out or switch backend.

The app disables Android cloud backup and device-transfer backup for its data. Signing out removes local session credentials. Clearing the app's storage or uninstalling removes its local preferences and session files; it does not delete your account or records on the backend.

## Connections, permissions and third-party services

Release builds require HTTPS for the configured backend and use Android's certificate trust checks. Debug builds additionally allow selected local development HTTP addresses. The backend receives requests and normal connection metadata, such as the network address from which you connect.

The app requests internet access. On supported Android versions it offers an optional local-network permission for connecting to a backend on your local network. This permission is unnecessary for a public internet backend.

The current Android app includes no advertising, analytics, crash-reporting or third-party tracking SDK and sends no Firebase Cloud Messaging device token. It does not request access to location, contacts, microphone, camera, photos or health information. It does not contain advertising or in-app purchases.

Opening the privacy-policy link launches your browser. That browser contacts the policy website under its normal settings; this does not send the app's backend credentials to the website. Email and source-code links likewise open the relevant external application or website.

## Retention, deletion and requests

Local preferences remain until changed or removed through Android app-storage controls or uninstall. Session persistence is subject to the security conditions described above; signing out removes the local session.

The backend operator determines how long account information, financial records, commands and server logs are retained. Contact the operator of your configured backend for access, correction, deletion or export requests. The Android client has no self-service account-deletion workflow, and logging out does not delete server data. The publisher cannot delete records on a backend controlled by someone else.

For questions about the Android client or this policy, contact [mateusz@klatt.ie](mailto:mateusz@klatt.ie). Do not include passwords, session tokens or exchange credentials in email or public issues.

## Changes and source code

Material policy changes will be reflected in the last-updated date and published at the canonical policy URL. The Android source and policy history are available in the [MIT-licensed Snapper Android repository](https://github.com/mateusz-klatt/snapper-android).

The separate [Snapper iOS policy](https://snapper.ch/privacy-policy.html) describes the iOS app.
