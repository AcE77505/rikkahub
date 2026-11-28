package me.rerere.ai.agent

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class NoticesTest {
    private val tool = UIMessagePart.Tool(
        toolCallId = "call-1",
        toolName = "shell",
        input = "{}",
        output = listOf(UIMessagePart.Text("ok")),
    )
    private val notice = UIMessagePart.Notice(title = "done", text = "<notice>done</notice>")

    @Test
    fun `notice after tools splits the assistant message at that point`() {
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("checking"), tool, notice, UIMessagePart.Text("answer")),
        )

        val result = listOf(UIMessage.user("hi"), assistant).expandNotices()

        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT),
            result.map { it.role },
        )
        assertEquals(listOf(UIMessagePart.Text("checking"), tool), result[1].parts)
        assertEquals("<notice>done</notice>", result[2].toText())
        assertEquals(listOf(UIMessagePart.Text("answer")), result[3].parts)
        assertTrue(result.none { message -> message.parts.any { it is UIMessagePart.Notice } })
    }

    @Test
    fun `notice at the start of a turn becomes a user message before the reply`() {
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(notice, UIMessagePart.Text("answer")),
        )

        val result = listOf(UIMessage.user("hi"), assistant).expandNotices()

        assertEquals(listOf(MessageRole.USER, MessageRole.USER, MessageRole.ASSISTANT), result.map { it.role })
        assertEquals(listOf(UIMessagePart.Text("answer")), result[2].parts)
    }

    @Test
    fun `a notice only user message becomes plain user messages`() {
        val other = UIMessagePart.Notice(title = "other", text = "<notice>other</notice>")
        val message = UIMessage(role = MessageRole.USER, parts = listOf(notice, other))

        val result = listOf(message).expandNotices()

        assertEquals(listOf("<notice>done</notice>", "<notice>other</notice>"), result.map { it.toText() })
        assertTrue(result.all { it.role == MessageRole.USER && it.isSynthetic })
    }

    @Test
    fun `a steer keeps its own parts and is not marked synthetic`() {
        val steer = UIMessagePart.Steer(
            parts = listOf(UIMessagePart.Text("look at this"), UIMessagePart.Image("file:///a.png")),
        )
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("thinking"), steer, UIMessagePart.Text("answer")),
        )

        val result = listOf(UIMessage.user("hi"), assistant).expandNotices()

        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT),
            result.map { it.role },
        )
        // 附件照旧保留，只有文本被套上插话标记
        assertEquals(UIMessagePart.Image("file:///a.png"), result[2].parts[1])
        assertTrue(result[2].parts[0].asText().startsWith(MID_TURN_STEER_PREFIX))
        assertTrue(result[2].parts[0].asText().endsWith("look at this"))
        // 插话是用户真说的话，不能标成 synthetic，否则模板和时间提醒会跳过它
        assertFalse(result[2].isSynthetic)
    }

    @Test
    fun `extractSteers pulls steers out before transformers and keeps notices inline`() {
        val diagnosticId = Uuid.random()
        val steer = UIMessagePart.Steer(
            parts = listOf(UIMessagePart.Text("interrupt")),
            diagnosticId = diagnosticId,
        )
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("thinking"), notice, steer, UIMessagePart.Text("answer")),
        )

        val result = listOf(UIMessage.user("hi"), assistant).extractSteers()

        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT),
            result.map { it.role },
        )
        // 插话文本前带上标记，模型才知道这是生成中途的补充而不是新任务
        assertEquals(listOf(UIMessagePart.Text("$MID_TURN_STEER_PREFIX\ninterrupt")), result[2].parts)
        assertEquals(diagnosticId, result[2].steerDiagnosticId)
        assertFalse(result[2].isSynthetic)
        // 通知不在这里拆：它要留在转换之后，避免被套上模板和时间提醒
        assertEquals(listOf(UIMessagePart.Text("thinking"), notice), result[1].parts)
        assertEquals(listOf(UIMessagePart.Text("answer")), result[3].parts)
    }

    @Test
    fun `the steer wrapper is stripped for display and replay`() {
        // 存下来的历史里带标记，展示/导出/回放时要还原成用户实际打的内容
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Steer(parts = listOf(UIMessagePart.Text("go on"))),
            ),
        )

        val wrapped = listOf(assistant).extractSteers().single().parts.single().asText()
        assertTrue(wrapped.startsWith(MID_TURN_STEER_PREFIX))

        assertEquals("go on", wrapped.stripMidTurnSteerPrefix())
        assertEquals("plain text", "plain text".stripMidTurnSteerPrefix())
    }

    @Test
    fun `messages without steers are returned as is by extractSteers`() {
        val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(notice, UIMessagePart.Text("answer")))

        val result = listOf(UIMessage.user("hi"), assistant).extractSteers()

        assertEquals(2, result.size)
        assertSame(assistant, result[1])
    }

    @Test
    fun `messages without notices are returned as is`() {
        val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool, UIMessagePart.Text("answer")))

        val result = listOf(UIMessage.user("hi"), assistant).expandNotices()

        assertEquals(2, result.size)
        assertSame(assistant, result[1])
    }
}

private fun UIMessagePart.asText(): String = (this as UIMessagePart.Text).text
