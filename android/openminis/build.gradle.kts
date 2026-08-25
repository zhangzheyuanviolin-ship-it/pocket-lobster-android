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
        generatedSources.get().file("com/openminis/app/browser/BrowserAction.kt").asFile.apply {
            replaceRequired(
                """    NAVIGATE("navigate"),
    SCREENSHOT("screenshot"),""",
                """    NAVIGATE("navigate"),
    BACK("back"),
    FORWARD("forward"),
    RELOAD("reload"),
    SCREENSHOT("screenshot"),""",
            )
            replaceRequired(
                "NAVIGATE, CLICK, SCROLL, HOVER, TYPE",
                "NAVIGATE, BACK, FORWARD, RELOAD, CLICK, SCROLL, HOVER, TYPE",
            )
        }
        generatedSources.get().file("com/openminis/app/browser/BrowserActionInput.kt").asFile.apply {
            replaceRequired(
                """                    timeoutMs = if (obj.has("timeout")) obj.optInt("timeout") else null,""",
                """                    timeoutMs = when {
                        obj.has("timeout_ms") -> obj.optInt("timeout_ms")
                        obj.has("timeout") -> obj.optInt("timeout").coerceAtLeast(0) * 1_000
                        else -> null
                    },""",
            )
        }
        generatedSources.get().file("com/openminis/app/browser/BrowserUseManager.kt").asFile.apply {
            replaceRequired(
                """            BrowserAction.NAVIGATE -> navigate(input.url)
            BrowserAction.SCREENSHOT -> return screenshot(fullPage = input.fullPage)""",
                """            BrowserAction.NAVIGATE -> navigate(input.url)
            BrowserAction.BACK -> navigateHistory(back = true)
            BrowserAction.FORWARD -> navigateHistory(back = false)
            BrowserAction.RELOAD -> reloadAction()
            BrowserAction.SCREENSHOT -> return screenshot(fullPage = input.fullPage)""",
            )
            replaceRequired(
                """    // -- Screenshot --

    private suspend fun screenshot(fullPage: Boolean = false): BrowserActionResult {""",
                """    // -- Browser history --

    private suspend fun navigateHistory(back: Boolean): BrowserActionResult {
        val canNavigate = withContext(Dispatchers.Main) {
            if (back) webView.canGoBack() else webView.canGoForward()
        }
        if (!canNavigate) {
            return BrowserActionResult.error(if (back) "No previous history entry" else "No forward history entry")
        }
        withContext(Dispatchers.Main) {
            if (back) webView.goBack() else webView.goForward()
        }
        delay(500)
        val meta = navigationMetadata()
        return BrowserActionResult(text = (if (back) "Went back" else "Went forward") + "\n" + meta)
    }

    private suspend fun reloadAction(): BrowserActionResult {
        withContext(Dispatchers.Main) { reloadAndWait() }
        return BrowserActionResult(text = "Reloaded current page\n" + navigationMetadata())
    }

    // -- Screenshot --

    private suspend fun screenshot(fullPage: Boolean = false): BrowserActionResult {""",
            )
            replaceRequired(
                """        return evaluateAndReturn(js)
    }

    // -- Type --

    private suspend fun type(selector: String?, text: String?): BrowserActionResult {
        if (selector == null) return BrowserActionResult.error("type requires 'selector'")
        if (text == null) return BrowserActionResult.error("type requires 'text'")
        return evaluateAndReturn(BrowserUseJS.type(selector, text))
    }

    // -- Get Text --""",
                """        return evaluateSelectorWithRetry(js)
    }

    // -- Type --

    private suspend fun type(selector: String?, text: String?): BrowserActionResult {
        if (selector == null) return BrowserActionResult.error("type requires 'selector'")
        if (text == null) return BrowserActionResult.error("type requires 'text'")
        return evaluateSelectorWithRetry(BrowserUseJS.type(selector, text))
    }

    private suspend fun evaluateSelectorWithRetry(js: String): BrowserActionResult {
        var result = evaluateAndReturn(js)
        repeat(3) {
            if (result.success || !result.text.contains("Element not found", ignoreCase = true)) return result
            delay(350)
            result = evaluateAndReturn(js)
        }
        if (!result.success) {
            val page = getPageInfo()
            result = result.copy(text = result.text + "\nPage context after retries:\n" + page.text)
        }
        return result
    }

    // -- Get Text --""",
            )
        }
        generatedSources.get().file("com/openminis/app/tools/AgentTools.kt").asFile.replaceRequired(
            "        add(shellExecuteDefinition())",
            """        add(shellExecuteDefinition())
        add(com.openminis.app.integration.PocketLobsterHostTools.localTerminalDefinition())
        add(com.openminis.app.integration.PocketLobsterHostTools.ubuntuDefinition())""",
        )
        generatedSources.get().file("com/openminis/app/tools/AgentTools.kt").asFile.apply {
            replaceRequired(
                """Use navigate to open URLs, screenshot to see the page (returns an image), " +
            "click/type to interact with elements, get_text/get_readable to extract content, " +""",
                """Use navigate to open URLs; use back, forward, and reload for normal history navigation. Use screenshot to see the page (returns a directly readable image), " +
            "click/type to interact with elements and automatically retry transient selector misses; after navigate, use wait_for_dom_stable before interaction on dynamic pages. Use get_text/get_readable to extract content, " +""",
            )
        }
        generatedSources.get().file("com/openminis/app/ui/chat/ChatViewModel.kt").asFile.replaceRequired(
            """            "memory_get" -> executeMemoryGetTool(argsJson)
            else -> ToolExecutionResult("Unknown tool: ${'$'}name", false)""",
            """            "memory_get" -> executeMemoryGetTool(argsJson)
            com.openminis.app.integration.PocketLobsterHostTools.LOCAL_TOOL,
            com.openminis.app.integration.PocketLobsterHostTools.UBUNTU_TOOL ->
                com.openminis.app.integration.PocketLobsterHostTools.execute(name, argsJson, context)
            else -> ToolExecutionResult("Unknown tool: ${'$'}name", false)""",
        )
        generatedSources.get().file("com/openminis/app/ui/chat/ChatViewModel.kt").asFile.apply {
            replaceRequired(
                """        BrowserTabPool(context).also {
            it.setSession(activeSessionId)""",
                """        com.openminis.app.integration.SharedMinisRuntime.browser(context).also {
            com.openminis.app.integration.SharedMinisRuntime.registerBrowser(it)""",
            )
            replaceRequired(
                """            val result = browserTabPool.execute(input)""",
                """            val result = com.openminis.app.integration.SharedMinisRuntime.executeBrowser(
                context,
                "minis",
                input,
            )""",
            )
        }
        generatedSources.get().file("com/openminis/app/ui/chat/ChatFlatItems.kt").asFile.replaceRequired(
            """        if (message.role == "user") {
            // [T-android-candidate-bubble-gap] Flag when the previous message
            // is also a user message so the bubble can add a separating top
            // gap — back-to-back candidate / queued sends otherwise have no
            // AssistantHeader between them and visually merge.
            val prevIsUser = idx > 0 && messages[idx - 1].role == "user"
            out.add(dedupe(FlatChatItem.UserBubble(message, precededByUser = prevIsUser)))
            continue
        }""",
            """        if (message.role == "user") {
            // Collaboration routing instructions stay in model context but are
            // reduced to the user's original request in the visible transcript.
            val raw = message.content.trim()
            val isCollaboration = raw.startsWith("[三智能体协作任务")
            val visibleMessage = when {
                !isCollaboration -> message
                raw.contains("：总调度最终审核]") -> null
                raw.contains("用户原始请求：") -> message.copy(
                    content = raw.substringAfterLast("用户原始请求：").trim(),
                )
                else -> message.copy(content = "三智能体协作任务")
            }
            val prevIsUser = idx > 0 && messages[idx - 1].role == "user"
            if (visibleMessage != null) {
                out.add(dedupe(FlatChatItem.UserBubble(visibleMessage, precededByUser = prevIsUser)))
            }
            continue
        }""",
        )
        generatedSources.get().file("com/openminis/app/debug/ChatMutationMethods.kt").asFile.replaceRequired(
            """        val sessionId = existingId ?: HeadlessChatRunner.ensureSession(context)

        // Resolve model override (mutually exclusive with sessionId binding).""",
            """        val sessionId = existingId ?: HeadlessChatRunner.ensureSession(context)
        params.optString("sessionTitle", "").trim().takeIf { it.isNotEmpty() }?.let { title ->
            app.chatRepository.updateSessionTitle(sessionId, title)
        }

        // Resolve model override (mutually exclusive with sessionId binding).""",
        )
        generatedSources.get().file("com/openminis/app/ui/chat/ChatScreen.kt").asFile.apply {
            replaceRequired(
                """    val snackbarHostState = remember { SnackbarHostState() }""",
                """    val snackbarHostState = remember { SnackbarHostState() }
    var pocketLobsterCollaborationEnabled by remember {
        mutableStateOf(com.openminis.app.integration.CollaborationClient.isEnabled(context))
    }""",
            )
            replaceRequired(
                """                            MinisMenuDivider()
                            // Clear Chat""",
                """                            DropdownMenuItem(
                                text = { Text("三智能体协作") },
                                onClick = {
                                    pocketLobsterCollaborationEnabled = !pocketLobsterCollaborationEnabled
                                    com.openminis.app.integration.CollaborationClient.setEnabled(
                                        context,
                                        pocketLobsterCollaborationEnabled,
                                    )
                                },
                                trailingIcon = {
                                    androidx.compose.material3.Switch(
                                        checked = pocketLobsterCollaborationEnabled,
                                        onCheckedChange = { enabled ->
                                            pocketLobsterCollaborationEnabled = enabled
                                            com.openminis.app.integration.CollaborationClient.setEnabled(context, enabled)
                                        },
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("协作看板") },
                                onClick = {
                                    showChatMenu = false
                                    com.openminis.app.integration.CollaborationClient.openBoard(context)
                                },
                            )
                            MinisMenuDivider()
                            // Clear Chat""",
            )
            replaceRequired(
                """        viewModel.sendMessage(rawText)""",
                """        if (!com.openminis.app.integration.CollaborationClient.startIfEnabled(
            context,
            rawText,
            restorePrompt = { viewModel.setInputText(it) },
        )) {
            viewModel.sendMessage(rawText)
        }""",
            )
            replaceRequired(
                """                            viewModel.sendMessage(toSend)""",
                """                            if (!com.openminis.app.integration.CollaborationClient.startIfEnabled(
                                context,
                                toSend,
                                restorePrompt = { viewModel.setInputText(it) },
                            )) {
                                viewModel.sendMessage(toSend)
                            }""",
            )
        }
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
        check("SharedMinisRuntime.executeBrowser" in chatViewModel)
        val chatScreen = generatedSources.get()
            .file("com/openminis/app/ui/chat/ChatScreen.kt").asFile.readText()
        check("CollaborationClient.startIfEnabled" in chatScreen)
        check("pocketLobsterCollaborationEnabled" in chatScreen)
        val browserAction = generatedSources.get()
            .file("com/openminis/app/browser/BrowserAction.kt").asFile.readText()
        val browserInput = generatedSources.get()
            .file("com/openminis/app/browser/BrowserActionInput.kt").asFile.readText()
        val browserManager = generatedSources.get()
            .file("com/openminis/app/browser/BrowserUseManager.kt").asFile.readText()
        check("BACK(\"back\")" in browserAction)
        check("FORWARD(\"forward\")" in browserAction)
        check("RELOAD(\"reload\")" in browserAction)
        check("obj.has(\"timeout_ms\")" in browserInput)
        check("evaluateSelectorWithRetry" in browserManager)
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
