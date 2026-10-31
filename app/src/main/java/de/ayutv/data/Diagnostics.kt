package de.ayutv.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import de.ayutv.AppContainer
import de.ayutv.BuildConfig
import de.ayutv.NativeCrashLog
import de.ayutv.data.repo.Translators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fehlerbericht per Knopfdruck: Gerät, Speicher, Einstellungen, Übersetzer, TV-Guide, die letzte
 * Wiedergabe und das eigene Systemprotokoll in einer Textdatei. Ohne Zugangsdaten: Adressen nur mit
 * Server, Passwörter, Schlüssel und lange Kennungen werden unkenntlich gemacht.
 */
object Diagnostics {
    private const val PLAYER_FILE = "player_session.txt"
    private const val SELFTEST_FILE = "last_selftest.txt"
    private const val CRASH_FILE = "last_crash_report.txt"

    /** Letzte Wiedergabe für den nächsten Bericht merken (Statusmeldungen, Technik, Abbrüche). */
    fun savePlayerSession(context: Context, title: String, lines: List<String>) {
        val time = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date())
        runCatching { File(context.filesDir, PLAYER_FILE).writeText("$time · $title\n" + lines.takeLast(200).joinToString("\n")) }
    }

    fun saveSelfTest(context: Context, result: String) {
        runCatching { File(context.filesDir, SELFTEST_FILE).writeText(result) }
    }

    /** Absturzmeldung aufheben, damit sie auch nach dem Anzeigen im Bericht steht. */
    fun saveCrash(context: Context, report: String) {
        runCatching { File(context.filesDir, CRASH_FILE).writeText(report) }
    }

    suspend fun build(context: Context, container: AppContainer): String = withContext(Dispatchers.IO) {
        val out = StringBuilder()
        fun section(title: String) = out.append("\n== ").append(title).append(" ==\n")
        fun file(name: String) = runCatching { File(context.filesDir, name).takeIf { it.isFile }?.readText() }.getOrNull()

        out.append("DnA-TV Fehlerbericht ").append(SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date())).append('\n')
        out.append("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        // Wann zuerst installiert und zuletzt aktualisiert: Liegt „installiert“ beim letzten Update, wurde die App neu
        // installiert (vorher deinstalliert) und alle eigenen Daten waren deshalb weg.
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val format = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)
            out.append("Installiert ${format.format(Date(info.firstInstallTime))}, zuletzt aktualisiert ${format.format(Date(info.lastUpdateTime))}\n")
        }
        out.append("Gerät ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.joinToString()}\n")
        runCatching {
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
            val disk = StatFs(context.filesDir.path)
            out.append("Arbeitsspeicher ${memory.totalMem / MB} MB, frei ${memory.availMem / MB} MB" + (if (memory.lowMemory) " (knapp)" else "") + '\n')
            out.append("Speicher frei ${disk.availableBytes / MB} MB\n")
        }
        out.append("Zeitzone ${java.util.TimeZone.getDefault().id}\n")

        section("Oberfläche")
        out.append("Menüs: ${de.ayutv.ui.UiPerformance.summary()}\n")

        section("Einstellungen")
        runCatching {
            val s = container.settings.current()
            out.append("Puffer ${s.bufferProfile}, Live ${s.liveFormat}, Tunneling ${s.tunneling}, asynchron ${s.asyncDecoding}, Bildrate ${s.matchFrameRate}, Ton ${s.audioOutput}\n")
            out.append("Untertitel ${s.subtitleLanguage}, automatisch ${s.subtitlesAutoOn}, OpenSubtitles ${s.onlineSubtitles}" +
                " (eigener Schlüssel ${s.openSubtitlesKey.isNotBlank()}, Konto ${s.openSubtitlesUser.isNotBlank()})\n")
            out.append("Übersetzung ${s.translationProvider}, zuletzt ${s.lastTranslationTarget.ifEmpty { "-" }}" +
                (if (s.translationProvider == de.ayutv.data.TranslationProvider.DEEPL) ", DeepL-Schlüssel ${s.deeplKey.isNotBlank()}" +
                    (de.ayutv.data.translate.DeepLTranslator.problem?.let { ", zuletzt: $it" } ?: "") else "") + "\n")
            out.append("Zeitversatz ${s.liveTimeshift} (${s.timeshiftMinutes} min), TV-Guide eigener ${s.ownGuideOnly}, frei ${s.internetGuide}, Verschiebung ${s.epgShiftHours} h\n")
            out.append("Vereine ${s.followedTeams.size}, Stichwörter ${s.alertKeywords.size}, Handy-Fernbedienung ${s.phoneRemote}\n")
            out.append("Proxy ${s.proxyMode}" + (if (s.proxyMode != de.ayutv.data.net.ProxyMode.OFF) " ${s.proxyType}, Anmeldung ${s.proxyUser.isNotBlank()}, Playlists ${s.proxyPlaylists.size}" else "") +
                (de.ayutv.data.net.AppProxy.lastFailure?.let { ", zuletzt: ${it.message}" } ?: "") + "\n")
        }

        section("Playlists")
        runCatching {
            for (p in container.db.playlists().all()) {
                val status = container.playlists.epgStatus(p.id)
                val limit = container.accountLimits.maxConnections(p.id)
                out.append("${p.name} (Nr. ${p.id}): ${p.type}, ${if (p.enabled) "an" else "aus"}, Server ${host(p.url)}" +
                    (limit?.let { ", höchstens $it Verbindungen" } ?: "") +
                    (status?.let { ", TV-Guide ${it.withGuide}/${it.total} Sender (${it.sources.joinToString { s -> "${s.first} ${s.second}" }})" } ?: "") + '\n')
            }
        }

        // Sender ohne TV-Guide (je Gruppe), damit fehlende Zuordnungen gezielt verbessert werden können.
        section("Sender ohne TV-Guide")
        runCatching {
            for (p in container.db.playlists().all()) {
                val status = container.playlists.epgStatus(p.id) ?: continue
                if (status.missingChannels.isEmpty()) continue
                out.append("${p.name} (Nr. ${p.id}): ${status.total - status.withGuide} von ${status.total}\n")
                status.missingInGroups.groupBy({ it.first }, { it.second }).entries.take(40).forEach { (group, names) ->
                    out.append("  ${group.ifEmpty { "-" }}: ${names.distinct().take(40).joinToString(", ")}${if (names.size > 40) " … (+${names.size - 40})" else ""}\n")
                }
            }
        }

        section("Eigene Daten")
        runCatching {
            val db = container.db
            val items = db.customGroups().allItems()
            out.append("Favoriten ${db.favorites().keys().size}, eigene Gruppen ${db.customGroups().groups().size} mit ${items.size} Sendern" +
                " (davon ohne passenden Sender ${db.customGroups().orphanItems()}), ausgeblendete Gruppen ${db.hiddenGroups().all().size}\n")
            // Schlüssel beginnen mit der Playlist-Nummer: zeigt, ob Einträge zu einer Playlist gehören, die es nicht mehr gibt.
            val prefixes = (items.map { it.key } + db.favorites().keys()).map { it.substringBefore(':') }.groupingBy { it }.eachCount()
            if (prefixes.isNotEmpty()) out.append("Einträge je Playlist-Nr.: ${prefixes.entries.joinToString { "${it.key}: ${it.value}" }}\n")
        }

        section("Übersetzer")
        runCatching {
            out.append("Rechenkern ${Translators.remote.engine.label}\n")
            val pairs = Translators.onDevice.installedPairs()
            out.append("Modelle ${pairs.joinToString().ifEmpty { "keine" }} (${Translators.onDevice.installedBytes() / 1_000_000} MB)\n")
            Translators.remote.lastPeakMb?.let { out.append("Speicher höchstens $it MB\n") }
            out.append("Handy: ${de.ayutv.data.translate.PhoneHelper.describe()}\n")
        }
        file(SELFTEST_FILE)?.let { out.append("Letzter Test:\n").append(it).append('\n') }
        NativeCrashLog.last(context.filesDir)?.let { out.append("Letzter nativer Absturz:\n").append(it).append('\n') }

        file(CRASH_FILE)?.let { section("Letzter Absturz"); out.append(it).append('\n') }
        file(PLAYER_FILE)?.let { section("Letzte Wiedergabe"); out.append(it).append('\n') }

        section("Protokoll")
        out.append(logcat())
        Redaction.apply(out.toString(), secrets(container))
    }

    /** Alle eigenen Zugangsdaten im Klartext, damit sie im Bericht garantiert nirgends stehen. */
    private suspend fun secrets(container: AppContainer): List<String> {
        val out = ArrayList<String>()
        runCatching {
            for (p in container.db.playlists().all()) {
                p.username?.let { out += it }
                p.password?.let { out += (runCatching { de.ayutv.data.security.Credentials.reveal(it) }.getOrNull() ?: it) }
            }
        }
        runCatching {
            val s = container.settings.current()
            out += listOf(s.openSubtitlesUser, s.openSubtitlesPassword, s.openSubtitlesApiKey, s.proxyUser, s.proxyPassword, s.deeplKey)
        }
        return out
    }

    /** Eigenes Systemprotokoll (Android zeigt jeder App nur ihre eigenen Zeilen). */
    private fun logcat(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "1500").redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor()
        lines.filterNot { NOISE.containsMatchIn(it) }.takeLast(900).joinToString("\n") { it.take(400) }
    }.getOrElse { "nicht lesbar: ${it.message}" }

    private val NOISE = Regex("""Choreographer|OpenGLRenderer|ViewRootImpl|InputEventReceiver|chatty|BufferQueue""")

    private fun host(url: String): String = runCatching { java.net.URI(url.trim()).host }.getOrNull() ?: "?"

    private const val MB = 1024L * 1024L
}
