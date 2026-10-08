# Optional BYD ambient lighting

Contributed by 寒叙 (@Hanxu4131), adapted from original ambient-light work in the contributor's local DiPlay-based BYD CarPlay project. The original [upstream PR #345](https://github.com/shihabal3amri/DiPlay/pull/345) was based on main b26cd544 (v0.2.13). This fork ports commit 60300d8977c04f875953aae7a1facdf546b36696 onto `dev` (v0.2.14), preserving the driver-screen music/artwork companion and call-audio handling. Application identifiers and existing network behavior are unchanged.

Open **Settings → Advanced → Music ambient lighting (experimental) → Ambient light settings**. All seven app languages have translated controls. Enable control and music following, choose OEM color numbers, and set a maximum brightness above 0 to see music-dependent changes. The default brightness is the minimum, even if control is enabled. Changes apply after Save without restarting CarPlay.

Configure the feature while parked. Control is off by default. Saving an enabled configuration first performs a read-only check using existing ADB authorization and the vehicle lamp interface. The feature does not enable ADB, offer an authorization key for approval or change debugging settings. A failed check leaves the saved configuration unchanged. Access setup remains a separate user-controlled action.

Only head units exposing the expected BYD interior-lamp API and valid color, brightness and area values are supported. This is not a claim of support for every BYD model, model year or firmware. Unknown or unreadable states are rejected before taking control. Missing older preferences use defaults; absent or incorrectly typed enable values remain disabled.

On the tested Sealion 06, this implementation controls the interior light strips. The passenger-dashboard ambient panel is a separate, unresolved integration: the user confirmed that it reacts to OEM Bluetooth music but stays dark during this CarPlay effect. The front/rear/all area values refer to this lamp API's zones; they do not establish control of every illuminated surface in the cabin.

## Behavior

The media channel supplies energy from successful PCM writes. Only level and frame metadata are retained, not recorded audio. AudioTrack playback position ties the analysis to played audio. Navigation and call channels do not supply the light envelope. Phone playback ownership prevents stale callbacks from a replaced CarPlay session from resuming the lights.

Available modes are sound energy, beat, estimated BPM, bass priority and smart follow. BPM is an estimate from recent onsets, not track metadata. Selected OEM color numbers form the palette. Brightness transition speed affects brightness smoothing; color beat detection keeps its standard cadence. The configured brightness is a ceiling. Setting 0 means raw OEM level 1, the lowest supported level, not confirmed physical power off.

With control enabled, paused or disconnected playback holds all zones at the lowest level. During playback, disabling music following uses a fixed color and brightness. Disabling ambient control attempts to restore the pre-control front/rear colors, brightness and area. Changing the configuration takes effect only after Save; Cancel does not apply the draft.

The controller samples every 50 ms and limits lamp submissions to one per 200 ms. Lamp calls run outside audio writes through a token-bound, bounded stdin worker. The worker accepts only interior-lamp operations. It holds a process lock, reads back writes, keeps one original-state snapshot and restores on normal stop or EOF. A five-second heartbeat and fifteen-second lease attempt restoration after a lost connection. Recovery uses bounded retry delays and retains the original snapshot when a stop is unconfirmed.

Restoration is best effort. Power loss, forced process death, a permanently blocked vehicle service or repeated failed readback can prevent restoration. The worker's final failed restore is reported before it exits. This patch does not guarantee recovery under those conditions.

## Evidence and verification

The source ambient-light feature was field-tested by the contributor on a 2023 BYD Tang DM-i Champion Edition / platform-controller 21. These observations motivated the original module; they do not verify this fork integration, other model years or every recovery path. Exact earlier-build behavior should be attributed to that local build only.

On 2026-10-08, a separate controlled shell probe on a Sealion 06 / DiLink 5 / Android API 32 confirmed readable state, the expected setter signatures, physical dimming (raw brightness 6 → 3), color changes (OEM 28 → 1), and restoration to the original five-field state. The user observed both changes. See [BYD_AMBIENT_PROBE.md](BYD_AMBIENT_PROBE.md) for the scope and limits. This verifies the lamp API on that firmware; live validation of the ported APK is recorded below.

The contributor's local validation on 2026-10-06 passed shared/common Kotlin compilation, 63 focused shared tests and three focused UI tests. Native build tasks were excluded. Those results cover the original source and simulated UI behavior; no APK or vehicle validation was performed for that submitted port.

Fork validation on 2026-10-08 passed the complete `AGENTS.md` Android checks plus the music companion's tests, lint and debug build: 1,028 shared tests, 864 common/UI tests, four home tests and three companion tests (1,899 total, no failures). The common suite checks all locale translations, placement only in Advanced, localized palette updates, Cancel, and the read-only support gate. The read-only probe's Java harness and four Python tests also passed. A public-tree scan found no credential files. These automated checks are separate from physical vehicle verification.

### Live fork validation: 2026-10-08

