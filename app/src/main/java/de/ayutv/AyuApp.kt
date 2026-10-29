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
        AppTime.fixZone()
        val process = processName()
        CrashReport.install(this, process)
        // Der Übersetzer-Prozess braucht nichts davon (keine Datenbank, keine Synchronisierung).
        if (process.endsWith(":mt")) return
        de.ayutv.ui.UiPerformance.init(this)
        container = AppContainer(this)
        // Veraltete Playlists/EPG im Hintergrund auffrischen, ohne den Start zu blockieren.
        // Kindersicherung: Verlässt man die App (Home-Taste, anderes Programm), ist wieder alles gesperrt.
        // So ist die Sperre auch beim nächsten Start sofort aktiv.
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && container.parental.unlocked.value) {
                container.scope.launch { container.parental.lock() }
            }
            // Handy als Fernbedienung: nur auf dem Fernseher. Der Hintergrund-Dienst hält sie auch dann am Laufen,
            // wenn DnA-TV nicht zu sehen ist (Übergabe vom Handy startet den Player von selbst).
            when (event) {
                Lifecycle.Event.ON_START -> container.scope.launch {
                    if (!hasTouchscreen() && container.settings.current().phoneRemote) container.remote.start()
                }
                Lifecycle.Event.ON_STOP -> if (!de.ayutv.data.remote.RemoteService.running) container.remote.stop()
                else -> Unit
            }
        })
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                container.foreground = java.lang.ref.WeakReference(activity)
                // Ruckeln der Menüs messen (für den Fehlerbericht); der Player zählt seine Bilder selbst.
                if (activity is MainActivity) de.ayutv.ui.UiPerformance.watch(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (container.foreground?.get() === activity) container.foreground = null
                de.ayutv.ui.UiPerformance.unwatch(activity)
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
        // Fernseher: auch im Hintergrund (und nach dem Einschalten) für das Handy erreichbar.
        container.scope.launch {
            if (!hasTouchscreen() && container.settings.current().phoneRemote) de.ayutv.data.remote.RemoteService.start(this@AyuApp)
        }
        runCatching { SyncWorker.schedule(this) }
        runCatching { de.ayutv.data.repo.PretranslateWorker.schedule(this) }
        runCatching { de.ayutv.data.repo.ChannelCheckWorker.schedule(this) }
        container.scope.launch {
            kotlinx.coroutines.delay(15_000)
            runCatching { de.ayutv.data.FireTvHome.update(this@AyuApp, container) }
        }
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
    /**
     * Speicher abgeben, sobald die Menüs nicht mehr zu sehen sind oder das System knapp wird: Die App bleibt für das
     * Handy im Hintergrund erreichbar, soll dem Fire TV dabei aber keinen Platz wegnehmen. Bilder kommen beim
     * nächsten Öffnen schnell aus dem Speicher auf der Platte zurück.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        val hidden = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        if (hidden) runCatching { SingletonImageLoader.get(this).memoryCache?.clear() }
    }

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
