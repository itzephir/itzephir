package com.itzephir.website

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.jsoup.Jsoup
import java.nio.file.Files
import kotlin.test.*

class StagingBadgeTest {
    @Test
    fun `stage shows the badge by default and only zero hides it`() = testApplication {
        application { module(stagingBadge = true) }
        for (path in listOf("/", "/projects", "/about")) {
            for (query in listOf("", "?show_badge=1", "?show_badge=anything")) {
                val response = client.get(path + query)
                val document = Jsoup.parse(response.bodyAsText())
                assertEquals(1, document.select("body > #staging-badge").size)
                assertEquals("Вы на тестовом стенде", document.select("#staging-badge > span")[1].text())
                assertEquals(1, document.select("head > link[href^=/assets/staging-badge.css]").size)
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            }
            val hidden = client.get("$path?show_badge=0").bodyAsText()
            assertFalse(hidden.contains("staging-badge"))
        }
        val response = client.get("/projects?theme=light&q=%D1%82%D0%B5%D1%81%D1%82&tag=a&tag=b&show_badge=1")
        val close = Jsoup.parse(response.bodyAsText()).selectFirst(".staging-badge-close")!!
        val target = close.attr("href")
        assertTrue(target.startsWith("/projects?"))
        assertTrue(target.contains("theme=light"))
        assertTrue(target.contains("tag=a&tag=b"))
        assertTrue(target.contains("show_badge=0"))
        assertFalse(target.contains("show_badge=1"))
        assertFalse(client.get(target).bodyAsText().contains("staging-badge"))
    }

    @Test
    fun `production ignores badge query and spoofed stage host`() = testApplication {
        application { module(stagingBadge = false) }
        for (query in listOf("", "?show_badge=1", "?show_badge=0")) {
            val html = client.get("/$query") { header(HttpHeaders.Host, "dev.itzephir.com") }.bodyAsText()
            assertFalse(html.contains("staging-badge"))
        }
    }

    @Test
    fun `htmx fragments stay clean while history restoration has one badge`() = testApplication {
        application { module(stagingBadge = true) }
        val fragment = client.get("/about?show_badge=1") { header("HX-Request", "true") }.bodyAsText()
        assertFalse(fragment.contains("staging-badge"))
        val restored = client.get("/about") {
            header("HX-Request", "true")
            header("HX-History-Restore-Request", "true")
        }.bodyAsText()
        assertEquals(1, Jsoup.parse(restored).select("#staging-badge").size)
        assertEquals("ok", client.get("/healthz?show_badge=1").bodyAsText())
        val css = client.get("/assets/styles.css?show_badge=1")
        assertFalse(css.bodyAsText().contains("Вы на тестовом стенде"))
        assertTrue(css.headers[HttpHeaders.ContentType]!!.startsWith("text/css"))
    }

    @Test
    fun `interceptor decorates arbitrary HTML documents and preserves status and fragments`() = testApplication {
        application {
            install(StagingBadge)
            routing {
                get("/html") {
                    call.respondText("<HTML><HEAD></HEAD><BODY>failure</BODY></HTML>", ContentType.Text.Html, HttpStatusCode.Forbidden)
                }
                get("/partial") { call.respondText("<p>fragment</p>", ContentType.Text.Html) }
                get("/json") { call.respondText("{\"ok\":true}", ContentType.Application.Json) }
            }
        }
        val response = client.get("/html")
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(1, Jsoup.parse(response.bodyAsText()).select("body > #staging-badge").size)
        assertEquals("<p>fragment</p>", client.get("/partial").bodyAsText())
        assertEquals("{\"ok\":true}", client.get("/json").bodyAsText())
    }

    @Test
    fun `release target takes precedence and absence defaults to production`() {
        val directory = Files.createTempDirectory("staging-badge")
        val file = directory.resolve("environment")
        try {
            assertFalse(isStagingDeployment(file, null))
            assertTrue(isStagingDeployment(file, "dev"))
            Files.writeString(file, "prod\n")
            assertFalse(isStagingDeployment(file, "dev"))
            Files.writeString(file, "dev\n")
            assertTrue(isStagingDeployment(file, "prod"))
            Files.writeString(file, "unknown\n")
            assertFalse(isStagingDeployment(file, "dev"))
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }
}
