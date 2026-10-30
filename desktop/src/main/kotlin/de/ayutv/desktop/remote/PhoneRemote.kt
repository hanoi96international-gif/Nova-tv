package de.ayutv.desktop.remote

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.ayutv.desktop.data.Store
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.Window
import java.awt.event.KeyEvent
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URLDecoder
import java.util.concurrent.Executors
import javax.swing.SwingUtilities

/**
 * Handy als Fernbedienung: kleine Webseite im Heimnetz (http://<PC>:8096/<PIN>), keine App nötig.
 * Tasten gehen direkt an das DnA-TV-Fenster (nicht an andere Programme), Sender lassen sich suchen und starten.
 */
class PhoneRemote(private val store: Store, private val onPlay: (String) -> Unit) {
    private var server: HttpServer? = null
    @Volatile var window: Window? = null

    val running: Boolean get() = server != null

    /** Adresse für das Handy (null = aus oder kein Netz). */
    fun address(): String? {
        if (server == null) return null
        val ip = localAddress() ?: return null
        return "http://$ip:$PORT/${pin()}"
    }

    fun pin(): String = store.user.value.remotePin.ifEmpty {
        val fresh = (1000..9999).random().toString()
        store.updateUser { it.copy(remotePin = fresh) }
        fresh
    }

