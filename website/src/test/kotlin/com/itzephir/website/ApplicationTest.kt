package com.itzephir.website

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.*

class ApplicationTest {
    @Test
    fun `welcome and project routes contain complete accessible server rendered content`() = testApplication {
        application { module(neighborNames = { WebringNeighbors() }) }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val page = Jsoup.parse(response.bodyAsText())
        assertEquals("ru", page.selectFirst("html")!!.attr("lang"))
        assertEquals("dark", page.selectFirst("#portfolio")!!.attr("data-theme"))
        assertEquals("Дмитрий Дворянников", page.selectFirst("h1")!!.text())
        assertEquals(listOf("/projects", "/about", "https://t.me/ItzEphir"), page.select(".shortcut").map { it.attr("href") })
        assertTrue(page.select("canvas").isEmpty())
        assertEquals(listOf("https://webring.otomir23.me/itzephir/prev", "https://webring.otomir23.me/", "https://webring.otomir23.me/itzephir/next"), page.select(".webring a").map { it.attr("href") })
        assertEquals(listOf("CMD/OPT/S", "webring", "badinteger"), page.select(".webring a").map { it.text() })
        page.select(".webring a").forEach { assertFalse(it.hasAttr("target")) }
        assertEquals("/?theme=light", page.selectFirst("#theme-toggle")!!.attr("href"))
        assertTrue(page.select("script[src]").single().attr("src").startsWith("/assets/htmx-2.0.11.min.js?v="))
        page.select("a[target=_blank]").forEach { assertTrue(it.attr("rel").contains("noopener")) }

        val projectPage = Jsoup.parse(client.get("/projects").bodyAsText())
        assertEquals("Мои проекты", projectPage.selectFirst("h1")!!.text())
        assertEquals(6, projectPage.select("a.project").size)
        assertEquals(6, projectPage.select("a.project").map { it.attr("href") }.toSet().size)
        assertEquals("projects", projectPage.selectFirst(".editor-tab[aria-current=page]")!!.text().trim())
        assertEquals("/", projectPage.selectFirst(".back-link")!!.attr("href"))
        assertEquals("https://itzephir.com/projects", projectPage.selectFirst("link[rel=canonical]")!!.attr("href"))
        val profile = Jsoup.parse(client.get("/about").bodyAsText())
        assertEquals("Обо мне", profile.selectFirst("h1")!!.text())
        assertTrue(profile.text().contains("одной лекцией"))
        assertFalse(profile.text().contains("преподаю"))
    }

    @Test
    fun `htmx theme requests keep the current page and persist a secure preference`() = testApplication {
        application { module(secureCookies = true, neighborNames = { WebringNeighbors() }) }
        for (path in listOf("/", "/projects", "/about")) {
            val response = client.get("$path?theme=light") { header("HX-Request", "true") }
            val html = response.bodyAsText()
            assertFalse(html.contains("<!DOCTYPE", ignoreCase = true))
            assertFalse(html.contains("<html"))
            assertFalse(html.contains("<script"))
            val fragment = Jsoup.parseBodyFragment(html)
            assertEquals("light", fragment.selectFirst("#portfolio")!!.attr("data-theme"))
            assertEquals("$path?theme=dark", fragment.selectFirst("#theme-toggle")!!.attr("href"))
            assertEquals("$path?theme=dark", fragment.selectFirst("#theme-toggle-mobile")!!.attr("href"))
            if (path == "/projects") assertEquals(6, fragment.select("a.project").size)
            val cookie = assertNotNull(response.headers[HttpHeaders.SetCookie])
            listOf("theme=light", "HttpOnly", "Secure", "SameSite=Lax").forEach { assertTrue(cookie.contains(it, ignoreCase = true)) }
            assertTrue(response.headers[HttpHeaders.Vary]!!.contains("HX-Request"))
            val nextVisit = client.get(path) { header(HttpHeaders.Cookie, "theme=light") }
            assertEquals("light", Jsoup.parse(nextVisit.bodyAsText()).selectFirst("#portfolio")!!.attr("data-theme"))
        }
    }

    @Test
    fun `plain links and history restore return full documents without accepting unknown themes`() = testApplication {
        application { module(neighborNames = { WebringNeighbors() }) }
        for (path in listOf("/", "/projects", "/about")) {
            for (restore in listOf(false, true)) {
                val response = client.get("$path?theme=light") {
                    if (restore) { header("HX-Request", "true"); header("HX-History-Restore-Request", "true") }
                }
                assertTrue(response.bodyAsText().contains("<!DOCTYPE", ignoreCase = true))
                assertEquals("light", Jsoup.parse(response.bodyAsText()).selectFirst("#portfolio")!!.attr("data-theme"))
            }
            assertEquals(HttpStatusCode.BadRequest, client.get("$path?theme=invalid").status)
        }
        val response = client.get("/") { header(HttpHeaders.Cookie, "theme=invalid") }
        assertEquals("dark", Jsoup.parse(response.bodyAsText()).selectFirst("#portfolio")!!.attr("data-theme"))
        assertEquals(HttpStatusCode.NotFound, client.get("/missing").status)
        assertEquals("ok", client.get("/healthz").bodyAsText())
    }

    @Test
    fun `all theme assets are local and favicon exports have their declared pixel dimensions`() = testApplication {
        application { module(neighborNames = { WebringNeighbors() }) }
        val urls = mutableSetOf<String>()
        for (theme in Theme.entries) {
            for (path in listOf("/", "/projects", "/about")) {
                val page = Jsoup.parse(client.get("$path?theme=${theme.value}").bodyAsText())
                assertEquals(6, page.select("link[rel=icon]").size)
                page.select("link[href], script[src], img[src], source[srcset]").forEach {
                    val url = it.attr(if (it.hasAttr("href")) "href" else if (it.hasAttr("srcset")) "srcset" else "src")
                    if (url.startsWith("/assets/")) urls += url
                }
            }
            for (size in listOf(16, 32)) {
                val bytes = client.get("/assets/design/${theme.value}/favicon-$size.png").bodyAsBytes()
                val image = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
                assertEquals(size, image.width); assertEquals(size, image.height)
            }
        }
        urls += listOf("/assets/fonts/ibm-plex-sans.ttf", "/assets/fonts/ibm-plex-mono.ttf", "/assets/fonts/ibm-plex-mono-medium.ttf")
        urls.forEach { url ->
            val response = client.get(url)
            assertEquals(HttpStatusCode.OK, response.status, url)
            assertTrue(response.bodyAsBytes().isNotEmpty(), url)
            assertTrue(response.headers[HttpHeaders.CacheControl]!!.contains("max-age=3600"))
            assertFalse(response.headers[HttpHeaders.ContentType]!!.startsWith("text/html"), url)
        }
        assertTrue(client.get("/assets/styles.css").headers[HttpHeaders.ContentType]!!.startsWith(ContentType.Text.CSS.toString()))
        assertEquals(HttpStatusCode.NotFound, client.get("/assets/missing.js").status)
    }

    @Test
    fun `neighbor names are escaped text and cannot replace the registered navigation URLs`() = testApplication {
        application { module(neighborNames = { WebringNeighbors("<b>previous</b>", "next & friend") }) }
        val page = Jsoup.parse(client.get("/").bodyAsText())
        assertEquals("<b>previous</b>", page.selectFirst(".ring-previous")!!.text())
        assertEquals("next & friend", page.selectFirst(".ring-next")!!.text())
        assertTrue(page.select(".webring b").isEmpty())
        assertEquals("https://webring.otomir23.me/itzephir/prev", page.selectFirst(".ring-previous")!!.attr("href"))
    }
}
