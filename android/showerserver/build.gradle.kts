plugins {
    id("com.android.application")
}

android {
    namespace = "com.ai.assistance.shower"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ai.assistance.shower"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
