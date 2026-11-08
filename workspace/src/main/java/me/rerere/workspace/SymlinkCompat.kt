package me.rerere.workspace

import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.nio.file.Files

/**
 * 符号链接的兼容封装。
 *
 * `java.nio.file` 从 API 26 才开始提供，minSdk 24/25 上直接引用会在类加载时抛
 * NoClassDefFoundError；低版本退回到系统自带的 `ln -s`。
 */
internal object SymlinkCompat {
    fun isSymbolicLink(file: File): Boolean =
        runCatching { file.canonicalPath != file.absolutePath }.getOrDefault(false)

    fun create(link: File, target: File): Result<Unit> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Api26.create(link, target)
        } else {
            runCatching {
                val process = ProcessBuilder("ln", "-s", target.absolutePath, link.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()
                check(exitCode == 0) { "ln -s failed with exit code $exitCode: $output" }
            }
        }

    @RequiresApi(Build.VERSION_CODES.O)
    private object Api26 {
        fun create(link: File, target: File): Result<Unit> = runCatching {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        }
    }
}
