package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserToolArgsTest {

    @Test
    fun `fetch args carry url dump timeout and quiet`() {
        val args = buildFetchArgs(
            url = "https://example.com",
            dump = "markdown",
            eval = null,
            waitUntil = null,
            selector = null,
            timeoutSeconds = 20,
            allowPrivateNetwork = false,
        )
        assertEquals(listOf("fetch", "https://example.com", "--dump", "markdown", "--timeout", "20", "--quiet"), args)
    }

    @Test
    fun `unknown dump format falls back to markdown`() {
        val args = buildFetchArgs(
            url = "https://example.com",
            dump = "pdf",
            eval = null,
            waitUntil = null,
            selector = null,
            timeoutSeconds = 30,
            allowPrivateNetwork = false,
        )
        assertTrue(args.contains("markdown"))
        assertFalse(args.contains("pdf"))
    }

    @Test
    fun `blank eval and selector are dropped`() {
        val args = buildFetchArgs(
            url = "https://example.com",
            dump = "text",
            eval = "   ",
            waitUntil = null,
            selector = "",
            timeoutSeconds = 30,
            allowPrivateNetwork = false,
        )
        assertFalse(args.contains("--eval"))
        assertFalse(args.contains("--selector"))
    }

    @Test
    fun `eval selector and wait until are passed through`() {
        val args = buildFetchArgs(
            url = "https://example.com",
            dump = "html",
            eval = "document.title",
            waitUntil = "networkidle0",
            selector = "main",
            timeoutSeconds = 30,
            allowPrivateNetwork = true,
        )
        assertEquals("document.title", args[args.indexOf("--eval") + 1])
        assertEquals("main", args[args.indexOf("--selector") + 1])
        assertEquals("networkidle0", args[args.indexOf("--wait-until") + 1])
        assertTrue(args.contains("--allow-private-network"))
    }

    @Test
    fun `unknown wait until value is dropped`() {
        val args = buildFetchArgs(
            url = "https://example.com",
            dump = "text",
            eval = null,
            waitUntil = "idle",
            selector = null,
            timeoutSeconds = 30,
            allowPrivateNetwork = false,
        )
        assertFalse(args.contains("--wait-until"))
    }

    @Test
    fun `timeout is clamped to the supported range`() {
        assertEquals(
            "1",
            buildFetchArgs("u", "text", null, null, null, 0, false).let { it[it.indexOf("--timeout") + 1] },
        )
        assertEquals(
            "120",
            buildFetchArgs("u", "text", null, null, null, 9_999, false).let { it[it.indexOf("--timeout") + 1] },
        )
    }

    @Test
    fun `private network access is opt in`() {
        val args = buildFetchArgs("u", "text", null, null, null, 30, false)
        assertFalse(args.contains("--allow-private-network"))
    }

    @Test
    fun `screenshot args point at the output file`() {
        val args = buildScreenshotArgs(
            url = "https://example.com",
            screenshotPath = "/data/cache/shot.png",
            waitUntil = "load",
            timeoutSeconds = 45,
            allowPrivateNetwork = false,
        )
        assertEquals("fetch", args[0])
        assertEquals("/data/cache/shot.png", args[args.indexOf("--screenshot") + 1])
        assertEquals("45", args[args.indexOf("--timeout") + 1])
        assertEquals("load", args[args.indexOf("--wait-until") + 1])
        assertTrue(args.contains("--quiet"))
    }
}
