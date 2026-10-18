#!/usr/bin/env bash
# Erzeugt Testinhalte für den Emulator-Rundgang: Live-Streams, 4K, HLS, Film mit Untertiteln, M3U und EPG.
set -euo pipefail
dir=${1:-ci-media}
mkdir -p "$dir/movie/test/test" "$dir/hls"
cd "$dir"

# 1080p mit 50 fps, H.264, Keyframe jede Sekunde: typischer deutscher HD-Sender
ffmpeg -loglevel error -y -f lavfi -i "testsrc2=size=1920x1080:rate=50" -f lavfi -i "sine=frequency=440:sample_rate=48000" \
  -t 90 -c:v libx264 -preset veryfast -b:v 8M -g 50 -pix_fmt yuv420p -c:a aac -b:a 128k -f mpegts hd50.ts
# 4K mit 25 fps, HEVC 10 Bit: der Härtefall
ffmpeg -loglevel error -y -f lavfi -i "testsrc2=size=3840x2160:rate=25" -f lavfi -i "sine=frequency=330:sample_rate=48000" \
  -t 40 -c:v libx265 -preset ultrafast -b:v 15M -g 25 -pix_fmt yuv420p10le -x265-params log-level=error \
  -c:a aac -b:a 128k -f mpegts uhd.ts
# HLS (wie bei vielen Anbietern)
ffmpeg -loglevel error -y -f lavfi -i "testsrc2=size=1280x720:rate=25" -f lavfi -i "sine=frequency=550:sample_rate=48000" \
  -t 60 -c:v libx264 -preset veryfast -g 50 -pix_fmt yuv420p -c:a aac -f hls -hls_time 2 -hls_list_size 0 hls/index.m3u8

# Film mit eingebetteten englischen Untertiteln
python3 - <<'PY'
phrases = ["Hello, how are you?", "I told you we should never have come here.", "Where is my brother?",
           "We have to leave before it gets dark.", "Thank you for everything.", "- Where is he?\n- I don't know."]
lines = []
for i in range(60):
    s, e = i * 4, i * 4 + 3
    lines.append(f"{i+1}\n00:{s//60:02d}:{s%60:02d},000 --> 00:{e//60:02d}:{e%60:02d},000\n{phrases[i % len(phrases)]}\n")
open("subs.en.srt", "w").write("\n".join(lines))
PY
ffmpeg -loglevel error -y -f lavfi -i "testsrc2=size=1920x1080:rate=24" -f lavfi -i "sine=frequency=660:sample_rate=48000" -i subs.en.srt \
  -t 240 -map 0 -map 1 -map 2 -c:v libx264 -preset veryfast -g 48 -pix_fmt yuv420p -c:a aac -c:s srt \
  -metadata:s:s:0 language=eng movie/test/test/1001.mkv

# Tonformate, die das Gerät (und der Emulator) nicht selbst dekodiert: Prüfstein für FFmpeg
ffmpeg -loglevel error -y -f lavfi -i "testsrc2=size=1280x720:rate=25" -f lavfi -i "sine=frequency=500:sample_rate=48000" \
  -t 60 -c:v libx264 -preset veryfast -g 50 -pix_fmt yuv420p -af "pan=5.1|c0=c0|c1=c0|c2=c0|c3=c0|c4=c0|c5=c0" -c:a dca -strict -2 -b:a 1509k dts.mkv
ffmpeg -loglevel error -y -f lavfi -i "testsrc2=size=1280x720:rate=25" -f lavfi -i "sine=frequency=600:sample_rate=48000" \
  -t 60 -c:v libx264 -preset veryfast -g 50 -pix_fmt yuv420p -af "pan=5.1|c0=c0|c1=c0|c2=c0|c3=c0|c4=c0|c5=c0" \
  -c:a eac3 -b:a 384k -f mpegts eac3.ts
ffmpeg -loglevel error -y -f lavfi -i "testsrc2=size=1920x1080:rate=24" -f lavfi -i "sine=frequency=700:sample_rate=48000" \
  -t 90 -c:v libx264 -preset veryfast -g 48 -pix_fmt yuv420p -af "pan=5.1|c0=c0|c1=c0|c2=c0|c3=c0|c4=c0|c5=c0" -c:a truehd -strict -2 movie/test/test/1002.mkv

# Film ohne eingebettete Untertitel: Übersetzung aus einer Untertiteldatei
ffmpeg -loglevel error -y -f lavfi -i "testsrc2=size=1280x720:rate=25" -f lavfi -i "sine=frequency=800:sample_rate=48000" \
  -t 120 -c:v libx264 -preset veryfast -g 50 -pix_fmt yuv420p -c:a aac movie/test/test/1003.mkv

