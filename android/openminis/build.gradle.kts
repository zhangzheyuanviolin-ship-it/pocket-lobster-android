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
val generatedResources = layout.buildDirectory.dir("generated/openminis-resources")

fun File.replaceRequired(oldValue: String, newValue: String) {
    val source = readText()
    check(source.contains(oldValue)) { "OpenMinis integration anchor missing in $path: $oldValue" }
    writeText(source.replace(oldValue, newValue))
}

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
        res.srcDir(generatedResources)
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

val stageOpenMinisResources by tasks.registering(Sync::class) {
    from(openMinisAndroid.resolve("src/main/res"))
    into(generatedResources)
    doLast {
        var declarationCount = 0
        generatedResources.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .forEach { file ->
                val source = file.readText()
                val transformed = source
                    .replace("name=\"app_name\"", "name=\"minis_app_name\"")
                    .replace("@string/app_name", "@string/minis_app_name")
                if (transformed != source) {
                    declarationCount += "name=\"app_name\"".toRegex().findAll(source).count()
                    file.writeText(transformed)
                }
            }
        check(declarationCount > 0) { "OpenMinis app_name resource anchor missing" }
    }
}

val stageOpenMinisSources by tasks.registering(Sync::class) {
    from(openMinisAndroid.resolve("src/main/java")) {
        exclude("com/openminis/app/ui/settings/CheckUpdateSection.kt")
    }
    into(generatedSources)
    doLast {
        generatedSources.get().file("com/openminis/app/MinisApp.kt").asFile.replaceRequired(
            "class MinisApp : Application(), ImageLoaderFactory {",
            """open class MinisApp : Application(), ImageLoaderFactory {
    protected open fun shouldInitializeMinisRuntime(): Boolean = true""",
        )
        generatedSources.get().file("com/openminis/app/MinisApp.kt").asFile.replaceRequired(
            """    override fun onCreate() {
        super.onCreate()""",
            """    override fun onCreate() {
        super.onCreate()
        if (!shouldInitializeMinisRuntime()) return""",
        )
        generatedSources.get().file("com/openminis/app/sandbox/NativeOffload.kt").asFile.apply {
            replaceRequired(
                "private const val SOCKET_NAME = \"native-offload\"",
                "private val SOCKET_NAME = \"native-offload-${'$'}{android.os.Process.myUid()}\"",
            )
            replaceRequired(
                "const val socketName: String = SOCKET_NAME",
                "val socketName: String = SOCKET_NAME",
            )
        }
        generatedSources.get().file("com/openminis/app/tools/AgentTools.kt").asFile.replaceRequired(
            "        add(shellExecuteDefinition())",
            """        add(shellExecuteDefinition())
        add(com.openminis.app.integration.PocketLobsterHostTools.localTerminalDefinition())
        add(com.openminis.app.integration.PocketLobsterHostTools.ubuntuDefinition())""",
        )
        generatedSources.get().file("com/openminis/app/ui/chat/ChatViewModel.kt").asFile.replaceRequired(
            """            "memory_get" -> executeMemoryGetTool(argsJson)
            else -> ToolExecutionResult("Unknown tool: ${'$'}name", false)""",
            """            "memory_get" -> executeMemoryGetTool(argsJson)
            com.openminis.app.integration.PocketLobsterHostTools.LOCAL_TOOL,
            com.openminis.app.integration.PocketLobsterHostTools.UBUNTU_TOOL ->
                com.openminis.app.integration.PocketLobsterHostTools.execute(name, argsJson, context)
            else -> ToolExecutionResult("Unknown tool: ${'$'}name", false)""",
        )
        generatedSources.get().file("com/openminis/app/ui/chat/ChatViewModel.kt").asFile.replaceRequired(
            """        BrowserTabPool(context).also {
            it.setSession(activeSessionId)""",
            """        BrowserTabPool(context).also {
            com.openminis.app.integration.SharedMinisRuntime.registerBrowser(it)
            it.setSession(activeSessionId)""",
        )
        var appNameReferenceCount = 0
        generatedSources.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val source = file.readText()
                appNameReferenceCount += "R.string.app_name".toRegex().findAll(source).count()
                if ("R.string.app_name" in source) {
                    file.writeText(source.replace("R.string.app_name", "R.string.minis_app_name"))
                }
            }
        check(appNameReferenceCount > 0) { "OpenMinis app_name source anchor missing" }
    }
}

val verifyOpenMinisIntegrationSources by tasks.registering {
    dependsOn(stageOpenMinisSources)
    dependsOn(stageOpenMinisResources)
    doLast {
        val minisApp = generatedSources.get().file("com/openminis/app/MinisApp.kt").asFile.readText()
        val nativeOffload = generatedSources.get()
            .file("com/openminis/app/sandbox/NativeOffload.kt").asFile.readText()
        check("open class MinisApp" in minisApp)
        check("if (!shouldInitializeMinisRuntime()) return" in minisApp)
        check("native-offload-${'$'}{android.os.Process.myUid()}" in nativeOffload)
        check("const val socketName" !in nativeOffload)
        val agentTools = generatedSources.get()
            .file("com/openminis/app/tools/AgentTools.kt").asFile.readText()
        val chatViewModel = generatedSources.get()
            .file("com/openminis/app/ui/chat/ChatViewModel.kt").asFile.readText()
        check("PocketLobsterHostTools.localTerminalDefinition" in agentTools)
        check("PocketLobsterHostTools.ubuntuDefinition" in agentTools)
        check("PocketLobsterHostTools.execute" in chatViewModel)
        check("SharedMinisRuntime.registerBrowser" in chatViewModel)
        check(generatedSources.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .none { "R.string.app_name" in it.readText() })
        check(generatedResources.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .none { "name=\"app_name\"" in it.readText() || "@string/app_name" in it.readText() })
    }
}

tasks.named("preBuild") {
    dependsOn(stageOpenMinisSharedAssets)
    dependsOn(stageOpenMinisSources)
    dependsOn(stageOpenMinisResources)
    dependsOn(verifyOpenMinisIntegrationSources)
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
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
