import java.security.MessageDigest

plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }

android {
    namespace = "com.stremli.tv"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.stremli.tv"; minSdk = 25; targetSdk = 35; versionCode = 3; versionName = "1.0.1"
        // Optional HTTPS URL of a Gotaku parameter file.
        // Set gotakuConfigUrl in gradle.properties or with -PgotakuConfigUrl=...; empty uses the built-in values.
        val configUrl = (project.findProperty("gotakuConfigUrl") as String?).orEmpty()
        buildConfigField("String", "GOTAKU_CONFIG_URL", "\"" + configUrl.replace("\"", "") + "\"")
        // Admin master PIN: resets any profile's forgotten PIN. Set stremliMasterPin=NNNN in your
        // user-level ~/.gradle/gradle.properties (not in this project) or with -PstremliMasterPin=NNNN.
        // Only an iterated SHA-256 hash is built into the APK. Without it, "Forgot PIN?" is not offered.
        val masterPin = (project.findProperty("stremliMasterPin") as String?).orEmpty().trim()
        require(masterPin.isEmpty() || masterPin.matches(Regex("\\d{4}"))) { "stremliMasterPin must be 4 digits" }
        val masterHash = if (masterPin.isEmpty()) "" else {
            var digest = "stremli-master-v1:$masterPin".toByteArray(Charsets.UTF_8)
            repeat(20_000) { digest = MessageDigest.getInstance("SHA-256").digest(digest) }
            digest.joinToString("") { "%02x".format(it) }
        }
        buildConfigField("String", "MASTER_PIN_HASH", "\"" + masterHash + "\"")
        // The AniList / MyAnimeList sign-in relay. Override with -PtrackerRelayUrl=...
        val relayUrl = (project.findProperty("trackerRelayUrl") as String?)?.trim()?.ifEmpty { null } ?: "https://stremli-link.anilili.workers.dev"
        buildConfigField("String", "TRACKER_RELAY_URL", "\"" + relayUrl.replace("\"", "") + "\"")
    }
    buildTypes {
        // R8 matters on Fire TV hardware: unoptimised Compose code is markedly slower and larger.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    // QR codes for signing in to AniList / MyAnimeList from a phone (the encoder only; R8 drops the rest).
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
