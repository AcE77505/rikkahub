package me.rerere.rikkahub.data.ai

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestLoggingInterceptorTest {
    @Test
    fun `error response preview is bounded and retains provider error details`() {
        val body = """{"error":{"message":"Insufficient Balance","api_key":"private"}}"""
        val response = response(402, body)

        val loggedBody = response.errorBodyForLogging()

        assertTrue(loggedBody.orEmpty().contains("Insufficient Balance"))
        assertTrue(loggedBody.orEmpty().contains(""""api_key":"██""""))
        assertNull(response(200, body).errorBodyForLogging())
    }

    @Test
    fun `large error response previews are truncated`() {
        val response = response(500, """{"error":"${"x".repeat(5_000)}"}""")

        assertTrue(response.errorBodyForLogging().orEmpty().endsWith("[truncated]"))
    }

    @Test
    fun `request URL headers and body redact common credentials`() {
        val url =
            "https://user:password@example.test/chat?api_key=private&query=ok&access_token=secret&client_secret=private"
                .toHttpUrl()
        val headers = Headers.Builder()
            .add("Authorization", "Bearer private")
            .add("X-Api-Key", "secret")
            .add("Content-Type", "application/json")
            .build()

        assertEquals("██", url.redactLogSecrets().queryParameter("api_key"))
        assertEquals("ok", url.redactLogSecrets().queryParameter("query"))
        assertEquals("██", url.redactLogSecrets().queryParameter("client_secret"))
        assertEquals("", url.redactLogSecrets().username())
        assertEquals("", url.redactLogSecrets().password())
        assertEquals("██", headers.toSafeLogMap()["Authorization"])
        assertEquals("██", headers.toSafeLogMap()["X-Api-Key"])
        assertEquals("""{"api_key":"██"}""", """{"api_key":"private"}""".redactLogSecrets())
    }

    private fun response(code: Int, body: String) = Response.Builder()
        .request(Request.Builder().url("https://example.test/chat").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 200) "OK" else "Request failed")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()
}
