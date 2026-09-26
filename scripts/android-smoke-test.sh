#!/usr/bin/env bash
# Install an APK on the connected device or emulator, start it, poke the UI with monkey,
# and fail on any crash or ANR in the app process (Java, native, DI, R8, migrations...).
#
# Usage: scripts/android-smoke-test.sh APK [OUT_DIR]
# Env: PREVIOUS_APK (installed and started first, to exercise the upgrade path),
#      SETTLE_SECONDS (default 20), MONKEY_EVENTS (default 500, 0 disables).
set -euo pipefail

apk=$1
out=${2:-smoke-test}
package=coredevices.coreapp
settle=${SETTLE_SECONDS:-20}
monkey_events=${MONKEY_EVENTS:-500}
mkdir -p "$out"

fail() {
    echo "SMOKE TEST FAILED: $*" >&2
    exit 1
}

# Crashes and ANRs of our package since the last logcat clear.
check_health() {
    local stage=$1
    adb logcat -d -b crash > "$out/crash-$stage.txt" || true
    adb logcat -d > "$out/logcat-$stage.txt" || true
    if grep -q "Process: $package\b\|>>> $package <<<" "$out/crash-$stage.txt"; then
        cat "$out/crash-$stage.txt" >&2
        fail "$stage: app crashed"
    fi
    if grep -q "ANR in $package\b" "$out/logcat-$stage.txt"; then
        grep -A20 "ANR in $package" "$out/logcat-$stage.txt" >&2
        fail "$stage: app not responding"
    fi
}

launch() {
    local stage=$1
    adb logcat -c
    adb logcat -b crash -c
    adb shell monkey -p "$package" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
    sleep "$settle"
    check_health "$stage"
    adb shell pidof "$package" > /dev/null || fail "$stage: app is not running"
    echo "$stage: app started and is running"
}

adb wait-for-device
adb uninstall "$package" > /dev/null 2>&1 || true

if [[ -n ${PREVIOUS_APK:-} ]]; then
    adb install -g "$PREVIOUS_APK"
    # Only the new APK is under test; a broken previous release is what a fix release replaces.
    (launch previous) || echo "WARNING: previous release failed to start, upgrading anyway" >&2
    adb install -r -g "$apk"
    launch upgrade
else
    adb install -g "$apk"
    launch start
fi

if (( monkey_events > 0 )); then
    adb logcat -c
    adb logcat -b crash -c
    # Stays inside the app; system keys could leave it or toggle airplane mode.
    if ! adb shell monkey -p "$package" -s 42 --throttle 100 --pct-syskeys 0 \
        --pct-appswitch 0 -v "$monkey_events" > "$out/monkey.txt" 2>&1; then
        tail -40 "$out/monkey.txt" >&2
        check_health monkey
        fail "monkey reported an error"
    fi
    check_health monkey
    echo "monkey: $monkey_events events without crashes"
fi

# Cold start again, e.g. to catch state persisted by the first run that fails to load.
adb shell am force-stop "$package"
launch restart
echo "SMOKE TEST PASSED"
