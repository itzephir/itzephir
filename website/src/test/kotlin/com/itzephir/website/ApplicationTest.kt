package com.itzephir.website

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parametersOf
import io.ktor.server.testing.testApplication
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.*

class ApplicationTest {
    @Test
    fun `welcome and project routes contain complete accessible server rendered content`() = testApplication {
        application { module(neighborNames = { WebringNeighbors("Previous from API", "Next from API") }) }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val page = Jsoup.parse(response.bodyAsText())
        assertEquals("ru", page.selectFirst("html")!!.attr("lang"))
        assertEquals("dark", page.selectFirst("#portfolio")!!.attr("data-theme"))
        assertEquals("Дмитрий Дворянников", page.selectFirst("h1")!!.text())
        assertFalse(page.text().contains("ШМР"))
        assertEquals(listOf("/projects", "/about", "https://t.me/ItzEphir"), page.select(".shortcut").map { it.attr("href") })
        assertTrue(page.select("canvas").isEmpty())
        assertEquals(listOf("https://webring.otomir23.me/itzephir/prev", "https://webring.otomir23.me/", "https://webring.otomir23.me/itzephir/next"), page.select(".webring a").map { it.attr("href") })
        assertEquals(listOf("Previous from API", "webring", "Next from API"), page.select(".webring a").map { it.text() })
        page.select(".webring a").forEach { assertFalse(it.hasAttr("target")) }
        assertEquals("/?theme=light", page.selectFirst("#theme-toggle")!!.attr("href"))
        assertTrue(page.select("script[src]").single().attr("src").startsWith("/assets/htmx-2.0.11.min.js?v="))
        page.select("a[target=_blank]").forEach { assertTrue(it.attr("rel").contains("noopener")) }

        val projectPage = Jsoup.parse(client.get("/projects").bodyAsText())
        assertEquals("Мои проекты", projectPage.selectFirst("h1")!!.text())
        assertEquals(6, projectPage.select("a.project").size)
        assertEquals(6, projectPage.select("a.project").map { it.attr("href") }.toSet().size)
        assertEquals("projects", projectPage.selectFirst(".editor-tab-link[aria-current=page]")!!.text().trim())
        assertEquals("/", projectPage.selectFirst(".back-link")!!.attr("href"))
        assertEquals("https://itzephir.com/projects", projectPage.selectFirst("link[rel=canonical]")!!.attr("href"))
        val profile = Jsoup.parse(client.get("/about").bodyAsText())
        assertEquals("Обо мне", profile.selectFirst("h1")!!.text())
        assertTrue(profile.text().contains("Лектор ШМР Яндекса · 2026"))
        assertFalse(profile.text().contains("преподаю"))
    }

    @Test
    fun `only visited pages become tabs and the session cookie preserves their order`() = testApplication {
        application { module(secureCookies = true, neighborNames = { WebringNeighbors() }) }
        val first = client.get("/")
        val welcome = Jsoup.parse(first.bodyAsText())
        assertEquals(listOf("welcome"), welcome.select(".editor-tab").map { it.attr("data-tab") })
        assertTrue(welcome.select(".tab-close").isEmpty())
        val projectResponse = client.get("/projects") { header(HttpHeaders.Cookie, "workspace_tabs=welcome") }
        val project = Jsoup.parse(projectResponse.bodyAsText())
        assertEquals(listOf("welcome", "projects"), project.select(".editor-tab").map { it.attr("data-tab") })
        val cookie = projectResponse.headers.getAll(HttpHeaders.SetCookie)!!.single { it.startsWith("workspace_tabs=") }
        listOf("workspace_tabs=welcome.projects", "HttpOnly", "Secure", "SameSite=Lax").forEach { assertTrue(cookie.contains(it)) }
        assertFalse(cookie.contains("Max-Age", ignoreCase = true))
        val profile = Jsoup.parse(client.get("/about") { header(HttpHeaders.Cookie, "workspace_tabs=welcome.projects") }.bodyAsText())
        assertEquals(listOf("welcome", "projects", "profile"), profile.select(".editor-tab").map { it.attr("data-tab") })
        assertEquals("about.md", profile.selectFirst(".editor-tab-link[aria-current=page]")!!.text())
        profile.select(".tab-close-form").forEach {
            assertEquals("post", it.attr("method")); assertEquals("/tabs/close", it.attr("action"))
            assertEquals("profile", it.selectFirst("input[name=current]")!!.attr("value"))
        }
        val themed = Jsoup.parse(client.get("/about?theme=light") {
            header(HttpHeaders.Cookie, "workspace_tabs=welcome.projects.profile")
            header("HX-Request", "true")
        }.bodyAsText())
        assertEquals(listOf("welcome", "projects", "profile"), themed.select(".editor-tab").map { it.attr("data-tab") })
        val direct = Jsoup.parse(client.get("/projects").bodyAsText())
        assertEquals(listOf("projects"), direct.select(".editor-tab").map { it.attr("data-tab") })
    }

    @Test
    fun `close form removes a tab and redirects only to the appropriate internal page`() = testApplication {
        application { module(neighborNames = { WebringNeighbors() }) }
        val noRedirect = client.config { followRedirects = false }
        for ((closing, current, target, expectedCookie) in listOf(
            listOf("profile", "profile", "/projects", "welcome.projects"),
            listOf("projects", "profile", "/about", "welcome.profile"),
            listOf("welcome", "projects", "/projects", "projects.profile"),
        )) {
            val response = noRedirect.submitForm("/tabs/close", parametersOf("tab" to listOf(closing), "current" to listOf(current))) {
                header(HttpHeaders.Cookie, "workspace_tabs=welcome.projects.profile")
            }
            assertEquals(HttpStatusCode.SeeOther, response.status)
            assertEquals(target, response.headers[HttpHeaders.Location])
            assertTrue(response.headers[HttpHeaders.SetCookie]!!.startsWith("workspace_tabs=$expectedCookie;"))
        }
        val last = noRedirect.submitForm("/tabs/close", parametersOf("tab" to listOf("projects"), "current" to listOf("projects"))) {
            header(HttpHeaders.Cookie, "workspace_tabs=projects")
        }
        assertEquals("/", last.headers[HttpHeaders.Location])
        assertTrue(last.headers[HttpHeaders.SetCookie]!!.startsWith("workspace_tabs=welcome;"))
        val invalid = noRedirect.submitForm("/tabs/close", parametersOf("tab" to listOf("https://example.com"), "current" to listOf("welcome")))
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        val crossSite = noRedirect.submitForm("/tabs/close", parametersOf("tab" to listOf("projects"), "current" to listOf("projects"))) {
            header(HttpHeaders.Origin, "https://example.com")
        }
        assertEquals(HttpStatusCode.Forbidden, crossSite.status)
        assertNull(crossSite.headers[HttpHeaders.SetCookie])
        assertEquals(HttpStatusCode.MethodNotAllowed, client.get("/tabs/close").status)
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
