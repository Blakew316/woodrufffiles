plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.woodruff.tvexplorer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.woodruff.shows"
        minSdk = 24
        targetSdk = 35
        // CI passes -PbuildNumber=<run number>: release v2.0.N is versionCode N, which is how the
        // in-app updater knows a release is newer. Local builds stay at 12, below every release.
        val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull()
        versionCode = buildNumber ?: 12
        versionName = if (buildNumber != null) "2.0.$buildNumber" else "2.0.0"
        // TVs are ARM. Leaving out the x86 builds of WebRTC (cameras) keeps the APK ~10 MB smaller.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    // One fixed key for every build, so updates install over the previous version and the
    // SHA-1 registered with Google (for Drive sign-in) never changes. Sideload use only:
    // the key is in the repo, so swap it for a private one before any store release.
    signingConfigs {
        create("shared") {
            storeFile = file("woodruff-shows.keystore")
            storePassword = "woodruffshows"
            keyAlias = "woodruff"
            keyPassword = "woodruffshows"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/LICENSE.txt", "META-INF/NOTICE.txt")
    }
    lint {
        abortOnError = false
    }
    testOptions {
        // Robolectric (the UI gallery) needs the app's real resources: fonts, drawables, strings.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    // The player's media session: voice ("Hey Google, pause", Alexa) and Bluetooth buttons.
    implementation(libs.media3.session)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)
    implementation(libs.media3.datasource)
    // Drive streams over OkHttp: one kept-open connection instead of a new one per request.
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.android.mail)
    implementation(libs.android.activation)
    implementation(libs.webrtc)
    // Installs the baseline profiles (ours and Compose's) on sideloaded installs, where the Play
    // Store would normally do it: code is compiled ahead of time instead of interpreted at first use.
    implementation(libs.androidx.profileinstaller)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    // UI gallery: draws the TV screens on the JVM (Robolectric + Roborazzi) for GitHub Pages.
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// The UI gallery only runs when asked (./gradlew testDebugUnitTest -Pgallery), so ordinary builds
// stay fast and a drawing problem can never block an APK release.
tasks.withType<Test>().configureEach {
    if (project.hasProperty("gallery")) {
        systemProperty("roborazzi.test.record", "true")
        systemProperty("gallery.dir", rootProject.layout.buildDirectory.dir("gallery").get().asFile.path)
        maxHeapSize = "3g"
    } else {
        exclude("**/gallery/**")
    }
}
