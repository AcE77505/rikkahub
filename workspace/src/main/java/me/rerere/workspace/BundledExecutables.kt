package me.rerere.workspace

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 把随 APK 打包的可执行文件暴露成 PATH 里的普通命令。
 *
 * Android 10 起只允许从 `nativeLibraryDir` 执行文件，而那里的文件名必须匹配 `lib*.so`；
 * 因此这里在 app 私有目录建一个只含符号链接的 shim 目录（链接目标在 nativeLibraryDir），
 * 把 shim 加进 PATH 后就能直接 `obscura fetch ...`。这与 [ToolchainManager] 用符号链接
 * 把 `bin` 指向 termex 的 nativeLibraryDir 是同一套做法。
 *
 * 只在 [ENTRIES] 里登记的文件存在时才把 shim 目录加进 PATH，避免在未打包目标 ABI 的设备上
 * 暴露一个空目录。
 */
class BundledExecutables(
    context: Context,
    shimRoot: File,
) {
    private val nativeLibraryDir: File? =
        context.applicationInfo
            ?.nativeLibraryDir
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)

    private val shimDir = File(shimRoot, SHIM_DIR)

    private val prepared: Boolean by lazy { prepare() }

    /** 可执行文件的真实路径（nativeLibraryDir 内的 ELF）；未打包或索引失败时为 null */
    fun executable(name: String): File? {
        val libName = ENTRIES[name] ?: return null
        val dir = nativeLibraryDir ?: return null
        if (!prepared) return null
        return File(dir, libName).takeIf { it.isFile }
    }

    /** 需要追加到 PATH 最前面的目录；没有可用命令时为空 */
    fun pathEntries(): List<String> = if (prepared) listOf(shimDir.absolutePath) else emptyList()

    private fun prepare(): Boolean {
        val dir = nativeLibraryDir ?: return false
        var any = false
        for ((name, libName) in ENTRIES) {
            val target = File(dir, libName)
            if (!target.isFile) continue
            shimDir.mkdirs()
            val link = File(shimDir, name)
            val unchanged = SymlinkCompat.isSymbolicLink(link) &&
                runCatching { link.canonicalPath }.getOrNull() == target.absolutePath
            if (!unchanged) {
                link.delete()
                val created = SymlinkCompat.create(link, target)
                if (created.isFailure) {
                    Log.w(TAG, "link $name -> ${target.absolutePath} failed", created.exceptionOrNull())
                    continue
                }
            }
            any = true
        }
        return any
    }

    companion object {
        private const val TAG = "BundledExecutables"
        private const val SHIM_DIR = "bin"

        /** 内置浏览器 CLI 的命令名 */
        const val OBSCURA = "obscura"

        /** 命令名 -> jniLibs 文件名；新增内置可执行文件时在这里登记 */
        private val ENTRIES = mapOf(OBSCURA to "libobscura.so")
    }
}
