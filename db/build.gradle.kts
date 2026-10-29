// Datenbank (Room) als eigenes Modul, Code-Erzeugung mit kapt: KSP wartete im CI über eine Stunde (kaum Rechenzeit),
// selbst für diese drei Dateien. kapt ist für Room seit Jahren erprobt und hier in Sekunden fertig.
plugins {
    id("com.android.library")
    id("com.android.legacy-kapt")
}

android {
    namespace = "de.ayutv.data.db"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kapt {
    arguments {
        arg("room.schemaLocation", layout.buildDirectory.dir("schemas").get().asFile.path)
        arg("room.incremental", "true")
    }
}

dependencies {
    val room = "2.8.5"
    api("androidx.room:room-runtime:$room")
    api("androidx.room:room-ktx:$room")
    "kapt"("androidx.room:room-compiler:$room")
    // Room liest die Kotlin-Metadaten mit einer Bibliothek, die nur bis Kotlin 2.3 reicht; die Version passend zum
    // Compiler (2.4) nachziehen.
    "kapt"("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.21")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
