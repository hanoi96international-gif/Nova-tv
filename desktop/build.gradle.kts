import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.compose") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

// Gleiche Versionsnummer wie die Fire-TV-App (aus app/build.gradle.kts).
val appGradle = file("../app/build.gradle.kts").readText()
val appVersionCode = Regex("""versionCode\s*=\s*(\d+)""").find(appGradle)!!.groupValues[1]
val appVersionName = Regex("""versionName\s*=\s*"([^"]+)"""").find(appGradle)!!.groupValues[1]
version = appVersionName

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.add("kotlin.io.encoding.ExperimentalEncodingApi")
        optIn.add("androidx.compose.ui.ExperimentalComposeUiApi")
        optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
    }
}

// Nur die Versionsnummer (für die Update-Prüfung) in die Ressourcen. Bewusst KEINE Schlüssel oder Zugangsdaten:
// die App enthält keine; sie kommen nur vom Nutzer oder aus seiner übertragenen Sicherung.
val keyResources = layout.buildDirectory.dir("generated/keys")
val writeKeys by tasks.registering {
    val versionName = appVersionName
    inputs.property("version", versionName)
    outputs.dir(keyResources)
    doLast {
        val file = keyResources.get().file("app.version").asFile
        file.parentFile.mkdirs()
        file.writeText(versionName)
    }
}

sourceSets {
    main {
        resources.srcDir(writeKeys)
        // Leser und Hilfen aus der Fire-TV-App mitbenutzen (reines Kotlin, kein Android).
        kotlin.srcDir("../app/src/main/java")
        kotlin.include(
            "de/ayutv/desktop/**",
            "de/ayutv/data/parse/M3uParser.kt",
            "de/ayutv/data/parse/XtreamParsing.kt",
            "de/ayutv/data/parse/XmltvParser.kt",
            "de/ayutv/data/parse/EpgSources.kt",
            "de/ayutv/data/parse/EpgMatcher.kt",
            "de/ayutv/data/parse/Episodes.kt",
            "de/ayutv/data/parse/ChannelVariants.kt",
            "de/ayutv/data/parse/Countries.kt",
            "de/ayutv/data/parse/CatchupUrls.kt",
            "de/ayutv/data/parse/KeyRescue.kt",
            "de/ayutv/data/parse/PlaylistIdentity.kt",
            "de/ayutv/data/parse/StreamingApps.kt",
            "de/ayutv/data/parse/StreamBadges.kt",
            "de/ayutv/data/remote/SyncMerge.kt",
            "de/ayutv/data/parse/OpenSubtitlesParsing.kt",
            "de/ayutv/data/parse/Srt.kt",
            "de/ayutv/data/parse/ProgrammeText.kt",
            "de/ayutv/data/parse/Teams.kt",
            "de/ayutv/data/parse/ChannelQuality.kt",
            "de/ayutv/data/sport/LiveScores.kt",
            "de/ayutv/data/net/AppProxy.kt",
            "de/ayutv/data/net/ProxyCheck.kt",
            "de/ayutv/player/SpeechSync.kt",
            "de/ayutv/player/SubtitleSync.kt",
            "de/ayutv/data/setup/BackupCrypto.kt",
            "de/ayutv/ui/live/BlockMove.kt",
        )
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("uk.co.caprica:vlcj:4.12.1")
    // Fire-TV-Fernbedienung: Windows-Schnittstellen (Raw Input, Tastatur-Hook). Dieselbe Version, die vlcj mitbringt.
    implementation("net.java.dev.jna:jna-jpms:5.16.0")
    implementation("net.java.dev.jna:jna-platform-jpms:5.16.0")
    // XmlPullParser gibt es unter Android eingebaut, hier aus kxml2.
    implementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "de.ayutv.desktop.MainKt"
        jvmArgs += listOf("-Xmx1g", "-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "DnA-TV"
            // Windows verlangt Hauptversion > 0: 1.0.<Build der Fire-TV-App>.
            packageVersion = "1.0.$appVersionCode"
            description = "DnA-TV für Windows"
            vendor = "DnA-TV"
            includeAllModules = true
            // Das mitgelieferte VLC liegt in resources/windows/vlc (lädt die CI vor dem Paketieren herunter).
            appResourcesRootDir.set(layout.projectDirectory.dir("resources"))
            windows {
                menu = true
                menuGroup = "DnA-TV"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6f1c6b0e-3c52-4c8e-9d0a-5b8f2f6a7d41"
                iconFile.set(project.file("icons/dna-tv.ico"))
            }
        }
    }
}

tasks.test {
    useJUnit()
}
