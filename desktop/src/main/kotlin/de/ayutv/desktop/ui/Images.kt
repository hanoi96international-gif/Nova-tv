package de.ayutv.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import de.ayutv.desktop.data.Network
import de.ayutv.desktop.data.Sync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Senderlogos und Poster: laden und im Speicher halten (die zuletzt gebrauchten 600). */
object Images {
    private const val MAX_ENTRIES = 600
    private const val MAX_BYTES = 4_000_000
    private val http = Network.http.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val parallel = Semaphore(6)
    private val failed = HashSet<String>()
    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > MAX_ENTRIES
    }

    @Synchronized fun cached(url: String): ImageBitmap? = cache[url]

    suspend fun load(url: String): ImageBitmap? {
        cached(url)?.let { return it }
        synchronized(this) { if (url in failed) return null }
        return parallel.withPermit {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = http.newCall(Request.Builder().url(url).header("User-Agent", Sync.USER_AGENT).build()).execute().use { r ->
                        if (!r.isSuccessful) return@runCatching null
                        r.body.bytes().takeIf { it.size <= MAX_BYTES }
                    } ?: return@runCatching null
                    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                }.getOrNull()
            }
        }.also { image ->
            synchronized(this) { if (image != null) cache[url] = image else failed += url }
        }
    }
}

@Composable
fun rememberImage(url: String?): ImageBitmap? {
    if (url.isNullOrBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) return null
    var image by remember(url) { mutableStateOf(Images.cached(url)) }
    LaunchedEffect(url) { if (image == null) image = Images.load(url) }
    return image
}
