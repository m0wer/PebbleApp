# Pebble mobile app custom build

This repository tracks the [official Pebble mobile app](https://github.com/coredevices/mobileapp)
and carries a small patch stack for personal builds. Refer to the upstream repository for the full
architecture, development, and contribution documentation.

## Changes

- Preserve and export detailed health and sleep-classification context
  ([upstream issue #347](https://github.com/coredevices/mobileapp/issues/347)).
- Retain, analyze, and export watch battery history
  ([upstream issue #320](https://github.com/coredevices/mobileapp/issues/320)).
- Expose the firmware's double-flick notification setting (`publish/double-flick-settings`).
- Stream raw watch voice recordings to PebbleKit JS (`publish/raw-voice-pkjs`; requires the
  [firmware API from PR #1035](https://github.com/coredevices/PebbleOS/pull/1035)).
- Add OpenAI-compatible voice transcription
  ([upstream PR #261](https://github.com/coredevices/mobileapp/pull/261)).
- Sort Rebble app-store collections by likes (`publish/appstore-likes-sort`).
- Persist and export firmware sleep diagnostics (`publish/sleep-diagnostics`).

Branches named `publish/*` contain focused changes based directly on the upstream branch.

## Install

Open the latest successful **Build** workflow run, download `android-debug-apk`, extract it, and
install the APK:

```shell
adb install -r androidApp-debug.apk
```

The CI artifact is a debug build. Its signature differs from the official app and may also change
between CI runners. If Android reports a signature mismatch, uninstall the existing
`coredevices.coreapp` package first; uninstalling removes its local app data.

For a stable signed release, add `ANDROID_RELEASE_KEYSTORE` (the base64-encoded JKS),
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEYSTORE_ALIAS`, and `RELEASE_KEY_PASSWORD` as repository
secrets, then publish a GitHub release. The release workflow builds, signs, and attaches the APK
and its SHA-256 checksum.

To build locally, copy `androidApp/src/google-services-dummy.json` to
`androidApp/src/google-services.json`, then run `./gradlew :androidApp:assembleDebug`. iOS builds
require macOS, Xcode, CocoaPods, and a physical device for watch connectivity.

## License

The combined mobile app remains available under [GNU GPLv3](LICENSE) or Core Devices'
[commercial terms](LICENSE-COMMERCIAL), as applicable. Original patches authored by m0wer are also
offered under the [MIT License](LICENSE-MIT) to the extent that m0wer owns them. The MIT grant does
not relicense upstream or third-party code, and distribution of the combined app must comply with
its applicable license.
