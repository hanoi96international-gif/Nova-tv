plugins {
    // AGP 8.13 statt 9.x: stabile, bekannte DSL. Unterstützt compileSdk 36 und R8 mit Kotlin 2.3.
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
