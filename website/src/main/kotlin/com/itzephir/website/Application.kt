package com.itzephir.website

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.html.respondHtml
import io.ktor.server.html.respondHtmlPartial
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("BIND_HOST") ?: "127.0.0.1"
    embeddedServer(CIO, host = host, port = port) { module() }.start(wait = true)
}

fun Application.module(secureCookies: Boolean = System.getenv("COOKIE_SECURE") == "true") {
    routing {
        get("/healthz") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText("ok", ContentType.Text.Plain)
        }
        get("/") {
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
            if (requestedTheme != null) {
                call.response.cookies.append(
                    Cookie(
                        name = "theme", value = theme.value, path = "/", maxAge = 31_536_000,
                        httpOnly = true, secure = secureCookies, extensions = mapOf("SameSite" to "Lax"),
                    ),
                )
            }
            // History cache misses require a complete document, even when HX-Request is set.
            val fragment = call.request.headers["HX-Request"] == "true" &&
                call.request.headers["HX-History-Restore-Request"] != "true"
            if (fragment) {
                call.respondHtmlPartial { portfolio(theme) }
            } else {
                call.respondHtml { document(theme) }
            }
        }
        get("/index.html") { call.respondRedirect("/", permanent = true) }
        staticResources("/assets", "static") {
            cacheControl { listOf(CacheControl.MaxAge(maxAgeSeconds = 3600)) }
        }
    }
}
