package de.ayutv

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.ayutv.data.db.HistoryKind
import de.ayutv.data.db.PlaylistEntity
import de.ayutv.data.db.PlaylistType
import de.ayutv.data.repo.XtreamClient
import de.ayutv.player.PlayerActivity
import de.ayutv.player.VodRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Rundgang über die ganze App auf einem echten (emulierten) Android-TV-Gerät.
 * Die CI stellt unter 10.0.2.2:8000 eine Playlist, ein EPG und Testvideos bereit
 * (1080p50 H.264 TS, 4K HEVC TS, HLS, MKV mit englischen Untertiteln).
 * Ergebnisse: Screenshots und results.txt im externen App-Verzeichnis.
 */
@RunWith(AndroidJUnit4::class)
class DeviceTourTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<AyuApp>()
    // Interner Speicher: die CI holt ihn per "run-as" ab (zuverlässiger als Android/data unter Scoped Storage).
    private val out = File(app.filesDir, "ci").apply { deleteRecursively(); mkdirs() }
    private val results = StringBuilder()

    private fun log(line: String) {
        results.append(line).append('\n')
        File(out, "results.txt").writeText(results.toString())
        android.util.Log.i("AyuTour", line)
    }

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val scaled = Bitmap.createScaledBitmap(bitmap, 960, 540, true)
        File(out, "$name.jpg").outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }

    private fun key(code: Int, times: Int = 1) = repeat(times) {
        instrumentation.sendKeyDownUpSync(code)
        Thread.sleep(350)
    }

    /** Player und Activity-Zustand nur auf dem Main-Thread lesen (ExoPlayer prüft das). */
    private fun <T> ActivityScenario<PlayerActivity>.read(block: (PlayerActivity) -> T): T {
        var value: T? = null
        onActivity { value = block(it) }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    /** Jeder Abschnitt läuft für sich: ein Fehler wird protokolliert, der Rundgang geht weiter. */
    private var failures = 0

    private fun section(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            failures++
            log("FEHLER in $name: ${t.javaClass.simpleName}: ${t.message}")
            android.util.Log.e("AyuTour", "Fehler in $name", t)
        }
    }

    /**
     * Startet den Player. Beendet das Testframework gerade noch die vorige Activity, kann der neue
     * Start in derselben Task mit abgeräumt werden; dann einmal sauber neu starten.
     */
    private fun launchPlayer(intent: android.content.Intent): ActivityScenario<PlayerActivity> {
        Thread.sleep(1_500)
        repeat(2) {
            val scenario = ActivityScenario.launch<PlayerActivity>(intent)
            if (scenario.state == androidx.lifecycle.Lifecycle.State.RESUMED) return scenario
            log("player start state=${scenario.state}, neuer Versuch")
            scenario.close()
            Thread.sleep(2_000)
        }
        return ActivityScenario.launch(intent)
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(200)
        }
        return false
    }

    @Test
    fun tour() {
        val container = app.container
        val base = "http://10.0.2.2:8000"

        // 0. Nach dem Upgrade von der Vorversion: Datenbank muss migriert sein (nicht gelöscht/abgestürzt)
        section("upgrade") {
            val version = container.db.openHelper.readableDatabase.version
            val existing = runBlocking { container.db.playlists().all() }
            log("db_version=$version playlists_before=${existing.size}")
            assertTrue("Datenbank nicht auf Version 2", version == 2)
        }

        // 0b. Textmuster auf Android prüfen (Android nutzt eine andere Regex-Engine als der PC)
        section("regex") {
            val plain = de.ayutv.data.parse.Srt.plainText("<i>Hallo</i> {\\an8}Welt")
            val (title, year) = de.ayutv.data.parse.OpenSubtitlesParsing.cleanTitle("DE - Der Film [4K] (Director's Cut) 2020")
            log("regex: plain=$plain title=$title year=$year")
            assertTrue("Srt.plainText: $plain", plain == "Hallo Welt")
        }

        // 1. Playlist + EPG importieren
        val started = System.currentTimeMillis()
        runBlocking {
            val id = container.playlists.add(
                PlaylistEntity(name = "CI-Test", type = PlaylistType.M3U, url = "$base/playlist.m3u", epgUrl = "$base/epg.xml")
            )
            container.playlists.sync(id)
        }
        val channels = runBlocking { container.content.zapList(null, false) }
        val programmes = runBlocking { container.db.programmes().observeCount().first() }
        log("import_ms=${System.currentTimeMillis() - started} channels=${channels.size} programmes=$programmes sync=${container.playlists.state.value}")
        assertTrue("Keine Sender importiert", channels.isNotEmpty())

        // 1b. Xtream-Codes-Anbieter (simuliert): Login, Import, Serien-Episoden
        val wrongLogin = runBlocking { container.playlists.verifyXtream(base, "ayu", "falsch") }
        val login = runBlocking { container.playlists.verifyXtream(base, "ayu", "test") }
        val xtreamStarted = System.currentTimeMillis()
        runBlocking {
            val id = container.playlists.add(
                PlaylistEntity(name = "Xtream-Test", type = PlaylistType.XTREAM, url = XtreamClient.normalizeServer(base), username = "ayu", password = "test")
            )
            container.playlists.sync(id)
        }
        val allChannels = runBlocking { container.content.zapList(null, false) }
        val movies = runBlocking { container.db.vod().search("", 50) }
        val seriesList = runBlocking { container.db.series().search("", 50) }
        val episodes = seriesList.firstOrNull()?.let { runBlocking { container.content.episodes(it) } }.orEmpty()
        log(
            "xtream: wrong_login_rejected=${wrongLogin != null} login=${login ?: "ok"} import_ms=${System.currentTimeMillis() - xtreamStarted} " +
                "channels_total=${allChannels.size} movies=${movies.size} series=${seriesList.size} episodes=${episodes.size} " +
                "sync=${container.playlists.state.value}"
        )

        // 2. Oberfläche per Fernbedienung durchgehen
        section("ui") { ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(4_000)
            shot("01_home")
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER); Thread.sleep(1_500)
            shot("02_live")
            key(KeyEvent.KEYCODE_DPAD_RIGHT, 2); Thread.sleep(800)
            shot("03_live_focus")
            key(KeyEvent.KEYCODE_DPAD_LEFT, 3)
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER); Thread.sleep(1_500)
            shot("04_guide")
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER); Thread.sleep(1_500)
            shot("05_movies")
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER); Thread.sleep(1_000)
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER); Thread.sleep(1_500)
            shot("06_search")
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER); Thread.sleep(1_500)
            shot("07_settings")
        } }

        // 3. Live: Start, Zappen, Senderliste, Menü
        val live = channels.filter { "/movie/" !in it.url }
        live.forEachIndexed { index, channel -> section("live[$index]") {
            launchPlayer(PlayerActivity.liveIntent(app, channel.key, null)).use { scenario ->
                val ready = waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                Thread.sleep(8_000)
                val stats = scenario.read { it.stats }
                log(
                    "live[$index] ${channel.name}: ready=$ready startup_ms=${stats.startupMs} ${stats.width}x${stats.height} " +
                        "fps=${stats.frameRate} codec=${stats.videoCodec} decoder=${stats.decoder} dropped=${stats.droppedFrames} " +
                        "hdr=${stats.hdr} audio=${stats.audioCodec}/${stats.audioChannels}ch audio_decoder=${stats.audioDecoder} " +
                        "message=${scenario.read { it.message }}"
                )
                shot("1${index}_live_${channel.name.filter(Char::isLetterOrDigit)}")
                if (index == 0) {
                    key(KeyEvent.KEYCODE_DPAD_RIGHT); Thread.sleep(500); key(KeyEvent.KEYCODE_DPAD_RIGHT); Thread.sleep(800)
                    shot("1${index}_live_info_tech")
                    // Zweimal umschalten: über den 4K-Sender (im Emulator nicht dekodierbar) zum nächsten.
                    // Prüft auch, dass sich der Player nach einem Formatfehler wieder fängt.
                    key(KeyEvent.KEYCODE_DPAD_UP); Thread.sleep(3_000)
                    log("zap1 now=${scenario.read { it.current?.name }} message=${scenario.read { it.message }}")
                    key(KeyEvent.KEYCODE_DPAD_UP)
                    val zapReady = waitFor(15_000) {
                        scenario.read { it.player?.playbackState == Player.STATE_READY && it.stats.startupMs > 0 }
                    }
                    log("zap: ready=$zapReady startup_ms=${scenario.read { it.stats.startupMs }} now=${scenario.read { it.current?.name }}")
                    key(KeyEvent.KEYCODE_DPAD_CENTER); Thread.sleep(1_200)
                    shot("1${index}_live_channel_list")
                    key(KeyEvent.KEYCODE_BACK); Thread.sleep(500)
                    key(KeyEvent.KEYCODE_MENU); Thread.sleep(1_000)
                    shot("1${index}_live_menu")
                    key(KeyEvent.KEYCODE_BACK)
                }
            }
        } }

        // 2b. Archiv (Catch-up): Xtream-Sender mit Archiv, vergangene Sendung abspielen
        section("catchup") {
            val archived = runBlocking { container.content.zapList(null, false) }.firstOrNull { it.catchupDays > 0 }
                ?: error("Kein Sender mit Archiv importiert")
            val now = System.currentTimeMillis()
            val programme = de.ayutv.data.db.ProgrammeEntity(
                playlistId = archived.playlistId, channelEpgId = archived.epgId ?: "x",
                start = now - 3_600_000L, stop = now - 1_800_000L, title = "Archiv-Test", description = null, category = null,
            )
            val url = runBlocking { container.content.catchupUrl(archived, programme) } ?: error("Keine Archiv-Adresse")
            val intent = PlayerActivity.vodIntent(
                app,
                VodRequest("catchup:test", HistoryKind.CATCHUP, programme.title, archived.name, null, url),
            )
            launchPlayer(intent).use { scenario ->
                val ready = waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                log("catchup: channel=${archived.name} days=${archived.catchupDays} url=${url.substringAfter("8000")} ready=$ready")
                assertTrue("Archiv spielt nicht ab", ready && "/timeshift/" in url)
            }
        }

        // 3a. Bildwiederholrate anpassen + Tunneling eingeschaltet: darf nie hängen oder sich neu starten
        channels.firstOrNull { it.name == "HD Test 50fps" }?.let { hd -> section("afr_tunneling") {
            runBlocking { container.settings.update { it.copy(matchFrameRate = true, tunneling = true) } }
            try {
                launchPlayer(PlayerActivity.liveIntent(app, hd.key, null)).use { scenario ->
                    waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                    var playing = 0
                    val messages = mutableSetOf<String>()
                    repeat(30) {
                        Thread.sleep(1_000)
                        if (scenario.read { it.player?.isPlaying } == true) playing++
                        scenario.read { it.message }?.let(messages::add)
                    }
                    val stats = scenario.read { it.stats }
                    log("afr_tunneling: playing_s=$playing fps=${stats.frameRate} measured=${stats.frameRateMeasured} messages=$messages")
                    assertTrue("Mit Bildwiederholrate/Tunneling nur $playing von 30 s gelaufen", playing >= 27)
                }
            } finally {
                runBlocking { container.settings.update { it.copy(matchFrameRate = false, tunneling = false) } }
            }
        } }

        // 3b. Wackelige Leitung: Server liefert in Echtzeit und trennt alle 15 s. Das Bild muss weiterlaufen.
        channels.firstOrNull { it.name == "Wackelige Leitung" }?.let { flaky -> section("flaky") {
            launchPlayer(PlayerActivity.liveIntent(app, flaky.key, null)).use { scenario ->
                waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                // Gezählt wird, in wie vielen der 50 Sekunden das Bild tatsächlich lief.
                var playing = 0
                var buffering = 0
                repeat(50) {
                    Thread.sleep(1_000)
                    when {
                        scenario.read { it.player?.isPlaying } == true -> playing++
                        scenario.read { it.player?.playbackState } == Player.STATE_BUFFERING -> buffering++
                    }
                }
                log("flaky: playing_s=$playing buffering_s=$buffering cushion=${scenario.read { it.cushionActive }} message=${scenario.read { it.message }}")
                assertTrue("Live-Stream hängt bei Verbindungsabbrüchen ($playing von 50 s gelaufen)", playing >= 45)
            }
        } }

        // 4. Film: Untertitel standardmäßig aus, eingebettete englische Spur einschalten
        val movie = runBlocking { container.db.vod().search("", 5) }.firstOrNull()
        // OpenSubtitles (eingebauter Schlüssel): Zugang gültig, Suche nach einem bekannten Film liefert Treffer.
        // Danach aus, damit die Übersetzungstests nicht von zufälligen Online-Treffern abhängen.
        section("opensubtitles") {
            val settings = runBlocking { container.settings.current() }
            if (settings.openSubtitlesApiKey.isBlank()) {
                log("opensubtitles: kein eingebauter Schlüssel (GitHub-Secret fehlt)")
            } else {
                val check = runBlocking { de.ayutv.data.setup.KeyCheck.run(container.http, settings) }
                val german = runBlocking { container.subtitles.countResults("Inception (2010)", "de", settings) }
                val english = runBlocking { container.subtitles.countResults("The Matrix (1999)", "en", settings) }
                log("opensubtitles: check=${check.map { "${it.ok}:${it.text}" }} inception_de=$german matrix_en=$english")
                assertTrue("OpenSubtitles-Schlüssel ungültig: $check", check.all { it.ok })
                assertTrue("OpenSubtitles findet nichts", german > 0 && english > 0)
            }
        }
        runBlocking { container.settings.update { it.copy(onlineSubtitles = false) } }

        if (movie != null) section("vod") {
            val request = VodRequest(movie.key, HistoryKind.VOD, movie.name, movie.category, movie.poster, movie.url)
            launchPlayer(PlayerActivity.vodIntent(app, request)).use { scenario ->
                log("vod launched state=${scenario.state}")
                val ready = waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                Thread.sleep(3_000)
                val textGroups = scenario.read { a -> a.tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT } }
                log("vod ${movie.name}: ready=$ready text_tracks=${textGroups.size} subtitles_off_by_default=${scenario.read { it.textDisabled }}")
                key(KeyEvent.KEYCODE_MENU); Thread.sleep(1_200)
                shot("20_vod_menu")
                key(KeyEvent.KEYCODE_BACK); Thread.sleep(500)
                textGroups.firstOrNull()?.let { group -> scenario.onActivity { it.selectTrack(group, 0) } }
                Thread.sleep(4_000)
                log("vod subtitle_enabled=${!scenario.read { it.textDisabled }} dropped=${scenario.read { it.stats.droppedFrames }}")
                shot("21_vod_subtitles")
                // Wie auf dem Fire TV: Indonesisch wählen, obwohl der Film nur englische Untertitel hat.
                // Erwartet: Live-Übersetzung der englischen Spur auf dem Gerät (Modell wird einmalig geladen).
                val englishLines = setOf("Hello, how are you?", "I told you we should never have come here.", "Where is my brother?",
                    "We have to leave before it gets dark.", "Thank you for everything.", "- Where is he?\n- I don't know.")
                fun translatedNow() = scenario.read { it.translatedCue }?.takeIf { it.isNotBlank() && it !in englishLines }
                scenario.onActivity { it.loadSubtitles("id") }
                val translated = waitFor(240_000) { translatedNow() != null }
                log(
                    "translate_id: ok=$translated target=${scenario.read { it.liveTranslateTarget }} " +
                        "cue=${scenario.read { it.translatedCue }?.replace('\n', ' ')} statuses=${scenario.read { it.statusHistory.toList() }}"
                )
                shot("23_vod_translated_id")
                assertTrue("Übersetzung nach Indonesisch fehlgeschlagen", translated)
                scenario.onActivity { it.loadSubtitles("de") }
                Thread.sleep(2_000)
                val german = waitFor(240_000) { scenario.read { it.liveTranslateTarget } == "de" && translatedNow() != null }
                log("translate_de: target=${scenario.read { it.liveTranslateTarget }} cue=${scenario.read { it.translatedCue }?.replace('\n', ' ')}")
                shot("24_vod_translated_de")
                assertTrue("Übersetzung nach Deutsch fehlgeschlagen", german)
                key(KeyEvent.KEYCODE_DPAD_RIGHT); Thread.sleep(1_500)
                log("vod after_seek position_ms=${scenario.read { it.player?.currentPosition }} message=${scenario.read { it.message }}")
                shot("22_vod_seek")
            }
        }
        if (movie == null) log("vod: kein Film gefunden")
        // 5. Serienfolge über Xtream abspielen
        episodes.firstOrNull()?.let { (episode, url) -> section("episode") {
            val series = seriesList.first()
            val request = VodRequest("${series.key}:e:${episode.id}", HistoryKind.EPISODE, "${series.name} S1E1", episode.title, null, url)
            launchPlayer(PlayerActivity.vodIntent(app, request)).use { scenario ->
                val ready = waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                Thread.sleep(2_000)
                log("episode ${episode.title}: ready=$ready message=${scenario.read { it.message }}")
                shot("30_episode")
            }
        } }
        // 5. Tonformate, die Fire TV ohne AV-Receiver nicht selbst dekodiert: FFmpeg muss sie abspielen
        val audioTests = runBlocking { container.db.vod().search("Tontest", 20) }
        log("audio_tests=${audioTests.size}")
        audioTests.forEach { movie -> section("audio ${movie.name}") {
            val request = VodRequest(movie.key, HistoryKind.VOD, movie.name, movie.category, null, movie.url)
            launchPlayer(PlayerActivity.vodIntent(app, request)).use { scenario ->
                val ready = waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                val start = scenario.read { it.player?.currentPosition } ?: 0L
                Thread.sleep(5_000)
                val position = scenario.read { it.player?.currentPosition } ?: 0L
                val stats = scenario.read { it.stats }
                val audioTracks = scenario.read { a -> a.tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.map { it.getTrackFormat(0).sampleMimeType } }
                log(
                    "audio ${movie.name}: ready=$ready advanced_ms=${position - start} audio=${stats.audioCodec}/${stats.audioChannels}ch " +
                        "decoder=${stats.audioDecoder} tracks=$audioTracks message=${scenario.read { it.message }} status=${scenario.read { it.subtitleStatus }}"
                )
                assertTrue("${movie.name} spielt nicht (ready=$ready, ${position - start} ms)", ready && position - start >= 3_000 && stats.audioDecoder.isNotEmpty())
            }
        } }

        // 5b. Übersetzung auf dem Gerät (OPUS-MT): echtes Modell laden, Qualität und Tempo messen
        section("mt_on_device") {
            val translator = de.ayutv.data.repo.Translators.onDevice
            val progress = mutableListOf<String>()
            translator.onProgress = { progress += it }
            val sentences = listOf("Hello, how are you?", "I told you we should never have come here.", "- Where is he?\n- I don't know.")
            val loadStart = System.currentTimeMillis()
            val german = runBlocking { translator.translate(sentences.take(1), "de", "en") }
            val firstMs = System.currentTimeMillis() - loadStart
            val runStart = System.currentTimeMillis()
            val more = runBlocking { translator.translate(sentences, "de", "en") }
            val perSentence = (System.currentTimeMillis() - runStart) / sentences.size
            log("mt de: first_ms=$firstMs per_sentence_ms=$perSentence result=${more.joinToString(" | ") { it.replace('\n', '/') }} progress_events=${progress.size}")
            assertTrue("Keine deutsche Übersetzung: $german", german.single().contains("Hallo") || german.single().contains("geht"))
            // Sprache ohne eigenes Modell: über das Mehrsprachen-Modell mit Sprachkennung
            val polish = runBlocking { translator.translate(listOf("Thank you for everything."), "pl", "en") }
            log("mt pl (en-mul): ${polish.single()}")
            assertTrue("Keine polnische Übersetzung", polish.single().isNotBlank() && polish.single() != "Thank you for everything.")
            log("mt installed=${translator.installedPairs()} bytes=${translator.installedBytes()}")
        }

        // 5c. Film ohne eingebettete Untertitel, aber mit (geladener) englischer Datei: vorausschauend übersetzen
        section("file_translation") {
            val movie = runBlocking { container.db.vod().search("Stummfilm", 5) }.firstOrNull() ?: error("Film ohne Untertitel fehlt")
            val english = listOf("Hello, how are you?", "Where is my brother?", "Thank you for everything.", "We have to leave before it gets dark.")
            val cues = (0 until 30).map { i ->
                val start = i * 4
                fun t(sec: Int) = "00:%02d:%02d,000".format(sec / 60, sec % 60)
                de.ayutv.data.parse.SrtCue(t(start), t(start + 3), english[i % english.size])
            }
            container.subtitles.saveTranslation(movie.key, "en", cues)
            val request = VodRequest(movie.key, HistoryKind.VOD, movie.name, movie.category, null, movie.url)
            launchPlayer(PlayerActivity.vodIntent(app, request)).use { scenario ->
                waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                scenario.onActivity { it.loadSubtitles("de") }
                val ok = waitFor(120_000) {
                    val cue = scenario.read { it.translatedCue }
                    cue != null && english.none { e -> cue == e } && cue.isNotBlank()
                }
                Thread.sleep(1_500)
                shot("25_file_translation_de")
                log("file_translation: ok=$ok cue=${scenario.read { it.translatedCue }?.replace('\n', '/')} target=${scenario.read { it.liveTranslateTarget }} statuses=${scenario.read { it.statusHistory.toList() }.takeLast(4)}")
                assertTrue("Datei-Übersetzung fehlgeschlagen", ok)
            }
        }

        // 6. Belastung: großer Anbieter (3000 Sender, 36.000 EPG-Einträge als .xml.gz)
        section("big") {
            val bigStart = System.currentTimeMillis()
            runBlocking {
                val id = container.playlists.add(
                    PlaylistEntity(name = "Groß", type = PlaylistType.M3U, url = "$base/playlist_big.m3u", epgUrl = "$base/epg_big.xml.gz")
                )
                container.playlists.sync(id)
            }
            val total = runBlocking { container.content.zapList(null, false) }.size
            val epgCount = runBlocking { container.db.programmes().observeCount().first() }
            val nowStart = System.currentTimeMillis()
            val now = runBlocking { container.content.nowPlaying(container.content.zapList(null, false)) }
            log(
                "big: import_ms=${nowStart - bigStart} channels_total=$total programmes_total=$epgCount " +
                    "now_playing_all_ms=${System.currentTimeMillis() - nowStart} now_found=${now.size} sync=${container.playlists.state.value}"
            )
        }
        // Einrichtung per Handy: Seite erreichbar, Formular speichert Werte
        section("phone_setup") {
            val server = de.ayutv.data.setup.PhoneSetupServer(container)
            server.start()
            try {
                assertTrue("Keine Adresse", waitFor(5_000) { server.state.value.url != null })
                val url = server.state.value.url!!
                val page = container.http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { it.code to it.body.string() }
                val form = okhttp3.FormBody.Builder().add("os_user", "ayu-ci").build()
                val saved = container.http.newCall(okhttp3.Request.Builder().url(url).post(form).build()).execute().use { it.code }
                val user = runBlocking { container.settings.current().openSubtitlesUser }
                log("phone_setup: url=$url get=${page.first} has_form=${"os_key" in page.second} post=$saved user=$user report=${server.state.value.lastSaved?.replace('\n', '|')}")
                assertTrue("Formular nicht gespeichert", page.first == 200 && saved == 200 && user == "ayu-ci")
                runBlocking { container.settings.update { it.copy(openSubtitlesUser = "") } }
            } finally {
                server.stop()
            }
        }

        // Suche zeigt alle Treffer (früher fest maximal 40)
        section("search_all") {
            val startedSearch = System.currentTimeMillis()
            val results = runBlocking { container.content.search("Sender") }
            log("search_all: channels=${results.channels.size} programmes=${results.programmes.size} ms=${System.currentTimeMillis() - startedSearch}")
            assertTrue("Suche begrenzt: ${results.channels.size}", results.channels.size >= 3000)
            runBlocking {
                container.settings.rememberSearch("Tagesschau")
                container.settings.rememberSearch("Sender")
                container.settings.rememberSearch("tagesschau")
            }
            val history = runBlocking { container.settings.searchHistory.first() }
            log("search_history=$history")
            assertTrue("Suchverlauf falsch: $history", history.take(2) == listOf("tagesschau", "Sender"))
        }

        // Playlist ausschalten: verschwindet überall, Daten bleiben; wieder einschalten: alles wieder da
        section("playlist_toggle") {
            val big = runBlocking { container.db.playlists().all() }.first { it.name == "Groß" }
            val before = runBlocking { container.content.zapList(null, false) }.size
            runBlocking { container.playlists.setEnabled(big.id, false) }
            val off = runBlocking { container.content.zapList(null, false) }.size
            val searchOff = runBlocking { container.content.search("Sender") }.channels.size
            runBlocking { container.playlists.setEnabled(big.id, true) }
            val on = runBlocking { container.content.zapList(null, false) }.size
            log("playlist_toggle: before=$before off=$off search_off=$searchOff on=$on")
            assertTrue("Ausschalten wirkt nicht", off < before && searchOff == 0 && on == before)
        }
        log("done failures=$failures")
        assertTrue("$failures Abschnitte fehlgeschlagen", failures == 0)
    }
}
