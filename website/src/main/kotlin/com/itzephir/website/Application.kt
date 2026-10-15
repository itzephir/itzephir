package com.itzephir.website

import com.itzephir.locator.PostgresLocator
import com.itzephir.locator.locatorFromEnvironment
import com.itzephir.locator.projectLocator
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.html.respondHtml
import io.ktor.server.html.respondHtmlPartial
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.net.URI

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("BIND_HOST") ?: "127.0.0.1"
    val locator = locatorFromEnvironment()
    embeddedServer(CIO, host = host, port = port) { module(locator = locator) }.start(wait = true)
}

fun Application.module(
    secureCookies: Boolean = System.getenv("COOKIE_SECURE") == "true",
    neighborNames: () -> WebringNeighbors = WebringDirectory()::neighbors,
    locator: PostgresLocator? = null,
) {
    if (locator != null) projectLocator(locator)
    routing {
        get("/healthz") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText("ok", ContentType.Text.Plain)
        }
        PortfolioPage.entries.forEach { page ->
            get(page.path) {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.response.header(HttpHeaders.Vary, "Cookie, HX-Request, HX-History-Restore-Request")
                val requestedTheme = call.request.queryParameters["theme"]
                val theme = if (requestedTheme != null) {
                    Theme.from(requestedTheme) ?: run {
                        call.respondText("Unknown theme", status = HttpStatusCode.BadRequest)
                        return@get
                    }
                } else {
                    Theme.from(call.request.cookies["theme"]) ?: Theme.Dark
                }
                val tabs = WorkspaceTabs.fromCookie(call.request.cookies["workspace_tabs"]).open(page)
                if (requestedTheme != null) {
                    call.response.cookies.append(
                        Cookie(
                            name = "theme", value = theme.value, path = "/", maxAge = 31_536_000,
                            httpOnly = true, secure = secureCookies, extensions = mapOf("SameSite" to "Lax"),
                        ),
                    )
                }
                if (call.request.cookies["workspace_tabs"] != tabs.cookieValue) call.saveTabs(tabs, secureCookies)
                // History cache misses require a complete document, even when HX-Request is set.
                val fragment = call.request.headers["HX-Request"] == "true" &&
                    call.request.headers["HX-History-Restore-Request"] != "true"
                if (fragment) {
                    call.respondHtmlPartial { portfolio(theme, page, neighborNames(), tabs) }
                } else {
                    call.respondHtml { document(theme, page, neighborNames(), tabs) }
                }
            }
        }
        post("/tabs/close") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val origin = call.request.headers[HttpHeaders.Origin]
            val sameOrigin = origin == null || runCatching {
                val uri = URI(origin)
                uri.scheme in listOf("http", "https") && uri.rawAuthority.equals(call.request.headers[HttpHeaders.Host], ignoreCase = true)
            }.getOrDefault(false)
            if (!sameOrigin || call.request.headers["Sec-Fetch-Site"] == "cross-site") {
                call.respondText("Cross-site request rejected", status = HttpStatusCode.Forbidden)
                return@post
            }
            val parameters = call.receiveParameters()
            val closing = parameters["tab"]?.let(PortfolioPage::fromTabKey)
            val current = parameters["current"]?.let(PortfolioPage::fromTabKey)
            if (closing == null || current == null) {
                call.respondText("Unknown tab", status = HttpStatusCode.BadRequest)
                return@post
            }
            val result = WorkspaceTabs.fromCookie(call.request.cookies["workspace_tabs"]).open(current).close(closing, current)
            call.saveTabs(result.tabs, secureCookies)
            call.response.header(HttpHeaders.Location, result.page.path)
            call.respond(HttpStatusCode.SeeOther)
        }
        get("/index.html") { call.respondRedirect("/", permanent = true) }
        staticResources("/assets", "static") {
            cacheControl { listOf(CacheControl.MaxAge(maxAgeSeconds = 3600)) }
        }
    }
}

private fun ApplicationCall.saveTabs(tabs: WorkspaceTabs, secureCookies: Boolean) {
    response.cookies.append(
        Cookie("workspace_tabs", tabs.cookieValue, path = "/", httpOnly = true, secure = secureCookies, extensions = mapOf("SameSite" to "Lax")),
    )
}