cat > playlist.m3u <<'M3U'
#EXTM3U url-tvg="http://10.0.2.2:8000/epg.xml"
#EXTINF:-1 tvg-id="hd.test" tvg-chno="1" group-title="Test HD",HD Test 50fps
http://10.0.2.2:8000/hd50.ts
#EXTINF:-1 tvg-id="uhd.test" tvg-chno="2" group-title="Test 4K",UHD Test HEVC
http://10.0.2.2:8000/uhd.ts
#EXTINF:-1 tvg-id="hls.test" tvg-chno="3" group-title="Test HD",HLS Test
http://10.0.2.2:8000/hls/index.m3u8
#EXTINF:-1 tvg-chno="4" group-title="Test Ton",DTS Ton Test
http://10.0.2.2:8000/dts.mkv
#EXTINF:-1 tvg-chno="5" group-title="Test Ton",EAC3 Ton Test
http://10.0.2.2:8000/eac3.ts
#EXTINF:-1 group-title="Filme Test",Testfilm (2024)
http://10.0.2.2:8000/movie/test/test/1001.mkv
#EXTINF:-1 group-title="Filme Test",TrueHD Testfilm (2025)
http://10.0.2.2:8000/movie/test/test/1002.mkv
#EXTINF:-1 group-title="Filme Test",Stummfilm ohne Untertitel (2023)
http://10.0.2.2:8000/movie/test/test/1003.mkv
M3U

python3 - <<'PY'
import datetime as dt
now = dt.datetime.now(dt.timezone.utc).replace(minute=0, second=0, microsecond=0) - dt.timedelta(hours=2)
fmt = lambda t: t.strftime("%Y%m%d%H%M%S +0000")
out = ['<?xml version="1.0" encoding="UTF-8"?>', "<tv>"]
for cid, name in [("hd.test", "HD Test 50fps"), ("uhd.test", "UHD Test HEVC"), ("hls.test", "HLS Test")]:
    out.append(f'<channel id="{cid}"><display-name>{name}</display-name></channel>')
for cid in ["hd.test", "uhd.test", "hls.test"]:
    t = now
    for i in range(24):
        e = t + dt.timedelta(minutes=30 if i % 2 else 45)
        out.append(f'<programme start="{fmt(t)}" stop="{fmt(e)}" channel="{cid}"><title>Sendung {i+1} auf {cid}</title><desc>Beschreibung der Sendung {i+1}.</desc></programme>')
        t = e
out.append("</tv>")
open("epg.xml", "w").write("\n".join(out))
PY
ls -la . hls movie/test/test | head -30

# Belastungstest: großer Anbieter mit 3000 Sendern in 60 Gruppen und 36.000 EPG-Einträgen (gzip)
python3 - <<'PY'
import datetime as dt, gzip, random
random.seed(1)
streams = ["hd50.ts", "hls/index.m3u8"]
lines = ['#EXTM3U']
for i in range(3000):
    g = i // 50
    lines.append(f'#EXTINF:-1 tvg-id="big{i}" tvg-chno="{100+i}" tvg-logo="" group-title="Land {g:02d} | Gruppe",Sender {i:04d} {"HD" if i % 3 else "UHD"}')
    lines.append(f"http://10.0.2.2:8000/{streams[i % 2]}?ch={i}")
open("playlist_big.m3u", "w").write("\n".join(lines))
now = dt.datetime.now(dt.timezone.utc).replace(minute=0, second=0, microsecond=0) - dt.timedelta(hours=2)
fmt = lambda t: t.strftime("%Y%m%d%H%M%S +0000")
out = ['<?xml version="1.0" encoding="UTF-8"?>', "<tv>"]
for i in range(3000):
    out.append(f'<channel id="big{i}"><display-name>Sender {i:04d}</display-name></channel>')
for i in range(3000):
    t = now
    for j in range(12):
        e = t + dt.timedelta(minutes=random.choice([30, 45, 60, 90]))
        out.append(f'<programme start="{fmt(t)}" stop="{fmt(e)}" channel="big{i}"><title>Programm {j} auf {i}</title><desc>Text {j}</desc></programme>')
        t = e
out.append("</tv>")
with gzip.open("epg_big.xml.gz", "wt") as f:
    f.write("\n".join(out))
PY
ls -la playlist_big.m3u epg_big.xml.gz
