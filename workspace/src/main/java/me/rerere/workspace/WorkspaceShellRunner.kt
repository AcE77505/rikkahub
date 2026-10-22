package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

interface WorkspaceShellRunner {
    fun execute(context: WorkspaceShellContext): WorkspaceCommandResult
}

/**
 * 一次 shell 执行的上下文。
 *
 * 命令直接跑在 Android 原生环境（`/system/bin/sh` + toybox）上，不再经过 proot。
 * 启用 Linux 工具链时由 [ToolchainEnvironment] 给出 [extraEnv] 与 [pathPrepend]，
 * 因此 runner 只负责「拼环境 + 起进程 + 收输出」。
 */
data class WorkspaceShellContext(
    val root: String,
    val command: String,
    /** 相对工作区目录的 cwd，仅用于展示 */
    val cwd: String,
    /** 解析后的绝对工作目录 */
    val workingDir: File,
    /** 工作区私有 HOME */
    val homeDir: File,
    /** 工作区私有 TMPDIR */
    val tempDir: File,
    /**
     * 超时提示阈值（毫秒）。<= 0 表示无限制：既不提示也不自动停止。
     * 语义是「软阈值」—— 超过它默认只是通知，命令继续执行。
     */
    val overtimeMillis: Long = DEFAULT_OVERTIME_MILLIS,
    /** 超过阈值后是否自动停止整个进程组（等价于旧版硬超时行为） */
    val autoStopOnOvertime: Boolean = false,
    /** 额外环境变量；非空时整体替换默认环境（用于注入工具链 PREFIX / LD_LIBRARY_PATH） */
    val extraEnv: Map<String, String> = emptyMap(),
    /** 追加到 PATH 最前面的目录，用于让工具链的 bin 优先命中 */
    val pathPrepend: List<String> = emptyList(),
    val stdin: ByteArray? = null,
    /** 输出溢出文件；超出 [maxOutputChars] 的部分流式写入它，避免丢弃也避免 OOM */
    val overflowFile: File? = null,
    val maxOutputChars: Int = MAX_OUTPUT_CHARS,
    /** 首次超过 [overtimeMillis] 时回调一次，用于通知用户命令仍在运行 */
    val onOvertime: (() -> Unit)? = null,
) {
    companion object {
        const val DEFAULT_OVERTIME_MILLIS = 30_000L
    }
}

class HostShellRunner : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        context.workingDir.mkdirs()
        context.homeDir.mkdirs()
        context.tempDir.mkdirs()
        val process = ProcessBuilder(defaultShell(), "-c", context.command)
            .directory(context.workingDir)
            .redirectErrorStream(false)
            .apply { environment().putAll(context.environment()) }
            .start()
        return process.readResult(context)
    }

    private fun defaultShell(): String =
        if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
}

/** 计算子进程最终的环境变量表：系统 PATH 在前（保证 sh 与 toybox 可用），工具链目录随后 */
internal fun WorkspaceShellContext.environment(): Map<String, String> {
    val systemPath = listOf("/system/bin", "/system/xbin", "/vendor/bin", "/sbin", "/bin")
        .distinct()
        .joinToString(":")
    val path = (pathPrepend + listOf(systemPath, "/usr/bin", "/usr/local/bin"))
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(":")
    val base = buildMap {
        put("PATH", path)
        put("HOME", homeDir.absolutePath)
        put("TMPDIR", tempDir.absolutePath)
        put("PWD", workingDir.absolutePath)
        put("SHELL", "/system/bin/sh")
        put("TERM", "xterm-256color")
        put("LANG", "C.UTF-8")
        put("LC_ALL", "C.UTF-8")
        // 非交互执行约定, 抑制各类 CLI 的交互行为 (确认提示/分页器/颜色转义)
        put("CI", "true")
        put("NO_COLOR", "1")
        put("PAGER", "cat")
    }
    return base + extraEnv
}

// 单个流保留的最大字符数, 防止命令疯狂输出导致 OOM 或撑爆 LLM 上下文
const val MAX_OUTPUT_CHARS = 128 * 1024

private const val POLL_INTERVAL_MILLIS = 200L

fun Process.readResult(
    context: WorkspaceShellContext,
    stdinOverride: ByteArray? = null,
): WorkspaceCommandResult {
    val overflowFile = context.overflowFile
    val stdout = StreamCollector(inputStream, context.maxOutputChars, overflowFile)
    val stderr = StreamCollector(errorStream, context.maxOutputChars, null)
    val stdinBytes = stdinOverride ?: context.stdin
    val stdinWriter = stdinBytes?.let { bytes -> StreamWriter(outputStream, bytes) }
    if (stdinWriter == null) {
        // 没有 stdin 输入时立即关闭管道, 让子进程读到 EOF;
        // 否则 cat/gh/kubectl 等按 isatty 判断的工具会把这根永不写入的管道当成待输入流而永久阻塞
        closeQuietly()
    }
    val overtimeMillis = context.overtimeMillis
    val hasThreshold = overtimeMillis > 0
    try {
        var finished = false
        var timedOut = false
        val deadline = if (hasThreshold) System.currentTimeMillis() + overtimeMillis else Long.MAX_VALUE
        var notified = false
        while (true) {
            if (waitFor(POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)) {
                finished = true
                break
            }
            if (!hasThreshold || notified || System.currentTimeMillis() < deadline) continue
            notified = true
            runCatching { context.onOvertime?.invoke() }
            if (context.autoStopOnOvertime) {
                killProcessGroup()
                timedOut = true
                break
            }
        }
        if (timedOut) killProcessGroup()
        stdinWriter?.join(1_000)
        stdout.join(1_000)
        stderr.join(1_000)
        return WorkspaceCommandResult(
            exitCode = if (finished) runCatching { exitValue() }.getOrDefault(-1) else -1,
            stdout = stdout.text(),
            stderr = stderr.text(),
            timedOut = timedOut,
            truncated = stdout.truncated || stderr.truncated,
            fullOutputPath = stdout.overflowPath(),
        )
    } catch (e: InterruptedException) {
        // 调用方线程被中断（如协程取消时的 runInterruptible），杀掉整个进程组避免孙进程继续执行
        killProcessGroup()
        // 进程被杀后 stdout/stderr 会关闭, 这里 join 回收两个采集线程, 避免每次取消泄漏一对线程
        stdinWriter?.join(1_000)
        stdout.join(1_000)
        stderr.join(1_000)
        throw e
    }
}

