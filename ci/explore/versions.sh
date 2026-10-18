#!/usr/bin/env bash
# Neueste Versionen aller Abhängigkeiten (Google Maven + Maven Central + Gradle-Plugins)
out=$1; mkdir -p "$out"; f="$out/versions.txt"; : > "$f"
g() { curl -s "https://dl.google.com/android/maven2/$1/maven-metadata.xml" | grep -o "<version>[^<]*</version>" | sed 's/<[^>]*>//g' | grep -v -iE "alpha|beta|rc|dev" | tail -1; }
c() { curl -s "https://repo1.maven.org/maven2/$1/maven-metadata.xml" | grep -o "<release>[^<]*</release>" | sed 's/<[^>]*>//g'; }
for a in androidx/media3/media3-exoplayer androidx/compose/compose-bom androidx/tv/tv-material androidx/room/room-runtime androidx/datastore/datastore-preferences androidx/activity/activity-compose androidx/lifecycle/lifecycle-runtime-compose androidx/profileinstaller/profileinstaller com/android/tools/build/gradle androidx/core/core-ktx androidx/test/runner androidx/test/core androidx/test/ext/junit androidx/benchmark/benchmark-macro-junit4 androidx/baselineprofile/androidx.baselineprofile.gradle.plugin; do echo "google $a $(g $a)" >> "$f"; done
for a in org/jetbrains/kotlin/kotlin-stdlib com/google/devtools/ksp/com.google.devtools.ksp.gradle.plugin com/squareup/okhttp3/okhttp io/coil-kt/coil3/coil-compose org/jetbrains/kotlinx/kotlinx-serialization-json org/jetbrains/kotlinx/kotlinx-coroutines-android com/microsoft/onnxruntime/onnxruntime-android com/google/zxing/core; do echo "central $a $(c $a)" >> "$f"; done
echo "gradle $(curl -s https://services.gradle.org/versions/current | grep -o '"version" *: *"[^"]*"')" >> "$f"
echo "media3 tags: $(git ls-remote --tags https://github.com/androidx/media.git | grep -o 'refs/tags/[0-9.]*$' | sed 's#refs/tags/##' | sort -V | tail -3 | tr '\n' ' ')" >> "$f"
echo "ffmpeg branches: $(git ls-remote --heads https://github.com/FFmpeg/FFmpeg.git 'release/*' | grep -o 'release/.*' | sort -V | tail -4 | tr '\n' ' ')" >> "$f"
cat "$f"
