package me.rerere.workspace

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipFile

/** 一次工具链解析结果，供 [WorkspaceShellContext] 注入环境变量与探针描述 */
data class ToolchainEnvironment(
    val prefixDir: File,
    val binDir: File,
    val libDir: File,
    /** 可用的 bash 绝对路径；为空表示只能回退到 toybox sh */
    val shellPath: String?,
    val extraEnv: Map<String, String>,
    val pathPrepend: List<String>,
    val probes: List<ToolchainProbe> = emptyList(),
) {
    val usable: Boolean get() = binDir.isDirectory && libDir.isDirectory
}

data class ToolchainProbe(val name: String, val version: String)

/** termex 扩展包的检测结果 */
data class TermexStatus(
    val installed: Boolean,
    val versionName: String? = null,
    /** 扩展包的可执行文件目录（nativeLibraryDir），bin 直接链接到这里 */
    val nativeLibraryDir: String? = null,
    /** base.apk 路径，lib 从这里解出 */
    val apkPath: String? = null,
    val abi: String? = null,
    val lastUpdateTime: Long = 0L,
)

/**
 * Linux 工具链（bash / openssl / curl / ssh 等）的来源与装配。
 *
 * 资源全部来自 `bin.mt.termex` 这一个包，无需 root、无需网络：
 * - `bin` 直接指向该扩展包的 `nativeLibraryDir`（符号链接，不复制）
 * - `lib` 从该包的 `base.apk` 里解出（apk 全局可读；安装时不会解压 `lib/<abi>/lib/` 子目录）
 *
 * 全局共享一份：`files/toolchain/termex-<versionName>/`，与工作区无关；
 * HOME / TMPDIR 由 [WorkspaceManager] 按工作区各自创建。
 */
