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
import de.ayutv.data.repo.SyncWorker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import okio.Path.Companion.toOkioPath

class AyuApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val process = processName()
        CrashReport.install(this, process)
        // Der Übersetzer-Prozess braucht nichts davon (keine Datenbank, keine Synchronisierung).
        if (process.endsWith(":mt")) return
        container = AppContainer(this)
        // Veraltete Playlists/EPG im Hintergrund auffrischen, ohne den Start zu blockieren.
        // Kindersicherung: Verlässt man die App (Home-Taste, anderes Programm), ist wieder alles gesperrt.
        // So ist die Sperre auch beim nächsten Start sofort aktiv.
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && container.parental.unlocked.value) {
                container.scope.launch { container.parental.lock() }
            }
        })
        // Kindersicherung: Nach jedem Start ist wieder alles gesperrt (vor allem anderen anwenden).
        container.scope.launch {
            container.parental.applyLocks()
            container.playlists.syncAll()
        }
        runCatching { SyncWorker.schedule(this) }
        // Erinnerungen nach einem Update oder Prozessneustart sicher wieder einplanen.
        container.scope.launch { container.reminders.rescheduleAll() }
    }

    private fun processName(): String =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) getProcessName()
        else runCatching { java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrDefault(packageName)

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
