#!/usr/bin/env bash
# Erkundung: Findet die OpenSubtitles-Suche der App spanische Untertitel für „Harry Potter und der Halbblutprinz“?
OUT=${1:-explore-out}; mkdir -p "$OUT"
API=https://api.opensubtitles.com/api/v1
q() { curl -s -A "DnA-TV v0.15" -H "Api-Key: $OPENSUBTITLES_API_KEY" "$API/subtitles?$1" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items=d.get('data',[])
print('total', d.get('total_count'), 'msg', d.get('message'))
for it in items[:5]:
    a=it['attributes']; f=(a.get('files') or [{}])[0]
    print('  ', a.get('language'), a.get('feature_details',{}).get('title'), a.get('feature_details',{}).get('year'), 'downloads', a.get('download_count'), 'ai', a.get('ai_translated'), 'file', f.get('file_id'), a.get('release'))
"; }
{
  for lang in es id de; do
    echo "== tmdb_id=767 languages=$lang"; q "ai_translated=include&languages=$lang&machine_translated=include&tmdb_id=767"
    echo "== query 'harry potter und der halbblutprinz' languages=$lang"; q "ai_translated=include&languages=$lang&machine_translated=include&query=harry%20potter%20und%20der%20halbblutprinz"
  done
} > "$OUT/opensubs.txt" 2>&1
cat "$OUT/opensubs.txt"
