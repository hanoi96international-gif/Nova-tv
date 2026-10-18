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

        // 2. Oberfläche per Fernbedienung durchgehen
        ActivityScenario.launch(MainActivity::class.java).use {
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
        }

        // 3. Live: Start, Zappen, Senderliste, Menü
        val live = channels.filter { "/movie/" !in it.url }
        live.forEachIndexed { index, channel ->
            ActivityScenario.launch<PlayerActivity>(PlayerActivity.liveIntent(app, channel.key, null)).use { scenario ->
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
        }

        // 4. Film: Untertitel standardmäßig aus, eingebettete englische Spur einschalten
        val movie = runBlocking { container.db.vod().search("", 5) }.firstOrNull()
        if (movie != null) {
            val request = VodRequest(movie.key, HistoryKind.VOD, movie.name, movie.category, movie.poster, movie.url)
            ActivityScenario.launch<PlayerActivity>(PlayerActivity.vodIntent(app, request)).use { scenario ->
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
        } else {
            log("vod: kein Film gefunden")
        }
        log("done")
    }
}
