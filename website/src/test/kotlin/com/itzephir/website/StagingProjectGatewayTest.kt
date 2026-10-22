package com.itzephir.website

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.jsoup.Jsoup
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.*

class StagingProjectGatewayTest {
    private val requests = ConcurrentLinkedQueue<List<String>>()
    private val html = "<!doctype html><html><head><title>Project</title></head><body><h1>Project</h1>${"content ".repeat(8000)}</body></html>"
    private val binary = ByteArray(256) { it.toByte() }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val incoming = exchange.requestBody.readAllBytes()
            requests.add(listOf(exchange.requestMethod, exchange.requestURI.toASCIIString(),
                exchange.requestHeaders.getFirst(HttpHeaders.AcceptEncoding).orEmpty(),
                exchange.requestHeaders.getFirst(HttpHeaders.Cookie).orEmpty(), incoming.toString(Charsets.UTF_8),
                exchange.requestHeaders.getFirst(HttpHeaders.IfNoneMatch).orEmpty()))
            val path = exchange.requestURI.path
            var status = 200
            val payload = when {
                path.endsWith("/redirect") -> {
                    status = 308
                    exchange.responseHeaders.add(HttpHeaders.Location, "/dev/project/example/?next=1")
                    byteArrayOf()
                }
                path.endsWith("/api") -> "{\"ok\":true}".toByteArray()
                path.endsWith("/file") -> binary
                path.endsWith("/upload") -> incoming
                path.endsWith("/fragment") -> "<main>partial</main>".toByteArray()
                else -> html.toByteArray()
            }
            val type = when {
                path.endsWith("/api") -> "application/json"
                path.endsWith("/file") || path.endsWith("/upload") -> "application/octet-stream"
                else -> "text/html; charset=utf-8"
            }
            if (path.endsWith("/range")) {
                status = 206
                exchange.responseHeaders.add(HttpHeaders.ContentRange, "bytes 0-${payload.size - 1}/${payload.size}")
            }
            exchange.responseHeaders.add(HttpHeaders.ContentType, type)
            if (!path.endsWith("/range")) exchange.responseHeaders.add(HttpHeaders.ETag, "\"project-v1\"")
            exchange.responseHeaders.add(HttpHeaders.CacheControl, "public, max-age=3600")
            exchange.responseHeaders.add(HttpHeaders.SetCookie, "project=a; Path=/dev/project/example/; HttpOnly")
            exchange.responseHeaders.add(HttpHeaders.SetCookie, "other=b; Path=/dev/project/example/")
            exchange.responseHeaders.add("X-Project", "upstream")
            if (path.endsWith("/download")) exchange.responseHeaders.add(HttpHeaders.ContentDisposition, "attachment; filename=page.html")
            if (exchange.requestMethod == "HEAD") {
                exchange.responseHeaders.add(HttpHeaders.ContentLength, payload.size.toString())
                exchange.sendResponseHeaders(status, -1)
            } else {
                exchange.sendResponseHeaders(status, if (payload.isEmpty()) -1 else payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            exchange.close()
        }
        start()
    }

    @AfterTest fun stop() { server.stop(0) }

    @Test
    fun `project HTML gets one badge with public URL and identity encoding`() = testApplication {
        application { install(StagingBadge); stagingProjectGateway("http://127.0.0.1:${server.address.port}") }
        for (query in listOf("", "?show_badge=1&tag=a&tag=b", "?q=a%2Fb&show_badge=1")) {
            val response = client.get("$stagingProjectPrefix/example/docs$query") {
                header(HttpHeaders.AcceptEncoding, "gzip, br")
                header(HttpHeaders.Cookie, "project=a")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val doc = Jsoup.parse(response.bodyAsText())
            assertEquals(1, doc.select("#staging-badge").size)
            assertTrue(doc.selectFirst(".staging-badge-close")!!.attr("href").startsWith("/dev/project/example/docs?"))
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertNull(response.headers[HttpHeaders.ETag])
            assertEquals("upstream", response.headers["X-Project"])
            assertEquals(2, response.headers.getAll(HttpHeaders.SetCookie)!!.size)
            val request = requests.remove()
            assertEquals("/dev/project/example/docs$query", request[1])
            assertEquals("identity", request[2])
            assertEquals("project=a", request[3])
        }
        val hidden = client.get("$stagingProjectPrefix/example/docs?show_badge=0")
        assertEquals(html, hidden.bodyAsText())
        assertEquals("\"project-v1\"", hidden.headers[HttpHeaders.ETag])
        assertEquals("public, max-age=3600", hidden.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `proxy preserves non HTML downloads request bodies redirects and HEAD`() = testApplication {
        application { install(StagingBadge); stagingProjectGateway("http://127.0.0.1:${server.address.port}") }
        assertEquals("{\"ok\":true}", client.get("$stagingProjectPrefix/example/api").bodyAsText())
        assertContentEquals(binary, client.get("$stagingProjectPrefix/example/file").bodyAsBytes())
        val uploaded = client.post("$stagingProjectPrefix/example/upload?q=a%2Fb") { setBody(binary) }
        assertContentEquals(binary, uploaded.bodyAsBytes())
        val conditional = client.post("$stagingProjectPrefix/example/upload") {
            header(HttpHeaders.IfNoneMatch, "*")
            header(HttpHeaders.IfModifiedSince, "Tue, 20 Oct 2026 10:00:00 GMT")
            setBody(binary)
        }
        assertContentEquals(binary, conditional.bodyAsBytes())
        assertEquals("*", requests.last()[5], "Write preconditions must reach the project unchanged")
        val download = client.get("$stagingProjectPrefix/example/download")
        assertEquals(html, download.bodyAsText())
        assertTrue(download.headers[HttpHeaders.ContentDisposition]!!.startsWith("attachment"))
        assertEquals("<main>partial</main>", client.get("$stagingProjectPrefix/example/fragment").bodyAsText())
        val range = client.get("$stagingProjectPrefix/example/range")
        assertEquals(HttpStatusCode.PartialContent, range.status)
        assertEquals(html, range.bodyAsText())
        val redirect = createClient { followRedirects = false }.get("$stagingProjectPrefix/example/redirect")
        assertEquals(HttpStatusCode.PermanentRedirect, redirect.status)
        assertEquals("/dev/project/example/?next=1", redirect.headers[HttpHeaders.Location])
        val head = client.head("$stagingProjectPrefix/example/")
        assertEquals(HttpStatusCode.OK, head.status)
        assertEquals(html.toByteArray().size.toString(), head.headers[HttpHeaders.ContentLength])
        assertContentEquals(byteArrayOf(), head.bodyAsBytes())
    }

    @Test
    fun `production installs no project interception endpoint`() = testApplication {
        application { module(stagingBadge = false) }
        assertEquals(HttpStatusCode.NotFound, client.get("$stagingProjectPrefix/example/?show_badge=1").status)
    }
}
