package de.ayutv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import de.ayutv.ui.LocalContainer
import de.ayutv.ui.AyuRoot
import de.ayutv.ui.theme.AyuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as AyuApp).container
        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                AyuTheme { AyuRoot() }
            }
        }
        // Ist beim letzten Mal etwas abgestürzt, den Grund zeigen (zum Fotografieren und Melden).
        if (savedInstanceState == null) CrashReport.takePending(this)?.let { report ->
            android.app.AlertDialog.Builder(this)
                .setTitle("Beim letzten Mal ist etwas schiefgegangen")
                .setMessage("$report\n\nBitte ein Foto davon an den Entwickler schicken.")
                .setPositiveButton("OK", null)
                .show()
        }
    }
}
