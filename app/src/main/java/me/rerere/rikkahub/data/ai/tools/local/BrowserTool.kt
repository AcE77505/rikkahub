package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.workspace.WorkspaceShellContext
import me.rerere.workspace.readResult
import java.io.File

const val BROWSER_FETCH_TOOL_NAME = "browser_fetch"
const val BROWSER_SCREENSHOT_TOOL_NAME = "browser_screenshot"

private const val DEFAULT_TIMEOUT_SECONDS = 30
private const val MAX_TIMEOUT_SECONDS = 120
/** 导航超时之外留给进程启动 / 页面收尾的余量 */
private const val PROCESS_GRACE_MILLIS = 60_000L

private val DUMP_FORMATS = listOf("markdown", "text", "html", "links", "assets", "cookies", "original")
private val WAIT_UNTIL = listOf("load", "domcontentloaded", "networkidle0")

/**
 * 内置浏览器工具（obscura CLI）。
 *
 * 每次调用都是一次独立的 `obscura fetch`：无状态，不能保留登录态，也不能点击/填表。
 * 需要交互能力的场景留给后续的常驻 MCP / CDP 方案。
 *
 * [resolveBinary] 返回 `nativeLibraryDir/libobscura.so` 的路径；当前 ABI 没打包时返回 null。
 */
internal fun buildBrowserFetchTool(
    context: Context,
    resolveBinary: () -> File?,
): Tool = Tool(
    name = BROWSER_FETCH_TOOL_NAME,
    description = """
        Open a web page in a real headless browser (obscura), including JavaScript execution, and return its content.
        Use dump=markdown for readable text (best default), text for plain text, html for the DOM, links for anchors,
        assets for sub-resource URLs, cookies for the cookie jar, and original for the raw response body (binary-safe,
        e.g. JSON or images).
        Use eval to run a JavaScript expression in the page and return its value (wrap multi-statement code in an IIFE).
        Pages on localhost / LAN addresses are blocked unless allow_private_network is true.
        This tool is one-shot: it cannot keep a session, click, type, or follow a login flow.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute http(s) URL to open")
                })
                put("dump", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { DUMP_FORMATS.forEach { add(it) } })
                    put("description", "Output format (default markdown)")
                })
                put("eval", buildJsonObject {
                    put("type", "string")
                    put("description", "JavaScript expression to evaluate in the page; its value is returned")
                })
                put("wait_until", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { WAIT_UNTIL.forEach { add(it) } })
                    put("description", "Navigation wait condition (default load)")
                })
                put("selector", buildJsonObject {
                    put("type", "string")
                    put("description", "Wait for this CSS selector before reading the page")
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Navigation timeout in seconds (1-$MAX_TIMEOUT_SECONDS, default $DEFAULT_TIMEOUT_SECONDS)")
                })
                put("allow_private_network", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Allow loopback / RFC1918 / link-local targets (default false)")
                })
            },
            required = listOf("url")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val url = params["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        require(url.isNotEmpty()) { "url is required" }
        val dump = params["dump"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it in DUMP_FORMATS }
            ?: "markdown"
        val timeout = params.timeoutSeconds() ?: DEFAULT_TIMEOUT_SECONDS

        val command = buildList {
            add("fetch")
            add(url)
            add("--dump")
            add(dump)
            add("--timeout")
            add(timeout.toString())
            add("--quiet")
            params["eval"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                add("--eval")
                add(it)
            }
            params["wait_until"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it in WAIT_UNTIL }
                ?.let { add("--wait-until"); add(it) }
            params["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                add("--selector")
                add(it)
            }
            if (params.allowPrivateNetwork()) add("--allow-private-network")
        }

        val result = runObscura(context, resolveBinary, command, PROCESS_GRACE_MILLIS + timeout * 1_000L)
        val output = result.stdout.ifBlank { result.stderr }
        if (result.exitCode != 0) {
            error("obscura fetch failed (exit ${result.exitCode}): ${output.take(2_000)}")
        }
        listOf(UIMessagePart.Text(output))
    }
)

/**
 * 渲染页面并截图，图片作为图片内容返回给模型。
 *
 * [saveScreenshot] 把 PNG 字节存成聊天文件并返回地址（与手机操作工具共用同一套文件存储）。
 */
internal fun buildBrowserScreenshotTool(
    context: Context,
    resolveBinary: () -> File?,
    saveScreenshot: (ByteArray) -> String,
): Tool = Tool(
    name = BROWSER_SCREENSHOT_TOOL_NAME,
    description = """
        Render a web page in a real headless browser (obscura) and return a PNG screenshot as image content.
        Use this when layout, images, charts or visual state matter and the text dump is not enough.
        Pages on localhost / LAN addresses are blocked unless allow_private_network is true.
        Text uses the fonts bundled with obscura; CJK glyphs may render as boxes.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute http(s) URL to render")
                })
                put("wait_until", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { WAIT_UNTIL.forEach { add(it) } })
                    put("description", "Navigation wait condition (default load)")
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Navigation timeout in seconds (1-$MAX_TIMEOUT_SECONDS, default $DEFAULT_TIMEOUT_SECONDS)")
                })
                put("allow_private_network", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Allow loopback / RFC1918 / link-local targets (default false)")
                })
            },
            required = listOf("url")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val url = params["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        require(url.isNotEmpty()) { "url is required" }
        val timeout = params.timeoutSeconds() ?: DEFAULT_TIMEOUT_SECONDS

        val workDir = obscuraTempDir(context)
        val shot = File(workDir, "shot-${System.currentTimeMillis()}.png")
        val command = buildList {
            add("fetch")
            add(url)
            add("--screenshot")
            add(shot.absolutePath)
            add("--timeout")
            add(timeout.toString())
            add("--quiet")
            params["wait_until"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it in WAIT_UNTIL }
                ?.let { add("--wait-until"); add(it) }
            if (params.allowPrivateNetwork()) add("--allow-private-network")
        }

        val result = runObscura(context, resolveBinary, command, PROCESS_GRACE_MILLIS + timeout * 1_000L)
        val bytes = runCatching { shot.readBytes() }.getOrNull()
        shot.delete()
        if (result.exitCode != 0 || bytes == null || bytes.isEmpty()) {
            val output = result.stdout.ifBlank { result.stderr }
            error("obscura screenshot failed (exit ${result.exitCode}): ${output.take(2_000)}")
        }
        listOf(UIMessagePart.Image(url = saveScreenshot(bytes)))
    }
)

