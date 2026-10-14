plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.woodruff.tvexplorer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.woodruff.shows"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "1.2.1"
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    lint {
        abortOnError = false
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
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.tv.material)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)
    implementation(libs.media3.datasource)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
