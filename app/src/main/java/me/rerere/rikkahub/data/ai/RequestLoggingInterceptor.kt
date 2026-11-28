package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.IOException

private const val MAX_LOGGED_RESPONSE_BODY_BYTES = 4_096L
private const val MAX_LOGGED_REQUEST_BODY_BYTES = 65_536L
private const val REDACTED = "██"

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()
        val requestHeaders = request.headers.toSafeLogMap()
        val requestBody = request.body?.captureForLogging()
        val requestUrl = request.url.redactLogSecrets().toString()

        val response: Response
        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = requestUrl,
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    error = e::class.simpleName ?: "IOException",
                )
            )
            throw e
        }

        val responseBody = response.errorBodyForLogging()
        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = requestUrl,
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = response.headers.toSafeLogMap(),
                responseBody = responseBody,
                durationMs = System.currentTimeMillis() - startTime,
            )
        )
        return response
    }
}

internal fun okhttp3.Headers.toSafeLogMap(): Map<String, String> =
    names().associateWith { name ->
        if (name.isSensitiveLogHeader()) REDACTED else get(name)?.redactLogSecrets() ?: ""
    }

internal fun Response.errorBodyForLogging(): String? {
    val body = body ?: return null
    val contentType = body.contentType() ?: return null
    if (code < 400 ||
        (contentType.type != "text" && !contentType.subtype.endsWith("json", ignoreCase = true))
    ) {
        return null
    }

    val preview = try {
        peekBody(MAX_LOGGED_RESPONSE_BODY_BYTES).string().redactLogSecrets()
    } catch (error: IOException) {
        return "[response body unavailable: ${error::class.simpleName ?: "IOException"}]"
    }
    val truncated = body.contentLength() > MAX_LOGGED_RESPONSE_BODY_BYTES ||
        preview.toByteArray().size >= MAX_LOGGED_RESPONSE_BODY_BYTES
    return if (truncated) "$preview\n[truncated]" else preview
}

private fun okhttp3.RequestBody.captureForLogging(): String {
    if (isOneShot || isDuplex) return "[omitted: streaming request body]"
    val size = contentLength()
    if (size < 0) return "[omitted: unknown request body length]"
    if (size > MAX_LOGGED_REQUEST_BODY_BYTES) return "[omitted: request body exceeds limit]"
    val buffer = Buffer()
    try {
        writeTo(buffer)
    } catch (error: IOException) {
        return "[request body unavailable: ${error::class.simpleName ?: "IOException"}]"
    }
    return buffer.readUtf8().redactLogSecrets()
}

internal fun String.isSensitiveLogHeader(): Boolean {
    val normalized = lowercase().replace("-", "")
    return normalized.contains("authorization") ||
        normalized.contains("apikey") ||
        normalized.contains("token") ||
        normalized.contains("secret") ||
        normalized == "cookie" ||
        normalized == "setcookie"
}

internal fun HttpUrl.redactLogSecrets(): HttpUrl = newBuilder().apply {
    username("")
    password("")
    queryParameterNames
        .filter { name ->
            val normalized = name.lowercase().replace("-", "").replace("_", "")
            normalized in setOf(
                "key",
                "apikey",
                "accesskey",
                "apitoken",
                "token",
                "accesstoken",
                "refreshtoken",
                "secret",
                "clientsecret",
                "password",
                "auth",
                "authorization",
                "signature",
            )
        }
        .forEach { name -> setQueryParameter(name, REDACTED) }
}.build()

internal fun String.redactLogSecrets(): String = replace(
    Regex("""(?i)("(?:authorization|proxy-authorization|x-api-key|api[_-]?(?:key|token)|access[_-]?token|refresh[_-]?token|client[_-]?secret|password|secret|token)"\s*:\s*")[^"]*(")"""),
    { match -> match.groupValues[1] + REDACTED + match.groupValues[2] },
).replace(
    Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/-]+=*"""),
    { "Bearer $REDACTED" },
).replace(
    Regex("""(?i)([?&](?:api[_-]?(?:key|token)|key|access[_-]?token|refresh[_-]?token|token|client[_-]?secret|password|secret|auth|authorization|signature)=)[^&\s]+"""),
    { match -> match.groupValues[1] + REDACTED },
)