private fun obscuraTempDir(context: Context): File =
    File(context.cacheDir, "obscura").apply { mkdirs() }

private suspend fun runObscura(
    context: Context,
    resolveBinary: () -> File?,
    args: List<String>,
    timeoutMillis: Long,
): me.rerere.workspace.WorkspaceCommandResult {
    val binary = resolveBinary()
        ?: error(
            "obscura is not bundled for this ABI (missing libobscura.so). " +
                "Rebuild the app with the obscura jniLibs artifact."
        )
    val workDir = obscuraTempDir(context)
    val shellContext = WorkspaceShellContext(
        root = "",
        command = "",
        cwd = "",
        workingDir = workDir,
        homeDir = workDir,
        tempDir = workDir,
        overtimeMillis = timeoutMillis,
        autoStopOnOvertime = true,
    )
    // 阻塞读取放到可中断的 IO 线程：协程取消时 readResult 会杀掉整个进程组
    return runInterruptible(Dispatchers.IO) {
        val process = ProcessBuilder(listOf(binary.absolutePath) + args)
            .directory(workDir)
            .redirectErrorStream(false)
            .apply {
                environment().apply {
                    put("HOME", workDir.absolutePath)
                    put("TMPDIR", workDir.absolutePath)
                    put("NO_COLOR", "1")
                }
            }
            .start()
        process.readResult(shellContext)
    }
}

private fun kotlinx.serialization.json.JsonObject.timeoutSeconds(): Int? =
    this["timeout_seconds"]?.jsonPrimitive?.intOrNull
        ?.coerceIn(1, MAX_TIMEOUT_SECONDS)

private fun kotlinx.serialization.json.JsonObject.allowPrivateNetwork(): Boolean =
    this["allow_private_network"]?.jsonPrimitive?.booleanOrNull ?: false
