package de.ayutv

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import de.ayutv.ui.LocalContainer
import de.ayutv.ui.AyuRoot
import de.ayutv.ui.components.FitToScreen
import de.ayutv.ui.components.hasTouchscreen
import de.ayutv.ui.theme.AyuTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as AyuApp).container
        if (hasTouchscreen()) hideSystemBars()
        // Kurzes Intro nur beim echten Start (nicht nach Drehen o. Ä.), passend zum Design (Standard: keins).
        // Schwaches Gerät: kein Intro, die Zeit gehört dem Laden der App.
        var intro by androidx.compose.runtime.mutableStateOf(savedInstanceState == null && !de.ayutv.ui.UiPerformance.reduced)
        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                AyuTheme {
                    FitToScreen {
                        androidx.compose.foundation.layout.Box {
                            AyuRoot()
                            if (intro) when (de.ayutv.ui.theme.AyuColors.theme) {
                                de.ayutv.ui.theme.UiTheme.NORDLICHT -> de.ayutv.ui.theme.AuroraIntro { intro = false }
                                de.ayutv.ui.theme.UiTheme.HANNOVER -> de.ayutv.ui.theme.StadiumIntro { intro = false }
                                de.ayutv.ui.theme.UiTheme.CLASSIC -> Unit
                            }
                        }
                    }
                }
            }
        }
        handleSearchIntent(intent)
        // Ist beim letzten Mal etwas abgestürzt, den Grund zeigen (zum Fotografieren und Melden).
        if (savedInstanceState == null) CrashReport.takePending(this)?.let { report ->
            de.ayutv.data.Diagnostics.saveCrash(this, report)
            android.app.AlertDialog.Builder(this)
                .setTitle("Beim letzten Mal ist etwas schiefgegangen")
                .setMessage("$report\n\nAlles Weitere steht im Fehlerbericht: Einstellungen → Daten & Info → Fehlerbericht.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSearchIntent(intent)
    }

    /** Sprachassistent oder Suche des Systems: Begriff direkt in der Suche öffnen. */
    private fun handleSearchIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEARCH) return
        val query = intent.getStringExtra(SearchManager.QUERY).orEmpty()
        (application as AyuApp).container.searchRequests.value = query
    }

    /** Suchtaste der Fernbedienung öffnet die Suche. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_SEARCH) {
            if (event.action == KeyEvent.ACTION_UP) (application as AyuApp).container.searchRequests.value = ""
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Zurück im Menü (kein Film läuft, genug Speicher frei): Übersetzer vorbereiten, die während
     * eines Films keinen Platz hatten. Beim nächsten Film passen sie dann neben die Wiedergabe.
     */
    override fun onResume() {
        super.onResume()
        // Neue Version bei GitHub? (höchstens alle 6 Stunden nachfragen)
        (application as AyuApp).container.let { c -> c.scope.launch { c.updates.check() } }
        if (!de.ayutv.data.translate.OnDeviceTranslator.hasPendingPreparation(filesDir)) return
        val container = (application as AyuApp).container
        container.scope.launch {
            runCatching { de.ayutv.data.repo.Translators.remote.preparePending() }
                .onSuccess { android.util.Log.i("DnAPrepare", "Vorbereitet: $it") }
            de.ayutv.data.repo.Translators.remote.release()
        }
    }
}

/** Handy/Tablet: Vollbild wie auf dem Fernseher, Statusleiste und Navigation nur nach Wischen vom Rand. */
fun android.app.Activity.hideSystemBars() {
    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
        hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
