package de.ayutv

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath

class AyuApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Veraltete Playlists/EPG im Hintergrund auffrischen, ohne den Start zu blockieren.
        container.scope.launch { container.playlists.syncAll() }
    }

    /** Logos und Poster: kleiner Speicher-Cache (Fire TV Stick hat wenig RAM), großer Disk-Cache. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("images").toOkioPath())
                .maxSizeBytes(150L * 1024 * 1024)
                .build()
        }
        .crossfade(false)
        .build()
}
