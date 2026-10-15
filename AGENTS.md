# Snapper Android

This is the native Kotlin and Jetpack Compose client for the Snapper backend.

- Use Material 3, ViewModel, StateFlow and structured coroutines. Inject dependencies explicitly.
- Preserve decimal financial values. Convert to floating point only for drawing coordinates.
- Follow the existing backend envelope, cookie, CSRF and permission contracts.
- Keep credentials out of logs, backups, saved UI state and source control.
- Keep code and Markdown in English. Put UI copy in Android string resources.
- Reuse existing reviewed translations mechanically; do not invent translations.
- Run `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` before committing.
- Keep the Gradle wrapper and dependencies pinned. Never commit signing keys or local SDK paths.
- Every assistant commit requires the configured `Co-authored-by` trailer.
