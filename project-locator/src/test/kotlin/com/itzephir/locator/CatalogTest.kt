package com.itzephir.locator

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import kotlin.test.*

class CatalogTest : LocatorDatabaseTest() {
    @Test fun `catalog migration preserves approved v3 routes credentials and revisions`() = runBlocking {
        val admin = locator.authenticate(TEST_ADMIN)!!
        val registration = locator.register(ProjectRequest("existing", "existing", ProjectRouting(RoutingMode.REDIRECT, "https://example.org/")), admin)
        val id = UUID.fromString(registration.project.id)
        val before = locator.update(id, ProjectUpdate("existing-renamed", "existing", 0), admin)
        // Restore the v3 table shape in this test's isolated schema, then run the new migration.
        source.connection.use { connection -> connection.createStatement().use {
            it.execute("ALTER TABLE locator_projects DROP COLUMN description, DROP COLUMN readiness")
            it.execute("DELETE FROM locator_schema_migrations WHERE version = 4")
        } }
        val upgraded = PostgresLocator(source)
        upgraded.initialize(TEST_ADMIN)
        assertEquals(registration.identity, upgraded.authenticate(registration.token))
        val after = upgraded.get(id, admin)
        assertEquals(before, after)
        assertEquals(ProjectStatus.APPROVED, after.status)
        assertTrue(after.readiness.canOpen)
    }

    @Test fun `approved planning projects are listed without launch links and cannot route to a saved target`() = testApplication {
        val admin = locator.authenticate(TEST_ADMIN)!!
        val request = ProjectRequest("itzmoney", "itzmoney", ProjectRouting(RoutingMode.REDIRECT, "https://money.itzephir.com/"),
            "Личный и семейный бюджет <script>example</script>", ProjectReadiness.PLANNING)
        val registration = locator.register(request, admin)
        val id = UUID.fromString(registration.project.id)
        application { projectLocator(locator) }
        val card = Jsoup.parse(client.get("/dev").bodyAsText()).selectFirst(".project-card")!!
        assertEquals(request.description, card.selectFirst(".project-description")!!.text())
        assertTrue(card.text().contains("Проектирование"))
        assertTrue(card.select("a, script").isEmpty())
        // Resolution describes an approved record, not an available application.
        assertEquals(HttpStatusCode.OK, client.get("/api/project-locator/v1/resolve/itzmoney").status)
        for (mode in RoutingMode.entries) {
            val current = locator.get(id, admin)
            val target = when (mode) {
                RoutingMode.LEGACY -> ""
                RoutingMode.DOCKER -> "app:8080"
                RoutingMode.PROXY -> "https://example.org"
                RoutingMode.REDIRECT -> "https://money.itzephir.com/"
            }
            locator.update(id, ProjectUpdate("itzmoney", "itzmoney", current.revision, ProjectRouting(mode, target)), admin)
            for (host in listOf("itzephir.com", "dev.itzephir.com", "itzmoney.itzephir.com", "itzmoney.dev.itzephir.com")) {
                val entry = host == "itzephir.com" || host == "dev.itzephir.com"
                val response = client.get("/api/project-locator/v1/gateway/itzmoney") {
                    header("X-Project-Host", host); header("X-Project-Entry", entry.toString())
                    header("X-Original-URI", if (entry) "/dev/project/itzmoney/" else "/")
                }
                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertNull(response.headers[HttpHeaders.Location])
                assertNull(response.headers["X-Project-Upstream"])
            }
        }
        val current = locator.get(id, admin)
        locator.update(id, ProjectUpdate("itzmoney", "itzmoney", current.revision, request.routing, readiness = ProjectReadiness.DEVELOPMENT), admin)
        assertEquals(1, Jsoup.parse(client.get("/dev").bodyAsText()).select("a[href='/dev/project/itzmoney/']").size)
        val launched = client.get("/api/project-locator/v1/gateway/itzmoney") {
            header("X-Project-Host", "itzephir.com"); header("X-Project-Entry", "true"); header("X-Original-URI", "/dev/project/itzmoney/")
        }
        assertEquals(request.routing.target, launched.headers[HttpHeaders.Location])
    }

