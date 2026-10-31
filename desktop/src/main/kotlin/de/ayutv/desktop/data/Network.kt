package de.ayutv.desktop.data

import de.ayutv.data.net.AppProxy
import de.ayutv.data.net.ProxyConfig
import de.ayutv.data.net.ProxyMode
import de.ayutv.data.net.ProxyType
import okhttp3.OkHttpClient
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Netz für die ganze App: ein gemeinsamer OkHttp-Client mit dem Proxy der Fire-TV-App ([AppProxy]: alle Verbindungen
 * oder nur die ausgewählter Playlists, HTTP oder SOCKS5, Heimnetz nie über den Proxy). VLC bekommt denselben Proxy
 * je Stream als Option.
 */
object Network {
    init {
        // SOCKS5 mit Anmeldung: Java holt die Zugangsdaten über den systemweiten Authenticator.
        java.net.Authenticator.setDefault(AppProxy.socksAuthenticator)
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .proxySelector(AppProxy.selector)
        .proxyAuthenticator(AppProxy.authenticator)
        .addNetworkInterceptor(AppProxy.interceptor)
        .build()

    /** Proxy-Einstellungen anwenden; bei „Nur ausgewählte Playlists“ deren Server (Playlist, TV-Guide, Streams). */
    fun apply(store: Store) {
        val config = store.user.value.proxy.config()
        val hosts = if (config.active && config.mode == ProxyMode.SELECTED) buildSet {
            for (p in store.playlists.value.filter { it.id in config.playlists }) {
                AppProxy.hostOf(p.url)?.let(::add)
                AppProxy.hostOf(p.epgUrl)?.let(::add)
                store.content.value[p.id]?.let { c ->
                    c.channels.mapNotNullTo(this) { AppProxy.hostOf(it.url) }
                    c.movies.mapNotNullTo(this) { AppProxy.hostOf(it.url) }
                }
            }
        } else emptySet()
        AppProxy.configure(config, hosts)
        // Offene Verbindungen nicht weiterverwenden: Sie liefen über den bisherigen Weg.
        http.connectionPool.evictAll()
    }

    /** VLC-Optionen, damit der Stream [url] denselben Weg nimmt wie der Rest der App. */
    fun vlcOptions(url: String): List<String> {
        if (!AppProxy.proxies(AppProxy.hostOf(url))) return emptyList()
        val c = AppProxy.current
        val host = c.host.trim()
        return when (c.type) {
            ProxyType.HTTP -> buildList {
                val user = if (c.user.isNotEmpty()) URLEncoder.encode(c.user, "UTF-8") + "@" else ""
                add(":http-proxy=http://$user$host:${c.port}/")
                if (c.password.isNotEmpty()) add(":http-proxy-pwd=${c.password}")
            }
            ProxyType.SOCKS5 -> buildList {
                add(":socks=$host:${c.port}")
                if (c.user.isNotEmpty()) add(":socks-user=${c.user}")
                if (c.password.isNotEmpty()) add(":socks-pwd=${c.password}")
            }
        }
    }
}

fun ProxySettings.config() = ProxyConfig(
    mode = runCatching { ProxyMode.valueOf(mode) }.getOrDefault(ProxyMode.OFF),
    type = runCatching { ProxyType.valueOf(type) }.getOrDefault(ProxyType.HTTP),
    host = host,
    port = port,
    user = user,
    password = password,
    playlists = playlists,
)
