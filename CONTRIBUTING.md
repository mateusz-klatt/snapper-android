# Contributing

Use JDK 17 and Android SDK 37.0 with Build Tools 36.0.0, then run:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Keep Android platform details in the application/UI layer and backend contracts in the data layer. Inject test doubles for session and settings behavior. Tests must cover session teardown, permission changes and wallet isolation when those flows change.

Put UI text in string resources, preserve exact financial decimals, and keep credentials, signing keys, local SDK paths and Firebase configuration out of Git. Open a pull request for changes and describe the user-visible result and validation performed.
