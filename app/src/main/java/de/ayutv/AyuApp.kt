package de.ayutv

import android.app.Activity
import android.app.Application
import android.os.Bundle
import de.ayutv.ui.components.hasTouchscreen
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
            // Handy als Fernbedienung: nur auf dem Fernseher und nur, solange DnA-TV zu sehen ist.
            when (event) {
                Lifecycle.Event.ON_START -> container.scope.launch {
                    if (!hasTouchscreen() && container.settings.current().phoneRemote) container.remote.start()
                }
                Lifecycle.Event.ON_STOP -> container.remote.stop()
                else -> Unit
            }
        })
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { container.foreground = java.lang.ref.WeakReference(activity) }
            override fun onActivityPaused(activity: Activity) {
                if (container.foreground?.get() === activity) container.foreground = null
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        // Kindersicherung: Nach jedem Start ist wieder alles gesperrt (vor allem anderen anwenden).
        container.scope.launch {
            container.parental.applyLocks()
            container.playlists.syncAll()
            container.refreshTeamReminders()
        }
        runCatching { SyncWorker.schedule(this) }
        // Erinnerungen nach einem Update oder Prozessneustart sicher wieder einplanen.
        container.scope.launch {
            container.reminders.rescheduleAll()
            container.recordings.rescheduleAll()
        }
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
