package com.itzephir.website

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.copyTo
import kotlinx.coroutines.CancellationException
import java.net.URI

internal val originalProjectUri = AttributeKey<String>("OriginalProjectUri")
internal const val stagingProjectPrefix = "/__staging-project"
private val hopHeaders = setOf(
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailer", "transfer-encoding", "upgrade",
)

private fun Headers.hopHeaders(): Set<String> = hopHeaders +
    getAll(HttpHeaders.Connection).orEmpty().flatMap { it.split(',') }.map { it.trim().lowercase() }

/** Fixed loopback target: nginx still owns project lookup, routing and upstream redirects. */
internal fun Application.stagingProjectGateway(gatewayUrl: String = "http://127.0.0.1:18101") {
    val upstream = HttpClient(CIO) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) { connectTimeoutMillis = 5_000; socketTimeoutMillis = 60_000 }
    }
    monitor.subscribe(ApplicationStopped) { upstream.close() }
    routing {
        route("$stagingProjectPrefix/{path...}") {
            handle {
                // Use the raw URI: preserve escaped paths, duplicate parameters and their order.
                val original = "/dev/project" + call.request.uri.removePrefix(stagingProjectPrefix)
                call.attributes.put(originalProjectUri, original)
                val decorate = call.request.httpMethod != HttpMethod.Head &&
                    call.request.queryParameters["show_badge"] != "0" &&
                    (call.request.headers["HX-Request"] != "true" ||
                        call.request.headers["HX-History-Restore-Request"] == "true")
                val requestHopHeaders = call.request.headers.hopHeaders()
                val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                val requestContentType = call.request.headers[HttpHeaders.ContentType]?.let(ContentType::parse)
                val incoming = if (length != null || call.request.headers[HttpHeaders.TransferEncoding] != null || requestContentType != null) {
                    call.receiveChannel()
                } else null
                try {
                    upstream.prepareRequest(gatewayUrl + original) {
                        method = call.request.httpMethod
                        call.request.headers.forEach { name, values ->
                            if (name.lowercase() !in requestHopHeaders &&
                                !name.equals(HttpHeaders.ContentLength, true) &&
                                !name.equals(HttpHeaders.ContentType, true) &&
                                !name.equals(HttpHeaders.AcceptEncoding, true)
                            ) headers.appendAll(name, values)
                        }
                        // Both gateway hops forward this: project compression cannot hide HTML.
                        headers.append(HttpHeaders.AcceptEncoding, "identity")
                        if (incoming != null) {
                            setBody(object : OutgoingContent.ReadChannelContent() {
                                override val contentType = requestContentType
                                override val contentLength = length
                                override fun readFrom() = incoming
                            })
                        }
                    }.execute { response ->
                        val contentType = response.headers[HttpHeaders.ContentType]?.let(ContentType::parse)
                        val html = decorate && contentType?.match(ContentType.Text.Html) == true &&
                            response.headers[HttpHeaders.ContentDisposition] == null &&
                            response.headers[HttpHeaders.ContentEncoding] == null &&
                            response.headers[HttpHeaders.ContentRange] == null
                        val responseHopHeaders = response.headers.hopHeaders()
                        val transformedHeaders = setOf("content-length", "etag", "last-modified", "cache-control", "accept-ranges")
                        response.headers.forEach { name, values ->
                            if (name.lowercase() !in responseHopHeaders &&
                                !name.equals(HttpHeaders.ContentType, true) &&
                                !name.equals(HttpHeaders.ContentLength, true) &&
                                (!html || name.lowercase() !in transformedHeaders)
                            ) values.forEach { value ->
                                val location = if (name.equals(HttpHeaders.Location, true)) {
                                    publicProjectRedirect(value, gatewayUrl, call.request.headers[HttpHeaders.Host])
                                } else value
                                call.response.headers.append(name, location, safeOnly = false)
                            }
                        }
                        if (html) call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                        call.respond(object : OutgoingContent.WriteChannelContent() {
                            override val contentType = contentType
                            override val status = response.status
                            override val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeUnless { html }
                            override suspend fun writeTo(channel: ByteWriteChannel) {
                                response.bodyAsChannel().copyTo(channel)
                            }
                        })
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (call.response.isCommitted) throw failure
                    log.warn("Staging project gateway unavailable: {}", failure.javaClass.simpleName)
                    call.respondText("Project gateway unavailable", ContentType.Text.Plain, HttpStatusCode.BadGateway)
                }
            }
        }
    }
}

/** nginx may expand a relative project redirect using its private listener's port. */
private fun publicProjectRedirect(location: String, gatewayUrl: String, publicHost: String?): String {
    val uri = runCatching { URI(location) }.getOrNull() ?: return location
    val gateway = URI(gatewayUrl)
    val host = publicHost?.substringBefore(':')
    return if (uri.port == gateway.port && uri.host in setOf(gateway.host, host)) {
        uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "") +
            (uri.rawFragment?.let { "#$it" } ?: "")
    } else location
}
