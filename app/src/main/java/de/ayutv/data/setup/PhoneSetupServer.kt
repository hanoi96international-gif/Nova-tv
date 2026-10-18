package de.ayutv.data.setup

import android.util.Log
import de.ayutv.AppContainer
import de.ayutv.data.AppSettings
import de.ayutv.data.db.PlaylistEntity
import de.ayutv.data.db.PlaylistType
import de.ayutv.data.repo.XtreamClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * Einrichtung per Handy: Der Fire TV öffnet im Heimnetz eine kleine Webseite. Dort fügt man
 * API-Schlüssel und Playlists bequem per Kopieren & Einfügen ein, statt sie mit der Fernbedienung
 * zu tippen. Läuft nur, solange die Einstellungen offen sind, und nur mit der angezeigten PIN.
 */
class PhoneSetupServer(private val container: AppContainer) {
    data class State(val url: String? = null, val lastSaved: String? = null, val error: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var socket: ServerSocket? = null
    private val pin = (100_000 + SecureRandom().nextInt(900_000)).toString()

    fun start() {
        if (scope != null) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        newScope.launch {
            val server = runCatching { ServerSocket(PORT) }.recoverCatching { ServerSocket(0) }.getOrElse {
                _state.value = State(error = "Einrichtung per Handy nicht möglich: ${it.message}")
                return@launch
            }
            socket = server
            val ip = localAddress()
            _state.value = State(url = ip?.let { "http://$it:${server.localPort}/$pin" }, error = if (ip == null) "Kein WLAN/LAN gefunden" else null)
            while (true) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                launch { client.use { handle(it) } }
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        scope?.cancel()
        scope = null
    }

    private suspend fun handle(client: Socket) {
        client.soTimeout = 15_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val (method, path) = requestLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        var length = 0
        while (true) {
            val header = readLine(input) ?: return
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        if (path.substringBefore('?').trimEnd('/') != "/$pin") {
            respond(client, 404, "<h1>Falsche Adresse</h1><p>Bitte die Adresse genau so eingeben, wie sie auf dem Fernseher steht.</p>")
            return
        }
        if (method == "POST") {
            val body = ByteArray(length.coerceAtMost(64 * 1024)).also { readFully(input, it) }.toString(Charsets.UTF_8)
            val form = parseForm(body)
            val report = save(form)
            respond(client, 200, page(container.settings.current(), report))
        } else {
            respond(client, 200, page(container.settings.current(), null))
        }
    }

    /** Übernimmt nur ausgefüllte Felder: Leere Felder lassen gespeicherte Werte unverändert. */
    private suspend fun save(form: Map<String, String>): List<KeyCheck.Line> {
        fun field(name: String) = form[name]?.trim().orEmpty()
        container.settings.update { current ->
            var next = current
            field("os_key").takeIf { it.isNotEmpty() }?.let { next = next.copy(openSubtitlesKey = it) }
            field("os_user").takeIf { it.isNotEmpty() }?.let { next = next.copy(openSubtitlesUser = it) }
            form["os_password"]?.takeIf { it.isNotEmpty() }?.let { next = next.copy(openSubtitlesPassword = it) }
            next
        }
        val lines = mutableListOf<KeyCheck.Line>()
        val m3u = field("m3u_url")
        if (m3u.isNotEmpty()) {
            val id = container.playlists.add(
                PlaylistEntity(name = field("m3u_name").ifEmpty { "Playlist" }, type = PlaylistType.M3U, url = m3u, epgUrl = field("epg_url").ifEmpty { null })
            )
            container.scope.launch { container.playlists.sync(id) }
            lines += KeyCheck.Line(true, "Playlist hinzugefügt, wird jetzt geladen")
        }
        val server = field("xt_server")
        if (server.isNotEmpty()) {
            val normalized = XtreamClient.normalizeServer(server)
            val error = container.playlists.verifyXtream(normalized, field("xt_user"), field("xt_password"))
            if (error == null) {
                val id = container.playlists.add(
                    PlaylistEntity(
                        name = field("xt_name").ifEmpty { "Xtream" },
                        type = PlaylistType.XTREAM,
                        url = normalized,
                        username = field("xt_user"),
                        password = field("xt_password"),
                    )
                )
                container.scope.launch { container.playlists.sync(id) }
                lines += KeyCheck.Line(true, "Xtream-Zugang hinzugefügt, wird jetzt geladen")
            } else {
                lines += KeyCheck.Line(false, "Xtream: $error")
            }
        }
        lines += KeyCheck.run(container.http, container.settings.current())
        _state.value = _state.value.copy(lastSaved = lines.joinToString("\n") { (if (it.ok) "✓ " else "✗ ") + it.text })
        return lines
    }

    private fun page(settings: AppSettings, report: List<KeyCheck.Line>?): String {
        fun status(set: Boolean) = if (set) "<span class=ok>gespeichert</span>" else "<span class=no>fehlt</span>"
        val result = report?.joinToString("") { "<li class=${if (it.ok) "ok" else "no"}>${if (it.ok) "✓" else "✗"} ${esc(it.text)}</li>" }
            ?.let { "<h2>Ergebnis</h2><ul>$it</ul>" }.orEmpty()
        return """
            <h1>DnA-TV einrichten</h1>
            $result
            <form method=post>
            <h2>Untertitel aus dem Internet (optional): OpenSubtitles ${status(settings.openSubtitlesApiKey.isNotBlank())}</h2>
            <p>Untertitel für fast jeden Film in fast jeder Sprache (auch automatisch übersetzte).
            Kostenloses Konto auf <a href="https://www.opensubtitles.com" target=_blank>opensubtitles.com</a> anlegen,
            dann <a href="https://www.opensubtitles.com/consumers" target=_blank>opensubtitles.com/consumers</a> →
            „New consumer“ → Name z. B. „DnA-TV“ → API-Key kopieren.</p>
            <label>API-Key<input name=os_key autocomplete=off placeholder="${if (settings.openSubtitlesApiKey.isNotBlank()) "eingebaut – nur für einen eigenen Schlüssel ausfüllen" else "API-Key einfügen"}"></label>
            <label>Benutzername (empfohlen: 20 statt 5 Downloads pro Tag)<input name=os_user autocomplete=username value="${esc(settings.openSubtitlesUser)}"></label>
            <label>Passwort<input name=os_password type=password autocomplete=current-password placeholder="${if (settings.openSubtitlesPassword.isNotEmpty()) "gespeichert" else ""}"></label>

            <p>Übersetzen braucht nichts davon: DnA-TV übersetzt vorhandene Untertitel direkt auf dem Fire TV.
            OpenSubtitles liefert Untertitel für Filme, die selbst keine haben.</p>

            <h2>Playlist hinzufügen (optional)</h2>
            <label>Name<input name=m3u_name placeholder="z. B. Mein Anbieter"></label>
            <label>M3U-Adresse<input name=m3u_url type=url placeholder="http://…/get.php?…"></label>
            <label>EPG-Adresse (optional)<input name=epg_url type=url></label>
            <p>oder Xtream Codes:</p>
            <label>Name<input name=xt_name></label>
            <label>Server<input name=xt_server placeholder="http://server:port"></label>
            <label>Benutzername<input name=xt_user autocomplete=off></label>
            <label>Passwort<input name=xt_password autocomplete=off></label>

            <button>Speichern und prüfen</button>
            </form>
        """.trimIndent()
    }

    private fun respond(client: Socket, code: Int, body: String) {
        val html = """<!doctype html><html lang=de><head><meta charset=utf-8>
            <meta name=viewport content="width=device-width,initial-scale=1"><title>DnA-TV einrichten</title>
            <style>body{font-family:system-ui,sans-serif;background:#0d0f14;color:#e8eaf0;margin:0 auto;max-width:640px;padding:16px}
            h1{color:#8fb4ff}h2{font-size:1.05em;margin-top:28px}p{color:#aab0bd;line-height:1.45}a{color:#8fb4ff}
            label{display:block;margin:10px 0;color:#c8ccd6}input{display:block;width:100%;box-sizing:border-box;margin-top:4px;padding:12px;
            border-radius:8px;border:1px solid #333a48;background:#171a22;color:#fff;font-size:16px}
            button{margin:24px 0 40px;width:100%;padding:14px;border:0;border-radius:10px;background:#4f7cff;color:#fff;font-size:17px}
            .ok{color:#5fd38d}.no{color:#ff8a7a}ul{padding-left:18px;line-height:1.6}</style></head><body>$body</body></html>"""
        val bytes = html.toByteArray(Charsets.UTF_8)
        val status = if (code == 200) "OK" else "Not Found"
        runCatching {
            client.getOutputStream().apply {
                write("HTTP/1.1 $code $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes)
                flush()
            }
        }.onFailure { Log.w(TAG, "Antwort fehlgeschlagen", it) }
    }

    private fun readLine(input: InputStream): String? {
        val out = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (out.isEmpty()) null else out.toString()
            if (c == '\n'.code) return out.toString().trimEnd('\r')
            if (out.length > 8192) throw IOException("Zeile zu lang")
            out.append(c.toChar())
        }
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var read = 0
        while (read < target.size) {
            val n = input.read(target, read, target.size - read)
            if (n < 0) break
            read += n
        }
    }

    private fun parseForm(body: String): Map<String, String> = body.split('&').mapNotNull { pair ->
        val name = pair.substringBefore('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
    }.toMap()

    private fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private companion object {
        const val TAG = "PhoneSetup"
        const val PORT = 8095
    }
}
