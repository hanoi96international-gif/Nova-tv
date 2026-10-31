package de.ayutv.desktop

import de.ayutv.desktop.player.PlayerConfig
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
            // Start zweimal messen: beim ersten Mal legt VLC den Plugin-Zwischenspeicher an, danach muss es schnell gehen.
            val first = System.currentTimeMillis()
            VideoPlayer().release()
            val firstMs = System.currentTimeMillis() - first
            val second = System.currentTimeMillis()
            val player = VideoPlayer(PlayerConfig.EVERYTHING)
            val secondMs = System.currentTimeMillis() - second
            lines += "vlc=${player.vlcVersion ?: "fehlt"} ${player.error.orEmpty()}".trim()
            // Jede Einstellung aus den Einstellungen muss VLC kennen (sonst startet es mit ihr gar nicht).
            var optionsOk = true
            for (variant in PlayerConfig.VARIANTS) {
                val probe = VideoPlayer(variant)
                lines += "options ${variant.vlcOptions().joinToString(" ")}: " + (probe.configProblem ?: if (probe.available) "OK" else "fehlt")
                if (probe.configProblem != null || !probe.available) optionsOk = false
                probe.release()
            }
            lines += "vlc_start_ms first=$firstMs second=$secondMs"
            if (player.available) {
                val image = File.createTempFile("dna-tv-test", ".png")
                val picture = BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB)
                picture.createGraphics().apply { color = Color(0x3DA9FC); fillRect(0, 0, 640, 360); dispose() }
                ImageIO.write(picture, "png", image)
                // Pfad direkt übergeben (VLC versteht unter Windows „file:/D:/…“ nicht, nur „file:///D:/…“).
                player.play(image.absolutePath, null, 0, ":image-duration=10")
                val until = System.currentTimeMillis() + 30_000
                while (player.framesRendered.get() == 0L && System.currentTimeMillis() < until) Thread.sleep(200)
                Thread.sleep(300)
                lines += "frames=${player.framesRendered.get()} playing=${player.playing} error=${player.error}"
                ok = player.framesRendered.get() > 0 && optionsOk
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
