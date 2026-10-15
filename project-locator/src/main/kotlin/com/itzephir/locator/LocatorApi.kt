package com.itzephir.locator

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.patch
import io.ktor.server.request.path
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

fun Application.projectLocator(locator: PostgresLocator, secureCookies: Boolean = true, panelAuth: PanelAuth? = null) {
    install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
    install(StatusPages) {
        exception<LocatorException> { call, error ->
            call.response.header(HttpHeaders.CacheControl, "no-store")
            if (call.request.path().startsWith("/dev/panel")) {
                call.panelError(error)
                return@exception
            }
            if (error.status == 401) call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"project-locator\"")
            call.respond(HttpStatusCode.fromValue(error.status), ApiError(error.code, error.message))
        }
        exception<BadRequestException> { call, _ ->
            call.response.header(HttpHeaders.CacheControl, "no-store")
            if (call.request.path().startsWith("/dev/panel")) {
                call.panelError(LocatorException(400, "invalid_request", "Invalid request"))
                return@exception
            }
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request", "Expected a valid JSON request"))
        }
    }
    locatorPanel(locator, secureCookies, panelAuth)
    routing {
        route("/api/project-locator/v1") {
            install(createRouteScopedPlugin("LocatorResponseHeaders") {
                onCall { call -> call.response.header(HttpHeaders.CacheControl, "no-store") }
            })
            post("/projects") {
                val actor = call.identity(locator, optional = true)
                val request = call.receive<ProjectRequest>()
                val registration = locator.register(request, actor)
                call.response.header(HttpHeaders.Location, "/api/project-locator/v1/projects/${registration.project.id}")
                call.respond(HttpStatusCode.Created, registration)
            }
            get("/identity") { call.respond(call.identity(locator)!!) }
            get("/projects") {
                val actor = call.identity(locator)!!
                val status = call.request.queryParameters["status"]?.let { value ->
                    ProjectStatus.entries.firstOrNull { it.databaseValue == value }
                        ?: throw LocatorException(400, "invalid_status", "Expected pending, approved or rejected")
                }
                val limit = call.integerQuery("limit", 50, 1..100)
                val offset = call.integerQuery("offset", 0, 0..1_000_000)
                call.respond(locator.list(actor, status, limit, offset))
            }
            get("/projects/{id}") {
                val actor = call.identity(locator)!!
                call.respond(locator.get(parseId(call.parameters["id"]!!), actor))
            }
            patch("/projects/{id}") {
                val actor = call.identity(locator)!!
                val request = call.receive<ProjectUpdate>()
                call.respond(locator.update(parseId(call.parameters["id"]!!), request, actor))
            }
            post("/projects/{id}/approve") {
                val actor = call.identity(locator)!!
                call.respond(locator.review(parseId(call.parameters["id"]!!), ProjectStatus.APPROVED, actor))
            }
            post("/projects/{id}/reject") {
                val actor = call.identity(locator)!!
                call.respond(locator.review(parseId(call.parameters["id"]!!), ProjectStatus.REJECTED, actor))
            }
            post("/projects/{id}/identity/rotate") {
                val actor = call.identity(locator)!!
                call.respond(locator.rotate(parseId(call.parameters["id"]!!), actor))
            }
            delete("/projects/{id}/identity") {
                val actor = call.identity(locator)!!
                locator.revoke(parseId(call.parameters["id"]!!), actor)
                call.respond(HttpStatusCode.NoContent)
            }
            get("/resolve/{slug}") {
                val project = locator.resolve(call.parameters["slug"]!!)
                    ?: throw LocatorException(404, "project_not_found", "Approved project not found")
                call.respond(ResolvedProject(project, project.productionUrl, project.stageUrl))
            }
        }
    }
}

private suspend fun ApplicationCall.identity(locator: PostgresLocator, optional: Boolean = false): Identity? {
    val header = request.headers[HttpHeaders.Authorization]
    if (header == null && optional) return null
    val token = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)
    return token?.let { locator.authenticate(it) }
        ?: throw LocatorException(401, "invalid_token", "A valid bearer token is required")
}

private fun ApplicationCall.integerQuery(name: String, default: Int, range: IntRange): Int {
    val value = request.queryParameters[name] ?: return default
    return value.toIntOrNull()?.takeIf { it in range }
        ?: throw LocatorException(400, "invalid_pagination", "Invalid $name")
}
