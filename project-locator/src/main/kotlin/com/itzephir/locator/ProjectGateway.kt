package com.itzephir.locator

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import java.net.URI

/** nginx auth_request: 204 permits proxying; 403 either redirects or fails closed to 404. */
internal suspend fun ApplicationCall.gateway(project: Project?) {
    response.header(HttpHeaders.CacheControl, "no-store")
    val host = request.headers["X-Project-Host"]
    val entry = request.headers["X-Project-Entry"] == "true"
    val slug = project?.slug
    val stage = host == "dev.itzephir.com" || host == "$slug.dev.itzephir.com"
    val expectedHost = if (entry) {
        if (stage) "dev.itzephir.com" else "itzephir.com"
    } else {
        if (stage) "$slug.dev.itzephir.com" else "$slug.itzephir.com"
    }
    val original = request.headers["X-Original-URI"].orEmpty()
    if (project == null || host != expectedHost || !original.startsWith('/') || original.startsWith("//") ||
        original.length > 8192 || original.any { it.code < 32 || it.code == 127 } || '#' in original || '\\' in original) {
        respond(HttpStatusCode.Forbidden)
        return
    }
    val routing = project.routing
    when {
        routing.mode == RoutingMode.REDIRECT -> {
            // An external destination is exact: do not leak the source query or append arbitrary paths.
            response.header(HttpHeaders.Location, routing.target)
            respond(HttpStatusCode.Forbidden)
        }
        entry && routing.mode != RoutingMode.LEGACY -> {
            val prefix = Regex("^/dev/projects?/${Regex.escape(project.slug)}(?=/|\\?|$)")
            if (!prefix.containsMatchIn(original)) {
                respond(HttpStatusCode.Forbidden)
                return
            }
            val suffix = original.replaceFirst(prefix, "").let { if (it.startsWith('/')) it else "/$it" }
            response.header(HttpHeaders.Location, (if (stage) project.stageAppUrl else project.productionAppUrl).trimEnd('/') + suffix)
            respond(HttpStatusCode.Forbidden)
        }
        routing.mode == RoutingMode.LEGACY -> {
            if (!entry) {
                respond(HttpStatusCode.Forbidden)
                return
            }
            val originalPath = original.replaceFirst("/dev/projects/", "/dev/project/")
            val intercept = stage && request.headers["X-Project-Upgrade"].isNullOrEmpty()
            response.header("X-Project-Upstream", "http://127.0.0.1:${if (intercept) 18081 else if (stage) 18101 else 18100}")
            response.header("X-Project-URI", if (intercept) originalPath.replaceFirst("/dev/project/", "/__staging-project/") else originalPath)
            response.header("X-Project-Upstream-Host", host)
            respond(HttpStatusCode.NoContent)
        }
        else -> {
            val upstream = routing.upstream()
            val uri = URI(upstream)
            response.header("X-Project-Upstream", upstream)
            response.header("X-Project-URI", original)
            response.header("X-Project-Upstream-Host", uri.rawAuthority)
            response.header("X-Project-TLS-Name", uri.host)
            respond(HttpStatusCode.NoContent)
        }
    }
}
