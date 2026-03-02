package top.wsdx233.r2droid.feature.plugin

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import top.wsdx233.r2droid.util.R2PipeManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

object PluginRuntime {

    enum class Permission(val key: String) {
        FILE_READ("file.read"),
        FILE_WRITE("file.write"),
        NETWORK("network"),
        PROCESS("process"),
        R2("r2"),
        TERMINAL("terminal"),
        FRIDA("frida"),
        SETTINGS_READ("settings.read"),
        SETTINGS_WRITE("settings.write")
    }

    private data class RunContext(
        val pluginId: String,
        val installDir: File,
        val permissions: Set<String>
    )

    private data class ProcessSession(
        val key: String,
        val process: Process,
        val outputQueue: LinkedBlockingQueue<String>
    )

    private val processSessions = ConcurrentHashMap<String, ProcessSession>()

    suspend fun runPluginScript(pluginId: String, code: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val context = createContext(pluginId)
            executePluginScript(context, code)
        }
    }

    suspend fun runPluginScriptFunction(pluginId: String, script: String, functionName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val context = createContext(pluginId)
            val normalized = functionName.trim()
            require(normalized.matches(Regex("""[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*"""))) {
                "invalid function name: $functionName"
            }
            val invokeCode = buildString {
                append(script)
                append('\n')
                append(';')
                append(normalized)
                append("();")
            }
            executePluginScript(context, invokeCode)
        }
    }

    fun stopAllForPlugin(pluginId: String) {
        val prefix = "$pluginId#"
        processSessions.keys
            .filter { it.startsWith(prefix) }
            .forEach { key -> stopProcessByKey(key) }
    }

    fun getPluginDir(pluginId: String): Result<String> = runCatching {
        createContext(pluginId).installDir.absolutePath
    }

    fun getPluginDataDir(pluginId: String): Result<String> = runCatching {
        File(createContext(pluginId).installDir, "data").apply { mkdirs() }.absolutePath
    }

    fun requestNetwork(
        pluginId: String,
        method: String,
        url: String,
        body: String = "",
        headersJson: String = ""
    ): Result<String> = runCatching {
        val context = createContext(pluginId)
        requirePermission(context, Permission.NETWORK)
        httpRequest(method, url, body, headersJson)
    }

    fun downloadToPluginData(pluginId: String, url: String, relativePath: String): Result<String> = runCatching {
        val context = createContext(pluginId)
        requirePermission(context, Permission.NETWORK)
        requirePermission(context, Permission.FILE_WRITE)
        val target = resolvePluginDataFile(context, relativePath)
        target.parentFile?.mkdirs()
        downloadTo(url, target)
        target.absolutePath
    }

    fun processStart(pluginId: String, sessionId: String, commandLine: String): Result<String> = runCatching {
        val context = createContext(pluginId)
        requirePermission(context, Permission.PROCESS)
        startProcess(context, sessionId, commandLine)
    }

    fun processWrite(pluginId: String, sessionId: String, input: String): Result<String> = runCatching {
        val context = createContext(pluginId)
        requirePermission(context, Permission.PROCESS)
        writeProcess(context, sessionId, input)
    }

    fun processRead(pluginId: String, sessionId: String, timeoutMs: Long = 30L, maxLines: Int = 200): Result<String> = runCatching {
        val context = createContext(pluginId)
        requirePermission(context, Permission.PROCESS)
        readProcess(context, sessionId, timeoutMs, maxLines)
    }

    fun processStop(pluginId: String, sessionId: String): Result<String> = runCatching {
        val context = createContext(pluginId)
        requirePermission(context, Permission.PROCESS)
        stopProcess(context, sessionId)
    }

    fun processAlive(pluginId: String, sessionId: String): Result<Boolean> = runCatching {
        val context = createContext(pluginId)
        requirePermission(context, Permission.PROCESS)
        isProcessAlive(context, sessionId)
    }

    fun runFridaScript(pluginId: String, script: String): Result<String> = runCatching {
        val context = createContext(pluginId)
        requirePermission(context, Permission.FRIDA)
        executeFridaScript(context, script)
    }

    private fun createContext(pluginId: String): RunContext {
        val installed = PluginManager.findInstalledPlugin(pluginId)
            ?: error("plugin not installed: $pluginId")
        val manifest = installed.manifest ?: error("manifest unavailable for $pluginId")
        return RunContext(
            pluginId = pluginId,
            installDir = File(installed.state.installDir),
            permissions = manifest.permissions.toSet()
        )
    }

    private fun requirePermission(context: RunContext, permission: Permission) {
        check(context.permissions.contains(permission.key)) {
            "permission denied: ${permission.key}"
        }
    }

    private fun resolvePluginDataFile(context: RunContext, relativePath: String): File {
        val dataRoot = File(context.installDir, "data").apply { mkdirs() }
        val file = File(dataRoot, relativePath)
        val basePath = dataRoot.canonicalPath
        val filePath = file.canonicalPath
        check(filePath.startsWith(basePath)) { "invalid path" }
        return file
    }

    private fun executeFridaScript(context: RunContext, script: String): String {
        val scriptDir = File(context.installDir, "data").apply { mkdirs() }
        val scriptFile = File(scriptDir, "frida_plugin_${System.currentTimeMillis()}.js")
        return try {
            scriptFile.writeText(script)
            runBlocking {
                R2PipeManager.execute(":. ${scriptFile.absolutePath}")
            }.getOrThrow()
        } finally {
            scriptFile.delete()
        }
    }

    private fun executePluginScript(context: RunContext, code: String): String {
        val logs = mutableListOf<String>()
        val quickJs = QuickJs.create(Dispatchers.IO)
        quickJs.use { qjs ->
            qjs.define("console") {
                function("log") { args ->
                    logs += args.joinToString(" ")
                    Unit
                }
                function("error") { args ->
                    logs += "ERROR: " + args.joinToString(" ")
                    Unit
                }
            }

            qjs.define("host") {
                function<String, String>("r2") { command ->
                    requirePermission(context, Permission.R2)
                    val result = runBlocking { R2PipeManager.execute(command) }
                    result.getOrElse { "Error: ${it.message}" }
                }

                function<String, String>("frida") { script ->
                    requirePermission(context, Permission.FRIDA)
                    executeFridaScript(context, script)
                }

                function<String, String>("readText") { relativePath ->
                    requirePermission(context, Permission.FILE_READ)
                    val file = resolvePluginDataFile(context, relativePath)
                    if (!file.exists()) return@function ""
                    file.readText()
                }

                function<String>("writeText") { args ->
                    requirePermission(context, Permission.FILE_WRITE)
                    val relativePath = args.getOrNull(0)?.toString() ?: ""
                    val content = args.getOrNull(1)?.toString() ?: ""
                    val file = resolvePluginDataFile(context, relativePath)
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                    "ok"
                }

                function<String>("pluginDir") { _ ->
                    context.installDir.absolutePath
                }

                function<String>("dataDir") { _ ->
                    File(context.installDir, "data").apply { mkdirs() }.absolutePath
                }

                function<String, String>("httpGet") { url ->
                    requirePermission(context, Permission.NETWORK)
                    httpRequest("GET", url, "", "")
                }

                function<String>("httpRequest") { args ->
                    requirePermission(context, Permission.NETWORK)
                    val method = args.getOrNull(0)?.toString()?.ifBlank { "GET" } ?: "GET"
                    val url = args.getOrNull(1)?.toString() ?: ""
                    val body = args.getOrNull(2)?.toString() ?: ""
                    val headersJson = args.getOrNull(3)?.toString() ?: ""
                    httpRequest(method, url, body, headersJson)
                }

                function<String>("download") { args ->
                    requirePermission(context, Permission.NETWORK)
                    requirePermission(context, Permission.FILE_WRITE)
                    val url = args.getOrNull(0)?.toString() ?: ""
                    val relativePath = args.getOrNull(1)?.toString() ?: ""
                    val target = resolvePluginDataFile(context, relativePath)
                    target.parentFile?.mkdirs()
                    downloadTo(url, target)
                    target.absolutePath
                }

                function<String>("procStart") { args ->
                    requirePermission(context, Permission.PROCESS)
                    val sessionId = args.getOrNull(0)?.toString()?.ifBlank { "default" } ?: "default"
                    val commandLine = args.getOrNull(1)?.toString() ?: ""
                    startProcess(context, sessionId, commandLine)
                }

                function<String>("procWrite") { args ->
                    requirePermission(context, Permission.PROCESS)
                    val sessionId = args.getOrNull(0)?.toString()?.ifBlank { "default" } ?: "default"
                    val input = args.getOrNull(1)?.toString() ?: ""
                    writeProcess(context, sessionId, input)
                }

                function<String>("procRead") { args ->
                    requirePermission(context, Permission.PROCESS)
                    val sessionId = args.getOrNull(0)?.toString()?.ifBlank { "default" } ?: "default"
                    val timeoutMs = args.getOrNull(1)?.toString()?.toLongOrNull() ?: 30L
                    val maxLines = args.getOrNull(2)?.toString()?.toIntOrNull() ?: 200
                    readProcess(context, sessionId, timeoutMs, maxLines)
                }

                function<String, String>("procStop") { sessionId ->
                    requirePermission(context, Permission.PROCESS)
                    stopProcess(context, sessionId)
                }

                function<String, String>("procAlive") { sessionId ->
                    requirePermission(context, Permission.PROCESS)
                    if (isProcessAlive(context, sessionId)) "true" else "false"
                }

                function("emit") { args ->
                    val event = args.firstOrNull()?.toString() ?: "event"
                    val payload = args.drop(1).joinToString(" ")
                    logs += "[emit] $event $payload"
                    Unit
                }
            }

            val result = runBlocking { qjs.evaluate<Any?>(code) }?.toString().orEmpty()
            val logText = logs.joinToString("\n")
            return when {
                logText.isNotBlank() && result.isNotBlank() -> "$logText\n$result"
                logText.isNotBlank() -> logText
                result.isNotBlank() -> result
                else -> "(plugin script executed)"
            }
        }
    }

    private fun startProcess(context: RunContext, sessionId: String, commandLine: String): String {
        val parts = splitCommandLine(commandLine)
        require(parts.isNotEmpty()) { "empty command" }

        val key = processKey(context.pluginId, sessionId)
        stopProcessByKey(key)

        val dataDir = File(context.installDir, "data").apply { mkdirs() }
        val tmpDir = File(dataDir, "tmp").apply { mkdirs() }

        val processBuilder = ProcessBuilder(parts)
            .directory(context.installDir)
            .redirectErrorStream(true)

        val env = processBuilder.environment()
        val pathSep = File.pathSeparator

        // 对齐 r2pipe 关键运行环境，保证插件进程可找到 radare2 依赖库
        val appFilesDir = context.installDir.parentFile
            ?.parentFile
            ?.parentFile
            ?.takeIf { it.exists() }
            ?: context.installDir

        val r2LibDir = File(appFilesDir, "radare2/lib")
        val libsDir = File(appFilesDir, "libs")
        val existingLd = env["LD_LIBRARY_PATH"]?.takeIf { it.isNotBlank() }
        val myLd = "${r2LibDir.absolutePath}:${libsDir.absolutePath}"
        env["LD_LIBRARY_PATH"] = if (existingLd != null) "$myLd:$existingLd" else myLd

        val xdgDataHome = File(appFilesDir, "r2work")
        val xdgCacheHome = File(appFilesDir, ".cache")
        xdgDataHome.mkdirs()
        xdgCacheHome.mkdirs()
        env["XDG_DATA_HOME"] = xdgDataHome.absolutePath
        env["XDG_CACHE_HOME"] = xdgCacheHome.absolutePath

        val r2BinDir = File(appFilesDir, "radare2/bin")
        env["HOME"] = env["HOME"].takeUnless { it.isNullOrBlank() }
            ?: if (r2BinDir.exists()) r2BinDir.absolutePath else dataDir.absolutePath
        env["PWD"] = context.installDir.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath
        env["TMP"] = tmpDir.absolutePath
        env["TEMP"] = tmpDir.absolutePath
        env["TERM"] = env["TERM"].takeUnless { it.isNullOrBlank() } ?: "dumb"
        env["R2_NOCOLOR"] = env["R2_NOCOLOR"].takeUnless { it.isNullOrBlank() } ?: "1"

        val systemPath = env["PATH"]?.takeIf { it.isNotBlank() }
            ?: "/system/bin:/system/xbin"
        val extraPath = listOf(
            context.installDir.absolutePath,
            dataDir.absolutePath,
            r2BinDir.absolutePath
        ).joinToString(pathSep)
        env["PATH"] = "$extraPath$pathSep$systemPath"

        val process = processBuilder.start()

        val queue = LinkedBlockingQueue<String>()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { queue.offer(it) }
                }
            }
            queue.offer("[[exit:${process.exitValueOrMinusOne()}]]")
        }
        reader.isDaemon = true
        reader.name = "PluginProc-${context.pluginId}-$sessionId"
        reader.start()

        processSessions[key] = ProcessSession(
            key = key,
            process = process,
            outputQueue = queue
        )
        return "ok"
    }

    private fun writeProcess(context: RunContext, sessionId: String, input: String): String {
        val key = processKey(context.pluginId, sessionId)
        val session = processSessions[key] ?: return "process not started"
        if (!session.process.isAlive) return "process exited"
        session.process.outputStream.write(input.toByteArray())
        session.process.outputStream.flush()
        return "ok"
    }

    private fun readProcess(context: RunContext, sessionId: String, timeoutMs: Long, maxLines: Int): String {
        val key = processKey(context.pluginId, sessionId)
        val session = processSessions[key] ?: return ""
        val lines = mutableListOf<String>()

        if (lines.size < maxLines) {
            val first = if (timeoutMs > 0) {
                session.outputQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            } else {
                session.outputQueue.poll()
            }
            if (first != null) lines += first
        }

        while (lines.size < maxLines) {
            val next = session.outputQueue.poll() ?: break
            lines += next
        }
        return lines.joinToString("\n")
    }

    private fun stopProcess(context: RunContext, sessionId: String): String {
        val key = processKey(context.pluginId, sessionId)
        return if (stopProcessByKey(key)) "ok" else "not running"
    }

    private fun isProcessAlive(context: RunContext, sessionId: String): Boolean {
        val key = processKey(context.pluginId, sessionId)
        return processSessions[key]?.process?.isAlive == true
    }

    private fun stopProcessByKey(key: String): Boolean {
        val session = processSessions.remove(key) ?: return false
        runCatching {
            if (session.process.isAlive) {
                session.process.destroy()
                Thread.sleep(80)
                if (session.process.isAlive) {
                    session.process.destroyForcibly()
                }
            }
        }
        return true
    }

    private fun processKey(pluginId: String, sessionId: String): String = "$pluginId#$sessionId"

    private fun splitCommandLine(commandLine: String): List<String> {
        val regex = Regex("""[^"]\S*|".+?"|'[^']*'""")
        return regex.findAll(commandLine)
            .map { it.value.trim().trim('"').trim('\'') }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun httpRequest(method: String, url: String, body: String, headersJson: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method.uppercase()
            connectTimeout = 15000
            readTimeout = 30000
            doInput = true
        }

        val headers = parseHeaders(headersJson)
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

        if (body.isNotEmpty() && method.uppercase() !in setOf("GET", "HEAD")) {
            conn.doOutput = true
            conn.outputStream.bufferedWriter().use { writer ->
                writer.write(body)
                writer.flush()
            }
        }

        return try {
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.use { it.readText() } ?: ""
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadTo(url: String, target: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 30000
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                error("HTTP $code")
            }
            conn.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseHeaders(headersJson: String): Map<String, String> {
        if (headersJson.isBlank()) return emptyMap()
        return runCatching {
            Json.parseToJsonElement(headersJson)
                .jsonObject
                .mapValues { it.value.toString().trim('"') }
        }.getOrDefault(emptyMap())
    }

    private fun Process.exitValueOrMinusOne(): Int {
        return runCatching { exitValue() }.getOrDefault(-1)
    }
}