class ToolchainManager(
    private val context: Context,
    private val toolchainRoot: File,
) {
    private var cached: Pair<String, ToolchainEnvironment>? = null

    // 探针要起 5 个子进程，进程内只跑一次；包升级后 cacheKey 变化会重新跑
    private var probeCache: Pair<String, ToolchainEnvironment>? = null

    @Volatile
    private var cachedTermex: TermexStatus? = null

    fun detect(forceRefresh: Boolean = false): TermexStatus {
        cachedTermex?.takeIf { !forceRefresh }?.let { return it }
        val status = queryPackage()
        cachedTermex = status
        return status
    }

    private fun queryPackage(): TermexStatus {
        val pm = context.packageManager
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(TERMEX_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(TERMEX_PACKAGE, 0)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return TermexStatus(installed = false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query $TERMEX_PACKAGE", e)
            return TermexStatus(installed = false)
        }
        val appInfo = info.applicationInfo ?: return TermexStatus(installed = false)
        val abi = currentAbi(appInfo.nativeLibraryDir)
        return TermexStatus(
            installed = true,
            versionName = info.versionName ?: info.versionCode.toString(),
            nativeLibraryDir = appInfo.nativeLibraryDir,
            apkPath = appInfo.publicSourceDir ?: appInfo.sourceDir,
            abi = abi,
            lastUpdateTime = info.lastUpdateTime,
        )
    }

    private fun currentAbi(nativeLibraryDir: String?): String? {
        // nativeLibraryDir 末段就是 ABI 目录名，优先以它为准，避免设备支持多 ABI 时错配
        val fromLibDir = nativeLibraryDir
            ?.trimEnd('/')
            ?.substringAfterLast('/')
            ?.takeIf { it in SUPPORTED_ABIS }
        if (fromLibDir != null) return fromLibDir
        return Build.SUPPORTED_ABIS.firstOrNull { it in SUPPORTED_ABIS }
    }

    /**
     * 解析可用的工具链。已解压且探针自检未过期时直接用缓存。
     *
     * 失败时返回 null，调用方回退到 toybox `/system/bin/sh`。
     */
    fun resolve(): ToolchainEnvironment? {
        val status = detect()
        if (!status.installed) return null
        val versionName = status.versionName ?: return null
        val cacheKey = "$TERMEX_PACKAGE@$versionName/${status.abi}"
        cached?.let { (key, env) -> if (key == cacheKey && env.usable) return env }

        val nativeLibraryDir = status.nativeLibraryDir
        if (nativeLibraryDir.isNullOrBlank() || !File(nativeLibraryDir).isDirectory) {
            Log.w(TAG, "termex nativeLibraryDir unavailable: $nativeLibraryDir")
            return null
        }
        val apkPath = status.apkPath
        if (apkPath.isNullOrBlank() || !File(apkPath).isFile) {
            Log.w(TAG, "termex apk unavailable: $apkPath")
            return null
        }
        val abi = status.abi ?: return null

        val prefixDir = File(toolchainRoot, "termex-$versionName")
        val binDir = File(prefixDir, BIN_DIR)
        val libDir = File(prefixDir, LIB_DIR)
        val marker = File(prefixDir, MARKER_FILE)

        val upToDate = marker.isFile &&
            marker.readText().trim() == cacheMarker(status) &&
            libDir.isDirectory
        if (!upToDate) {
            try {
                extractLib(File(apkPath), abi, libDir)
                linkBin(binDir, File(nativeLibraryDir))
                prefixDir.mkdirs()
                marker.writeText(cacheMarker(status))
            } catch (e: IOException) {
                Log.e(TAG, "Failed to prepare toolchain", e)
                return null
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to prepare toolchain", e)
                return null
            }
        }

        val env = buildEnvironment(prefixDir, binDir, libDir)
        if (!env.usable) return null
        val probed = probeCache?.takeIf { it.first == cacheKey }?.second ?: probe(env).also {
            probeCache = cacheKey to it
        }
        cached = cacheKey to probed
        return probed
    }

    private fun cacheMarker(status: TermexStatus): String =
        "${status.versionName}|${status.lastUpdateTime}|${status.abi}"

    /**
     * `bin` 用符号链接指向扩展包的 nativeLibraryDir：
     * 里面的可执行文件属主是 system，直接 exec 即可，不需要复制 13 MB。
     */
    private fun linkBin(binDir: File, nativeLibraryDir: File) {
        if (Files.isSymbolicLink(binDir.toPath())) {
            val existing = runCatching { Files.readSymbolicLink(binDir.toPath()).toString() }.getOrNull()
            if (existing == nativeLibraryDir.absolutePath) return
            Files.delete(binDir.toPath())
        }
        if (binDir.exists()) {
            // 之前解压成了实体目录（旧版本行为），换成链接
            binDir.deleteRecursively()
        }
        binDir.parentFile?.mkdirs()
        runCatching { Files.createSymbolicLink(binDir.toPath(), nativeLibraryDir.toPath()) }
            .onFailure { Log.w(TAG, "symlink bin failed, tools unavailable", it) }
    }

    /**
     * 从 apk 里解出 `lib/<abi>/lib/` 到 [libDir]。
     *
     * apk 内条目命名规则（实测 termex 3.8）：
     * - `*.rename`：真实 ELF，仅改后缀避开安装时被当 native lib 解压 → 去掉 `.rename`
     * - `*.link`：15 字节文本，内容是链接目标 → 建相对符号链接
     * - 其余（如 `libcrypto.so.3`、`engines-3` 子目录下的条目）：原样
     */
    private fun extractLib(apk: File, abi: String, libDir: File) {
        val zipEntryPrefix = "lib/$abi/lib/"
        libDir.deleteRecursively()
        libDir.mkdirs()
        ZipFile(apk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (!name.startsWith(zipEntryPrefix) || entry.isDirectory) continue
                val relative = name.removePrefix(zipEntryPrefix)
                if (relative.isBlank() || relative.contains("..")) continue

                when {
                    relative.endsWith(LINK_SUFFIX) -> {
                        val linkName = relative.removeSuffix(LINK_SUFFIX)
                        val target = zip.getInputStream(entry).use { input ->
                            input.readBytes().toString(Charsets.UTF_8).trim()
                        }
                        if (target.isBlank()) continue
                        val linkFile = File(libDir, linkName)
                        linkFile.parentFile?.mkdirs()
                        if (linkFile.exists()) linkFile.delete()
                        runCatching { Files.createSymbolicLink(linkFile.toPath(), File(target).toPath()) }
                            .onFailure { Log.w(TAG, "symlink lib failed: $linkName", it) }
                    }

                    else -> {
                        val targetName = relative.removeSuffix(RENAME_SUFFIX)
                        val target = File(libDir, targetName)
                        target.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                        // .so 不需要可执行位，但脚本类条目（若有）需要
                        target.setReadable(true, true)
                    }
                }
            }
        }
    }

    private fun buildEnvironment(prefixDir: File, binDir: File, libDir: File): ToolchainEnvironment {
        val bash = File(binDir, "bash")
        val extraEnv = mapOf(
            "PREFIX" to prefixDir.absolutePath,
            "LD_LIBRARY_PATH" to listOf(libDir.absolutePath, File("/system/lib64").absolutePath)
                .joinToString(":"),
        )
        val shellPath = bash.absolutePath.takeIf { bash.isFile }
        return ToolchainEnvironment(
            prefixDir = prefixDir,
            binDir = binDir,
            libDir = libDir,
            shellPath = shellPath,
            extraEnv = extraEnv,
            pathPrepend = listOf(binDir.absolutePath),
        )
    }

    /**
     * 逐个跑探针确认工具真的能用。
     *
     * 链接器行为与 ABI 版本强绑定，光看文件存在不够；失败的工具不会写进提示词。
     */
    fun probe(environment: ToolchainEnvironment): ToolchainEnvironment {
        val runner = HostShellRunner()
        val tempDir = File(context.cacheDir, "toolchain-probe").apply { mkdirs() }
        val homeDir = tempDir
        val results = PROBES.mapNotNull { probeSpec ->
            val result = runCatching {
                runner.execute(
                    WorkspaceShellContext(
                        root = "probe",
                        command = probeSpec.command,
                        cwd = "",
                        workingDir = tempDir,
                        homeDir = homeDir,
                        tempDir = tempDir,
                        overtimeMillis = PROBE_TIMEOUT_MILLIS,
                        autoStopOnOvertime = true,
                        extraEnv = environment.extraEnv,
                        pathPrepend = environment.pathPrepend,
                    )
                )
            }.getOrNull() ?: return@mapNotNull null
            if (result.exitCode != 0) return@mapNotNull null
            val output = result.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                ?: return@mapNotNull null
            ToolchainProbe(name = probeSpec.name, version = output.take(MAX_PROBE_LINE))
        }
        return environment.copy(probes = results)
    }

    private data class ProbeSpec(val name: String, val command: String)

    companion object {
        private const val TAG = "ToolchainManager"

        /** 固定只识别这个包，所有资源（bin 与 lib）都只来自它 */
        const val TERMEX_PACKAGE = "bin.mt.termex"

        private const val BIN_DIR = "bin"
        private const val LIB_DIR = "lib"
        private const val MARKER_FILE = ".prepared"
        private const val RENAME_SUFFIX = ".rename"
        private const val LINK_SUFFIX = ".link"
        private const val PROBE_TIMEOUT_MILLIS = 15_000L
        private const val MAX_PROBE_LINE = 120

        private val SUPPORTED_ABIS = setOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86")

        private val PROBES = listOf(
            ProbeSpec("bash", "bash -c 'echo \$BASH_VERSION'"),
            ProbeSpec("openssl", "openssl version"),
            ProbeSpec("curl", "curl --version"),
            ProbeSpec("ssh", "ssh -V 2>&1"),
            ProbeSpec("git", "git --version"),
        )
    }
}
