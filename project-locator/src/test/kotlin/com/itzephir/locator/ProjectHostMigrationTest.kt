package com.itzephir.locator

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

class ProjectHostMigrationTest : LocatorDatabaseTest() {
    private fun beforeMigration() {
        source.connection.use { it.createStatement().use { query ->
            query.execute("DELETE FROM locator_schema_migrations WHERE version = 5")
        } }
    }

    @Test fun `v4 projects migrate to the matching stand without changing identities review or readiness`() = testApplication {
        val admin = locator.authenticate(TEST_ADMIN)!!
        val registration = locator.register(ProjectRequest("minor-website", "minor-web", description = "Existing project"), admin)
        val id = UUID.fromString(registration.project.id)
        val ownerId = UUID.randomUUID()
        source.connection.use { connection ->
            connection.prepareStatement("INSERT INTO locator_identities (id, role) VALUES (?, 'owner')").use {
                it.setObject(1, ownerId); it.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO locator_users (id, username, github_id, role) VALUES (?, 'owner', 123, 'owner')").use {
                it.setObject(1, ownerId); it.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO locator_project_owners (project_id, user_id) VALUES (?, ?)").use {
                it.setObject(1, id); it.setObject(2, ownerId); it.executeUpdate()
            }
        }
        val owner = Identity(ownerId.toString(), IdentityRole.OWNER)
        application { projectLocator(locator) }
        for ((environment, port) in listOf("dev" to 18101, "prod" to 18100)) {
            // Reconstruct the same v4 snapshot in this test's isolated schema.
            source.connection.use { it.createStatement().use { query ->
                query.execute("UPDATE locator_projects SET routing_mode = 'legacy', routing_target = '', revision = 1 WHERE id = '$id'")
            } }
            beforeMigration()
            val before = locator.get(id, admin)
            val upgraded = PostgresLocator(source)
            upgraded.initialize(TEST_ADMIN, environment)
            val after = upgraded.get(id, admin)
            val target = "http://127.0.0.1:$port/dev/project/minor-web"
            assertEquals(before.copy(routing = ProjectRouting(RoutingMode.PROXY, target), revision = 2), after)
            assertEquals(registration.identity, upgraded.authenticate(registration.token))
            assertEquals(after, upgraded.get(id, owner))
            assertEquals(after, upgraded.resolve("minor-web"))
            val host = if (environment == "dev") "minor-web.dev.itzephir.com" else "minor-web.itzephir.com"
            val resolved = client.get("/api/project-locator/v1/gateway/minor-web") {
                header("X-Project-Host", host); header("X-Original-URI", "/docs?q=a%2Fb")
            }
            assertEquals(HttpStatusCode.NoContent, resolved.status)
            assertEquals(target, resolved.headers["X-Project-Upstream"])
            assertEquals(host, resolved.headers["X-Project-Upstream-Host"])
            assertEquals("/dev/project/minor-web", resolved.headers["X-Project-Base-Path"])
            assertEquals(port.toString(), resolved.headers["X-Project-Path-Gateway-Port"])
            assertEquals("/docs?q=a%2Fb", resolved.headers["X-Project-URI"])
            upgraded.initialize(TEST_ADMIN, environment)
            assertEquals(after, upgraded.get(id, admin))
        }
        // A later application-owned target change is never overwritten on restart.
        val current = locator.get(id, admin)
        val changed = locator.update(id, ProjectUpdate("minor-website", "minor-web", current.revision,
            ProjectRouting(RoutingMode.DOCKER, "minor-router:8080")), admin)
        PostgresLocator(source).initialize(TEST_ADMIN, "prod")
        assertEquals(changed, locator.get(id, admin))
    }

    @Test fun `migration preserves configured projects and planning rejected revoked states`() = runBlocking {
        val admin = locator.authenticate(TEST_ADMIN)!!
        val anyq = locator.register(ProjectRequest("anyq", "anyq", ProjectRouting(RoutingMode.DOCKER, "anyq-site:8080"), readiness = ProjectReadiness.PLANNING), admin)
        val money = locator.register(ProjectRequest("itzmoney", "itzmoney", ProjectRouting(RoutingMode.REDIRECT, "https://example.org/money"), readiness = ProjectReadiness.PLANNING), admin)
        val minor = locator.register(ProjectRequest("minor-website", "minor-web", readiness = ProjectReadiness.PLANNING), null)
        val id = UUID.fromString(minor.project.id)
        val reviewed = locator.review(id, ProjectStatus.REJECTED, admin)
        locator.revoke(id, admin)
        beforeMigration()
        val upgraded = PostgresLocator(source)
        upgraded.initialize(TEST_ADMIN, "dev")
        assertEquals(reviewed.copy(routing = ProjectRouting(RoutingMode.PROXY, "http://127.0.0.1:18101/dev/project/minor-web"), revision = reviewed.revision + 1), upgraded.get(id, admin))
        assertNull(upgraded.authenticate(minor.token))
        assertNull(upgraded.resolve("minor-web"))
        assertEquals(anyq.project, upgraded.get(UUID.fromString(anyq.project.id), admin))
        assertEquals(money.project, upgraded.get(UUID.fromString(money.project.id), admin))
    }

    @Test fun `migrating a pending record does not approve or launch it`() = runBlocking {
        val registration = locator.register(ProjectRequest("minor-website", "minor-web", readiness = ProjectReadiness.PLANNING), null)
        val admin = locator.authenticate(TEST_ADMIN)!!
        beforeMigration()
        PostgresLocator(source).initialize(TEST_ADMIN, "prod")
        val after = locator.get(UUID.fromString(registration.project.id), admin)
        assertEquals(registration.project.copy(routing = ProjectRouting(RoutingMode.PROXY,
            "http://127.0.0.1:18100/dev/project/minor-web"), revision = 1), after)
        assertNull(locator.resolve("minor-web"))
        assertEquals(registration.identity, locator.authenticate(registration.token))
    }

    @Test fun `migration never replaces an explicitly configured minor target or guesses an unknown environment`() = runBlocking {
        val admin = locator.authenticate(TEST_ADMIN)!!
        val minor = locator.register(ProjectRequest("minor-website", "minor-web", ProjectRouting(RoutingMode.PROXY, "http://127.0.0.1:19000")), admin)
        val id = UUID.fromString(minor.project.id)
        beforeMigration()
        PostgresLocator(source).initialize(TEST_ADMIN, "prod")
        assertEquals(minor.project, locator.get(id, admin))
        locator.update(id, ProjectUpdate("minor-website", "minor-web", 0, ProjectRouting()), admin)
        val before = locator.get(id, admin)
        beforeMigration()
        assertFailsWith<java.sql.SQLException> { PostgresLocator(source).initialize(TEST_ADMIN) }
        assertEquals(before, locator.get(id, admin))
        source.connection.use { it.createStatement().use { query ->
            query.executeQuery("SELECT count(*) FROM locator_schema_migrations WHERE version = 5").use { rows ->
                rows.next(); assertEquals(0, rows.getInt(1))
            }
        } }
        PostgresLocator(source).initialize(TEST_ADMIN, "dev")
        assertEquals("http://127.0.0.1:18101/dev/project/minor-web", locator.get(id, admin).routing.target)
    }
}
