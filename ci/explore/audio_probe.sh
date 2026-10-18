#!/usr/bin/env bash
# Erkundung: echte TrueHD-/DTS-Testdateien in den Media3-Testdaten finden
set -x
out=$1; mkdir -p "$out"
git clone -q --depth 1 --filter=blob:none --sparse https://github.com/androidx/media.git media
cd media && git sparse-checkout set libraries/test_data/src/test/assets/media >/dev/null 2>&1
find libraries/test_data/src/test/assets/media -type f | grep -iE "truehd|mlp|dts|thd|ac3|ac4|atmos" > "../$out/audio_assets.txt"
cd ..
for f in $(grep -iE "\.(mkv|mp4|ts|m2ts|webm)$" "$out/audio_assets.txt" | head -20); do
  echo "== $f" >> "$out/audio_assets.txt"
  ffprobe -v error -show_entries stream=codec_name,channels,sample_rate,profile -of compact "media/$f" >> "$out/audio_assets.txt" 2>&1
done
cat "$out/audio_assets.txt"
