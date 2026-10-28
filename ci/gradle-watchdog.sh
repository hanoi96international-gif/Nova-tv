#!/usr/bin/env bash
# Gradle mit Zeitlimit: Hängt der Bau (z. B. der Kotlin-Compiler), nach $1 Minuten den Zustand
# aller Java-Prozesse ausgeben (Thread-Dumps zeigen, woran gerechnet wird), dann abbrechen.
set -u
minutes="$1"; shift
./gradlew "$@" &
gradle=$!
end=$(( $(date +%s) + minutes * 60 ))
while kill -0 "$gradle" 2>/dev/null; do
  if [ "$(date +%s)" -ge "$end" ]; then
    echo "::error::Bau nach $minutes Minuten nicht fertig – Thread-Dumps folgen"
    for pid in $(jps -q 2>/dev/null); do
      echo "===== $(jps -l 2>/dev/null | grep "^$pid ") ====="
      jstack "$pid" 2>/dev/null | grep -v "^\s*$" | head -400
    done
    kill "$gradle" 2>/dev/null
    pkill -f KotlinCompileDaemon 2>/dev/null
    exit 1
  fi
  sleep 20
done
wait "$gradle"
