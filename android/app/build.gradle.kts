import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

fun loadEnv(): Properties {
    val props = Properties()
    val repoRoot = rootDir.parentFile
    val envProd = File(repoRoot, ".env.prod")
    val envFile = if (envProd.exists()) envProd else File(repoRoot, ".env")
    if (envFile.exists()) {
        envFile.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                val (key, value) = trimmed.split("=", limit = 2)
                props.setProperty(key.trim(), value.trim())
            }
        }
    }
    return props
}

val env = loadEnv()
val tmdbApiKey: String = env.getProperty("TMDB_API_KEY", "")

android {
    namespace = "com.mofy.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mofy.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Native SQLite extensions (sqlite-vec) are loaded from disk via
    // BundledSQLiteDriver.addExtension() at runtime, which requires the .so
    // to actually be extracted onto the filesystem rather than mapped
    // directly out of the APK.
    packaging {
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            kotlin.directories += "src/main/kotlin"
        }
        getByName("test") {
            kotlin.directories += "src/test/kotlin"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    // Cursor-based pagination for Discover's catalog browsing - bounded
    // in-memory window (old pages drop as you scroll away) and LazyColumn
    // integration, rather than hand-rolling scroll-position tracking.
    implementation("androidx.paging:paging-runtime-ktx:3.3.6")
    implementation("androidx.paging:paging-compose:3.3.6")

    implementation("androidx.room:room-runtime:2.8.1")
    implementation("androidx.room:room-ktx:2.8.1")
    ksp("androidx.room:room-compiler:2.8.1")
    // Custom driver needed to load native SQLite extensions (sqlite-vec) -
    // see docs/research/native-sqlite-extensions-android.md.
    implementation("androidx.sqlite:sqlite-bundled:2.6.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Architecture rules (Konsist works on Kotlin source/PSI, not bytecode -
    // can express Kotlin/Compose-specific conventions ArchUnit can't easily
    // see) - integration smoke test only for now, real rubric TBD.
    testImplementation("com.lemonappdev:konsist:0.17.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
