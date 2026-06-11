package com.codex.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class CodexExtensionManagerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "com.codex.mobile.extra.CODEX_EXTENSION_MODE"
        const val MODE_SKILLS = "skills"
        const val MODE_PLUGINS = "plugins"

        private const val SKILLS_DOCS_URL = "https://openai.com/academy/codex-plugins-and-skills/"
        private const val APPS_HELP_URL = "https://help.openai.com/en/articles/11487775-connectors-in-chatgpt"
    }

    private enum class Mode {
        SKILLS,
        PLUGINS,
    }

    private sealed class RowItem(
        open val title: String,
        open val summary: String,
        open val meta: String,
    ) {
        data class Skill(
            val skillName: String,
            val displayName: String,
            val description: String,
            val path: String,
            val cwd: String,
            val scope: String,
            val enabled: Boolean,
            val dependencySummary: String,
        ) : RowItem(
                title = displayName,
                summary = description.ifEmpty { skillName },
                meta = buildString {
                    append(if (enabled) "已启用" else "未启用")
                    if (scope.isNotEmpty()) append(" · ").append(scope)
                    if (dependencySummary.isNotEmpty()) append(" · ").append(dependencySummary)
                },
            )

        data class App(
            val appId: String,
            val displayName: String,
            val description: String,
            val status: String,
            val authorizationUrl: String,
            val oauthName: String,
            val raw: JSONObject,
        ) : RowItem(
                title = displayName,
                summary = description.ifEmpty { appId },
                meta = status.ifEmpty { "官方应用" },
            )

        data class Plugin(
            val pluginId: String,
            val pluginName: String,
            val displayName: String,
            val description: String,
            val marketplacePath: String,
            val marketplaceName: String,
            val marketplaceLabel: String,
            val installed: Boolean?,
            val oauthName: String,
            val authorizationUrl: String,
            val raw: JSONObject,
        ) : RowItem(
                title = displayName,
                summary = description.ifEmpty { pluginId },
                meta = buildString {
                    append(marketplaceLabel.ifEmpty { "官方插件目录" })
                    installed?.let {
                        append(" · ")
                        append(if (it) "已安装" else "未安装")
                    }
                },
            )

        data class LocalPlugin(
            val pluginId: String,
            val enabled: Boolean?,
            val rawConfig: JSONObject?,
        ) : RowItem(
                title = pluginId,
                summary = rawConfig?.toString(2) ?: "暂无额外配置",
                meta = when (enabled) {
                    true -> "本地配置 · 已启用"
                    false -> "本地配置 · 已禁用"
                    null -> "本地配置 · 未声明 enabled"
                },
            )

        data class Info(
            override val title: String,
            override val summary: String,
            override val meta: String,
        ) : RowItem(title, summary, meta)
    }

    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var btnModeSkills: Button
    private lateinit var btnModePlugins: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnPrimaryAction: Button
    private lateinit var btnSecondaryAction: Button
    private lateinit var btnTertiaryAction: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var listView: ListView

    private var currentMode = Mode.SKILLS
    private var loading = false
    private var rows = mutableListOf<RowItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_codex_extension_manager)

        tvTitle = findViewById(R.id.tvCodexExtensionTitle)
        tvSubtitle = findViewById(R.id.tvCodexExtensionSubtitle)
        btnModeSkills = findViewById(R.id.btnCodexExtensionModeSkills)
        btnModePlugins = findViewById(R.id.btnCodexExtensionModePlugins)
        btnRefresh = findViewById(R.id.btnCodexExtensionRefresh)
        btnPrimaryAction = findViewById(R.id.btnCodexExtensionPrimaryAction)
        btnSecondaryAction = findViewById(R.id.btnCodexExtensionSecondaryAction)
        btnTertiaryAction = findViewById(R.id.btnCodexExtensionTertiaryAction)
        progressBar = findViewById(R.id.progressCodexExtension)
        tvStatus = findViewById(R.id.tvCodexExtensionStatus)
        listView = findViewById(R.id.listCodexExtensions)

        currentMode =
            when (intent.getStringExtra(EXTRA_MODE)?.trim()) {
                MODE_PLUGINS -> Mode.PLUGINS
                else -> Mode.SKILLS
            }

        btnModeSkills.setOnClickListener {
            if (currentMode == Mode.SKILLS) return@setOnClickListener
            currentMode = Mode.SKILLS
            updateModeUi()
            loadData()
        }
        btnModePlugins.setOnClickListener {
            if (currentMode == Mode.PLUGINS) return@setOnClickListener
            currentMode = Mode.PLUGINS
            updateModeUi()
            loadData()
        }
        btnRefresh.setOnClickListener { loadData() }
        btnPrimaryAction.setOnClickListener {
            when (currentMode) {
                Mode.SKILLS -> openCreateSkillDialog()
                Mode.PLUGINS -> openCreateLocalPluginDialog()
            }
        }
        btnSecondaryAction.setOnClickListener {
            when (currentMode) {
                Mode.SKILLS -> openExternalUrl(SKILLS_DOCS_URL)
                Mode.PLUGINS -> openExternalUrl(APPS_HELP_URL)
            }
        }
        btnTertiaryAction.setOnClickListener {
            when (currentMode) {
                Mode.SKILLS -> copySkillsDirectoryPath()
                Mode.PLUGINS -> reloadMcpServers()
            }
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            val row = rows.getOrNull(position) ?: return@setOnItemClickListener
            when (row) {
                is RowItem.Skill -> showSkillActions(row)
                is RowItem.App -> showAppActions(row)
                is RowItem.Plugin -> showPluginActions(row)
                is RowItem.LocalPlugin -> showLocalPluginActions(row)
                is RowItem.Info -> showInfoDialog(row.title, row.summary, row.meta)
            }
        }

        updateModeUi()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun updateModeUi() {
        val skillsMode = currentMode == Mode.SKILLS
        btnModeSkills.isEnabled = !skillsMode
        btnModePlugins.isEnabled = skillsMode
        if (skillsMode) {
            tvTitle.text = getString(R.string.codex_skills_manager_title)
            tvSubtitle.text = getString(R.string.codex_skills_manager_subtitle)
            btnPrimaryAction.text = getString(R.string.codex_skills_action_create)
            btnSecondaryAction.text = getString(R.string.codex_extension_action_docs)
            btnTertiaryAction.text = getString(R.string.codex_skills_action_copy_path)
        } else {
            tvTitle.text = getString(R.string.codex_plugins_manager_title)
            tvSubtitle.text = getString(R.string.codex_plugins_manager_subtitle)
            btnPrimaryAction.text = getString(R.string.codex_plugins_action_create_local)
            btnSecondaryAction.text = getString(R.string.codex_plugins_action_apps_help)
            btnTertiaryAction.text = getString(R.string.codex_plugins_action_reload_mcp)
        }
    }

    private fun loadData() {
        if (loading) return
        loading = true
        progressBar.visibility = View.VISIBLE
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.codex_extension_loading)

        Thread {
            try {
                val loadedRows =
                    when (currentMode) {
                        Mode.SKILLS -> loadSkills()
                        Mode.PLUGINS -> loadPluginsAndApps()
                    }
                runOnUiThread {
                    rows = loadedRows.toMutableList()
                    loading = false
                    progressBar.visibility = View.GONE
                    renderRows()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    loading = false
                    progressBar.visibility = View.GONE
                    tvStatus.visibility = View.VISIBLE
                    tvStatus.text = getString(R.string.codex_extension_error_prefix) + (error.message ?: "unknown")
                    Toast.makeText(
                        this,
                        getString(R.string.codex_extension_error_prefix) + (error.message ?: "unknown"),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun renderRows() {
        tvStatus.visibility = View.VISIBLE
        tvStatus.text =
            if (rows.isEmpty()) {
                when (currentMode) {
                    Mode.SKILLS -> getString(R.string.codex_skills_empty)
                    Mode.PLUGINS -> getString(R.string.codex_plugins_empty)
                }
            } else {
                getString(R.string.codex_extension_loaded_count, rows.size)
            }
        listView.adapter = RowAdapter(rows)
    }

    private fun loadSkills(): List<RowItem> {
        val result = LocalBridgeClients.callCodexRpc("skills/list")
        val output = mutableListOf<RowItem>()
        val roots = result.optJSONArray("data") ?: JSONArray()
        for (i in 0 until roots.length()) {
            val rootObj = roots.optJSONObject(i) ?: continue
            val cwd = rootObj.optString("cwd", "").trim()
            val skills = rootObj.optJSONArray("skills") ?: JSONArray()
            for (j in 0 until skills.length()) {
                val skill = skills.optJSONObject(j) ?: continue
                val interfaceObj = skill.optJSONObject("interface")
                val displayName =
                    interfaceObj?.optString("displayName", "").orEmpty().trim()
                        .ifEmpty { skill.optString("name", "").trim() }
                val tools = skill.optJSONObject("dependencies")?.optJSONArray("tools")
                val dependencySummary =
                    if (tools != null && tools.length() > 0) {
                        "依赖 ${tools.length()} 个工具"
                    } else {
                        ""
                    }
                output +=
                    RowItem.Skill(
                        skillName = skill.optString("name", "").trim(),
                        displayName = displayName,
                        description = skill.optString("description", "").trim(),
                        path = skill.optString("path", "").trim(),
                        cwd = cwd,
                        scope = skill.optString("scope", "").trim(),
                        enabled = skill.optBoolean("enabled", true),
                        dependencySummary = dependencySummary,
                    )
            }
        }
        return output.sortedBy { it.title.lowercase(Locale.getDefault()) }
    }

    private fun loadPluginsAndApps(): List<RowItem> {
        val output = mutableListOf<RowItem>()

        val appResult = runCatching { LocalBridgeClients.callCodexRpc("app/list") }.getOrElse { JSONObject() }
        val pluginResult = runCatching { LocalBridgeClients.callCodexRpc("plugin/list") }.getOrElse { JSONObject() }
        val configResult = runCatching { LocalBridgeClients.callCodexRpc("config/read") }.getOrElse { JSONObject() }

        val apps = appResult.optJSONArray("data") ?: JSONArray()
        for (i in 0 until apps.length()) {
            val app = apps.optJSONObject(i) ?: continue
            output +=
                RowItem.App(
                    appId = app.optStringAny("id", "appId", "slug", "name"),
                    displayName = app.optStringAny("displayName", "title", "name", "id").ifEmpty { "未命名应用" },
                    description = app.optStringAny("description", "shortDescription", "subtitle"),
                    status = appStatus(app),
                    authorizationUrl = app.optStringAny("authorizationUrl", "authorizeUrl", "manageUrl", "settingsUrl", "url", "webUrl"),
                    oauthName = app.optStringAny("oauthName", "mcpServerName", "serverName"),
                    raw = app,
                )
        }

        val marketplaces = pluginResult.optJSONArray("marketplaces") ?: JSONArray()
        for (i in 0 until marketplaces.length()) {
            val marketplace = marketplaces.optJSONObject(i) ?: continue
            val marketplacePath = marketplace.optStringAny("marketplacePath", "path", "id", "name")
            val marketplaceName = marketplace.optStringAny("marketplaceName", "name", "id")
            val marketplaceLabel = marketplace.optStringAny("displayName", "title", "name", "path", "id").ifEmpty { "官方插件目录" }
            val plugins =
                marketplace.optJSONArray("plugins")
                    ?: marketplace.optJSONArray("entries")
                    ?: marketplace.optJSONArray("data")
                    ?: JSONArray()
            for (j in 0 until plugins.length()) {
                val plugin = plugins.optJSONObject(j) ?: continue
                output +=
                    RowItem.Plugin(
                        pluginId = plugin.optStringAny("pluginId", "id", "slug", "name"),
                        pluginName = plugin.optStringAny("pluginName", "name", "slug", "pluginId", "id"),
                        displayName = plugin.optStringAny("displayName", "title", "name", "pluginId", "id").ifEmpty { "未命名插件" },
                        description = plugin.optStringAny("description", "shortDescription", "subtitle"),
                        marketplacePath = plugin.optStringAny("marketplacePath", "path").ifEmpty { marketplacePath },
                        marketplaceName = plugin.optStringAny("marketplaceName", "remoteMarketplaceName").ifEmpty { marketplaceName },
                        marketplaceLabel = marketplaceLabel,
                        installed = plugin.optBooleanAny("installed", "enabled"),
                        oauthName = plugin.optStringAny("oauthName", "mcpServerName", "serverName"),
                        authorizationUrl = plugin.optStringAny("authorizationUrl", "authorizeUrl", "manageUrl", "settingsUrl", "url", "webUrl"),
                        raw = plugin,
                    )
            }
        }

        val config = configResult.optJSONObject("config") ?: JSONObject()
        val pluginEntries = config.optJSONObject("plugins")?.optJSONObject("entries") ?: JSONObject()
        val pluginKeys = jsonKeys(pluginEntries).sorted()
        for (key in pluginKeys) {
            if (key == "enabled") continue
            val raw = pluginEntries.optJSONObject(key)
            output +=
                RowItem.LocalPlugin(
                    pluginId = key,
                    enabled = raw?.optBooleanAny("enabled"),
                    rawConfig = raw?.optJSONObject("config"),
                )
        }

        if (output.isEmpty()) {
            output +=
                RowItem.Info(
                    title = getString(R.string.codex_plugins_empty_title),
                    summary = getString(R.string.codex_plugins_empty),
                    meta = getString(R.string.codex_plugins_empty_hint),
                )
        }
        return output.sortedBy { it.title.lowercase(Locale.getDefault()) }
    }

    private fun showSkillActions(row: RowItem.Skill) {
        val actions = mutableListOf<String>()
        actions += if (row.enabled) getString(R.string.codex_skills_action_disable) else getString(R.string.codex_skills_action_enable)
        actions += getString(R.string.codex_extension_action_view_detail)
        if (isUserSkill(row.path)) {
            actions += getString(R.string.codex_skills_action_delete)
        }

        AlertDialog.Builder(this)
            .setTitle(row.displayName)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    getString(R.string.codex_skills_action_enable) -> updateSkillEnabled(row, true)
                    getString(R.string.codex_skills_action_disable) -> updateSkillEnabled(row, false)
                    getString(R.string.codex_extension_action_view_detail) -> {
                        showInfoDialog(
                            row.displayName,
                            row.description.ifEmpty { row.skillName },
                            buildString {
                                append("名称：").append(row.skillName)
                                append("\n作用域：").append(row.scope.ifEmpty { "unknown" })
                                append("\n状态：").append(if (row.enabled) "已启用" else "未启用")
                                append("\n路径：").append(row.path)
                                if (row.cwd.isNotEmpty()) append("\n工作目录：").append(row.cwd)
                                if (row.dependencySummary.isNotEmpty()) append("\n").append(row.dependencySummary)
                            },
                        )
                    }
                    getString(R.string.codex_skills_action_delete) -> confirmDeleteSkill(row)
                }
            }
            .show()
    }

    private fun updateSkillEnabled(row: RowItem.Skill, enabled: Boolean) {
        performBackgroundAction(
            loadingText = getString(R.string.codex_extension_loading),
            successText = if (enabled) getString(R.string.codex_skills_enabled_toast) else getString(R.string.codex_skills_disabled_toast),
        ) {
            LocalBridgeClients.callCodexRpc(
                "skills/config/write",
                JSONObject()
                    .put("path", row.path)
                    .put("enabled", enabled),
            )
        }
    }

    private fun confirmDeleteSkill(row: RowItem.Skill) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.codex_skills_delete_confirm_title))
            .setMessage(getString(R.string.codex_skills_delete_confirm_message))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.prompt_delete_text)) { _, _ ->
                performBackgroundAction(
                    loadingText = getString(R.string.codex_extension_loading),
                    successText = getString(R.string.codex_skills_deleted_toast),
                ) {
                    val file = File(row.path)
                    val dir = if (file.isDirectory) file else file.parentFile
                    if (dir == null || !dir.exists() || !dir.deleteRecursively()) {
                        throw IllegalStateException(getString(R.string.codex_skills_delete_failed))
                    }
                }
            }
            .show()
    }

    private fun showAppActions(row: RowItem.App) {
        val actions = mutableListOf<String>()
        if (row.oauthName.isNotEmpty()) {
            actions += getString(R.string.codex_plugins_action_authorize)
        }
        if (row.authorizationUrl.isNotEmpty()) {
            actions += getString(R.string.codex_plugins_action_open_browser)
        }
        actions += getString(R.string.codex_extension_action_view_detail)

        AlertDialog.Builder(this)
            .setTitle(row.displayName)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    getString(R.string.codex_plugins_action_authorize) -> authorizeMcpServer(row.oauthName)
                    getString(R.string.codex_plugins_action_open_browser) -> openExternalUrl(row.authorizationUrl)
                    getString(R.string.codex_extension_action_view_detail) -> {
                        showInfoDialog(
                            row.displayName,
                            row.description.ifEmpty { row.appId },
                            prettyJson(row.raw),
                        )
                    }
                }
            }
            .show()
    }

    private fun showPluginActions(row: RowItem.Plugin) {
        val actions = mutableListOf<String>()
        if (row.marketplacePath.isNotEmpty()) {
            actions += if (row.installed == true) getString(R.string.codex_plugins_action_uninstall) else getString(R.string.codex_plugins_action_install)
        }
        actions += getString(R.string.codex_extension_action_view_detail)
        if (row.oauthName.isNotEmpty()) {
            actions += getString(R.string.codex_plugins_action_authorize)
        }
        if (row.authorizationUrl.isNotEmpty()) {
            actions += getString(R.string.codex_plugins_action_open_browser)
        }

        AlertDialog.Builder(this)
            .setTitle(row.displayName)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    getString(R.string.codex_plugins_action_install) -> installPlugin(row)
                    getString(R.string.codex_plugins_action_uninstall) -> uninstallPlugin(row)
                    getString(R.string.codex_plugins_action_authorize) -> authorizeMcpServer(row.oauthName)
                    getString(R.string.codex_plugins_action_open_browser) -> openExternalUrl(row.authorizationUrl)
                    getString(R.string.codex_extension_action_view_detail) -> readPluginDetail(row)
                }
            }
            .show()
    }

    private fun showLocalPluginActions(row: RowItem.LocalPlugin) {
        val actions = mutableListOf<String>()
        actions += if (row.enabled == true) getString(R.string.codex_plugins_action_disable_local) else getString(R.string.codex_plugins_action_enable_local)
        actions += getString(R.string.codex_extension_action_view_detail)

        AlertDialog.Builder(this)
            .setTitle(row.pluginId)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    getString(R.string.codex_plugins_action_enable_local) -> updateLocalPluginEnabled(row.pluginId, true)
                    getString(R.string.codex_plugins_action_disable_local) -> updateLocalPluginEnabled(row.pluginId, false)
                    getString(R.string.codex_extension_action_view_detail) -> {
                        showInfoDialog(
                            row.pluginId,
                            "本地插件配置",
                            row.rawConfig?.toString(2) ?: "{}",
                        )
                    }
                }
            }
            .show()
    }

    private fun openCreateSkillDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val nameInput = EditText(this).apply {
            hint = getString(R.string.codex_skills_name_hint)
            setSingleLine(true)
        }
        val slugInput = EditText(this).apply {
            hint = getString(R.string.codex_skills_slug_hint)
            setSingleLine(true)
        }
        val descInput = EditText(this).apply {
            hint = getString(R.string.codex_skills_description_hint)
            minLines = 2
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val contentInput = EditText(this).apply {
            hint = getString(R.string.codex_skills_content_hint)
            minLines = 6
            maxLines = 12
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        container.addView(nameInput)
        container.addView(slugInput)
        container.addView(descInput)
        container.addView(contentInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.codex_skills_action_create))
            .setView(container)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.prompt_save_text), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val slug = slugInput.text.toString().trim().ifEmpty { slugify(name) }
                val description = descInput.text.toString().trim()
                val content = contentInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, getString(R.string.codex_skills_invalid_name), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (slug.isEmpty()) {
                    Toast.makeText(this, getString(R.string.codex_skills_invalid_slug), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (content.isEmpty()) {
                    Toast.makeText(this, getString(R.string.codex_skills_invalid_content), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                performBackgroundAction(
                    loadingText = getString(R.string.codex_extension_loading),
                    successText = getString(R.string.codex_skills_created_toast),
                    dismissDialog = dialog,
                ) {
                    createLocalSkill(slug, name, description, content)
                }
            }
        }
        dialog.show()
    }

    private fun openCreateLocalPluginDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val pluginIdInput = EditText(this).apply {
            hint = getString(R.string.codex_plugins_id_hint)
            setSingleLine(true)
        }
        val configInput = EditText(this).apply {
            hint = getString(R.string.codex_plugins_config_hint)
            minLines = 4
            maxLines = 8
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        container.addView(pluginIdInput)
        container.addView(configInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.codex_plugins_action_create_local))
            .setView(container)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.prompt_save_text), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pluginId = pluginIdInput.text.toString().trim()
                val configRaw = configInput.text.toString().trim()
                if (!pluginId.matches(Regex("[A-Za-z0-9._-]+"))) {
                    Toast.makeText(this, getString(R.string.codex_plugins_invalid_id), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                performBackgroundAction(
                    loadingText = getString(R.string.codex_extension_loading),
                    successText = getString(R.string.codex_plugins_local_created_toast),
                    dismissDialog = dialog,
                ) {
                    createLocalPluginEntry(pluginId, configRaw)
                    runCatching { LocalBridgeClients.callCodexRpc("config/mcpServer/reload") }
                }
            }
        }
        dialog.show()
    }

    private fun installPlugin(row: RowItem.Plugin) {
        performBackgroundAction(
            loadingText = getString(R.string.codex_extension_loading),
            successText = getString(R.string.codex_plugins_installed_toast),
        ) {
            val params = buildPluginLookupParams(row)
            val result = LocalBridgeClients.callCodexRpc("plugin/install", params)
            val url =
                result.optStringAny("url", "authorizationUrl", "authorizeUrl", "browserUrl")
            if (url.isNotEmpty()) {
                runOnUiThread { openExternalUrl(url) }
            }
        }
    }

    private fun uninstallPlugin(row: RowItem.Plugin) {
        performBackgroundAction(
            loadingText = getString(R.string.codex_extension_loading),
            successText = getString(R.string.codex_plugins_uninstalled_toast),
        ) {
            val params = buildPluginLookupParams(row)
            if (row.pluginId.isNotEmpty()) params.put("pluginId", row.pluginId)
            LocalBridgeClients.callCodexRpc("plugin/uninstall", params)
        }
    }

    private fun readPluginDetail(row: RowItem.Plugin) {
        performBackgroundAction(
            loadingText = getString(R.string.codex_extension_loading),
            successText = null,
            autoReload = false,
        ) {
            val params = buildPluginLookupParams(row)
            val result = LocalBridgeClients.callCodexRpc("plugin/read", params)
            runOnUiThread {
                showInfoDialog(row.displayName, row.description.ifEmpty { row.pluginId }, prettyJson(result))
            }
        }
    }

    private fun authorizeMcpServer(name: String) {
        if (name.isBlank()) {
            Toast.makeText(this, getString(R.string.codex_plugins_no_oauth_target), Toast.LENGTH_SHORT).show()
            return
        }
        performBackgroundAction(
            loadingText = getString(R.string.codex_extension_loading),
            successText = getString(R.string.codex_plugins_authorize_started),
            autoReload = true,
        ) {
            val result = LocalBridgeClients.callCodexRpc(
                "mcpServer/oauth/login",
                JSONObject().put("name", name),
            )
            val url =
                result.optStringAny("url", "authorizationUrl", "authorizeUrl", "browserUrl")
            runOnUiThread {
                if (url.isNotEmpty()) {
                    openExternalUrl(url)
                } else {
                    showInfoDialog(name, getString(R.string.codex_plugins_authorize_started), prettyJson(result))
                }
            }
        }
    }

    private fun reloadMcpServers() {
        performBackgroundAction(
            loadingText = getString(R.string.codex_extension_loading),
            successText = getString(R.string.codex_plugins_reload_done),
        ) {
            LocalBridgeClients.callCodexRpc("config/mcpServer/reload")
        }
    }

    private fun createLocalSkill(slug: String, name: String, description: String, content: String) {
        val homeDir = BootstrapInstaller.getPaths(this).homeDir
        val skillDir = File(homeDir, ".codex/skills/$slug")
        if (skillDir.exists()) {
            throw IllegalStateException(getString(R.string.codex_skills_already_exists))
        }
        if (!skillDir.mkdirs()) {
            throw IllegalStateException(getString(R.string.codex_skills_create_failed))
        }
        val skillFile = File(skillDir, "SKILL.md")
        val text =
            buildString {
                append("# ").append(name).append("\n\n")
                if (description.isNotEmpty()) {
                    append(description).append("\n\n")
                }
                append(content.trim()).append("\n")
            }
        skillFile.writeText(text)
    }

    private fun createLocalPluginEntry(pluginId: String, configRaw: String) {
        val configFile = File(BootstrapInstaller.getPaths(this).homeDir, ".codex/config.toml")
        if (!configFile.exists()) {
            configFile.parentFile?.mkdirs()
            configFile.writeText("")
        }
        val configObject =
            if (configRaw.isBlank()) null
            else runCatching { JSONObject(configRaw) }.getOrElse {
                throw IllegalStateException(getString(R.string.codex_plugins_invalid_config))
            }
        val current = configFile.readText()
        if (current.contains("[plugins.entries.$pluginId]") || current.contains("[plugins.entries.$pluginId.config]")) {
            throw IllegalStateException(getString(R.string.codex_plugins_already_exists))
        }
        val block =
            buildString {
                if (current.isNotBlank() && !current.endsWith("\n")) append("\n")
                append("\n[plugins.entries.").append(pluginId).append("]\n")
                append("enabled = true\n")
                if (configObject != null) {
                    append("\n[plugins.entries.").append(pluginId).append(".config]\n")
                    append(jsonObjectToToml(configObject))
                }
            }
        configFile.appendText(block)
    }

    private fun updateLocalPluginEnabled(pluginId: String, enabled: Boolean) {
        performBackgroundAction(
            loadingText = getString(R.string.codex_extension_loading),
            successText = if (enabled) getString(R.string.codex_plugins_local_enabled_toast) else getString(R.string.codex_plugins_local_disabled_toast),
        ) {
            val configFile = File(BootstrapInstaller.getPaths(this).homeDir, ".codex/config.toml")
            if (!configFile.exists()) {
                configFile.parentFile?.mkdirs()
                configFile.writeText("")
            }
            val updated = upsertPluginEnabled(configFile.readText(), pluginId, enabled)
            configFile.writeText(updated)
            runCatching { LocalBridgeClients.callCodexRpc("config/mcpServer/reload") }
        }
    }

    private fun upsertPluginEnabled(current: String, pluginId: String, enabled: Boolean): String {
        val header = "[plugins.entries.$pluginId]"
        val lines = current.lines().toMutableList()
        val headerIndex = lines.indexOfFirst { it.trim() == header }
        val enabledLine = "enabled = $enabled"
        if (headerIndex >= 0) {
            var endIndex = lines.size
            for (i in headerIndex + 1 until lines.size) {
                if (lines[i].trim().startsWith("[") && lines[i].trim().endsWith("]")) {
                    endIndex = i
                    break
                }
            }
            for (i in headerIndex + 1 until endIndex) {
                if (lines[i].trim().startsWith("enabled =")) {
                    lines[i] = enabledLine
                    return lines.joinToString("\n").trimEnd() + "\n"
                }
            }
            lines.add(headerIndex + 1, enabledLine)
            return lines.joinToString("\n").trimEnd() + "\n"
        }
        return buildString {
            append(current.trimEnd())
            if (isNotEmpty()) append("\n\n")
            append(header).append("\n")
            append(enabledLine).append("\n")
        }
    }

    private fun jsonObjectToToml(obj: JSONObject): String {
        val keys = jsonKeys(obj).sorted()
        if (keys.isEmpty()) return ""
        return buildString {
            for (key in keys) {
                val value = obj.opt(key)
                if (value is JSONObject || value is JSONArray || value == JSONObject.NULL) {
                    throw IllegalStateException(getString(R.string.codex_plugins_invalid_config))
                }
                append(key).append(" = ").append(tomlLiteral(value)).append("\n")
            }
        }
    }

    private fun tomlLiteral(value: Any?): String {
        return when (value) {
            null -> "\"\""
            is Boolean, is Int, is Long, is Float, is Double -> value.toString()
            else -> "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }

    private fun isUserSkill(path: String): Boolean {
        val skillsRoot = File(BootstrapInstaller.getPaths(this).homeDir, ".codex/skills").absolutePath
        return path.startsWith(skillsRoot)
    }

    private fun copySkillsDirectoryPath() {
        val path = File(BootstrapInstaller.getPaths(this).homeDir, ".codex/skills").absolutePath
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("codex-skills-dir", path))
        Toast.makeText(this, getString(R.string.codex_skills_path_copied), Toast.LENGTH_SHORT).show()
    }

    private fun openExternalUrl(url: String) {
        if (url.isBlank()) {
            Toast.makeText(this, getString(R.string.codex_extension_open_url_failed), Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, getString(R.string.codex_extension_open_url_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showInfoDialog(title: String, summary: String, detail: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(
                buildString {
                    if (summary.isNotBlank()) append(summary)
                    if (detail.isNotBlank()) {
                        if (isNotEmpty()) append("\n\n")
                        append(detail)
                    }
                },
            )
            .setPositiveButton(getString(R.string.close), null)
            .show()
    }

    private fun performBackgroundAction(
        loadingText: String,
        successText: String?,
        dismissDialog: AlertDialog? = null,
        autoReload: Boolean = true,
        action: () -> Unit,
    ) {
        if (loading) return
        loading = true
        progressBar.visibility = View.VISIBLE
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = loadingText
        Thread {
            try {
                action()
                runOnUiThread {
                    dismissDialog?.dismiss()
                    successText?.let {
                        Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                    }
                    loading = false
                    progressBar.visibility = View.GONE
                    if (autoReload) {
                        loadData()
                    } else {
                        tvStatus.text = loadingText
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    loading = false
                    progressBar.visibility = View.GONE
                    tvStatus.visibility = View.VISIBLE
                    tvStatus.text = getString(R.string.codex_extension_error_prefix) + (error.message ?: "unknown")
                    Toast.makeText(
                        this,
                        getString(R.string.codex_extension_error_prefix) + (error.message ?: "unknown"),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun prettyJson(json: JSONObject): String = runCatching { json.toString(2) }.getOrElse { json.toString() }

    private fun slugify(value: String): String {
        return value
            .trim()
            .lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
    }

    private fun appStatus(app: JSONObject): String {
        val parts = mutableListOf<String>()
        app.optBooleanAny("connected", "authorized", "installed", "enabled")?.let {
            parts += if (it) "已连接" else "未连接"
        }
        val source = app.optStringAny("category", "type", "source")
        if (source.isNotEmpty()) {
            parts += source
        }
        return parts.joinToString(" · ")
    }

    private fun buildPluginLookupParams(row: RowItem.Plugin): JSONObject {
        val params = JSONObject()
        val pluginName = row.pluginName.ifBlank { row.pluginId }
        if (pluginName.isBlank()) {
            throw IllegalStateException("插件条目缺少 pluginName")
        }
        params.put("pluginName", pluginName)
        if (row.marketplacePath.isNotBlank()) {
            params.put("marketplacePath", row.marketplacePath)
        } else if (row.marketplaceName.isNotBlank()) {
            params.put("remoteMarketplaceName", row.marketplaceName)
        }
        return params
    }

    private fun jsonKeys(obj: JSONObject): List<String> {
        val output = mutableListOf<String>()
        val iterator = obj.keys()
        while (iterator.hasNext()) {
            output += iterator.next().toString()
        }
        return output
    }

    private fun JSONObject.optStringAny(vararg keys: String): String {
        for (key in keys) {
            val value = optString(key, "").trim()
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    private fun JSONObject.optBooleanAny(vararg keys: String): Boolean? {
        for (key in keys) {
            if (has(key) && !isNull(key)) return optBoolean(key)
        }
        return null
    }

    private inner class RowAdapter(private val items: List<RowItem>) : BaseAdapter() {
        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view =
                convertView ?: LayoutInflater.from(this@CodexExtensionManagerActivity)
                    .inflate(R.layout.item_codex_extension_row, parent, false)
            val row = items[position]
            view.findViewById<TextView>(R.id.tvCodexExtensionRowTitle).text = row.title
            view.findViewById<TextView>(R.id.tvCodexExtensionRowSummary).text = row.summary
            view.findViewById<TextView>(R.id.tvCodexExtensionRowMeta).text = row.meta
            return view
        }
    }
}
