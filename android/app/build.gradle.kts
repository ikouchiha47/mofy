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
// Watch Together signaling relay (B7). Empty = host embeds local WS server.
// Examples: ws://192.168.1.20:8787  |  wss://mofy-sig.fly.dev
val wtSignalingUrl: String = env.getProperty("WT_SIGNALING_URL", "")
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "com.mofy.app"
    compileSdk = 36

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    defaultConfig {
        applicationId = "com.mofy.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.0"

        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        buildConfigField("String", "WT_SIGNALING_URL", "\"$wtSignalingUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Personal-use app installed only on our own arm64 phones - shipping
        // libVLC + WebRTC native libs for x86/x86_64/armeabi-v7a as well
        // roughly quadruples APK size for architectures nobody runs.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            // Credentials read from .env / .env.prod (never committed).
            // Add to .env:
            //   KEYSTORE_PATH=/path/to/mofy.jks
            //   KEYSTORE_PASSWORD=...
            //   KEY_ALIAS=mofy
            //   KEY_PASSWORD=...
            val ksPath = env.getProperty("KEYSTORE_PATH", "")
            if (ksPath.isNotEmpty()) {
                storeFile = file(ksPath)
                storePassword = env.getProperty("KEYSTORE_PASSWORD", "")
                keyAlias = env.getProperty("KEY_ALIAS", "")
                keyPassword = env.getProperty("KEY_PASSWORD", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
        getByName("androidTest") {
            kotlin.directories += "src/androidTest/kotlin"
            assets.srcDirs("$projectDir/schemas")
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

    // Periodic TMDB new-releases sync (ADR 0009) - deferrable ~14-day
    // background job, the case WorkManager's docs describe it for.
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
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

    // In-app media engine, shared by solo playback (Phase 07) and Watch
    // Together (Phase 13). ADR 0006: libVLC over mpv/ExoPlayer/external VLC.
    implementation("org.videolan.android:libvlc-all:3.6.2")

    // Watch Together signaling bootstrap (B7): embedded host/relay WebSocket
    // server. Client side uses OkHttp WebSocket (already a dependency).
    // Same server binary can later run on Fly; app points at it via
    // SignalingSettings.relayBaseUrl.
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    // Watch Together data plane (B8+): WebRTC DataChannel only, no media.
    // stream-webrtc-android 1.3.10 is current stable 1.x on Maven Central.
    implementation("io.getstream:stream-webrtc-android:1.3.10")

    // Watch Together Stage C: QR encoding of the room deep link.
    implementation("com.google.zxing:core:3.5.3")

    // Watch Together Stage C3b: camera QR scan for join-by-scan (zxing
    // above is encode-only, this is the decode side).
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // On-device embedding — EmbeddingGemma-300m via LiteRT Interpreter API (CPU).
    // litert-api:2.2.0 (CompiledModel) aborts in Environment.nativeCreate on this device.
    // litert:2.2.0 + litert-api:2.2.0 share namespace com.google.ai.edge.litert → build conflict.
    // litert:1.4.0 uses namespace org.tensorflow.lite, litert-api:1.4.0 uses org.tensorflow.lite.api
    // — no conflict — and bundles the self-contained libtensorflowlite_jni.so.
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    implementation("ai.djl.huggingface:tokenizers:0.33.0")
    implementation("ai.djl.android:tokenizer-native:0.33.0")

    // On-device facet classification (Phase 09).
    // ONNX Runtime for running facet_model_fp16.onnx (distilbert-base-uncased, 127MB).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Architecture rules (Konsist works on Kotlin source/PSI, not bytecode -
    // can express Kotlin/Compose-specific conventions ArchUnit can't easily
    // see) - integration smoke test only for now, real rubric TBD.
    testImplementation("com.lemonappdev:konsist:0.17.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    // Virtual-time delay control for the TMDB 429 retry tests (matches the
    // coroutines version already on the classpath - 1.8.0).
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.21.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation("androidx.room:room-testing:2.8.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.room:room-testing:2.8.1")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
