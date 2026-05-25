plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.gprotts.animepip"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.gprotts.animepip"
        minSdk = 30 // WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE, which is the behaviour used to hide system bars in fullscreen/PiP mode and requires API 30
        targetSdk = 35

        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = System.getenv("GITHUB_REF_NAME") ?: "1.0-local"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("SIGNING_STORE_FILE")

            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            // Keep disabled for now. This is a WebView/PiP wrapper and should be tested on-device
            // before enabling R8/resource shrinking, because WebView/fullscreen behaviour is brittle.
            isMinifyEnabled = false

            if (!System.getenv("SIGNING_STORE_FILE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

}