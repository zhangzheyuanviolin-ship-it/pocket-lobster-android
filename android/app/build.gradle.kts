import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    FileInputStream(localPropertiesFile).use { input ->
        localProperties.load(input)
    }
}

fun getSigningValue(name: String): String? {
    val envValue = System.getenv(name)?.trim()
    if (!envValue.isNullOrEmpty()) return envValue
    val propValue = localProperties.getProperty(name)?.trim()
    if (!propValue.isNullOrEmpty()) return propValue
    return null
}

val signingStoreFilePath = getSigningValue("ANYCLAW_SIGNING_STORE_FILE")
val signingStorePassword = getSigningValue("ANYCLAW_SIGNING_STORE_PASSWORD")
val signingKeyAlias = getSigningValue("ANYCLAW_SIGNING_KEY_ALIAS")
val signingKeyPassword = getSigningValue("ANYCLAW_SIGNING_KEY_PASSWORD")

val hasFixedSigning =
    !signingStoreFilePath.isNullOrEmpty() &&
    !signingStorePassword.isNullOrEmpty() &&
    !signingKeyAlias.isNullOrEmpty() &&
    !signingKeyPassword.isNullOrEmpty() &&
    file(signingStoreFilePath).exists()

android {
    namespace = "com.codex.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.codex.mobile.pocketlobster"
        minSdk = 26
        // targetSdk 28 allows executing binaries from app data directory.
        // Android 10+ (targetSdk 29+) enforces W^X which blocks this via SELinux.
        // Termux (F-Droid) uses the same approach.
        targetSdk = 28
        versionCode = 315
        versionName = "1.0.74-codex-cli-0.147.0-gpt-5.6-openminis-1.12-collaboration-orchestrator-v315"
    }

    flavorDimensions += "channel"

    productFlavors {
        create("prod") {
            dimension = "channel"
            applicationId = "com.codex.mobile.pocketlobster"
            resValue("string", "app_name", "\"口袋大龙虾\"")
            resValue("string", "pocket_lobster_app_name", "\"口袋大龙虾\"")
        }
        create("operator") {
            dimension = "channel"
            applicationId = "com.codex.mobile.pocketlobster.test"
            resValue("string", "app_name", "\"口袋大龙虾\"")
            resValue("string", "pocket_lobster_app_name", "\"口袋大龙虾\"")
        }
        create("beta") {
            dimension = "channel"
            applicationId = "com.codex.mobile.pocketlobster.beta"
            versionNameSuffix = "-beta"
            resValue("string", "app_name", "\"口袋大龙虾测试版\"")
            resValue("string", "pocket_lobster_app_name", "\"口袋大龙虾测试版\"")
        }
    }

    signingConfigs {
        if (hasFixedSigning) {
            create("fixed") {
                storeFile = file(signingStoreFilePath!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                if (signingStoreFilePath.endsWith(".p12", ignoreCase = true) ||
                    signingStoreFilePath.endsWith(".pfx", ignoreCase = true)
                ) {
                    storeType = "pkcs12"
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasFixedSigning) {
                signingConfig = signingConfigs.getByName("fixed")
            }
        }
        debug {
            if (hasFixedSigning) {
                signingConfig = signingConfigs.getByName("fixed")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "lib/arm64-v8a/libc++_shared.so"
        }
    }

    // Don't compress bootstrap zip or server bundle in assets
    androidResources {
        noCompress += listOf("zip", "tar", "tar.gz", "node", "proot-aarch64")
    }

    sourceSets.getByName("main") {
        // Library assets were not propagated into the final APK on AGP 8.7.
        assets.srcDir(rootProject.file("../third_party/OpenMinis/src/android/app/src/main/assets"))
    }

}

dependencies {
    implementation(project(":openminis"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.android.material:material:1.12.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("io.coil-kt:coil:2.7.0")
    testImplementation("junit:junit:4.13.2")
}
