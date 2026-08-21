plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val openMinisRoot = rootProject.file("../third_party/OpenMinis")
val openMinisAndroid = openMinisRoot.resolve("src/android/app")
val generatedAssets = layout.buildDirectory.dir("generated/openminis-assets")
val generatedSources = layout.buildDirectory.dir("generated/openminis-sources")

android {
    namespace = "com.openminis.app"
    compileSdk = 36
    ndkVersion = "28.0.12433566"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT", "\"\"")
        buildConfigField("String", "VERSION_NAME", "\"1.12\"")
        buildConfigField("int", "VERSION_CODE", "24")

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = openMinisAndroid.resolve("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets.getByName("main") {
        java.srcDir(generatedSources)
        java.srcDir("src/main/java")
        res.srcDir(openMinisAndroid.resolve("src/main/res"))
        assets.srcDir(openMinisAndroid.resolve("src/main/assets"))
        assets.srcDir(generatedAssets)
        jniLibs.srcDir(openMinisAndroid.resolve("src/main/jniLibs"))
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

    androidResources {
        noCompress += listOf("tar.gz", "proot-aarch64")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val stageOpenMinisSharedAssets by tasks.registering(Copy::class) {
    from(openMinisRoot.resolve("src/shared/bashism"))
    into(generatedAssets.map { it.dir("bashism") })
}

val stageOpenMinisSources by tasks.registering(Sync::class) {
    from(openMinisAndroid.resolve("src/main/java")) {
        exclude("com/openminis/app/ui/settings/CheckUpdateSection.kt")
    }
    into(generatedSources)
}

tasks.named("preBuild") {
    dependsOn(stageOpenMinisSharedAssets)
    dependsOn(stageOpenMinisSources)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.github.helloooideeeeea:RealTimeCutVADLibraryForAndroid:1.0.5@aar")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-android:0.33.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3-android:0.33.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("sh.calvin.reorderable:reorderable:2.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("ch.acra:acra-core:5.12.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
