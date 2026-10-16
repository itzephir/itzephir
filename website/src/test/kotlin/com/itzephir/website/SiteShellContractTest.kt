package com.itzephir.website

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.server.application.call
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import com.itzephir.locator.Identity
import com.itzephir.locator.IdentityRole
import com.itzephir.locator.LocatorPage
import kotlinx.html.h1
import java.util.UUID
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SiteShellContractTest {
    @Test
    fun `every route and response mode obeys the approved shell contract`() = testApplication {
        val development = PortfolioPage.developmentPages + PortfolioPage.editProject(UUID.fromString("12345678-1234-1234-1234-123456789abc"))
        application {
            module(neighborNames = { WebringNeighbors() })
            // Exercise the real host renderer without database/auth fixtures; those have integration tests.
            val renderer = developmentRenderer(false) { WebringNeighbors() }
            routing { development.forEach { page -> get(page.path) {
                renderer.render(call, LocatorPage("Dev", Identity("test-admin", IdentityRole.ADMIN), "test-csrf", HttpStatusCode.OK) { h1 { +"Dev content" } })
            } } }
        }
        val expected = assertNotNull(javaClass.getResource("/contracts/site-header.html")).readText().trim()
        for (page in PortfolioPage.entries + development) for (theme in Theme.entries) {
            for (mode in listOf("document", "fragment", "history")) for (tabs in listOf("", "welcome.projects.profile")) {
                val response = client.get("${page.path}?theme=${theme.value}") {
                    header(HttpHeaders.Cookie, "workspace_tabs=$tabs")
                    if (mode != "document") header("HX-Request", "true")
                    if (mode == "history") header("HX-History-Restore-Request", "true")
                }
                val context = "${page.path}, ${theme.value}, $mode, tabs=$tabs"
                assertEquals(HttpStatusCode.OK, response.status, context)
                val document = Jsoup.parse(response.bodyAsText())
                val shell = document.select("#portfolio").single()
                assertEquals("portfolio", shell.className(), context)
                assertEquals("1", shell.attr("data-shell-contract"), context)
                assertEquals(listOf("a", "header", "nav", "main"), shell.children().map { it.tagName() }, context)
                assertEquals("skip-link", shell.child(0).className(), context)
                assertEquals("app-header", shell.child(1).className(), context)
                assertEquals("editor-tabs mono", shell.child(2).className(), context)
                assertEquals("content", shell.child(3).id(), context)
                assertEquals(1, document.select(".app-header").size, context)
                assertEquals(page.path, shell.selectFirst(".workspace-label")!!.text(), context)
                assertEquals("${page.path}?theme=${theme.opposite.value}", shell.selectFirst("#theme-toggle")!!.attr("href"), context)
                assertEquals("${page.path}?theme=${theme.opposite.value}", shell.selectFirst("#theme-toggle")!!.attr("hx-get"), context)
                shell.child(1).select("img[src], source[srcset]").forEach { image ->
                    val url = image.attr(if (image.hasAttr("src")) "src" else "srcset")
                    kotlin.test.assertTrue(url.startsWith("/assets/design/${theme.value}/"), context)
                }
                assertEquals(expected, normalizedHeader(shell.child(1)), context)
            }
        }
    }

    private fun normalizedHeader(header: Element): String {
        header.ownerDocument()!!.outputSettings().prettyPrint(false)
        header.getAllElements().forEach { element -> element.textNodes().filter { it.isBlank }.forEach { it.remove() } }
        header.selectFirst(".workspace-label")!!.text("{path}")
        for ((selector, name, label) in listOf(
            Triple(".ring-previous", "{previous}", "Предыдущий сайт"),
            Triple(".ring-next", "{next}", "Следующий сайт"),
        )) {
            val link = header.selectFirst(selector)!!
            link.attr("title", name).attr("aria-label", "$label: $name")
            link.selectFirst("span:not(.icon)")!!.text(name)
        }
        header.select(".theme-toggle").forEach {
            it.attr("href", "{path}?theme={opposite}").attr("hx-get", "{path}?theme={opposite}")
                .attr("aria-label", "{toggle-label}").attr("title", "{toggle-title}")
        }
        header.select("[src], [srcset]").forEach { element ->
            for (attribute in listOf("src", "srcset")) if (element.hasAttr(attribute)) {
                element.attr(attribute, element.attr(attribute).substringBefore('?')
                    .replace("/design/dark/", "/design/{theme}/").replace("/design/light/", "/design/{theme}/"))
            }
        }
        return header.outerHtml()
    }
}
