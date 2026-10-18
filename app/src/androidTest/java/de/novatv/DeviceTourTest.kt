package de.novatv

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.novatv.data.db.HistoryKind
import de.novatv.data.db.PlaylistEntity
import de.novatv.data.db.PlaylistType
import de.novatv.data.repo.XtreamClient
import de.novatv.player.PlayerActivity
import de.novatv.player.VodRequest
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
    private val app = ApplicationProvider.getApplicationContext<NovaApp>()
    // Interner Speicher: die CI holt ihn per "run-as" ab (zuverlässiger als Android/data unter Scoped Storage).
    private val out = File(app.filesDir, "ci").apply { deleteRecursively(); mkdirs() }
    private val results = StringBuilder()

    private fun log(line: String) {
        results.append(line).append('\n')
        File(out, "results.txt").writeText(results.toString())
        android.util.Log.i("NovaTour", line)
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
            android.util.Log.e("NovaTour", "Fehler in $name", t)
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
        val wrongLogin = runBlocking { container.playlists.verifyXtream(base, "nova", "falsch") }
        val login = runBlocking { container.playlists.verifyXtream(base, "nova", "test") }
        val xtreamStarted = System.currentTimeMillis()
        runBlocking {
            val id = container.playlists.add(
                PlaylistEntity(name = "Xtream-Test", type = PlaylistType.XTREAM, url = XtreamClient.normalizeServer(base), username = "nova", password = "test")
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
                        "hdr=${stats.hdr} message=${scenario.read { it.message }}"
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

        // 4. Film: Untertitel standardmäßig aus, eingebettete englische Spur einschalten
        val movie = runBlocking { container.db.vod().search("", 5) }.firstOrNull()
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
        log("done failures=$failures")
        assertTrue("$failures Abschnitte fehlgeschlagen", failures == 0)
    }
}
