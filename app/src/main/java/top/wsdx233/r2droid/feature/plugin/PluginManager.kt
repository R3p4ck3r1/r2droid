package top.wsdx233.r2droid.feature.plugin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

object PluginManager {
    private const val TAG = "PluginManager"
    private const val DEFAULT_REMOTE_INDEX = "https://raw.githubusercontent.com/wsdx233/r2droid-plugins/refs/heads/main/index.json"

    private val initialized = AtomicBoolean(false)
    private lateinit var appContext: Context

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val _catalog = MutableStateFlow<List<PluginCatalogItem>>(emptyList())
    val catalog = _catalog.asStateFlow()

    private val _installed = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val installed = _installed.asStateFlow()

    private val _projectTabs = MutableStateFlow<List<PluginProjectTabDescriptor>>(emptyList())
    val projectTabs = _projectTabs.asStateFlow()

    private val _screenTabs = MutableStateFlow<List<PluginScreenTabDescriptor>>(emptyList())
    val screenTabs = _screenTabs.asStateFlow()

    private val _repositorySources = MutableStateFlow<List<String>>(emptyList())
    val repositorySources = _repositorySources.asStateFlow()

    private val _isWorking = MutableStateFlow(false)
    val isWorking = _isWorking.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        ioScope.launch {
            ensureStructure()
            loadRepositorySources()
            installBundledPluginsIfNeeded()
            reloadInstalledFromDisk()
            runStartupEntryScripts()
            refreshCatalog()
        }
    }

    suspend fun refreshCatalog() = withContext(Dispatchers.IO) {
        ensureInitialized()
        _isWorking.value = true
        _status.value = "Refreshing plugin index..."
        try {
            val merged = linkedMapOf<String, PluginIndexEntry>()
            _repositorySources.value.forEach { source ->
                runCatching { loadIndexFromSource(source) }
                    .onSuccess { index ->
                        index.plugins.forEach { entry ->
                            val existing = merged[entry.id]
                            if (existing == null || compareVersion(entry.version, existing.version) > 0) {
                                merged[entry.id] = entry
                            }
                        }
                    }
                    .onFailure { e ->
                        appendLog("[catalog] source=$source failed: ${e.message}")
                    }
            }

            val installedMap = _installed.value.associateBy { it.state.id }
            _catalog.value = merged.values
                .sortedBy { it.name.lowercase() }
                .map { entry ->
                    val installedPlugin = installedMap[entry.id]
                    val hasUpgrade = installedPlugin?.let {
                        compareVersion(entry.version, it.state.version) > 0
                    } ?: false
                    PluginCatalogItem(
                        indexEntry = entry,
                        installed = installedPlugin,
                        hasUpgrade = hasUpgrade
                    )
                }
            _status.value = "Plugin index refreshed"
        } finally {
            _isWorking.value = false
        }
    }

    suspend fun install(entry: PluginIndexEntry): Result<Unit> = withContext(Dispatchers.IO) {
        ensureInitialized()
        _isWorking.value = true
        _status.value = "Installing ${entry.name}..."
        val tempZip = File(tempDir(), "${entry.id}-${System.currentTimeMillis()}.zip")
        val stagedDir = File(tempDir(), "${entry.id}-staged")
        val targetDir = File(packagesDir(), entry.id)
        val backupDir = File(packagesDir(), "${entry.id}-backup")

        runCatching {
            stagedDir.deleteRecursively()
            backupDir.deleteRecursively()

            downloadFile(entry.downloadUrl, tempZip)
            val digest = sha256(tempZip)
            if (!digest.equals(entry.sha256, ignoreCase = true)) {
                throw IllegalStateException("sha256 mismatch: expected=${entry.sha256}, actual=$digest")
            }

            stagedDir.mkdirs()
            unzipSafely(tempZip, stagedDir)

            val manifestFile = File(stagedDir, entry.manifestPath)
            if (!manifestFile.exists()) {
                throw IllegalStateException("manifest not found: ${entry.manifestPath}")
            }
            val manifest = json.decodeFromString(PluginManifest.serializer(), manifestFile.readText())
            if (manifest.id != entry.id) {
                throw IllegalStateException("manifest.id mismatch: ${manifest.id} != ${entry.id}")
            }

            if (targetDir.exists()) {
                if (!targetDir.renameTo(backupDir)) {
                    throw IllegalStateException("failed to backup current plugin directory")
                }
            }

            if (targetDir.exists()) targetDir.deleteRecursively()
            if (!stagedDir.renameTo(targetDir)) {
                throw IllegalStateException("failed to move staged plugin to target")
            }

            backupDir.deleteRecursively()

            val states = readInstalledStates().toMutableList()
            states.removeAll { it.id == entry.id }
            states += InstalledPluginState(
                id = entry.id,
                version = entry.version,
                installDir = targetDir.absolutePath,
                enabled = true,
                sourceUrl = entry.downloadUrl,
                sha256 = entry.sha256,
                manifestPath = entry.manifestPath,
                installedAt = System.currentTimeMillis()
            )
            writeInstalledStates(states)
            reloadInstalledFromDisk()
            runInstallScriptIfPresent(entry.id, reason = "install")
            refreshCatalog()
            appendLog("[install] ${entry.id}@${entry.version} success")
        }.recoverCatching { throwable ->
            appendLog("[install] ${entry.id} failed: ${throwable.message}")
            if (!targetDir.exists() && backupDir.exists()) {
                backupDir.renameTo(targetDir)
            }
            stagedDir.deleteRecursively()
            tempZip.delete()
            throw throwable
        }.onSuccess {
            _status.value = "Installed ${entry.name}"
        }.onFailure {
            _status.value = "Install failed: ${it.message}"
        }.also {
            tempZip.delete()
            stagedDir.deleteRecursively()
            _isWorking.value = false
        }
    }

    suspend fun uninstall(pluginId: String): Result<Unit> = withContext(Dispatchers.IO) {
        ensureInitialized()
        _isWorking.value = true
        _status.value = "Uninstalling $pluginId..."
        runCatching {
            val states = readInstalledStates().toMutableList()
            val target = states.find { it.id == pluginId }
            states.removeAll { it.id == pluginId }
            target?.let {
                PluginRuntime.stopAllForPlugin(pluginId)
                File(it.installDir).deleteRecursively()
            }
            writeInstalledStates(states)
            reloadInstalledFromDisk()
            refreshCatalog()
            appendLog("[uninstall] $pluginId success")
            _status.value = "Uninstalled $pluginId"
        }.onFailure {
            appendLog("[uninstall] $pluginId failed: ${it.message}")
            _status.value = "Uninstall failed: ${it.message}"
        }.also {
            _isWorking.value = false
        }
    }

    suspend fun delete(pluginId: String): Result<Unit> = uninstall(pluginId)

    suspend fun update(pluginId: String): Result<Unit> = withContext(Dispatchers.IO) {
        ensureInitialized()
        val entry = _catalog.value.firstOrNull { it.indexEntry.id == pluginId }?.indexEntry
            ?: return@withContext Result.failure(IllegalStateException("plugin not found in catalog: $pluginId"))
        install(entry)
    }

    suspend fun setEnabled(pluginId: String, enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        ensureInitialized()
        runCatching {
            val updated = readInstalledStates().map {
                if (it.id == pluginId) it.copy(enabled = enabled) else it
            }
            writeInstalledStates(updated)
            reloadInstalledFromDisk()
            if (enabled) {
                runInstallScriptIfPresent(pluginId, reason = "enable")
            }
            refreshCatalog()
            appendLog("[enable] $pluginId => $enabled")
        }
    }

    suspend fun addRepositorySource(url: String): Result<Unit> = withContext(Dispatchers.IO) {
        ensureInitialized()
        runCatching {
            val normalized = normalizeRepositorySource(url)
            if (normalized.isBlank()) error("empty source url")
            val current = _repositorySources.value.toMutableList()
            if (current.none { it.equals(normalized, ignoreCase = true) }) {
                current += normalized
            }
            writeRepositorySources(current)
            _repositorySources.value = current
            refreshCatalog()
        }
    }

    suspend fun removeRepositorySource(url: String): Result<Unit> = withContext(Dispatchers.IO) {
        ensureInitialized()
        runCatching {
            val normalized = normalizeRepositorySource(url)
            val current = _repositorySources.value.filterNot { it.equals(normalized, ignoreCase = true) }
            writeRepositorySources(current)
            _repositorySources.value = current
            refreshCatalog()
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun findInstalledPlugin(pluginId: String): InstalledPlugin? {
        return _installed.value.firstOrNull { it.state.id == pluginId }
    }

    fun resolvePluginFile(pluginId: String, relativePath: String): File? {
        val plugin = findInstalledPlugin(pluginId) ?: return null
        return resolvePathUnder(File(plugin.state.installDir), relativePath, mustExist = true)
    }

    fun resolvePluginPath(pluginId: String, relativePath: String, mustExist: Boolean = false): File? {
        val plugin = findInstalledPlugin(pluginId) ?: return null
        return resolvePathUnder(File(plugin.state.installDir), relativePath, mustExist = mustExist)
    }

    fun resolvePluginDataFile(pluginId: String, relativePath: String, mustExist: Boolean = false): File? {
        val plugin = findInstalledPlugin(pluginId) ?: return null
        val dataRoot = File(plugin.state.installDir, "data").apply { mkdirs() }
        return resolvePathUnder(dataRoot, relativePath, mustExist = mustExist)
    }

    fun getPluginPermissions(pluginId: String): Set<String> {
        val plugin = findInstalledPlugin(pluginId) ?: return emptySet()
        return plugin.manifest?.permissions?.toSet().orEmpty()
    }

    fun appendRuntimeLog(pluginId: String, line: String) {
        appendLog("[runtime][$pluginId] $line")
    }

    private fun ensureInitialized() {
        check(initialized.get()) { "PluginManager not initialized" }
    }

    private fun ensureStructure() {
        rootDir().mkdirs()
        packagesDir().mkdirs()
        tempDir().mkdirs()
    }

    private suspend fun loadRepositorySources() {
        val repoFile = repositoriesFile()
        val sources = runCatching {
            val raw = if (!repoFile.exists()) {
                listOf(DEFAULT_REMOTE_INDEX)
            } else {
                json.decodeFromString(ListSerializer(String.serializer()), repoFile.readText())
            }

            raw.map { normalizeRepositorySource(it) }
                .filter { it.isNotBlank() }
                .distinct()
                .ifEmpty { listOf(DEFAULT_REMOTE_INDEX) }
        }.getOrElse {
            appendLog("[repo] read failed: ${it.message}")
            listOf(DEFAULT_REMOTE_INDEX)
        }

        if (!repoFile.exists() || runCatching { repoFile.readText() }.getOrDefault("") != json.encodeToString(ListSerializer(String.serializer()), sources)) {
            writeRepositorySources(sources)
        }
        _repositorySources.value = sources
    }

    private fun normalizeRepositorySource(source: String): String {
        val normalized = source.trim()
        return when (normalized.lowercase()) {
            "asset://plugins/index.json",
            "plugin/index.json",
            "./plugin/index.json",
            "https://raw.githubusercontent.com/wsdx233/r2droid-plugins/refs/heads/main/index.json" -> DEFAULT_REMOTE_INDEX
            else -> normalized
        }
    }

    private suspend fun installBundledPluginsIfNeeded() {
        val assets = runCatching {
            appContext.assets.list("plugins/packages")
                .orEmpty()
                .sorted()
        }.getOrElse {
            appendLog("[bundled] list failed: ${it.message}")
            emptyList()
        }
        if (assets.isEmpty()) return

        val states = readInstalledStates().toMutableList()
        var changed = false

        assets.forEach { assetName ->
            val assetPath = "plugins/packages/$assetName"

            if (assetName.endsWith(".zip", ignoreCase = true)) {
                val fallbackId = assetName.removeSuffix(".zip")
                val tempZip = File(tempDir(), "bundled-$assetName-${System.currentTimeMillis()}.zip")
                val stagedDir = File(tempDir(), "bundled-${fallbackId}-${System.currentTimeMillis()}-staged")

                runCatching {
                    downloadFile("asset://$assetPath", tempZip)
                    val digest = sha256(tempZip)

                    stagedDir.mkdirs()
                    unzipSafely(tempZip, stagedDir)

                    val manifestFile = File(stagedDir, "manifest.json")
                    if (!manifestFile.exists()) {
                        throw IllegalStateException("manifest not found in bundled plugin: $assetPath")
                    }
                    val manifest = json.decodeFromString(PluginManifest.serializer(), manifestFile.readText())
                    val pluginId = manifest.id.ifBlank { fallbackId }

                    val existing = states.firstOrNull { it.id == pluginId }
                    if (existing != null && existing.sha256.equals(digest, ignoreCase = true) && File(existing.installDir).exists()) {
                        return@runCatching
                    }

                    val targetDir = File(packagesDir(), pluginId)
                    val backupDir = File(packagesDir(), "$pluginId-backup")
                    backupDir.deleteRecursively()

                    if (targetDir.exists()) {
                        PluginRuntime.stopAllForPlugin(pluginId)
                        if (!targetDir.renameTo(backupDir)) {
                            throw IllegalStateException("failed to backup current plugin directory")
                        }
                    }

                    if (targetDir.exists()) targetDir.deleteRecursively()
                    if (!stagedDir.renameTo(targetDir)) {
                        throw IllegalStateException("failed to move bundled plugin to target")
                    }

                    backupDir.deleteRecursively()
                    states.removeAll { it.id == pluginId }
                    states += InstalledPluginState(
                        id = pluginId,
                        version = manifest.version,
                        installDir = targetDir.absolutePath,
                        enabled = existing?.enabled ?: true,
                        sourceUrl = "asset://$assetPath",
                        sha256 = digest,
                        manifestPath = "manifest.json",
                        installedAt = System.currentTimeMillis()
                    )
                    appendLog("[bundled] synced $pluginId@${manifest.version}")
                    changed = true
                }.onFailure {
                    appendLog("[bundled] sync failed ($assetPath): ${it.message}")
                }.also {
                    stagedDir.deleteRecursively()
                    tempZip.delete()
                }
                return@forEach
            }

            val assetChildren = runCatching { appContext.assets.list(assetPath).orEmpty() }
                .getOrElse {
                    appendLog("[bundled] inspect failed ($assetPath): ${it.message}")
                    emptyArray()
                }
            if (assetChildren.isEmpty()) {
                appendLog("[bundled] skip unsupported asset entry: $assetPath")
                return@forEach
            }

            val stagedDir = File(tempDir(), "bundled-${assetName}-${System.currentTimeMillis()}-staged")
            runCatching {
                stagedDir.mkdirs()
                copyAssetTree(assetPath, stagedDir)

                val manifestFile = File(stagedDir, "manifest.json")
                if (!manifestFile.exists()) {
                    appendLog("[bundled] skip missing manifest: $assetPath")
                    return@runCatching
                }

                val manifest = json.decodeFromString(PluginManifest.serializer(), manifestFile.readText())
                val pluginId = manifest.id.ifBlank { assetName }
                val digest = sha256Directory(stagedDir)

                val existing = states.firstOrNull { it.id == pluginId }
                if (existing != null && existing.sha256.equals(digest, ignoreCase = true) && File(existing.installDir).exists()) {
                    return@runCatching
                }

                val targetDir = File(packagesDir(), pluginId)
                val backupDir = File(packagesDir(), "$pluginId-backup")
                backupDir.deleteRecursively()

                if (targetDir.exists()) {
                    PluginRuntime.stopAllForPlugin(pluginId)
                    if (!targetDir.renameTo(backupDir)) {
                        throw IllegalStateException("failed to backup current plugin directory")
                    }
                }

                if (targetDir.exists()) targetDir.deleteRecursively()
                if (!stagedDir.renameTo(targetDir)) {
                    throw IllegalStateException("failed to move bundled plugin to target")
                }

                backupDir.deleteRecursively()
                states.removeAll { it.id == pluginId }
                states += InstalledPluginState(
                    id = pluginId,
                    version = manifest.version,
                    installDir = targetDir.absolutePath,
                    enabled = existing?.enabled ?: true,
                    sourceUrl = "asset://$assetPath",
                    sha256 = digest,
                    manifestPath = "manifest.json",
                    installedAt = System.currentTimeMillis()
                )
                appendLog("[bundled] synced $pluginId@${manifest.version}")
                changed = true
            }.onFailure {
                appendLog("[bundled] sync failed ($assetPath): ${it.message}")
            }.also {
                stagedDir.deleteRecursively()
            }
        }

        if (changed) {
            writeInstalledStates(states)
        }
    }

    private suspend fun runStartupEntryScripts() {
        _installed.value
            .filter { it.state.enabled }
            .forEach { plugin ->
                runInstallScriptIfPresent(plugin.state.id, reason = "startup")
            }
    }

    private suspend fun runInstallScriptIfPresent(pluginId: String, reason: String) {
        val plugin = findInstalledPlugin(pluginId) ?: return
        val scriptRef = plugin.manifest?.entry?.script?.trim().orEmpty()
        if (scriptRef.isBlank()) return

        val scriptCode = resolvePluginFile(pluginId, scriptRef)
            ?.takeIf { it.exists() && it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull() }
            ?: scriptRef

        PluginRuntime.runPluginScript(pluginId, scriptCode)
            .onSuccess { result ->
                appendLog("[entry][$reason] $pluginId => ${result.lineSequence().firstOrNull().orEmpty()}")
            }
            .onFailure { e ->
                appendLog("[entry][$reason] $pluginId failed: ${e.message}")
            }
    }

    private fun writeRepositorySources(sources: List<String>) {
        repositoriesFile().writeText(json.encodeToString(ListSerializer(String.serializer()), sources))
    }

    private fun reloadInstalledFromDisk() {
        val installedStates = readInstalledStates()
        val installedPlugins = installedStates.mapNotNull { state ->
            val dir = File(state.installDir)
            if (!dir.exists()) {
                appendLog("[state] missing plugin dir: ${state.installDir}")
                return@mapNotNull null
            }
            val manifestFile = File(dir, state.manifestPath)
            val manifest = runCatching {
                json.decodeFromString(PluginManifest.serializer(), manifestFile.readText())
            }.getOrElse {
                appendLog("[state] manifest parse failed (${state.id}): ${it.message}")
                null
            }
            InstalledPlugin(state = state, manifest = manifest)
        }.sortedBy { it.state.id }

        _installed.value = installedPlugins
        _projectTabs.value = installedPlugins
            .filter { it.state.enabled }
            .flatMap { plugin ->
                val manifest = plugin.manifest ?: return@flatMap emptyList()
                manifest.projectTabs.map { tab ->
                    PluginProjectTabDescriptor(
                        pluginId = plugin.state.id,
                        pluginName = manifest.name,
                        tab = tab
                    )
                }
            }
        _screenTabs.value = installedPlugins
            .filter { it.state.enabled }
            .flatMap { plugin ->
                val manifest = plugin.manifest ?: return@flatMap emptyList()
                manifest.tabs.map { tab ->
                    PluginScreenTabDescriptor(
                        pluginId = plugin.state.id,
                        pluginName = manifest.name,
                        target = tab.target,
                        tab = tab
                    )
                }
            }
    }

    private fun readInstalledStates(): List<InstalledPluginState> {
        val file = installedStateFile()
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(InstalledPluginState.serializer()), file.readText())
        }.getOrElse {
            appendLog("[state] read failed: ${it.message}")
            emptyList()
        }
    }

    private fun writeInstalledStates(states: List<InstalledPluginState>) {
        installedStateFile().writeText(json.encodeToString(ListSerializer(InstalledPluginState.serializer()), states))
    }

    private fun loadIndexFromSource(source: String): PluginIndex {
        return when {
            source.startsWith("asset://") -> {
                val path = source.removePrefix("asset://")
                appContext.assets.open(path).bufferedReader().use { reader ->
                    json.decodeFromString(PluginIndex.serializer(), reader.readText())
                }
            }

            source.startsWith("file://") -> {
                val path = source.removePrefix("file://")
                json.decodeFromString(PluginIndex.serializer(), File(path).readText())
            }

            source.startsWith("http://") || source.startsWith("https://") -> {
                val text = downloadText(source)
                json.decodeFromString(PluginIndex.serializer(), text)
            }

            else -> {
                json.decodeFromString(PluginIndex.serializer(), File(source).readText())
            }
        }
    }

    private fun compareVersion(a: String, b: String): Int {
        val pa = a.split('.', '-', '_')
        val pb = b.split('.', '-', '_')
        val max = maxOf(pa.size, pb.size)
        repeat(max) { idx ->
            val xa = pa.getOrNull(idx)?.toIntOrNull() ?: 0
            val xb = pb.getOrNull(idx)?.toIntOrNull() ?: 0
            if (xa != xb) return xa.compareTo(xb)
        }
        return 0
    }

    private fun downloadText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP $code")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadFile(url: String, target: File) {
        when {
            url.startsWith("asset://") -> {
                val path = url.removePrefix("asset://")
                target.parentFile?.mkdirs()
                appContext.assets.open(path).use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            url.startsWith("file://") -> {
                val path = url.removePrefix("file://")
                val source = File(path)
                require(source.exists()) { "source file not found: $path" }
                target.parentFile?.mkdirs()
                source.inputStream().use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            else -> {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 30000
                }
                try {
                    val code = conn.responseCode
                    if (code != HttpURLConnection.HTTP_OK) {
                        throw IllegalStateException("download failed: HTTP $code")
                    }
                    target.parentFile?.mkdirs()
                    conn.inputStream.use { input ->
                        FileOutputStream(target).use { output ->
                            input.copyTo(output)
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun unzipSafely(zipFile: File, outputDir: File) {
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val normalizedName = entry.name
                    .replace('\\', '/')
                    .trimStart('/')
                if (normalizedName.isNotBlank()) {
                    val outFile = File(outputDir, normalizedName)
                    val baseCanonical = outputDir.canonicalFile
                    val outCanonical = outFile.canonicalFile
                    val basePath = baseCanonical.path
                    val outPath = outCanonical.path
                    if (!(outPath == basePath || outPath.startsWith(basePath + File.separator))) {
                        throw SecurityException("zip slip blocked: ${entry.name}")
                    }

                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val children = appContext.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            return
        }

        target.mkdirs()
        children.forEach { child ->
            val childAssetPath = "$assetPath/$child"
            val childTarget = File(target, child)
            val nested = appContext.assets.list(childAssetPath).orEmpty()
            if (nested.isEmpty()) {
                runCatching {
                    childTarget.parentFile?.mkdirs()
                    appContext.assets.open(childAssetPath).use { input ->
                        FileOutputStream(childTarget).use { output ->
                            input.copyTo(output)
                        }
                    }
                }.onFailure {
                    childTarget.mkdirs()
                }
            } else {
                copyAssetTree(childAssetPath, childTarget)
            }
        }
    }

    private fun sha256Directory(directory: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val base = directory.canonicalFile
        val files = base.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(base).path.replace('\\', '/') }
            .toList()

        files.forEach { file ->
            val relativePath = file.relativeTo(base).path.replace('\\', '/')
            digest.update(relativePath.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            FileInputStream(file).use { input ->
                val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    digest.update(buf, 0, read)
                }
            }
            digest.update(0.toByte())
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun appendLog(line: String) {
        Log.d(TAG, line)
        val next = _logs.value.toMutableList()
        next += "${System.currentTimeMillis()} | $line"
        if (next.size > 300) {
            _logs.value = next.takeLast(300)
        } else {
            _logs.value = next
        }
    }

    private fun resolvePathUnder(base: File, relativePath: String, mustExist: Boolean): File? {
        if (relativePath.isBlank()) return null
        return runCatching {
            val baseCanonical = base.canonicalFile
            val candidates = linkedSetOf(
                relativePath,
                relativePath.replace('\\', '/'),
                relativePath.replace('/', '\\')
            )

            candidates.firstNotNullOfOrNull { candidate ->
                val file = File(base, candidate)
                val fileCanonical = file.canonicalFile
                val basePath = baseCanonical.path
                val filePath = fileCanonical.path
                val inScope = filePath == basePath || filePath.startsWith(basePath + File.separator)
                if (!inScope) return@firstNotNullOfOrNull null
                if (mustExist && !file.exists()) return@firstNotNullOfOrNull null
                file
            }
        }.getOrNull()
    }

    private fun rootDir(): File = File(appContext.filesDir, "plugins")
    private fun packagesDir(): File = File(rootDir(), "packages")
    private fun tempDir(): File = File(rootDir(), "tmp")
    private fun repositoriesFile(): File = File(rootDir(), "repositories.json")
    private fun installedStateFile(): File = File(rootDir(), "installed.json")
}
