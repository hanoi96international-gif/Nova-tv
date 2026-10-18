#!/usr/bin/env bash
# Läuft im gestarteten Emulator: Rundgang-Test, Messwerte, Absturz-Check der Release-APK.
set -x
mkdir -p ci-out
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell dumpsys gfxinfo de.ayutv reset >/dev/null 2>&1 || true
adb logcat -c
timeout 1200 adb shell am instrument -w -e class de.ayutv.DeviceTourTest de.ayutv.test/androidx.test.runner.AndroidJUnitRunner | tee ci-out/instrument.txt
adb shell dumpsys gfxinfo de.ayutv > ci-out/gfxinfo.txt 2>&1 || true
adb logcat -d -v time '*:W' AyuSubtitles:I AyuTour:I > ci-out/logcat_warnings.txt 2>&1 || true
adb exec-out run-as de.ayutv tar c -C files ci | tar x -C ci-out --strip-components=1 || true
adb logcat -d -v time AyuTour:I ActivityTaskManager:I ActivityManager:I PlayerActivity:I ExoPlayerImpl:W MediaCodec:W AndroidRuntime:E PlaylistRepository:W Subtitles:I '*:E' > ci-out/logcat.txt 2>&1 || true

# Release-APK (R8-optimiert) über die Debug-Version installieren: gleiche Signatur, Daten bleiben erhalten.
adb uninstall de.ayutv.test || true
adb install -r app/build/outputs/apk/release/app-release.apk
# Wie auf dem Fire TV nach der Installation: Baseline-Profil anwenden (sonst erst später im Leerlauf).
adb shell cmd package compile -f -m speed-profile de.ayutv || true
adb shell am force-stop de.ayutv
adb logcat -c
adb shell am start -W -n de.ayutv/.MainActivity | tee ci-out/release_start.txt
sleep 12
adb exec-out screencap -p > ci-out/release_home.png
key() { adb shell input keyevent "$@"; }
# Ruckler im Dauerbetrieb messen (ohne Kaltstart): Live TV mit 3000 Sendern und TV-Guide durchscrollen.
adb shell dumpsys gfxinfo de.ayutv reset >/dev/null
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER; sleep 2
key KEYCODE_DPAD_RIGHT; sleep 1
for i in $(seq 1 25); do key KEYCODE_DPAD_DOWN; done; sleep 1
key KEYCODE_DPAD_RIGHT; sleep 1
for i in $(seq 1 40); do key KEYCODE_DPAD_DOWN; done; sleep 1
adb exec-out screencap -p > ci-out/release_live.png
for i in $(seq 1 20); do key KEYCODE_DPAD_UP; done; sleep 1
adb shell dumpsys gfxinfo de.ayutv > ci-out/release_gfxinfo_live.txt 2>&1 || true
adb shell dumpsys gfxinfo de.ayutv reset >/dev/null
for i in $(seq 1 6); do key KEYCODE_DPAD_LEFT; done; sleep 1
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER; sleep 3
key KEYCODE_DPAD_RIGHT; sleep 1
for i in $(seq 1 30); do key KEYCODE_DPAD_DOWN; done
for i in $(seq 1 8); do key KEYCODE_DPAD_RIGHT; done; sleep 1
adb exec-out screencap -p > ci-out/release_guide.png
adb shell dumpsys gfxinfo de.ayutv > ci-out/release_gfxinfo_guide.txt 2>&1 || true
adb logcat -d -v time AndroidRuntime:E '*:S' > ci-out/release_crash.txt 2>&1 || true
exit 0
