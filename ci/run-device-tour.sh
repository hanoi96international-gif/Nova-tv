#!/usr/bin/env bash
# Läuft im gestarteten Emulator: Rundgang-Test, Messwerte, Absturz-Check der Release-APK.
set -x
mkdir -p ci-out
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell dumpsys gfxinfo de.novatv reset >/dev/null 2>&1 || true
adb logcat -c
timeout 900 adb shell am instrument -w -e class de.novatv.DeviceTourTest de.novatv.test/androidx.test.runner.AndroidJUnitRunner | tee ci-out/instrument.txt
adb shell dumpsys gfxinfo de.novatv > ci-out/gfxinfo.txt 2>&1 || true
adb pull /sdcard/Android/data/de.novatv/files/ci/. ci-out/ || true
adb logcat -d -v time NovaTour:I ExoPlayerImpl:W MediaCodec:W AndroidRuntime:E PlaylistRepository:W Subtitles:I '*:E' > ci-out/logcat.txt 2>&1 || true

# Release-APK (R8-optimiert) über die Debug-Version installieren: gleiche Signatur, Daten bleiben erhalten.
adb uninstall de.novatv.test || true
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am force-stop de.novatv
adb logcat -c
adb shell am start -W -n de.novatv/.MainActivity | tee ci-out/release_start.txt
sleep 12
adb exec-out screencap -p > ci-out/release_home.png
adb shell input keyevent KEYCODE_DPAD_DOWN; adb shell input keyevent KEYCODE_DPAD_CENTER; sleep 3
adb exec-out screencap -p > ci-out/release_live.png
adb logcat -d -v time AndroidRuntime:E '*:S' > ci-out/release_crash.txt 2>&1 || true
adb shell dumpsys gfxinfo de.novatv > ci-out/release_gfxinfo.txt 2>&1 || true
exit 0