The standalone **DiPlay HUD Test 0.2.14** APK was installed as an update on the same parked Sealion 06, retaining preferences, existing local ADB authorization, and the installed music companion. The installed lamp helper completed a read-only protocol check without extra stdout or a hanging process.

- The user confirmed that the interior light strips changed color and brightness during CarPlay music. The app reported hundreds of successful lamp applies with readback, including changing color numbers and brightness levels. Bass priority and sound energy configurations ran; this does not establish perceptually exact beat synchronization, every effect mode, or passenger-dashboard ambient-panel support.
- The user confirmed separate driver-screen title/artist lines, an advancing progress bar, and album artwork in the updated build. Main, companion and BYD media sessions carried the playing track.
- A pause command through DiPlay's media-button session produced paused playback state 2 and stable front/rear raw brightness `1/1`, area `3`. Resume produced playing state 3 and changing above-minimum brightness again.
- Disabling control through its Save button restored the original five-field state `28,28,6,6,3`. Re-enabling passed the read-only gate and preserved the saved sound energy mode, brightness ceiling 6, and 31 selected color numbers.
- Disconnecting CarPlay through its UI held both lamp zones at raw brightness 1, area 3. Reconnecting through the saved phone configuration returned to playing state 3 and resumed successful lamp updates.

The short test did not verify each selected color independently, every mode, unequal front/rear restoration, incoming calls or navigation audio interruptions, extended driving, blocked-service recovery or power-loss restoration. Sequential independent getter samples during live writes can span different updates; paused and disabled restoration checks used stable states. Local diagnostic logs remain in ignored build output, with no phone records or authentication material added to the public source.

### Passenger-dashboard investigation: 2026-10-08

The user confirmed the passenger-dashboard panel reacting during OEM Bluetooth playback. Ten narrowly selected lamp/music/rhythm getter values were identical between the CarPlay and Bluetooth samples, so those samples did not identify a mode-setting difference. Bluetooth generated changing 16-level `BYDAutoAudioDevice.setAmbientLightFreq` calls from the system server; a ten-second sample during user-confirmed CarPlay playback contained no such native calls.

Inspection of this firmware's native audio service found an automatic lighting-visualizer package list containing `cn.kuwo.kwmusiccar`, `com.kugou.android.auto`, `com.byd.dynaudio_app`, `com.netease.cloudmusic.iot`, and `com.byd.caraudioaosp`. Neither DiPlay nor the artwork companion is in that list. The native color effect also toggles the audio-system `lamp_status` parameter and submits separate ECU color/brightness data. The current strip implementation does not invoke this native visualizer or reproduce those outputs.

A separate, Park-checked 30-second shell probe submitted 148 accepted spectrum updates using the native SDK method, then successfully submitted a quiet frame. The user observed no visible change. This establishes that accepted spectrum writes alone did not activate the passenger panel in that test; it does not establish that the panel is inaccessible. These probes are diagnostics, not an installed dashboard-panel fix. Decompiled vendor software and device logs remain outside the public source tree.

A second, Park-checked probe requested a 40-second native `IAudioService.startAudioOutput` session with its own Binder token and an unused approved OEM source identifier. No app was renamed or installed, and the probe made no audio-focus request. The user reported that the passenger panel remained dark while music continued. Sampled system-server spectrum frames contained only minimum levels; these samples do not establish the cause of the missing panel response. The matching stop request returned normally, the helper exited, and the music-mode, rhythm-mode and main-switch getters remained at their pre-test values `1`, `4` and `2`. The full native-session request therefore remains an unsuccessful diagnostic, not a verified integration route.

The focused JVM suite covers playback ownership, activity policy, written-versus-played envelopes, sampled beats, palette/low-frequency analysis, brightness smoothing, write cadence, fake-client recovery, protocol validation, restore order and worker ownership. Additional preference tests cover missing keys, legacy single-color settings and malformed values. UI tests cover Cancel, failed read-only access checks and a completed check after the dialog was canceled. Compilation and test results for this patch are recorded by the contribution review, separately from earlier vehicle observations.

The patch contains source and focused tests only. No local diagnostic logs, vehicle or phone records, ADB destination configuration, authentication assets, private logo, third-party APKs or decompiled code are included. Existing upstream LocalAdb loopback behavior and runtime authorization are reused unchanged.

## Local checks

```sh
./gradlew --offline :shared:testDebugUnitTest --tests 'com.shilapi.xcertplay.media.Ambient*' --tests 'com.shilapi.xcertplay.hud.BydAmbientLightPolicyTest' --tests 'com.shilapi.xcertplay.hud.AmbientWorkerOwnerLockTest' --tests 'com.shilapi.xcertplay.adb.LocalAdbInteractiveTest'
./gradlew --offline :shared:compileDebugKotlin :common:compileDebugKotlin
```

Before a release, check the default-off UI, all languages, failed authorization without new prompts, unsupported lamp states, Save/Cancel, music pause/resume, disconnect/reconnect, disabling control with unequal front/rear states, rapid setting changes and the complete normal vehicle workload while parked. Public build checks use no private authentication inputs.
