# NovaTV

Schneller, moderner IPTV-Player für **Fire TV** und Android TV. Gebaut mit Kotlin, Jetpack Compose for TV und Media3/ExoPlayer.

NovaTV ist ein reiner Player. Sender, Filme und Serien kommen aus deinem eigenen Zugang (M3U oder Xtream Codes).

## Was NovaTV besser machen will

| Problem bei anderen Playern | NovaTV |
|---|---|
| 4K ruckelt oder startet langsam | Hardware-Decoder mit Fallback, Pufferprofile (Schnell, Ausgewogen, Stabil 4K), optionaler Tunneling-Modus, Anpassung der Bildwiederholrate (50 Hz / 23,976 Hz) |
| Langsames Umschalten | Der TS-Stream startet ohne auf einen Keyframe zu warten, Verbindungen bleiben offen, das letzte Bild bleibt beim Zappen stehen. Die Umschaltzeit wird gemessen und im Stream-Info angezeigt |
| Große Playlists machen die App träge | Streaming-Parser für M3U, XMLTV und Xtream. Nichts wird komplett in den Speicher geladen. Der Import läuft in einer Transaktion im Hintergrund |
| Premium-Abo für Grundfunktionen | Alles ist frei: mehrere Playlists, Favoriten, EPG, Suche, Weiter schauen |
| Suche nur nach Sendernamen | Eine Suche über Sender, laufende und kommende Sendungen, Filme und Serien |
| Keine Empfehlungen | Die Startseite zeigt „Weiter schauen“, „Zuletzt gesehen“, Favoriten mit aktuellem Programm und „Mehr aus …“ für deine meistgeschauten Gruppen. Alles wird lokal berechnet, ohne Cloud |

## Funktionen (Version 0.1)

- **Quellen:** M3U/M3U8 (URL, gzip), XMLTV-EPG (auch `.xml.gz`, auch aus `url-tvg` im Playlist-Header), Xtream Codes API (Live, Filme, Serien, EPG)
- **Live TV:** Gruppen, Senderliste mit aktuellem Programm und Fortschritt, Detailspalte mit den nächsten Sendungen, Favoriten (OK lange drücken)
- **TV-Guide:** EPG-Raster pro Gruppe, 2-Stunden-Fenster, das sich mit links/rechts verschieben lässt
- **Player:** Hoch/Runter schaltet um, OK oder links öffnet die Senderliste, rechts zeigt das Stream-Info (Auflösung, fps, Codec, Decoder, verworfene Frames, Leitung, Startzeit), Zifferntasten wählen direkt einen Sender, Menü-Taste setzt einen Favoriten
- **Filme und Serien:** Kategorien, Poster-Raster, Staffeln und Episoden (Xtream), Fortsetzen an der letzten Position
- **Automatische Aktualisierung:** Playlists alle 12 Stunden, EPG alle 8 Stunden

## Installation auf dem Fire TV Stick

1. Auf dem Fire TV unter *Einstellungen → Mein Fire TV → Entwickleroptionen* „Apps unbekannter Herkunft“ für die App **Downloader** erlauben.
2. Die APK aus dem neuesten [Release](../../releases) bzw. dem Actions-Artefakt `NovaTV-apk` herunterladen.
3. In Downloader die APK-URL eingeben und installieren.
   Alternativ per ADB: `adb connect <IP-des-Sticks>` und danach `adb install NovaTV-xxxx.apk`.

Für den Alltag die **Release-APK** nehmen, nicht die Debug-Variante: Compose ist im Debug-Build deutlich langsamer.

## Tipps für flüssiges 4K

1. *Einstellungen → Puffer* auf **Stabil (4K)** stellen.
2. Ruckelt es weiter: **Tunneling-Modus** einschalten.
3. Ruckeln bei Kameraschwenks (z. B. Fußball): **Bildwiederholrate anpassen** einschalten.
4. Mit der rechten Pfeiltaste im Player das Stream-Info öffnen. Steigt „Verworfene Frames“ stark an, schafft der Decoder den Stream nicht. Liegt „Leitung“ unter der Video-Bitrate, ist das Netz der Engpass (LAN-Adapter statt WLAN hilft).

## Entwicklung

```bash
./gradlew testDebugUnitTest      # Parser-Tests (M3U, XMLTV, Xtream)
./gradlew assembleRelease        # optimierte APK (mit Debug-Schlüssel signiert, zum Sideloaden)
```

Voraussetzungen: JDK 17 und Android SDK (compileSdk 35). Die GitHub-Action baut bei jedem Push automatisch beide APKs.
Ein Tag `v*` (z. B. `v0.1.0`) erstellt ein Release mit der APK.

### Aufbau

```
app/src/main/java/de/novatv/
├── data/parse/   Streaming-Parser (M3U, XMLTV, Xtream-JSON), ohne Android-Abhängigkeiten und getestet
├── data/db/      Room-Datenbank (Sender, EPG, Filme, Serien, Favoriten, Verlauf)
├── data/repo/    Synchronisierung (PlaylistRepository) und Abfragen (ContentRepository)
├── player/       ExoPlayer-Konfiguration, Bildwiederholraten-Anpassung, Player-Activity und Overlays
└── ui/           Compose-for-TV-Oberfläche: Start, Live TV, TV-Guide, Filme, Serien, Suche, Einstellungen
```

## Geplant

- Catch-up / Timeshift (Xtream `tv_archive`)
- Mini-Vorschau in der Senderliste
- Multiview (2–4 Sender gleichzeitig)
- Aufnahmen
- Mehrere Profile und Kindersicherung
