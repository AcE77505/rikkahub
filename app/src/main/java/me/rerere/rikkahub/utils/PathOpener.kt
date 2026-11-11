package me.rerere.rikkahub.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StrictMode
import androidx.core.content.FileProvider
import java.io.File

/** 能通过 ACTION_VIEW 打开文件/目录的 MT 管理器包名，按优先级排列。 */
val MT_MANAGER_PACKAGES = listOf("bin.mt.plus", "bin.mt.plus.canary")

/** 设备上安装了哪些 MT 管理器，保持 [MT_MANAGER_PACKAGES] 的顺序。 */
fun installedMtManagers(context: Context): List<String> =
    MT_MANAGER_PACKAGES.filter { packageName ->
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess
    }

/**
 * 决定用哪个 MT 包。
 *
 * - 没装：null（调用方回退系统方式）
 * - 只装一个：直接用它
 * - 装了两个：用过且仍然安装的包优先，否则返回 null 由调用方询问用户
 */
internal fun chooseMtPackage(installed: List<String>, remembered: String?): String? = when {
    installed.isEmpty() -> null
    installed.size == 1 -> installed.first()
    remembered != null && remembered in installed -> remembered
    else -> null
}

/** 打开路径的结果，供 UI 决定是否还要弹选择框。 */
sealed interface PathOpenResult {
    data object Opened : PathOpenResult

    /** 需要用户在两个 MT 管理器之间选一个 */
    data class Ambiguous(val candidates: List<String>) : PathOpenResult

    /** 没有可用的打开方式 */
    data object Failed : PathOpenResult
}

/**
 * 用指定包（null 表示系统选择器）打开路径。
 *
 * 不检查路径是否存在：交给 MT 或系统判断，打不开时由它们提示。
 * 文件通过 FileProvider 暴露成 content://；目录只能用 file:// + application/x-directory，
 * 因为 MT 的 OpenFileActivity 只认这个组合，而 FileProvider 也无法提供目录内容。
 *
 * @return 是否成功发起了打开；false 表示没有能处理的应用或路径无法暴露
 */
internal fun openFileWith(
    context: Context,
    file: File,
    packageName: String?,
    isDirectory: Boolean = file.isDirectory,
): Boolean {
    if (isDirectory) return openDirectoryWith(context, file, packageName)
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return false
    val mimeType = context.contentResolver.getType(uri) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        packageName?.let { setPackage(it) }
    }
    return startIntent(context, intent, chooser = packageName == null)
}

/**
 * 目录：file:// + application/x-directory。
 *
 * MT 管理器只注册了这些 MIME（text/image/audio/video/application），其中 `application/x-directory`
 * 是它能定位目录的唯一选择；`resource/folder` 只会落到系统其它文件管理器。
 */
private fun openDirectoryWith(context: Context, file: File, packageName: String?): Boolean {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.fromFile(file), "application/x-directory")
        packageName?.let { setPackage(it) }
    }
    return startIntent(context, intent, chooser = packageName == null, allowFileUri = true)
}

private fun startIntent(
    context: Context,
    intent: Intent,
    chooser: Boolean,
    allowFileUri: Boolean = false,
): Boolean {
    // file:// 会被 StrictMode 的 FileUriExposure 拦截，只在这一次启动期间放开
    val previousPolicy = if (allowFileUri) StrictMode.getVmPolicy() else null
    if (allowFileUri) StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
    return try {
        if (chooser && context.packageManager.queryIntentActivities(intent, 0).isEmpty()) {
            false
        } else {
            context.startActivity(if (chooser) Intent.createChooser(intent, null) else intent)
            true
        }
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } finally {
        if (previousPolicy != null) StrictMode.setVmPolicy(previousPolicy)
    }
}

/** 系统方式：交给用户选择应用（AOSP 自带的选择器）。 */
internal fun openFileWithSystem(
    context: Context,
    file: File,
    isDirectory: Boolean = file.isDirectory,
): Boolean = openFileWith(context, file, null, isDirectory)

/** MT 包名对应的显示名，设置页需要列出实际包名。 */
internal fun mtManagerLabel(packageName: String): String = when (packageName) {
    "bin.mt.plus" -> "MT 管理器"
    "bin.mt.plus.canary" -> "MT 管理器 Canary"
    else -> packageName
}