    @Test fun `catalog metadata persists through API updates and older clients preserve omitted fields`() = testApplication {
        application { projectLocator(locator) }
        val response = client.post("/api/project-locator/v1/projects") {
            header(HttpHeaders.Authorization, "Bearer $TEST_ADMIN"); contentType(ContentType.Application.Json)
            setBody("""{"project":"planning-app","slug":"planning-app","description":"Бюджет","readiness":"planning"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val registration = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val project = registration.getValue("project").jsonObject
        val id = project.getValue("id").jsonPrimitive.content
        val saved = locator.get(UUID.fromString(id), locator.authenticate(TEST_ADMIN)!!)
        assertEquals("Бюджет", saved.description)
        assertEquals(ProjectReadiness.PLANNING, saved.readiness)
        val unchanged = client.patch("/api/project-locator/v1/projects/$id") {
            header(HttpHeaders.Authorization, "Bearer $TEST_ADMIN"); contentType(ContentType.Application.Json)
            setBody("""{"project":"renamed","slug":"planning-app","revision":0}""")
        }
        assertEquals(HttpStatusCode.OK, unchanged.status)
        val current = locator.get(UUID.fromString(id), locator.authenticate(TEST_ADMIN)!!)
        assertEquals(saved.description, current.description); assertEquals(saved.readiness, current.readiness)
        val changed = client.patch("/api/project-locator/v1/projects/$id") {
            header(HttpHeaders.Authorization, "Bearer ${registration.getValue("token").jsonPrimitive.content}")
            contentType(ContentType.Application.Json)
            setBody("""{"project":"renamed","slug":"planning-app","revision":1,"description":"","readiness":"development"}""")
        }
        assertEquals(HttpStatusCode.OK, changed.status)
        val pending = locator.get(UUID.fromString(id), locator.authenticate(TEST_ADMIN)!!)
        assertEquals("", pending.description); assertEquals(ProjectReadiness.DEVELOPMENT, pending.readiness)
        assertEquals(ProjectStatus.PENDING, pending.status)
        assertNull(locator.resolve("planning-app"))
        assertFalse(client.get("/dev").bodyAsText().contains("renamed"))
        val restarted = PostgresLocator(source)
        restarted.initialize(TEST_ADMIN)
        assertEquals(pending, restarted.get(UUID.fromString(id), restarted.authenticate(TEST_ADMIN)!!))
    }

    @Test fun `description limits and readiness values are validated on registration and update`() = testApplication {
        application { projectLocator(locator) }
        for (metadata in listOf("\"description\":\"${"x".repeat(501)}\"", "\"readiness\":\"unknown\"")) {
            val rejected = client.post("/api/project-locator/v1/projects") {
                contentType(ContentType.Application.Json); setBody("""{"project":"invalid","slug":"invalid",$metadata}""")
            }
            assertEquals(HttpStatusCode.BadRequest, rejected.status)
        }
        val admin = locator.authenticate(TEST_ADMIN)!!
        val registered = locator.register(ProjectRequest("valid", "valid", description = "x".repeat(500)), admin).project
        for (metadata in listOf("\"description\":\"${"x".repeat(501)}\"", "\"readiness\":\"unknown\"")) {
            val rejected = client.patch("/api/project-locator/v1/projects/${registered.id}") {
                header(HttpHeaders.Authorization, "Bearer $TEST_ADMIN"); contentType(ContentType.Application.Json)
                setBody("""{"project":"valid","slug":"valid","revision":0,$metadata}""")
            }
            assertEquals(HttpStatusCode.BadRequest, rejected.status)
        }
        assertEquals(registered, locator.get(UUID.fromString(registered.id), admin))
    }
}
