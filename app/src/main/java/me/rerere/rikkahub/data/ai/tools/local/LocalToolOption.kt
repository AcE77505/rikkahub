package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable
    @SerialName("screen_time")
    data object ScreenTime : LocalToolOption()

    @Serializable
    @SerialName("calendar")
    data object Calendar : LocalToolOption()

    @Serializable
    @SerialName("chart_display")
    data object ChartDisplay : LocalToolOption()

    // 工作区工具（全局开关，不再依赖助手绑定工作区）

    @Serializable
    @SerialName("workspace_read_file")
    data object WorkspaceReadFile : LocalToolOption()

    @Serializable
    @SerialName("workspace_write_file")
    data object WorkspaceWriteFile : LocalToolOption()

    @Serializable
    @SerialName("workspace_edit_file")
    data object WorkspaceEditFile : LocalToolOption()

    @Serializable
    @SerialName("workspace_shell")
    data object WorkspaceShell : LocalToolOption()

    companion object {
        /** 工作区工具，依赖「读取文件」的写/编辑在后者关闭时不可用 */
        val WORKSPACE_TOOLS = listOf(WorkspaceReadFile, WorkspaceWriteFile, WorkspaceEditFile, WorkspaceShell)
    }
}
