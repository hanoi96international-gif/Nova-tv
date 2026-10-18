package de.novatv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import de.novatv.ui.LocalContainer
import de.novatv.ui.NovaRoot
import de.novatv.ui.theme.NovaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as NovaApp).container
        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                NovaTheme { NovaRoot() }
            }
        }
    }
}
