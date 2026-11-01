# Trigenys Claude Monitor — Android

Android app and native home-screen widget for four independent Claude subscription accounts.

Based on [Claude Pulse Android](https://github.com/G-biggy/claude-pulse-android), copyright 2026 Ghayyath Huzayen. MIT license; original attribution and LICENSE retained.

## Features

- Four independent account profiles: Ibrahim, Thunderbrainstorm, Trigenys Data School, Hello Trigenys (labels can be edited).
- Separate OAuth PKCE verifier, state, access token and refresh token per profile.
- Credentials and account labels encrypted at rest with AES-256-GCM using Android Keystore.
- One 4-by-4 home-screen widget showing provider-reported 5-hour and 7-day utilization, reset times and last reading age.
- Android WorkManager periodic requests around every 15 minutes (timing is subject to OS battery restrictions), plus manual refresh.
- Last reliable usage readings remain visible after an API failure; missing values show a dash rather than a fabricated zero.
- No VMs, desktop collector, KWGT, external backend or server.

## Build and install

Use JDK 17 and Android SDK platform 35.

    ./gradlew :app:testDebugUnitTest :app:assembleDebug

GitHub Actions automatically builds a debug APK artifact named trigenys-claude-monitor-debug-apk. Download it from a successful Actions run for personal device testing.

On Android 8 or later, install the APK, open the app and select each of the four profiles. Use Sign in with Claude to start a separate browser OAuth authorization for that account. Paste the complete code plus state from the provider into the app, then connect the next account. Do not copy desktop Claude Code refresh tokens because they rotate and may cause desktop logouts. Finally add the Trigenys Claude Monitor widget to the Android home screen.

## Security and compatibility

This is independent software, not endorsed by Anthropic. OAuth and consumer usage endpoints are undocumented and can change or stop working. The OAuth authorization may grant permissions beyond usage-only access; inspect and test on one account before connecting others. Keep tokens, cookies, and authorization codes out of chats and GitHub issues.

Android Keystore encryption keys belong to this device; clearing app data or reinstalling requires account reconnection. Android may delay background refreshes. Stored values are historical readings until the next successful refresh.

MIT. Keep the original author attribution and LICENSE when redistributing.