/**
 * 结束整个进程组。
 *
 * 命令用 `setsid` 之外的普通方式启动，因此孙进程（`sh -lc "a | b"` 里的 a/b）不在
 * [Process.destroyForcibly] 的覆盖范围内；这里按 /proc 扫一遍把子进程树一并杀掉。
 */
internal fun Process.killProcessGroup() {
    val rootPid = pidOrNull()
    if (rootPid != null) {
        collectDescendants(rootPid).asReversed().forEach { pid ->
            // 子进程是同 uid 的普通进程，直接按 pid 结束即可
            runCatching { android.os.Process.killProcess(pid.toInt()) }
        }
    }
    destroyForcibly()
}

/** `Process.pid()` 不在 android.jar 的公开签名里，用反射取，取不到就只结束直接子进程 */
private fun Process.pidOrNull(): Long? = runCatching {
    val method = javaClass.getMethod("pid")
    (method.invoke(this) as? Number)?.toLong()
}.getOrNull()

private fun collectDescendants(rootPid: Long): List<Long> {
    val children = runCatching {
        File("/proc").listFiles()
            .orEmpty()
            .mapNotNull { it.name.toLongOrNull() }
            .mapNotNull { pid ->
                val ppid = runCatching {
                    File("/proc/$pid/stat").readText()
                        .substringAfterLast(')')
                        .trim()
                        .split(' ')
                        .getOrNull(1)
                        ?.toLongOrNull()
                }.getOrNull()
                ppid?.let { it to pid }
            }
            .groupBy({ it.first }, { it.second })
    }.getOrDefault(emptyMap())

    val result = mutableListOf<Long>()
    val queue = ArrayDeque<Long>().apply { add(rootPid) }
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        children[current].orEmpty().forEach { child ->
            if (child !in result) {
                result += child
                queue += child
            }
        }
    }
    return result
}

private fun Process.closeQuietly() {
    try {
        outputStream.close()
    } catch (_: IOException) {
        // 子进程已提前退出导致管道关闭, 忽略
    }
}

private class StreamWriter(
    private val stream: OutputStream,
    private val bytes: ByteArray,
) {
    private val thread = Thread {
        try {
            stream.use { output ->
                output.write(bytes)
                output.flush()
            }
        } catch (_: IOException) {
            // 子进程提前退出或被强杀时 stdin 可能关闭, 忽略即可, 退出状态会由进程本身返回
        }
    }.apply {
        isDaemon = true
        start()
    }

    fun join(millis: Long) = thread.join(millis)
}

/**
 * 收集单路输出。
 *
 * 超过 [maxChars] 后不再丢弃：若给了 [overflowFile]，溢出部分流式写入该文件，
 * 让模型可以用 head / tail / grep 读回完整输出；没有溢出文件时（stderr）仍然丢弃。
 */
private class StreamCollector(
    stream: InputStream,
    private val maxChars: Int = MAX_OUTPUT_CHARS,
    private val overflowFile: File? = null,
) {
    private val builder = StringBuilder()
    private var overflowWriter: java.io.Writer? = null

    @Volatile
    var truncated = false
        private set

    private val thread = Thread {
        try {
            stream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    // 超出上限后继续读到 EOF，否则管道写满会阻塞子进程导致其无法退出
                    synchronized(builder) {
                        val remaining = maxChars - builder.length
                        if (remaining > 0) {
                            builder.append(buffer, 0, minOf(read, remaining))
                        }
                        if (read > remaining) {
                            truncated = true
                            val overflowStart = (remaining.coerceAtLeast(0))
                            appendOverflow(buffer, overflowStart, read)
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // 进程被强杀（超时/取消）时流会被关闭，阻塞中的 read 会抛 InterruptedIOException 等，
            // 保留已读取的内容即可；不能让异常逃逸，否则会触发线程默认异常处理导致应用崩溃
        } finally {
            synchronized(builder) {
                runCatching { overflowWriter?.flush() }
                runCatching { overflowWriter?.close() }
                overflowWriter = null
            }
        }
    }.apply {
        // 设为 daemon: 即使残留 fd 导致 read() 永久阻塞, 也不会阻止 JVM 退出
        isDaemon = true
        start()
    }

    private fun appendOverflow(buffer: CharArray, start: Int, end: Int) {
        val target = overflowFile ?: return
        if (start >= end) return
        runCatching {
            val writer = overflowWriter ?: run {
                target.parentFile?.mkdirs()
                java.io.OutputStreamWriter(target.outputStream(), Charsets.UTF_8).also { overflowWriter = it }
            }
            writer.write(buffer, start, end - start)
        }
    }

    fun join(millis: Long) = thread.join(millis)

    fun text(): String = synchronized(builder) { builder.toString() }

    fun overflowPath(): String? =
        if (truncated && overflowFile != null && overflowFile.isFile) overflowFile.absolutePath else null
}
