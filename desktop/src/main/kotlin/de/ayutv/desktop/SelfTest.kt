package de.ayutv.desktop

import de.ayutv.desktop.player.VideoPlayer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * Starttest für die CI (`DnA-TV.exe --selftest <Datei>`): mitgeliefertes VLC laden, ein selbst erzeugtes Testbild
 * abspielen und prüfen, dass Bilder bei der Oberfläche ankommen. Ergebnis zeilenweise in die Datei.
 */
object SelfTest {
    fun run(output: String?) {
        val lines = ArrayList<String>()
        var ok = false
        try {
            // Fire-TV-Fernbedienung: Raw Input und Tastatur-Hook unter Windows einrichten (ohne Taste zu drücken).
            if (de.ayutv.desktop.input.WindowsRemote.isWindows) {
                val remote = de.ayutv.desktop.input.WindowsRemote(onButton = { _, _, _ -> }, isForeground = { false })
                remote.start()
                Thread.sleep(1_500)
                lines += "remote=" + (remote.problem?.let { "FEHLER $it" } ?: if (remote.running) "OK" else "beendet")
                remote.stop()
            }
            val player = VideoPlayer()
            lines += "video=" + if (player.gpuVideo) "gpu" else "rgb"
            lines += "vlc=${player.vlcVersion ?: "fehlt"} ${player.error.orEmpty()}".trim()
            if (player.available) {
                val image = File.createTempFile("dna-tv-test", ".png")
                val picture = BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB)
                picture.createGraphics().apply { color = Color(0x3DA9FC); fillRect(0, 0, 640, 360); dispose() }
                ImageIO.write(picture, "png", image)
                // Jeder Videoweg einmal: Automatisch (NV12 bei 8 Bit), 10 Bit (P010) und Kompatibel (I420).
                val layouts = if (player.gpuVideo) listOf("AUTO", "P010", "I420") else listOf("AUTO")
                ok = true
                for (mode in layouts) {
                    player.videoLayout = mode
                    val before = player.framesRendered.get()
                    // Pfad direkt übergeben (VLC versteht unter Windows „file:/D:/…“ nicht, nur „file:///D:/…“).
                    player.play(image.absolutePath, null, 0, ":image-duration=10")
                    val until = System.currentTimeMillis() + 30_000
                    while (player.framesRendered.get() <= before && System.currentTimeMillis() < until) Thread.sleep(200)
                    Thread.sleep(500)
                    val frames = player.framesRendered.get() - before
                    lines += "[$mode] frames=$frames layout=${player.layout} playing=${player.playing} error=${player.error}"
                    ok = ok && frames > 0
                    // Schneller Weg: das Testbild (Blau 3DA9FC) über den Shader zeichnen und die Farbe prüfen.
                    if (player.gpuVideo) {
                        // Auf dem Oberflächen-Thread zeichnen, wie in der App (dort werden alte Bilder freigegeben).
                        var color: Triple<Int, Int, Int>? = null
                        var drawn: String? = null
                        javax.swing.SwingUtilities.invokeAndWait { color = player.currentFrame()?.let { f ->
                            drawn = f.layout.name
                            val info = org.jetbrains.skia.ImageInfo(64, 36, org.jetbrains.skia.ColorType.RGBA_8888, org.jetbrains.skia.ColorAlphaType.PREMUL)
                            val surface = org.jetbrains.skia.Surface.makeRaster(info)
                            f.draw(surface.canvas, 64f, 36f)
                            val px = org.jetbrains.skia.Bitmap.makeFromImage(surface.makeImageSnapshot()).readPixels(info, 64 * 4, 0, 0)
                            px?.let { val at = (18 * 64 + 32) * 4; Triple(it[at].toInt() and 0xFF, it[at + 1].toInt() and 0xFF, it[at + 2].toInt() and 0xFF) }
                        } }
                        val c = color
                        lines += "[$mode] pixel=${c?.toList()} frame=$drawn (erwartet etwa [61, 169, 252])"
                        ok = ok && c != null && kotlin.math.abs(c.first - 61) < 25 && kotlin.math.abs(c.second - 169) < 25 && kotlin.math.abs(c.third - 252) < 25
                    }
                    player.stop()
                }
                // Direktausgabe (HDR-Bildschirm): VLC zeichnet selbst ins Vollbildfenster. Nur zur Info, kein Abbruch.
                if (de.ayutv.desktop.input.WindowsRemote.isWindows && !java.awt.GraphicsEnvironment.isHeadless()) {
                    runCatching {
                        javax.swing.SwingUtilities.invokeAndWait { player.directWindow = de.ayutv.desktop.player.DirectWindow({ null }, {}, {}) }
                        player.direct = true
                        val before = player.framesRendered.get()
                        javax.swing.SwingUtilities.invokeAndWait { player.play(image.absolutePath, null, 0, ":image-duration=10") }
                        val until = System.currentTimeMillis() + 15_000
                        while (player.framesRendered.get() <= before && System.currentTimeMillis() < until) Thread.sleep(200)
                        lines += "direct: running=${player.framesRendered.get() > before} playing=${player.playing} error=${player.error}"
                        javax.swing.SwingUtilities.invokeAndWait { player.stop() }
                        player.direct = false
                    }.onFailure { lines += "direct: FEHLER $it" }
                }
                player.release()
                image.delete()
            }
        } catch (t: Throwable) {
            lines += "error=$t"
        }
        lines += "result=" + if (ok) "OK" else "FEHLER"
        val text = lines.joinToString("\n")
        if (output != null) File(output).writeText(text) else println(text)
        exitProcess(if (ok) 0 else 1)
    }
}
