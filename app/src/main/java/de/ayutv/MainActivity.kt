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
    }
}
