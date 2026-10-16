# Android releases

The manually dispatched **Android release** workflow prepares Android App Bundles for
`ch.snapper.android`. Its default `build` mode produces an unsigned bundle without signing
credentials, or a signed bundle when upload signing and its certificate pin are configured.
Google Play publication requires account setup and credentials;
adding this workflow does not publish the app.

The workflow only runs from `master`. It never publishes on a push or tag. Runs are
serialized without cancelling an earlier release. Select the mode explicitly:

| Mode | Result |
| --- | --- |
| `build` | Release artifact only, unsigned without signing secrets or signed when all four are configured. |
| `play-draft` | Signed bundle uploaded to the selected Play track with status `draft`. |
| `play-release` | Signed bundle uploaded with status `completed`; Play review and account requirements still apply. |

The default track is `internal`; `beta` and `production` require explicit selection.
Before either Play mode uploads, the workflow requires successful `ci.yml` and
`gitleaks.yml` push runs on `master` for the exact dispatched commit. A missing, running
or failed latest run blocks upload. Wait for those workflows to finish before dispatching.

## Configure signing and Play access

Publication assets are indexed in [store/visuals/README.md](store/visuals/README.md).
Use the Android-specific [privacy policy](docs/privacy-policy.md), whose canonical URL
is `https://snapper.ch/android-privacy-policy.html`. Deployment of the matching
`android-privacy-policy.html` web page is a separate prerequisite: confirm that an
unauthenticated request returns the Android policy content, not an SPA fallback, login
screen or the iOS policy. Adding the source file does not establish public availability.

Keep listing and declarations consistent with the app: open-source client software for
a user-controlled backend and an existing backend account, with permission-gated financial
commands. The publisher does not operate an exchange, brokerage, custody or investment
advisory service through this client. Backend operators control account data, retention
and deletion; do not describe logout as account deletion or claim no data leaves the phone.

For a personal account subject to Google's
[new-account testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465),
internal testing does not replace the required closed test: at least 12 testers must
remain opted in continuously for 14 days before applying for production access.

1. Create the application in Google Play Console using package `ch.snapper.android`.
   Complete the required store listing, privacy policy, data safety, content declarations,
   developer verification and testing requirements for the account.
