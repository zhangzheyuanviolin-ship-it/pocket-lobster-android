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
                """    val selector: String? = null,
    val text: String? = null,""",
                """    val selector: String? = null,
    val selectorType: String? = null,
    val text: String? = null,""",
            )
            replaceRequired(
                """                    selector = obj.optString("selector").ifEmpty { null },
                    text = obj.optString("text").ifEmpty { null },""",
                """                    selector = obj.optString("selector").ifEmpty { null },
                    selectorType = obj.optString("selector_type").ifEmpty { null },
                    text = obj.optString("text").ifEmpty { null },""",
            )
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
                """        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_QUALITY, out)
        val jpegBytes = out.toByteArray()

        val file = saveBitmapToFile(bitmap, "screenshot")
        val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)""",
                """        val maxVisionDimension = 1_800
        val visionBitmap = if (bitmap.width > maxVisionDimension || bitmap.height > maxVisionDimension) {
            val scale = minOf(maxVisionDimension.toFloat() / bitmap.width, maxVisionDimension.toFloat() / bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        visionBitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_QUALITY, out)
        val jpegBytes = out.toByteArray()

        val file = saveBitmapToFile(bitmap, "screenshot")
        val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        if (visionBitmap !== bitmap) visionBitmap.recycle()""",
            )
            replaceRequired(
                """        var normalized = urlString
        if (!normalized.contains("://")) normalized = "https://${'$'}normalized"

        val deferred = CompletableDeferred<Unit>()""",
                """        var normalized = urlString
        if (!normalized.contains("://")) normalized = "https://${'$'}normalized"

        if (GoogleAuthRouter.shouldRouteExternally(normalized)) {
            withContext(Dispatchers.Main) {
                _isLoading.value = false
                GoogleAuthRouter.openInCustomTab(webView.context, normalized)
            }
            return BrowserActionResult(
                text = "Google sign-in cannot run inside Android WebView. Opened a secure Chrome Custom Tab for user takeover. Chrome cookies are isolated from this automated WebView; do not claim that this WebView is signed in. Continue only through a site-supported callback or another non-embedded authentication method.",
                pageURL = normalized,
            )
        }

        val deferred = CompletableDeferred<Unit>()""",
            )
            replaceRequired(
                """    fun loadURL(urlString: String) {
        var normalized = urlString
        if (!normalized.contains("://")) normalized = "https://${'$'}normalized"
        _isLoading.value = true
        webView.loadUrl(normalized)
    }""",
                """    fun loadURL(urlString: String) {
        var normalized = urlString
        if (!normalized.contains("://")) normalized = "https://${'$'}normalized"
        if (GoogleAuthRouter.shouldRouteExternally(normalized)) {
            _isLoading.value = false
            GoogleAuthRouter.openInCustomTab(webView.context, normalized)
            return
        }
        _isLoading.value = true
        webView.loadUrl(normalized)
    }""",
            )
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
        generatedSources.get().file("com/openminis/app/browser/GoogleAuthRouter.kt").asFile.apply {
            replaceRequired(
                """import android.content.Context
import android.content.Intent""",
                """import android.app.Activity
import android.content.Context
import android.content.Intent""",
            )
            replaceRequired(
                """            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(false)
                .build()
                .launchUrl(context, Uri.parse(url))""",
                """            val customTab = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(false)
                .build()
            if (context !is Activity) {
                customTab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            customTab.launchUrl(context, Uri.parse(url))""",
            )
        }
        generatedSources.get().file("com/openminis/app/ui/preview/WebViewHolder.kt").asFile.replaceRequired(
            """    fun startIfNeeded() {
        if (hasLoaded) return
        // T-htmlpreview-2d5c4f3d: defer the actual loadUrl until the""",
            """    fun startIfNeeded() {
        if (hasLoaded) return
        if (com.openminis.app.browser.GoogleAuthRouter.shouldRouteExternally(currentUrl)) {
            hasLoaded = true
            isLoading = false
            com.openminis.app.browser.GoogleAuthRouter.openInCustomTab(webView.context, currentUrl)
            return
        }
        // T-htmlpreview-2d5c4f3d: defer the actual loadUrl until the""",
        )
        generatedSources.get().file("com/openminis/app/tools/AgentTools.kt").asFile.replaceRequired(
            "        add(shellExecuteDefinition())",
            """        add(shellExecuteDefinition())
        add(com.openminis.app.integration.PocketLobsterHostTools.localTerminalDefinition())
        add(com.openminis.app.integration.PocketLobsterHostTools.ubuntuDefinition())
        addAll(com.openminis.app.integration.PocketLobsterHostTools.phoneAgentDefinitions())""",
        )
        generatedSources.get().file("com/openminis/app/tools/AgentTools.kt").asFile.apply {
            replaceRequired(
                """Use navigate to open URLs, screenshot to see the page (returns an image), " +
            "click/type to interact with elements, get_text/get_readable to extract content, " +""",
                """Use navigate to open URLs; use back, forward, and reload for normal history navigation. Use list_tabs and an explicit tab_id to continue another agent's shared tab; same-tab actions are serialized safely. Use screenshot to see the page (returns a directly readable image), " +
            "click/type to interact with elements and automatically retry transient selector misses; after navigate, use wait_for_dom_stable before interaction on dynamic pages. Use get_text/get_readable to extract content, " +""",
            )
            replaceRequired(
                """            "selector" to AgentToolParam("string", "CSS selector for targeting elements (click, type, get_text, scroll, hover, find_elements). For scroll: specify a scrollable container to scroll (e.g. 'div.timeline'); if omitted, auto-detects the best scrollable element."),
            "text" to AgentToolParam("string", "Text to type (for type action)"),""",
                """            "selector" to AgentToolParam("string", "Selector for click, type, get_text, scroll, hover, find_elements, or scroll_and_collect. CSS is the default."),
            "selector_type" to AgentToolParam("string", "Selector interpretation for click, type, get_text, hover, or find_elements (default: css).", enumValues = listOf("css", "xpath", "text")),
            "text" to AgentToolParam("string", "Text to type (for type action)"),""",
            )
            replaceRequired(
                """            "script" to AgentToolParam("string", "JavaScript code to execute (for execute_js action). The script runs inside an async function wrapper — `await` and top-level `return` are both supported (e.g. `var r = await fetch(url); return await r.json()`)."),""",
                """            "script" to AgentToolParam("string", "JavaScript code to execute (for execute_js action). Bare expressions such as document.title return their value; await and explicit top-level return are also supported."),""",
            )
            replaceRequired(
                """propertyOrdering = listOf("tool_title", "action", "tab_id", "url", "selector", "text"""",
                """propertyOrdering = listOf("tool_title", "action", "tab_id", "url", "selector", "selector_type", "text"""",
            )
        }
        generatedSources.get().file("com/openminis/app/sandbox/ExecutionCoordinator.kt").asFile.apply {
            replaceRequired(
                """        val mounts = linkedMapOf<String, String>()

        // [diag] previous attachments mount target""",
                """        val mounts = linkedMapOf<String, String>()

        // Pocket Lobster exposes Android shared storage at the same paths used
        // by the local and system shells, so every agent reaches one file.
        val androidSharedStorage = File("/storage/emulated/0")
        if (androidSharedStorage.isDirectory) {
            mounts["/sdcard"] = androidSharedStorage.absolutePath
            PRootKernel.addBindMount("/sdcard", androidSharedStorage.absolutePath)
        }

        // [diag] previous attachments mount target""",
            )
        }
        generatedSources.get().file("com/openminis/app/ui/chat/ChatViewModel.kt").asFile.replaceRequired(
            """            "memory_get" -> executeMemoryGetTool(argsJson)
            else -> ToolExecutionResult("Unknown tool: ${'$'}name", false)""",
            """            "memory_get" -> executeMemoryGetTool(argsJson)
            in com.openminis.app.integration.PocketLobsterHostTools.NAMES ->
                com.openminis.app.integration.PocketLobsterHostTools.execute(name, argsJson, context)
            in com.openminis.app.integration.PocketLobsterCollaborationTools.NAMES ->
                com.openminis.app.integration.PocketLobsterCollaborationTools.execute(name, argsJson)
            else -> ToolExecutionResult("Unknown tool: ${'$'}name", false)""",
        )
        generatedSources.get().file("com/openminis/app/ui/chat/ChatViewModel.kt").asFile.apply {
            replaceRequired(
                """            visionGroupConfigured = com.openminis.app.tools.VisionGroupResolver.isConfigured(
                providerRepository, context,
            ),
            memoryEnabled = _memoryEnabled.value,
        )""",
                """            visionGroupConfigured = com.openminis.app.tools.VisionGroupResolver.isConfigured(
                providerRepository, context,
            ),
            memoryEnabled = _memoryEnabled.value,
        ) + if (agentHistory.any {
            it.role == LLMMessage.Role.USER &&
                it.content.trim().startsWith("[口袋大龙虾三智能体协作：总调度工具运行时]")
        }) {
            com.openminis.app.integration.PocketLobsterCollaborationTools.definitions()
        } else {
            emptyList()
        }""",
            )
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
            """    for (idx in fromIndex until messages.size) {""",
            """    val followsInternalCoordinatorPrompt = messages.any { row ->
        row.role == "user" && (
            row.content.trim().startsWith("[口袋大龙虾三智能体协作：总调度") ||
                row.content.trim().startsWith("[三智能体协作任务：总调度最终审核]")
            )
    }
    if (followsInternalCoordinatorPrompt) return emptyList()
    for (idx in fromIndex until messages.size) {""",
        )
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
            // Collaboration routing instructions remain in model context but
            // never appear as user-visible chat content.
            val raw = message.content.trim()
            val isCoordinatorPrompt =
                raw.startsWith("[口袋大龙虾三智能体协作：总调度") ||
                    raw.startsWith("[三智能体协作任务：总调度最终审核]")
            val isCollaboration =
                raw.startsWith("[口袋大龙虾三智能体协作") ||
                    raw.startsWith("[三智能体协作任务")
            val visibleMessage = when {
                isCoordinatorPrompt -> null
                !isCollaboration -> message
                raw.contains("用户原始消息仅作为背景，不代表要求您重复执行全部任务：") -> message.copy(
                    content = raw.substringAfterLast("用户原始消息仅作为背景，不代表要求您重复执行全部任务：").trim(),
                )
                raw.contains("用户原始请求：") -> message.copy(
                    content = raw.substringAfterLast("用户原始请求：").trim(),
                )
                else -> null
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
        generatedSources.get().file("com/openminis/app/ui/sessions/SessionListViewModel.kt").asFile.replaceRequired(
            """    fun deleteSession(id: String) {
        viewModelScope.launch {
            chatRepository.deleteSession(id)
            ChatViewModelStore.release(id)
            com.openminis.app.service.SessionBadgeStore.clear(id)
        }
    }""",
            """    fun deleteSession(id: String) {
        viewModelScope.launch {
            chatRepository.deleteSession(id)
            ChatViewModelStore.release(id)
            com.openminis.app.service.SessionBadgeStore.clear(id)
        }
    }

    fun deleteAllSessions() {
        val ids = _allSessions.value.map { it.id }
        viewModelScope.launch {
            ids.forEach { id ->
                chatRepository.deleteSession(id)
                ChatViewModelStore.release(id)
                com.openminis.app.service.SessionBadgeStore.clear(id)
            }
        }
        clearSelection()
    }""",
        )
        generatedSources.get().file("com/openminis/app/ui/sessions/SessionListScreen.kt").asFile.apply {
            replaceRequired(
                """    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }""",
                """    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }""",
            )
            replaceRequired(
                """                                    MinisMenuDivider()
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sessionlist_shell_terminal)) },""",
                """                                    DropdownMenuItem(
                                        text = { Text("一键清空所有聊天记录") },
                                        enabled = sessions.isNotEmpty(),
                                        onClick = {
                                            showOverflowMenu = false
                                            showClearAllDialog = true
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Delete, contentDescription = null)
                                        },
                                    )
                                    MinisMenuDivider()
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sessionlist_shell_terminal)) },""",
            )
            replaceRequired(
                """    // ─── Session groups ────────────────────────────────────────────────────""",
                """    if (showClearAllDialog) {
        MinisAlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = "清空所有聊天记录",
            text = "将永久删除全部${'$'}{sessions.size}条Minis聊天记录。此操作无法撤销，是否继续？",
            confirmText = "确认清空",
            isDestructive = true,
            onConfirm = {
                viewModel.deleteAllSessions()
                showClearAllDialog = false
            },
        )
    }

    // ─── Session groups ────────────────────────────────────────────────────""",
            )
        }
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
        check("PocketLobsterHostTools.phoneAgentDefinitions" in agentTools)
        check("PocketLobsterHostTools.execute" in chatViewModel)
        check("PocketLobsterCollaborationTools.definitions" in chatViewModel)
        check("PocketLobsterCollaborationTools.execute" in chatViewModel)
        check("[口袋大龙虾三智能体协作：总调度工具运行时]" in chatViewModel)
        check("SharedMinisRuntime.registerBrowser" in chatViewModel)
        check("SharedMinisRuntime.executeBrowser" in chatViewModel)
        val chatScreen = generatedSources.get()
            .file("com/openminis/app/ui/chat/ChatScreen.kt").asFile.readText()
        check("CollaborationClient.startIfEnabled" in chatScreen)
        check("pocketLobsterCollaborationEnabled" in chatScreen)
        val sessionListScreen = generatedSources.get()
            .file("com/openminis/app/ui/sessions/SessionListScreen.kt").asFile.readText()
        val sessionListViewModel = generatedSources.get()
            .file("com/openminis/app/ui/sessions/SessionListViewModel.kt").asFile.readText()
        check("一键清空所有聊天记录" in sessionListScreen)
        check("showClearAllDialog" in sessionListScreen)
        check("fun deleteAllSessions()" in sessionListViewModel)
        val browserAction = generatedSources.get()
            .file("com/openminis/app/browser/BrowserAction.kt").asFile.readText()
        val browserInput = generatedSources.get()
            .file("com/openminis/app/browser/BrowserActionInput.kt").asFile.readText()
        val browserManager = generatedSources.get()
            .file("com/openminis/app/browser/BrowserUseManager.kt").asFile.readText()
        val googleAuthRouter = generatedSources.get()
            .file("com/openminis/app/browser/GoogleAuthRouter.kt").asFile.readText()
        val webViewHolder = generatedSources.get()
            .file("com/openminis/app/ui/preview/WebViewHolder.kt").asFile.readText()
        check("BACK(\"back\")" in browserAction)
        check("FORWARD(\"forward\")" in browserAction)
        check("RELOAD(\"reload\")" in browserAction)
        check("obj.has(\"timeout_ms\")" in browserInput)
        check("selectorType" in browserInput)
        check("evaluateSelectorWithRetry" in browserManager)
        check("maxVisionDimension" in browserManager)
        check("Chrome cookies are isolated from this automated WebView" in browserManager)
        check(browserManager.indexOf("GoogleAuthRouter.shouldRouteExternally(normalized)") < browserManager.indexOf("webView.loadUrl(normalized)"))
        check("context !is Activity" in googleAuthRouter)
        check("Intent.FLAG_ACTIVITY_NEW_TASK" in googleAuthRouter)
        check("GoogleAuthRouter.shouldRouteExternally(currentUrl)" in webViewHolder)
        check("GoogleAuthRouter.openInCustomTab(webView.context, currentUrl)" in webViewHolder)
        val executionCoordinator = generatedSources.get()
            .file("com/openminis/app/sandbox/ExecutionCoordinator.kt").asFile.readText()
        check("mounts[\"/sdcard\"]" in executionCoordinator)
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
