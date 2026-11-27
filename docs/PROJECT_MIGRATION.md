# ezTIK separation finalized — Android v0.1

## Source provenance

The player, parser and local feed-client source are adapted from `QABT26/ezTube`, branch `feat/eztube-tiktok-tab-webview-v1`, pinned revision `7bd301c13bd3c58b36fe08b231ff6eccbdbeb28b`. Samsung logs on that earlier integrated app show automatic 16-clip feed, MEDIA3 READY for repeated swipes, next-item prebuffer, and refresh batches.

Only TikTok-specific pieces are migrated. All old TikTok WebView feed-signing/observation experiments are excluded because they did not produce playable feed items. The separate optional HTTPS gateway is adapted from ezTube research PR #24; its production deployment was not tested.

## Separate identity

- Repository: `QABT26/ezTIK`; local Windows checkout: `E:\RESEACHs\ezTIK`
- Android application ID: `com.qabt.eztik`; can coexist with `com.qabt.eztube`.
- Android UI directly opens TikTok playback, not ezTube's five-tab navigation.
- No dependence on ezTube application source, Gradle wrapper, databases, login state or WebView experiments. ezTIK owns its Gradle 8.9 wrapper.

## Constraints

- ezTIK v0.1 was merged into ezTIK/main only after CI tests and Samsung local-feed Media3 QA. **No TikTok changes were merged into ezTube/main.**
- No automatic run on QBT/QTAT production VPS.
- Android release: HTTPS only, no localhost fallback.
- TikTok URL validation: secure origin and approved TikTok CDN suffixes; no arbitrary media proxy.
- Avoid logging signed URLs, guest-session tokens and cookies.
- Debug local lab uses pinned external upstream, never bundles its implementation into the APK.

## Evidence gates

1. `node --test backend/tiktok/feed-gateway.test.js` succeeds.
2. `testDebugUnitTest assembleDebug` succeeds from the **ezTIK** repository (Gradle 8.9).
3. `adb install --user 0` succeeds and Samsung runs `com.qabt.eztik/.MainActivity`.
4. With pinned local backend and ADB reverse, logs show `lab_initial`, `lab_preload_next`, `lab_preloaded_page`, repeated `lab_media3_ready`; check refresh, real frames, audio and screen timeout on the phone.
5. Later: a *real* independent HTTPS service must work with mobile data and no ADB reverse before calling ezTIK standalone operational.

If any gate fails, stop and diagnose from output rather than blindly patching TikTok signatures.

## Cutover record (2026-11-27)

- ezTIK PR #1 merged (squash) into `ezTIK/main` at `352ddb7e598438794b9d5b9b536159c5a163ecee`.
- ezTube `main` verified at `643d5598cc6c3f6d762ceb002035b3352a130076`: no TikTok source paths, navigation tab, gateway or runtime dependency.
- ezTube TikTok PRs #22, #23, #24, #25 **closed without merge**. Their research histories remain inspectable, but future TikTok work belongs exclusively to ezTIK.
- Standalone ezTIK debug lab passed on Samsung: 16 initial guest-feed items, player READY, preload and transitions from page 0 through 4. No playback error shown. Audio, refresh and screen-timeout not independently confirmed by those logs.
- An independent HTTPS guest-feed source **has not been deployed**. Local lab's Windows upstream and ADB reverse are still needed. Application separation is complete; feed-operation independence is not.
- Remote legacy Git branches in ezTube are research archives pending explicit cleanup; closing PRs does not delete their refs. Local user working trees are not modified by GitHub merges.
