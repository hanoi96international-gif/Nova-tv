# Ayu-TV

Schneller, moderner IPTV-Player für **Fire TV** und Android TV. Gebaut mit Kotlin, Jetpack Compose for TV und Media3/ExoPlayer.

Ayu-TV ist ein reiner Player. Sender, Filme und Serien kommen aus deinem eigenen Zugang (M3U oder Xtream Codes).

## Was Ayu-TV besser machen will

| Problem bei anderen Playern | Ayu-TV |
|---|---|
| 4K ruckelt oder startet langsam | Hardware-Decoder mit Fallback, Pufferprofile (Schnell, Ausgewogen, Stabil 4K), optionaler Tunneling-Modus, Anpassung der Bildwiederholrate (50 Hz / 23,976 Hz) |
| Langsames Umschalten | Der TS-Stream startet ohne auf einen Keyframe zu warten, Verbindungen bleiben offen, das letzte Bild bleibt beim Zappen stehen. Die Umschaltzeit wird gemessen und im Stream-Info angezeigt |
| Lange Gruppenlisten mit Ländern, die man nie schaut | Gruppen pro Playlist ausblenden. Die Auswahl bleibt auch nach dem Aktualisieren erhalten |
| Große Playlists machen die App träge | Streaming-Parser für M3U, XMLTV und Xtream. Nichts wird komplett in den Speicher geladen. Der Import läuft in einer Transaktion im Hintergrund |
| Premium-Abo für Grundfunktionen | Alles ist frei: mehrere Playlists, Favoriten, EPG, Suche, Weiter schauen |
| Suche nur nach Sendernamen | Eine Suche über Sender, laufende und kommende Sendungen, Filme und Serien |
| Keine Empfehlungen | Die Startseite zeigt „Weiter schauen“, „Zuletzt gesehen“, Favoriten mit aktuellem Programm und „Mehr aus …“ für deine meistgeschauten Gruppen. Alles wird lokal berechnet, ohne Cloud |

## Funktionen (Version 0.5)

