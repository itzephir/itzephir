package com.itzephir.website

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.ParametersBuilder
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.charset
import io.ktor.http.formUrlEncode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.request.path
import io.ktor.server.request.httpMethod
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.id
import kotlinx.html.title
import kotlinx.html.link
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.io.readByteArray
import java.nio.file.Files
import java.nio.file.Path

/** The activation script records the target before starting the packaged application. */
internal fun isStagingDeployment(
    environmentFile: Path = Path.of("deploy/environment"),
    localEnvironment: String? = System.getenv("DEPLOYMENT_ENV"),
): Boolean = if (Files.isRegularFile(environmentFile)) {
    Files.readString(environmentFile).trim() == "dev"
} else localEnvironment == "dev"

/** Decorates complete HTML documents; fragments, APIs and production bypass the plugin. */
internal val StagingBadge = createApplicationPlugin("StagingBadge") {
    on(ResponseBodyReadyForSend) { call, body ->
        if (call.request.httpMethod == HttpMethod.Head || call.request.queryParameters["show_badge"] == "0" ||
            (call.request.headers["HX-Request"] == "true" &&
                call.request.headers["HX-History-Restore-Request"] != "true") ||
            body.contentType?.match(ContentType.Text.Html) != true
        ) return@on
        // Leave downloads, encoded bodies and manually validated responses untouched.
        if (listOf(HttpHeaders.ContentDisposition, HttpHeaders.ContentLength, HttpHeaders.ContentEncoding, HttpHeaders.ContentRange,
                HttpHeaders.ETag, HttpHeaders.LastModified)
                .any { call.response.headers[it] != null || body.headers[it] != null }) return@on
        val charset = body.contentType?.charset() ?: Charsets.UTF_8
        val html = when (body) {
            is TextContent -> body.text
            is OutgoingContent.WriteChannelContent -> coroutineScope {
                val channel = ByteChannel()
                launch {
                    try { body.writeTo(channel) } finally { channel.close() }
                }
                channel.readBuffer().readByteArray().toString(charset)
            }
            is OutgoingContent.ReadChannelContent -> {
                val channel = body.readFrom()
                try { channel.readBuffer().readByteArray().toString(charset) } finally { channel.cancel(null) }
            }
            is OutgoingContent.ByteArrayContent -> body.bytes().toString(charset)
            else -> return@on
        }
        val bodyEnd = html.lastIndexOf("</body>", ignoreCase = true)
        val headEnd = html.lastIndexOf("</head>", ignoreCase = true)
        if (headEnd < 0 || bodyEnd < headEnd || html.contains("id=\"staging-badge\"")) {
            // A proxied writer is a one-shot stream, so replay the bytes we already read.
            transformBodyTo(body.withBytes(html.toByteArray(charset)))
            return@on
        }

        val query = ParametersBuilder().apply {
            appendAll(call.request.queryParameters)
            set("show_badge", "0")
        }.build().formUrlEncode()
        val badge = createHTML(prettyPrint = false).aside {
            id = "staging-badge"
            attributes["aria-label"] = "Окружение"
            span("staging-badge-dot") { attributes["aria-hidden"] = "true" }
            span { +"Вы на тестовом стенде" }
            val path = call.attributes.getOrNull(originalProjectUri)?.substringBefore('?') ?: call.request.path()
            a("$path?$query", classes = "staging-badge-close") {
                attributes["aria-label"] = "Скрыть уведомление о тестовом стенде"
                title = "Скрыть уведомление"
                span { attributes["aria-hidden"] = "true"; +"×" }
            }
        }
        val stylesheet = createHTML(prettyPrint = false).link {
            rel = "stylesheet"; href = asset("staging-badge.css")
        }
        val script = createHTML(prettyPrint = false).script {
            src = asset("staging-badge.js"); defer = true
        }
        // Insert from the end so the original head offset remains valid.
        val decorated = html.substring(0, bodyEnd) + badge + script + html.substring(bodyEnd)
        val bytes = (decorated.substring(0, headEnd) + stylesheet + decorated.substring(headEnd)).toByteArray(charset)
        if (call.response.headers[HttpHeaders.CacheControl] == null) {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        }
        transformBodyTo(body.withBytes(bytes))
    }
}

private fun OutgoingContent.withBytes(bytes: ByteArray): OutgoingContent = object : OutgoingContent.ByteArrayContent() {
    override val contentType = this@withBytes.contentType
    override val status = this@withBytes.status
    override val headers = this@withBytes.headers
    override val contentLength = bytes.size.toLong()
    override fun bytes() = bytes
}