2. Configure [Play App Signing](https://developer.android.com/studio/publish/app-signing).
   Keep the app signing key managed by Play and protect the separate upload key. The
   workflow never creates production keys. Keep the upload keystore and passwords in a
   secure backup controlled by the publisher.
3. Add these repository Actions secrets:

    | Secret | Value |
    | --- | --- |
    | `ANDROID_UPLOAD_KEYSTORE_BASE64` | Base64 encoding of the upload keystore bytes. |
    | `ANDROID_UPLOAD_STORE_PASSWORD` | Keystore password. |
    | `ANDROID_UPLOAD_KEY_ALIAS` | Upload key alias. |
    | `ANDROID_UPLOAD_KEY_PASSWORD` | Upload key password. |
    | `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | Full service account JSON credentials for the Android Publisher API. |

    Signing is all-or-none: a partially configured signing set fails even in `build` mode.
    Both Play modes require all five secrets and report missing names only. With no signing
    secrets, `build` mode produces an unsigned bundle that cannot be uploaded to Play.
    Also configure repository Actions variable `ANDROID_UPLOAD_CERTIFICATE_SHA256` with
    the expected upload certificate's SHA-256 fingerprint: exactly 64 hexadecimal
    characters, without colons. This public pin is required for every signed build.
    Obtain it from the trusted upload certificate and compare it with the upload
    certificate registered in Play Console. For an exported PEM certificate:

    ```bash
    openssl x509 -in upload-certificate.pem -outform DER | sha256sum
    ```

    The workflow verifies the bundle's cryptographic JAR signature with `jarsigner`,
    rejects unsigned entries, extracts its signing certificate with `keytool`, and compares
    its DER SHA-256 fingerprint against this pin. A missing, malformed or mismatched pin
    blocks a signed artifact and Play upload. Self-signed upload certificates are expected;
    verification uses the explicit fingerprint rather than a public certificate authority.
4. Enable the Google Play Android Developer API in the associated Google Cloud project.
   Create a dedicated service account, then invite its email in Play Console **Users and
   permissions**. Grant access to this application only, including app information access
   and permission to release to testing tracks. Production publication additionally needs
   permission to release to production. Avoid granting account administration or financial
   permissions. Follow the current [service account setup guide](https://developers.google.com/android-publisher/getting_started).
5. Build a signed bundle and make the first upload manually in Play Console. The
   [Edits API](https://developers.google.com/android-publisher/edits) cannot create the app
   or perform its initial publication setup. Resolve outstanding console requirements before
   using automated uploads.
6. Use `play-draft` on `internal` first, then explicitly release to `internal` and verify
   installation through Play testing. Select production only after the account and app meet
   Play requirements and testing is complete. See [release tracks](https://developers.google.com/android-publisher/tracks).

Credentials are decoded into a private directory under `RUNNER_TEMP` and removed by an
`always()` cleanup step. They are never copied into the workspace or release artifact.
Do not enable shell tracing, Gradle debug logs or workflow debug logging when using real
release credentials. The workflow pins its upload action to
[`r0adkll/upload-google-play` v1.1.5](https://github.com/r0adkll/upload-google-play/tree/e738b9dd8f2476ea806d921b64aacd24f34515a5).

## Dispatch a release

Run these commands from the Android repository, or add `--repo OWNER/REPOSITORY`:

```bash
gh workflow run release.yml --ref master -f mode=build

gh workflow run release.yml --ref master \
    -f mode=play-draft -f track=internal \
    -f version_code=2001 -f version_name=0.2.0 \
    -f release_notes='Native Android order entry and cancellation.'

gh workflow run release.yml --ref master \
    -f mode=play-release -f track=production \
    -f version_code=2002 -f version_name=0.2.1 \
    -f release_notes='Android maintenance update.'
```

Use an unused version code strictly greater than every code already uploaded to Play.
The accepted range is `1..2100000000`. A blank workflow input uses `1000 + github.run_number`.
This default does not inspect Play: explicitly override it if existing releases use larger
codes. Rerunning a workflow retains its run number and therefore its version code. If a
previous attempt uploaded successfully before a later failure, dispatch a new run with a
new unused code; rerunning cannot safely replace that upload. A draft also consumes its
uploaded version code. Release names are bounded SemVer strings of at most 100 characters.
Numeric prerelease identifiers must not have leading zeros: `0.2.0-01` and
`0.2.0-alpha.01` are invalid; `0.2.0-alpha.1` is valid.
Release notes accept 1–500 characters and are uploaded as English (`en-US`).

Every run compiles debug, runs debug JVM tests, lints both variants, then builds
the minified release bundle. Play modes additionally depend on the exact-commit native CI,
including its device checks and quality gate. The release artifact contains:

- `app-release.aab`, signed only when upload signing is configured.
- `mapping.txt`, for de-obfuscating this exact build; Play uploads include it.
- `whatsnew/whatsnew-en-US`, the supplied release notes.
- `provenance.json`, recording signed state, commit, repository, run/attempt, intended mode
    and track, version code and version name.
- `upload-certificate-sha256.txt`, the verified signing certificate fingerprint, or
    `unsigned` for the no-secret build. The fingerprint is also recorded in provenance.
- `SHA256SUMS`, covering the bundle, mapping, provenance, certificate fingerprint and release notes.

Artifacts are retained for 30 days. Archive the bundle and mapping under the release's
normal retention policy. Provenance records the requested action, not proof of publication;
verify the upload step and Play Console release state. The artifact is retained even if a
subsequent Play upload fails.

## Local release build

The local default version is code `2`, name `0.2.0`. Override using `SNAPPER_VERSION_CODE`
and `SNAPPER_VERSION_NAME`; validation applies to every Gradle invocation.

```bash
SNAPPER_VERSION_CODE=2001 SNAPPER_VERSION_NAME=0.2.0 \
    ./gradlew --no-daemon :app:bundleRelease
```

For a signed local build, provide all four environment variables
`ANDROID_UPLOAD_KEYSTORE_PATH`, `ANDROID_UPLOAD_STORE_PASSWORD`, `ANDROID_UPLOAD_KEY_ALIAS`
and `ANDROID_UPLOAD_KEY_PASSWORD` through a secure local credential mechanism. The path
must identify the protected upload keystore outside the repository. The base64 secret is
only a workflow transport format; Gradle reads the file path. Do not pass passwords as
command arguments, commit a keystore or credentials file, or use the debug key for Play.
