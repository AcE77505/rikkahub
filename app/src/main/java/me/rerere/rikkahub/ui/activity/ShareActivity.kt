package me.rerere.rikkahub.ui.activity

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.share.handler.ShareHandlerPage
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.ui.toast.Toaster
import me.rerere.ui.toast.rememberToasterState
import org.koin.android.ext.android.inject

/**
 * 接收来自其它应用的分享（文本 / 图片 / 文件，单个或多个）。
 *
 * 为什么不放在 RouteActivity 里：分享来源（比如 MT 管理器）会把自己的任务留在最近任务里，
 * 如果分享界面复用了它的任务，最近任务里就会出现一张「名字是 MT 管理器、内容却是 RikkaHub」
 * 的卡片，用户分不清该结束哪个进程。这里用一个独立的 Activity，配合清单里独立的
 * taskAffinity + documentLaunchMode=always + autoRemoveFromRecents：分享界面自成一个文档任务，
 * 选完助手跳到聊天页后自己 finish 并从最近任务移除，不再和来源应用混在同一个卡片里。
 */
class ShareActivity : ComponentActivity() {
    private val settingsStore by inject<SettingsStore>()

    private var shareText: String = ""
    private var shareUris: List<Uri> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consume(intent)
        render()
    }

    /** singleTop：连续分享会复用同一个实例，重新解析并刷新界面。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consume(intent)
        render()
    }

    private fun render() {
        setContent {
            RikkahubTheme {
                // 分享页里的 UIAvatar 会调用 useCropLauncher，它读取 LocalToaster；
                // 这个 CompositionLocal 默认值是 error("Not provided")，不提供会直接闪退。
                val toastState = rememberToasterState()
                CompositionLocalProvider(LocalToaster provides toastState) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ShareHandlerPage(
                            text = shareText,
                            files = shareUris,
                            onSelect = ::openChatWithAssistant,
                        )
                        Toaster(
                            state = toastState,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                }
            }
        }
    }

    private fun consume(intent: Intent?) {
        if (intent == null) return
        shareText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        shareUris = extractUris(intent)
    }

    /**
     * 不同 App 塞进来的类型不一致：单个 Uri、Uri 列表、甚至字符串；有的不写 EXTRA_STREAM，
     * 改用 ClipData 或 data 传 URI。全部兼容。
     */
    @Suppress("DEPRECATION") // 需要拿到任意类型的 extra，无法用类型化 getter
    private fun extractUris(intent: Intent): List<Uri> {
        val fromExtra = when (val extra = intent.extras?.get(Intent.EXTRA_STREAM)) {
            is Uri -> listOf(extra)
            is String -> listOfNotNull(extra.takeIf { it.isNotBlank() }?.toUri())
            is ArrayList<*> -> extra.filterIsInstance<Uri>()
            is Array<*> -> extra.filterIsInstance<Uri>()
            else -> emptyList()
        }
        if (fromExtra.isNotEmpty()) return fromExtra

        intent.clipData?.let { clip ->
            val items = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            if (items.isNotEmpty()) return items
        }
        return listOfNotNull(intent.data)
    }

    private fun openChatWithAssistant(assistant: Assistant) {
        lifecycleScope.launch {
            // 先固定助手，新会话会以它为配置模板创建
            settingsStore.selectAssistant(assistant.id)
            startActivity(buildChatIntent(assistant))
            finish()
        }
    }

    private fun buildChatIntent(assistant: Assistant): Intent {
        return Intent(this, RouteActivity::class.java).apply {
            action = RouteActivity.ACTION_SHARE_TO_CHAT
            putExtra(RouteActivity.EXTRA_SHARE_TEXT, shareText)
            putExtra(RouteActivity.EXTRA_SHARE_ASSISTANT_ID, assistant.id.toString())
            if (shareUris.isNotEmpty()) {
                putStringArrayListExtra(
                    RouteActivity.EXTRA_SHARE_FILES,
                    ArrayList(shareUris.map { it.toString() }),
                )
                // 跨任务把 content:// 传给聊天页，必须显式授予读权限（并放进 ClipData），否则读不到文件
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri(null, shareUris.first()).apply {
                    shareUris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }
}
