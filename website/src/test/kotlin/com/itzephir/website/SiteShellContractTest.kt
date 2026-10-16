package com.itzephir.website

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SiteShellContractTest {
    @Test
    fun `every route and response mode obeys the approved shell contract`() = testApplication {
        application { module(neighborNames = { WebringNeighbors() }) }
        val expected = assertNotNull(javaClass.getResource("/contracts/site-header.html")).readText().trim()
        for (page in PortfolioPage.entries) for (theme in Theme.entries) {
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
