plugins {
    // AGP 9 bringt Kotlin selbst mit ("built-in Kotlin"); die Kotlin-Version kommt über die Compose-/Serialization-Plugins.
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
