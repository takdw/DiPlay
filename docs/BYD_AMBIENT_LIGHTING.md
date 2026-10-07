# Optional BYD ambient lighting

Contributed by 寒叙 (@Hanxu4131), adapted from original ambient-light work in the contributor's local DiPlay-based BYD CarPlay project. The original [upstream PR #345](https://github.com/shihabal3amri/DiPlay/pull/345) was based on main b26cd544 (v0.2.13). This fork ports commit 60300d8977c04f875953aae7a1facdf546b36696 onto `dev` (v0.2.14), preserving the driver-screen music/artwork companion and call-audio handling. Application identifiers and existing network behavior are unchanged.

Open **Settings → Advanced → Music ambient lighting (experimental) → Ambient light settings**. All seven app languages have translated controls. Enable control and music following, choose OEM color numbers, and set a maximum brightness above 0 to see music-dependent changes. The default brightness is the minimum, even if control is enabled. Changes apply after Save without restarting CarPlay.

Configure the feature while parked. Control is off by default. Saving an enabled configuration first performs a read-only check using existing ADB authorization and the vehicle lamp interface. The feature does not enable ADB, offer an authorization key for approval or change debugging settings. A failed check leaves the saved configuration unchanged. Access setup remains a separate user-controlled action.

Only head units exposing the expected BYD interior-lamp API and valid color, brightness and area values are supported. This is not a claim of support for every BYD model, model year or firmware. Unknown or unreadable states are rejected before taking control. Missing older preferences use defaults; absent or incorrectly typed enable values remain disabled.

## Behavior

The media channel supplies energy from successful PCM writes. Only level and frame metadata are retained, not recorded audio. AudioTrack playback position ties the analysis to played audio. Navigation and call channels do not supply the light envelope. Phone playback ownership prevents stale callbacks from a replaced CarPlay session from resuming the lights.

Available modes are sound energy, beat, estimated BPM, bass priority and smart follow. BPM is an estimate from recent onsets, not track metadata. Selected OEM color numbers form the palette. Brightness transition speed affects brightness smoothing; color beat detection keeps its standard cadence. The configured brightness is a ceiling. Setting 0 means raw OEM level 1, the lowest supported level, not confirmed physical power off.

With control enabled, paused or disconnected playback holds all zones at the lowest level. During playback, disabling music following uses a fixed color and brightness. Disabling ambient control attempts to restore the pre-control front/rear colors, brightness and area. Changing the configuration takes effect only after Save; Cancel does not apply the draft.

The controller samples every 50 ms and limits lamp submissions to one per 200 ms. Lamp calls run outside audio writes through a token-bound, bounded stdin worker. The worker accepts only interior-lamp operations. It holds a process lock, reads back writes, keeps one original-state snapshot and restores on normal stop or EOF. A five-second heartbeat and fifteen-second lease attempt restoration after a lost connection. Recovery uses bounded retry delays and retains the original snapshot when a stop is unconfirmed.

Restoration is best effort. Power loss, forced process death, a permanently blocked vehicle service or repeated failed readback can prevent restoration. The worker's final failed restore is reported before it exits. This patch does not guarantee recovery under those conditions.

## Evidence and verification

The source ambient-light feature was field-tested by the contributor on a 2023 BYD Tang DM-i Champion Edition / platform-controller 21. These observations motivated the original module; they do not verify this fork integration, other model years or every recovery path. Exact earlier-build behavior should be attributed to that local build only.

On 2026-10-08, a separate controlled shell probe on a Sealion 06 / DiLink 5 / Android API 32 confirmed readable state, the expected setter signatures, physical dimming (raw brightness 6 → 3), color changes (OEM 28 → 1), and restoration to the original five-field state. The user observed both changes. See [BYD_AMBIENT_PROBE.md](BYD_AMBIENT_PROBE.md) for the scope and limits. This verifies the lamp API on that firmware; continuous music following and this APK still require live validation.

The contributor's local validation on 2026-10-06 passed shared/common Kotlin compilation, 63 focused shared tests and three focused UI tests. Native build tasks were excluded. Those results cover the original source and simulated UI behavior; no APK or vehicle validation was performed for that submitted port.

Fork validation on 2026-10-08 passed the complete `AGENTS.md` Android checks plus the music companion's tests, lint and debug build: 1,028 shared tests, 864 common/UI tests, four home tests and three companion tests (1,899 total, no failures). The common suite checks all locale translations, placement only in Advanced, localized palette updates, Cancel, and the read-only support gate. The read-only probe's Java harness and four Python tests also passed. A public-tree scan found no credential files. Vehicle installation and live music-following checks were deferred by the user; these automated results do not establish continuous lamp behavior on the car.

The focused JVM suite covers playback ownership, activity policy, written-versus-played envelopes, sampled beats, palette/low-frequency analysis, brightness smoothing, write cadence, fake-client recovery, protocol validation, restore order and worker ownership. Additional preference tests cover missing keys, legacy single-color settings and malformed values. UI tests cover Cancel, failed read-only access checks and a completed check after the dialog was canceled. Compilation and test results for this patch are recorded by the contribution review, separately from earlier vehicle observations.

The patch contains source and focused tests only. No local diagnostic logs, vehicle or phone records, ADB destination configuration, authentication assets, private logo, third-party APKs or decompiled code are included. Existing upstream LocalAdb loopback behavior and runtime authorization are reused unchanged.

## Local checks

```sh
./gradlew --offline :shared:testDebugUnitTest --tests 'com.shilapi.xcertplay.media.Ambient*' --tests 'com.shilapi.xcertplay.hud.BydAmbientLightPolicyTest' --tests 'com.shilapi.xcertplay.hud.AmbientWorkerOwnerLockTest' --tests 'com.shilapi.xcertplay.adb.LocalAdbInteractiveTest'
./gradlew --offline :shared:compileDebugKotlin :common:compileDebugKotlin
```

Before a release, check the default-off UI, all languages, failed authorization without new prompts, unsupported lamp states, Save/Cancel, music pause/resume, disconnect/reconnect, disabling control with unequal front/rear states, rapid setting changes and the complete normal vehicle workload while parked. Public build checks use no private authentication inputs.