    @Synchronized
    fun start() {
        if (server != null) return
        val http = HttpServer.create(InetSocketAddress(PORT), 0)
        http.executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "phone-remote").apply { isDaemon = true } }
        http.createContext("/") { exchange -> runCatching { handle(exchange) }.onFailure { exchange.reply(500, "text/plain", "Fehler") } }
        http.start()
        server = http
    }

    @Synchronized
    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun handle(exchange: HttpExchange) {
        val parts = exchange.requestURI.path.trim('/').split('/')
        if (parts.firstOrNull() != pin()) return exchange.reply(403, "text/plain", "Falsche Adresse. Bitte die Adresse vom PC eingeben.")
        val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { '=' in it }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        when (parts.getOrNull(1)) {
            null, "" -> exchange.reply(200, "text/html; charset=utf-8", PAGE)
            "key" -> {
                val code = KEYS[query["k"]] ?: return exchange.reply(400, "text/plain", "unbekannte Taste")
                press(code)
                exchange.reply(204, "text/plain", "")
            }
            "channels" -> {
                val q = query["q"].orEmpty().lowercase()
                val list = store.channels().filter { q.isEmpty() || it.name.lowercase().contains(q) }.take(60)
                val json = JsonArray(list.map { c -> buildJsonObject { put("key", c.key); put("name", c.name); put("group", c.group) } })
                exchange.reply(200, "application/json; charset=utf-8", json.toString())
            }
            "play" -> {
                val key = query["key"] ?: return exchange.reply(400, "text/plain", "kein Sender")
                SwingUtilities.invokeLater { window?.toFront(); onPlay(key) }
                exchange.reply(204, "text/plain", "")
            }
            else -> exchange.reply(404, "text/plain", "nicht gefunden")
        }
    }

    /** Taste an das DnA-TV-Fenster schicken (gedrückt und losgelassen). */
    private fun press(code: Int) = SwingUtilities.invokeLater {
        val w = window ?: return@invokeLater
        val target = w.mostRecentFocusOwner ?: w
        val now = System.currentTimeMillis()
        target.dispatchEvent(KeyEvent(target, KeyEvent.KEY_PRESSED, now, 0, code, KeyEvent.CHAR_UNDEFINED))
        target.dispatchEvent(KeyEvent(target, KeyEvent.KEY_RELEASED, now + 30, 0, code, KeyEvent.CHAR_UNDEFINED))
    }

    private fun HttpExchange.reply(code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", type)
        responseHeaders.add("Cache-Control", "no-store")
        sendResponseHeaders(code, if (code == 204) -1 else bytes.size.toLong())
        if (code != 204) responseBody.use { it.write(bytes) } else close()
    }

    companion object {
        const val PORT = 8096

        private val KEYS = mapOf(
            "up" to KeyEvent.VK_UP, "down" to KeyEvent.VK_DOWN, "left" to KeyEvent.VK_LEFT, "right" to KeyEvent.VK_RIGHT,
            "ok" to KeyEvent.VK_ENTER, "back" to KeyEvent.VK_ESCAPE, "menu" to KeyEvent.VK_CONTEXT_MENU,
            "play" to KeyEvent.VK_SPACE, "volup" to KeyEvent.VK_PLUS, "voldown" to KeyEvent.VK_MINUS, "mute" to KeyEvent.VK_M,
            "chup" to KeyEvent.VK_PAGE_UP, "chdown" to KeyEvent.VK_PAGE_DOWN, "full" to KeyEvent.VK_F11,
            "subs" to KeyEvent.VK_L, "audio" to KeyEvent.VK_T, "next" to KeyEvent.VK_N,
        )

        /** Adresse des PCs im Heimnetz (192.168.x bevorzugt). */
        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { it.hostAddress }
                .sortedBy { if (it.startsWith("192.168.")) 0 else 1 }
                .firstOrNull()
        }.getOrNull()

        private val PAGE = """
<!doctype html><html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<title>DnA-TV Fernbedienung</title>
<style>
:root{color-scheme:dark}body{margin:0;background:#0B0F14;color:#F2F5F8;font:16px system-ui,sans-serif;padding:16px;max-width:440px;margin:auto}
h1{font-size:20px;margin:4px 0 14px}.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}
button{background:#1E2631;color:#F2F5F8;border:0;border-radius:14px;font-size:20px;padding:18px 0;touch-action:manipulation}
button:active{background:#235F8E}.ok{background:#235F8E;font-weight:600}.row{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-top:10px}
.small{font-size:15px}input{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #1E2631;background:#151B23;color:#F2F5F8;font-size:16px;margin-top:18px}
li{list-style:none;padding:12px;border-bottom:1px solid #1E2631}ul{padding:0;margin:6px 0}li small{color:#9AA7B4;display:block}
</style></head><body>
<h1>DnA-TV Fernbedienung</h1>
<div class="grid">
<button class="small" onclick="k('menu')">☰ Menü</button><button onclick="k('up')">▲</button><button class="small" onclick="k('back')">↩ Zurück</button>
<button onclick="k('left')">◀</button><button class="ok" onclick="k('ok')">OK</button><button onclick="k('right')">▶</button>
<button class="small" onclick="k('chup')">Sender +</button><button onclick="k('down')">▼</button><button class="small" onclick="k('chdown')">Sender −</button>
</div>
<div class="row">
<button onclick="k('play')">⏯</button><button onclick="k('voldown')">🔉</button><button onclick="k('volup')">🔊</button><button onclick="k('mute')">🔇</button>
<button class="small" onclick="k('subs')">UT</button><button class="small" onclick="k('audio')">Ton</button><button class="small" onclick="k('next')">⏭</button><button class="small" onclick="k('full')">⛶</button>
</div>
<input id="q" placeholder="Sender suchen …" oninput="s()">
<ul id="list"></ul>
<script>
const base=location.pathname.replace(/\/$/,'');
function k(n){fetch(base+'/key?k='+n,{method:'POST'});if(navigator.vibrate)navigator.vibrate(15)}
let t;function s(){clearTimeout(t);t=setTimeout(async()=>{const q=document.getElementById('q').value;
const r=await fetch(base+'/channels?q='+encodeURIComponent(q));const l=await r.json();
document.getElementById('list').innerHTML='';for(const c of l){const li=document.createElement('li');
li.textContent=c.name;const g=document.createElement('small');g.textContent=c.group;li.appendChild(g);
li.onclick=()=>fetch(base+'/play?key='+encodeURIComponent(c.key),{method:'POST'});document.getElementById('list').appendChild(li)}},250)}
</script></body></html>
        """.trimIndent()
    }
}
