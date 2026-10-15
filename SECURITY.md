# Security

For a security issue, use [GitHub private vulnerability reporting](https://github.com/mateusz-klatt/snapper-android/security/advisories/new). Do not include credentials or personal account data in a public issue.

The release app requires HTTPS. Debug HTTP exceptions are limited to explicit local development hosts. Authentication cookies are private to the selected backend origin and persisted using Android Keystore encryption; application backups exclude session storage.
