# ezTIK extraction and QA gates

## Source provenance

The player, parser and local feed-client source are adapted from `QABT26/ezTube`, branch `feat/eztube-tiktok-tab-webview-v1`, pinned revision `7bd301c13bd3c58b36fe08b231ff6eccbdbeb28b`. Samsung logs on that earlier integrated app show automatic 16-clip feed, MEDIA3 READY for repeated swipes, next-item prebuffer, and refresh batches.

Only TikTok-specific pieces are migrated. All old TikTok WebView feed-signing/observation experiments are excluded because they did not produce playable feed items. The separate optional HTTPS gateway is adapted from ezTube research PR #24; its production deployment was not tested.

## Separate identity

- Repository: `QABT26/ezTIK`; local Windows checkout: `E:\RESEACHs\ezTIK`
- Android application ID: `com.qabt.eztik`; can coexist with `com.qabt.eztube`.
- Android UI directly opens TikTok playback, not ezTube's five-tab navigation.
- No dependence on ezTube application source, Gradle wrapper, databases, login state or WebView experiments. ezTIK owns its Gradle 8.9 wrapper.

## Constraints

- No unconditional merge into ezTube/main or ezTIK/main until Samsung tests pass.
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
