package me.rerere.rikkahub.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 是否能访问 app 私有目录之外的存储路径。
 *
 * 工作区默认目录在 `files/workspaces/` 下，不需要任何权限；只有把工作区目录改成
 * `/storage/emulated/0/...` 这类外部路径时才需要。
 *
 * - Android 11 (R) 及以上：「所有文件访问」只能在系统设置里授权
 * - Android 10 及以下：旧版读写运行时权限（配合 requestLegacyExternalStorage）
 */
fun Context.hasStorageAccess(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

/** Android 11+ 需要跳系统设置，低版本走运行时权限弹窗 */
val usesAllFilesAccessSettings: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

/** 跳到本应用的「所有文件访问」设置页 */
fun Context.openAllFilesAccessSettings() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val appSpecific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
        data = Uri.parse("package:$packageName")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val allApps = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // 个别 ROM 没有 app 专属页面，回退到全局页面
    runCatching { startActivity(appSpecific) }
        .recoverCatching { startActivity(allApps) }
}
