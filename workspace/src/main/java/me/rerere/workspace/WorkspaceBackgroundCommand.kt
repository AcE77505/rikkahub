package me.rerere.workspace

import java.io.File
import java.io.RandomAccessFile

/**
 * 在后台运行的命令，stdout 与 stderr 合并写入日志文件。
 *
 * 方法都是阻塞 IO，调用方负责切到 IO 线程。
 */
interface WorkspaceBackgroundCommand {
    /** 日志文件在执行环境内的绝对路径，模型可以用 shell 直接读取 */
    val logPath: String

    /** 阻塞到命令退出，返回退出码 */
    fun waitFor(): Int

    /** 日志当前的字节数 */
    fun outputSize(): Long

    /** 从 [offset] 起读取至多 [maxBytes] 字节的日志 */
    fun readOutput(offset: Long, maxBytes: Int): ByteArray

    /** 终止命令及其派生的所有进程，阻塞到进程退出 */
    fun kill()
}

/**
 * 本地工作区（Android 原生环境）的后台命令。
 *
 * stdout/stderr 由 [HostShellRunner.startBackground] 合并写入日志文件；终止时按 /proc
 * 遍历子进程树，避免 `sh -c "a | b"` 的孙进程留在后台。
 */
internal class LocalBackgroundCommand(
    private val process: Process,
    private val logFile: File,
    override val logPath: String,
) : WorkspaceBackgroundCommand {
    override fun waitFor(): Int = process.waitFor()

    override fun outputSize(): Long = logFile.length()

    override fun readOutput(offset: Long, maxBytes: Int): ByteArray {
        if (!logFile.isFile) return ByteArray(0)
        return RandomAccessFile(logFile, "r").use { file ->
            val available = (file.length() - offset).coerceAtLeast(0)
            val buffer = ByteArray(minOf(available, maxBytes.toLong()).toInt())
            file.seek(offset)
            file.readFully(buffer)
            buffer
        }
    }

    override fun kill() = process.killProcessGroup()
}
