package com.itzephir.website

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApplicationTest {
    @Test
    fun `initial document contains the complete portfolio and accessible links`() = testApplication {
        application { module() }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType]!!.startsWith("text/html"))
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val page = Jsoup.parse(response.bodyAsText())
        assertEquals("ru", page.selectFirst("html")!!.attr("lang"))
        assertEquals("dark", page.selectFirst("#portfolio")!!.attr("data-theme"))
        assertEquals(1, page.select("h1").size)
        assertEquals(6, page.select(".project").size)
        assertEquals(3, page.select(".contact-link").size)
        assertTrue(page.select("canvas").isEmpty())
        assertEquals(listOf("https://webring.otomir23.me/itzephir/prev", "https://webring.otomir23.me/", "https://webring.otomir23.me/itzephir/next"), page.select(".webring a").map { it.attr("href") })
        page.select(".webring a").forEach { assertFalse(it.hasAttr("target")) }
        assertEquals("/?theme=light", page.selectFirst("#theme-toggle")!!.attr("href"))
        assertTrue(page.select("script[src]").single().attr("src").startsWith("/assets/htmx-2.0.11.min.js?v="))
        page.select("a[target=_blank]").forEach { assertTrue(it.attr("rel").contains("noopener")) }
    }

    @Test
    fun `htmx theme request returns only a fragment and persists the preference`() = testApplication {
        application { module(secureCookies = true) }
        val response = client.get("/?theme=light") { header("HX-Request", "true") }
        assertEquals(HttpStatusCode.OK, response.status)
        val html = response.bodyAsText()
        assertFalse(html.contains("<!DOCTYPE", ignoreCase = true))
        assertFalse(html.contains("<html"))
        assertFalse(html.contains("<script"))
        val fragment = Jsoup.parseBodyFragment(html)
        assertEquals("light", fragment.selectFirst("#portfolio")!!.attr("data-theme"))
        assertEquals("/?theme=dark", fragment.selectFirst("#theme-toggle")!!.attr("href"))
        assertEquals(6, fragment.select(".project").size)
        val cookie = assertNotNull(response.headers[HttpHeaders.SetCookie])
        assertTrue(cookie.contains("theme=light"))
        assertTrue(cookie.contains("HttpOnly", ignoreCase = true))
        assertTrue(cookie.contains("Secure", ignoreCase = true))
        assertTrue(cookie.contains("SameSite=Lax", ignoreCase = true))
        assertTrue(response.headers[HttpHeaders.Vary]!!.contains("HX-Request"))
        val nextVisit = client.get("/") { header(HttpHeaders.Cookie, "theme=light") }
        assertEquals("light", Jsoup.parse(nextVisit.bodyAsText()).selectFirst("#portfolio")!!.attr("data-theme"))
    }

    @Test
    fun `plain links and htmx history restoration get a complete document`() = testApplication {
        application { module() }
        listOf(false, true).forEach { restore ->
            val response = client.get("/?theme=light") {
                if (restore) { header("HX-Request", "true"); header("HX-History-Restore-Request", "true") }
            }
            val html = response.bodyAsText()
            assertTrue(html.contains("<!DOCTYPE", ignoreCase = true))
            assertEquals("light", Jsoup.parse(html).selectFirst("#portfolio")!!.attr("data-theme"))
        }
        val invalidCookie = client.get("/") { header(HttpHeaders.Cookie, "theme=invalid") }
        assertEquals("dark", Jsoup.parse(invalidCookie.bodyAsText()).selectFirst("#portfolio")!!.attr("data-theme"))
        assertEquals(HttpStatusCode.BadRequest, client.get("/?theme=invalid").status)
    }

    @Test
    fun `every referenced asset is packaged with the expected content type`() = testApplication {
        application { module() }
        val page = Jsoup.parse(client.get("/").bodyAsText())
        val urls = page.select("link[href], script[src], img[src]").map {
            if (it.hasAttr("href")) it.attr("href") else it.attr("src")
        }.filter { it.startsWith("/assets/") } + listOf("/assets/fonts/roboto.ttf", "/assets/fonts/roboto-mono.ttf")
        urls.forEach { url ->
            val asset = client.get(url)
            assertEquals(HttpStatusCode.OK, asset.status, url)
            assertTrue(asset.headers[HttpHeaders.CacheControl]!!.contains("max-age=3600"))
            assertFalse(asset.headers[HttpHeaders.ContentType]!!.startsWith("text/html"), url)
        }
        assertTrue(client.get("/assets/styles.css").headers[HttpHeaders.ContentType]!!.startsWith(ContentType.Text.CSS.toString()))
        assertEquals(HttpStatusCode.NotFound, client.get("/assets/missing.js").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/missing").status)
        assertEquals("ok", client.get("/healthz").bodyAsText())
    }
}
