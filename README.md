# Pebble mobile app custom build

This repository tracks the [official Pebble mobile app](https://github.com/coredevices/mobileapp)
and carries a small patch stack for personal builds. Refer to the upstream repository for the full
architecture, development, and contribution documentation.

**[Download the latest Android release](https://github.com/m0wer/PebbleApp/releases/latest)**

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

Download `PebbleApp-*.apk` from the
[latest release](https://github.com/m0wer/PebbleApp/releases/latest). On the Android device, allow
the browser or file manager to install unknown apps, then open the APK and approve the installation.
You can also install it over ADB:

```shell
adb install -r PebbleApp-*.apk
```

The release has a stable fork signature that differs from the official app. If Android reports a
signature mismatch, uninstall the existing `coredevices.coreapp` package first; uninstalling
removes its local app data. Future releases from this fork can then be installed as updates.

For a stable signed release, add `ANDROID_RELEASE_KEYSTORE` (the base64-encoded JKS),
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEYSTORE_ALIAS`, and `RELEASE_KEY_PASSWORD` as repository
secrets, then push a three- or four-component numeric tag such as `1.10.0.3`. The release workflow
builds the signed APK, creates the GitHub Release, and attaches the APK and its SHA-256 checksum.

The regular **Build** workflow also provides an `android-debug-apk` artifact for development
testing. Debug artifacts are not stable release builds.

To build locally, copy `androidApp/src/google-services-dummy.json` to
`androidApp/src/google-services.json`, then run `./gradlew :androidApp:assembleDebug`. iOS builds
require macOS, Xcode, CocoaPods, and a physical device for watch connectivity.

## License

The combined mobile app remains available under [GNU GPLv3](LICENSE) or Core Devices'
[commercial terms](LICENSE-COMMERCIAL), as applicable. Original patches authored by m0wer are also
offered under the [MIT License](LICENSE-MIT) to the extent that m0wer owns them. The MIT grant does
not relicense upstream or third-party code, and distribution of the combined app must comply with
its applicable license.
