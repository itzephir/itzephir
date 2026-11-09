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
    val slug = project?.slug
    val stage = host == "dev.itzephir.com" || host?.endsWith(".dev.itzephir.com") == true
    val domain = if (stage) "dev.itzephir.com" else "itzephir.com"
    // One optional project-owned label; never infer a slug from the leftmost label.
    val validHost = host != null && host.length <= 253 &&
        Regex("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)?${Regex.escape("$slug.$domain")}").matches(host)
    val original = request.headers["X-Original-URI"].orEmpty()
    if (project == null || !project.readiness.canOpen || !project.routing.hasSubdomain(project.slug) || !validHost || !original.startsWith('/') || original.startsWith("//") ||
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
        else -> {
            val upstream = routing.upstream()
            val uri = URI(upstream)
            val pathGateway = uri.scheme == "http" && uri.host == "127.0.0.1" &&
                uri.port in listOf(18100, 18101) && uri.rawPath == "/dev/project/${project.slug}"
            response.header("X-Project-Upstream", upstream)
            response.header("X-Project-URI", original)
            response.header("X-Project-Base-Path", uri.rawPath.orEmpty().trimEnd('/'))
            if (pathGateway) response.header("X-Project-Path-Gateway-Port", uri.port.toString())
            // Docker targets are project routers. External proxies may require their own virtual host.
            response.header("X-Project-Upstream-Host", if (routing.mode == RoutingMode.DOCKER || pathGateway) host else uri.rawAuthority)
            response.header("X-Project-TLS-Name", uri.host)
            respond(HttpStatusCode.NoContent)
        }
    }
}
