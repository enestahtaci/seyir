plugins {
    alias(libs.plugins.android.application)
}

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

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Until the real release key exists (kept outside the repo), test builds use the debug key.
            signingConfig = signingConfigs.getByName("debug")
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
