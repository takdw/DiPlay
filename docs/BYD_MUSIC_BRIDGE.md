# BYD dashboard music and album art

DiPlay writes the CarPlay title and artist to separate BYD music fields over its already-authorized local ADB connection. Duration and elapsed time are sent as BYD's six-field time array; the progress percentage advances once a second between iPhone position updates. Pause freezes it, seek replaces it, and disconnect clears it. Existing wheel commands and CarPlay audio remain in DiPlay.

## Optional album-art companion

The `byd-music-bridge` module builds **DiPlay BYD Music Bridge**. The tested Chinese-market Sealion 06 / DiLink 5.0 media controller forwards artwork only for a fixed package allowlist. This companion uses `app.podcast.cosmos`, an entry verified on that head unit. It is our bridge, not the podcast application normally associated with that identifier. DiPlay keeps `com.shihab.diplay` (or its existing debug suffix).

This package workaround is specific to the tested firmware. Other firmware and future BYD updates may have different allowlists. Do not install this APK over an existing application using `app.podcast.cosmos`; check the installed package first. No vehicle firmware, whitelist, root access, or signature-only BYD provider permissions are changed.

The companion receives a DiPlay MediaSession token over a signature-protected bound service. It follows Android's metadata/artwork and playback callbacks, extrapolates progress from the original elapsed-time timestamp, and forwards media buttons and transport commands back to DiPlay's session. Metadata and artwork are republished only when the source changes. It has no network or ADB permission, no independent player, and no boot receiver.

BYD selects the audio-focus owner's session, so the companion requests permanent media focus while attached. DiPlay's existing permanent-loss behavior keeps CarPlay audio playing. Transient losses, ducking, and gains received by the companion are forwarded to the matching CarPlay sink; a subsequent CarPlay play resumes focus through the companion. Disconnect, disabling the feature, binder death, or source-session destruction releases the companion. DiPlay restores its own focus when the companion becomes unavailable during a continuing session.

## Build and enable

Both APKs must have the **same Android signing certificate**. Debug builds share the normal Android debug key; release builds use the same `ANDROID_KEYSTORE_*` inputs as Mobile. A separately signed companion cannot bind to an existing production-signed DiPlay installation.

```sh
./gradlew :byd-music-bridge:assembleDebug
# See BUILD.md for the explicit runtime-authentication input required by a car-test DiPlay APK.
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets ./gradlew :mobile:assembleStandaloneDebug
```

Install the companion and matching DiPlay build, and open **DiPlay BYD Music Bridge** once after installation. The tested BYD firmware did not start the original service-only package in the background. Preserve the installed production app and settings; do not uninstall it to work around a signature mismatch. In DiPlay's BYD settings, enable **Song on the dashboard** and **Album art on the dashboard**, and disable **Song only when it changes**. The companion currently follows the continuous music-card mode. Without a compatible installed companion, the ADB title/artist/progress output remains available. After initializing or reinstalling the companion, pause/resume CarPlay or toggle its artwork setting to retry a failed connection.

## Validation scope

Initial car-test validation base: upstream commit `e2fd8ea` (DiPlay 0.2.13).

2026-10-07 source checks: 29 focused shared tests, 35 media/artwork/focus tests in Common, and three companion tests passed (67 total, zero failures). Mobile standalone debug and companion debug APKs built; both debug lint tasks passed. Tests cover retained incremental metadata, progress/pause/seek, note/song timers, artwork publication/clearing, media-command forwarding, session cleanup, and focus delegation. The test APKs use matching debug signing certificates. The main APK's two explicitly selected authentication assets match the installed production 0.2.13 APK; these inputs remain outside the repository.

Integration validation on 2026-10-07: merged `dev` at `7887bb7` (DiPlay 0.2.14) into the feature branch and ran the complete repository CI command plus companion tests, lint, and build. Results: 1,832 cases (965 Shared, 860 Common, four Home, three companion), 1,831 passed, one existing macOS wildcard-bind assumption skip, zero failures/errors. Mobile, Home, map-host, and companion debug lint and APK builds passed. The Mobile/Home/map-host builds were source-only, without runtime authentication assets. The public-source credential check passed.

The merge preserves upstream's strict write-response validation and its observed DiLink 4 return value for source/state/title only. Artist, progress, and time require a zero result. Regression tests cover the expanded field set, progress-only updates, complete clearing, missing/duplicate fields, and rejection of the observed return value for unverified fields. Artwork-setting strings are present in every supported locale and marked experimental.

The matching debug APKs were installed alongside the production app on the owner's parked Sealion 06. After launching the companion once, Android reported its bound foreground service and active media session. The companion held permanent media focus and followed live CarPlay metadata, advancing position, a track change from “Nonstop” to “Going Bad (feat. Drake),” and a pause at the source's position. These ADB observations establish live forwarding to the companion; they do not establish what appeared on the driver screen or whether audio and wheel controls behaved correctly.

An earlier, separate 90-second snapshot probe using this allowlisted ID displayed album art and separate title/artist lines on the owner's Sealion 06. That probe did not establish continuous forwarding, wheel controls, or audio-focus behavior for the integrated implementation. Driver-screen and audible verification of the integrated build is pending, including track changes, pause/resume, next/previous, Siri, disconnect/reconnect, and turning the companion setting off.
