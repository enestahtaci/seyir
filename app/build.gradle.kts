import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing key: kept OUTSIDE the repository, in Documents\seyir-anahtar (or -Pseyir.keyDir=<folder>), with
// keystore.properties next to it (storeFile, storePassword, keyAlias, keyPassword). Nothing here is ever printed.
// Without that file the release build falls back to the debug key, which cannot update the real installs.
val keyDir: File = providers.gradleProperty("seyir.keyDir").orNull?.let(::File)
    ?: File(System.getProperty("user.home"), "Documents/seyir-anahtar")
val keyProps: Properties? = providers.fileContents(layout.projectDirectory.file(File(keyDir, "keystore.properties").absolutePath))
    .asText.orNull?.let { text -> Properties().apply { load(text.reader()) } }

android {
    namespace = "io.github.enestahtaci.seyir"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.enestahtaci.seyir"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keyProps != null) {
            create("release") {
                storeFile = File(keyDir, keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keyProps != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("Seyir: keystore.properties not found in $keyDir; release APK is signed with the DEBUG key.")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipe Extractor uses Java APIs newer than Android 9 (minSdk 28).
        isCoreLibraryDesugaringEnabled = true
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("seyir.liveTests", providers.gradleProperty("seyir.liveTests").getOrElse("false"))
    testLogging { showStandardStreams = true }
}

dependencies {
    implementation(libs.androidx.recyclerview)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.newpipe.extractor)
    coreLibraryDesugaring(libs.desugar)
    testImplementation(libs.junit)
}