- **Quellen:** M3U/M3U8 (URL, gzip), XMLTV-EPG (auch `.xml.gz`, auch aus `url-tvg` im Playlist-Header), Xtream Codes API (Live, Filme, Serien, EPG)
- **Gruppen ein- und ausblenden:** pro Playlist, getrennt für Live TV, Filme und Serien (*Einstellungen → Playlist → Gruppen*), mit Filter und „Alle ein/aus“. Schneller geht es mit langem Druck auf OK auf einer Gruppe in der Liste. Ausgeblendete Gruppen verschwinden überall: in Listen, TV-Guide, Suche, Startseite und beim Zappen. Sie bleiben auch nach dem Aktualisieren ausgeblendet.
- **Live TV:** Gruppen, Senderliste mit aktuellem Programm und Fortschritt, Detailspalte mit den nächsten Sendungen, Favoriten (OK lange drücken)
- **TV-Guide:** EPG-Raster pro Gruppe, 2-Stunden-Fenster, das sich mit links/rechts verschieben lässt
- **Player:** Hoch/Runter schaltet um, OK oder links öffnet die Senderliste, rechts zeigt das Stream-Info (Auflösung, fps, Codec, Decoder, verworfene Frames, Leitung, Startzeit), Zifferntasten wählen direkt einen Sender, die „Letzter Sender“-Taste bzw. die blaue Taste springt zurück, Menü-Taste setzt einen Favoriten. Erkennt 4K, HDR10, HLG und Dolby Vision
- **Untertitel (Filme & Serien), optional:** standardmäßig aus, im Film mit der Menütaste ☰ einschalten (oder in den Einstellungen „automatisch einschalten“). In **41 Sprachen**:
  1. eingebettete Spur des Films,
  2. echte Untertitel von [OpenSubtitles](https://www.opensubtitles.com) in der Wunschsprache (per Datei-Hash zeitgenau, sonst nach Titel/Jahr),
  3. gibt es keine: **automatische Übersetzung** einer vorhandenen Datei (meist Englisch) mit DeepL oder LibreTranslate,
  4. eingebettete Spuren lassen sich auch **live übersetzen** (jede Zeile beim Erscheinen).
  Alles Geladene und Übersetzte wird gespeichert; mehrere Sprachen hängen gleichzeitig am Film.
- **Ton- und Untertitelmenü (☰-Taste):** Audiospuren mit Sprache und Format (Dolby Atmos, Dolby Digital Plus, DTS, 5.1), Untertitelspuren, Online-Suche. Bei Live TV zusätzlich Favorit setzen.
- **Stabilität:** Ein Watchdog erkennt eingefrorene Streams (Dauer-Puffern, stehendes Bild) und verbindet automatisch neu. Stürzt der Decoder bei aktivem Tunneling ab, wird Tunneling für diesen Stream abgeschaltet und neu gestartet. Kurzer Lese-Timeout (12 s), damit tote Verbindungen schnell auffallen. Bei Fehlern gibt es bis zu drei Wiederholungen, HLS ohne Endung wird automatisch erkannt.
- **Filme und Serien:** Kategorien, Poster-Raster, Staffeln und Episoden (Xtream), Fortsetzen an der letzten Position
- **Automatische Aktualisierung:** Playlists alle 12 Stunden, EPG alle 8 Stunden

- **4K und Stabilität:** asynchrone Ansteuerung der Hardware-Decoder (mehr Reserven bei 4K, automatischer Rückfall bei Problemen), Speicher-Obergrenze für den Puffer (kein Absturz nach langem 4K-Schauen auf Sticks mit wenig RAM), Watchdog, Tunneling-Rückfall, Erkennung von HDR10/HLG/Dolby Vision.
- **TV-Guide optional:** Das Programm-Raster lässt sich in den Einstellungen ausblenden. Die Programmdaten bleiben für „Jetzt läuft“, Fortschritt, Suche und Empfehlungen aktiv.
- **Weitere:** EPG-Zeitverschiebung (−12 bis +12 Std.), gemessene Bildrate für TS-Streams ohne Angabe (damit greift die 50-Hz-Anpassung auch bei Live-TV), verständliche Fehlermeldungen („Dieses Gerät kann HEVC 10 Bit in 4K nicht abspielen“), Textfelder öffnen die Tastatur erst nach OK.

## Automatisch geprüft bei jedem Build

Jeder Push läuft durch einen Rundgang auf einem Android-TV-Emulator (API 30, wie Fire OS 8), mit selbst erzeugten Testinhalten (1080p50 TS, 4K HEVC 10 Bit, HLS, MKV mit Untertiteln) und einem simulierten Xtream-Anbieter. Letzte Messwerte:

| Prüfung | Ergebnis |
|---|---|
| Umschalten (Zappen) | ca. 0,35–0,45 s |
| Start 1080p50-TS / HLS | ca. 0,45–0,9 s / ca. 0,45–0,5 s |
| Import großer Anbieter: 3.000 Sender + 34.600 EPG-Einträge (gzip) | 4,1 s |
| „Jetzt läuft“ für 3.000 Sender | 68 ms |
| Xtream: Login, falsches Passwort, Live/Filme/Serien, Episoden | ✓ |
| Film: Untertitel standardmäßig aus, einschaltbar, Spulen | ✓ |
| Release-APK (R8): Kaltstart / Abstürze | ca. 0,5 s / 0 |

Screenshots, Logs und Ruckler-Statistik liegen nach jedem Lauf im Branch `ci-results`.

## Installation auf dem Fire TV Stick

1. Auf dem Fire TV unter *Einstellungen → Mein Fire TV → Entwickleroptionen* „Apps unbekannter Herkunft“ für die App **Downloader** erlauben.
2. Die Datei `AyuTV.apk` aus dem neuesten [Release](../../releases/latest) herunterladen. Jeder grüne Build aktualisiert es automatisch.
3. In Downloader die APK-URL eingeben und installieren.
   Alternativ per ADB: `adb connect <IP-des-Sticks>` und danach `adb install Ayu-TV-xxxx.apk`.

Für den Alltag die **Release-APK** nehmen, nicht die Debug-Variante: Compose ist im Debug-Build deutlich langsamer.

## Untertitel und Übersetzung einrichten (einmalig, kostenlos)

1. Auf [opensubtitles.com](https://www.opensubtitles.com) ein Konto anlegen.
2. Unter *Profil → API-Consumer* einen API-Key erzeugen.
3. In Ayu-TV unter *Einstellungen → Untertitel* den API-Key eintragen. Benutzername und Passwort sind optional, ergeben aber mehr Downloads pro Tag.

4. Für automatische Übersetzung: *Einstellungen → Untertitel → Automatische Übersetzung* auf **DeepL** stellen und den kostenlosen DeepL-API-Key eintragen ([deepl.com/pro-api](https://www.deepl.com/pro-api), 500.000 Zeichen pro Monat, reicht für etwa 10 Filme). Alternativ **LibreTranslate** mit eigener Server-Adresse.

Ohne Zugänge funktionieren eingebettete Untertitel trotzdem.

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

Voraussetzungen: JDK 17 und Android SDK (compileSdk 37). Unterstützt werden Fire OS 6 und neuer (minSdk 23), also alle Fire TV Sticks ab 2018.

Technikstand (Oktober 2026): Kotlin 2.4, AGP 9.4 (mit eingebautem Kotlin), Gradle 9.8, compileSdk 37, Media3 1.11.1 (mit dynamischem Scheduling der Wiedergabeschleife standardmäßig an, besserer Bildraten-Schätzung, Dolby Vision Profil 10), Compose 1.12, Compose for TV Material 1.1, Room 2.8, Coil 3, OkHttp 5. Die GitHub-Action baut bei jedem Push automatisch beide APKs.
Ein Tag `v*` (z. B. `v0.1.0`) erstellt ein Release mit der APK.

### Aufbau

```
app/src/main/java/de/ayutv/
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
