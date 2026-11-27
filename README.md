# ezTIK

**Status: Android v0.1 baseline merged into `main` (27/11/2026).** ezTIK is the sole repository for TikTok-related app/feed development; ezTube `main` contains no TikTok module. Former TikTok PRs #22–#25 in ezTube have been closed without merging.

**Independent Android project** for viewing public TikTok guest-feed clips through a standalone Kotlin/Jetpack Compose + Media3 app.

Repository: https://github.com/QABT26/ezTIK  
Local directory: `E:\RESEACHs\ezTIK`  
Android package: `com.qabt.eztik` (**not** the ezTube package).

## What is available

- Migrated **ezTube TikTok V2.2** player: vertical swipes, two bounded Media3 ExoPlayer instances (one plays, one preloads), refresh, FIT playback for horizontal clips, pause/seek, keep-screen-on while viewing.
- Dedicated source parser with TikTok-owned HTTPS media-host checks; prioritizes previously tested `/aweme/v1/play/` URLs.
- A guest-feed source abstraction: debug defaults to `127.0.0.1:39117` reached through `adb reverse` during QA; a separately configured HTTPS origin is supported for future independent use.
- Separate optional Node guest-feed gateway (`backend/tiktok/`) running only on loopback; **not** a complete server or bypass.
- No YouTube modules, no TikTok website WebView, no login or cookie collection, no VPS deployment.

## Important limitation

**Independent app does not mean independently available TikTok feed.** Android-only WebView probes on Samsung observed zero playable items and were stopped. The proven feed was produced by an isolated unofficial backend. Until a real HTTPS feed endpoint is deployed and tested, ezTIK can fetch clips only during the local debug lab (Windows backend + ADB reverse).

There is **no guarantee of an ad-free feed**, personalized For You, ongoing API compatibility or permission to redistribute TikTok data. Review relevant terms and privacy requirements before distribution.

## Local setup

1. Clone this repository into `E:\RESEACHs\ezTIK` next to the existing `ezTube` checkout.
2. Keep Node.js, Android SDK, Java 17/21 and Samsung device ADB available.
3. For reproducibility, use the pinned TikTok guest-feed lab already validated in ezTube (commit `d438d72fa903aa0ecb6f104fa9b915928134fe0a`, cached under Windows Temp).
4. Run `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\run-eztik-local-lab.ps1`.
5. Keep PowerShell open during video playback; pressing Enter ends backend/reverse.

The repository includes its **own Gradle 8.9 wrapper** (`gradlew.bat`, `gradlew`, `gradle/wrapper/`). The Android app and its build are independent of the ezTube checkout. The only reuse in the local debug lab is the existing *cached* pinned TikTok guest-feed backend, which is not included in the APK. CI uses the same pinned Gradle version.

## HTTPS option (future; not active)

To configure an HTTPS gateway at build time:

```powershell
.\gradlew.bat -PtiktokFeedBaseUrl=https://YOUR-REAL-FEED-DOMAIN testDebugUnitTest assembleDebug
```

Configured endpoint: `https://YOUR-REAL-FEED-DOMAIN/v1/guest-feed`. Release builds without a configured HTTPS feed fail closed; **do not use example hostnames as if they were deployed**.

## QA status

**ezTIK v0.1 is verified on Samsung in the local-backend lab:** Node gateway tests 4/4 PASS, Android `testDebugUnitTest assembleDebug` PASS, `com.qabt.eztik/.MainActivity` installed into Android user 0, live guest feed returned 16 items, `lab_media3_ready` and preloaded page transitions 0–4 were observed without playback errors in the supplied log. Audio, manual refresh and long-screen-timeout remain outside the supplied log evidence. **No public release or claim of standalone TikTok connectivity until HTTPS/no-ADB tests pass.**

Source provenance and test gates: `docs/PROJECT_MIGRATION.md`.
