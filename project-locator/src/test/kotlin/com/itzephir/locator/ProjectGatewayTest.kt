package com.itzephir.locator

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import org.junit.jupiter.api.Test
import kotlin.test.*

class ProjectGatewayTest : LocatorDatabaseTest() {
    @Test fun `entry points redirect to the matching stand preserving paths and query strings`() = testApplication {
        val admin = locator.authenticate(TEST_ADMIN)!!
        locator.register(ProjectRequest("app", "app", ProjectRouting(RoutingMode.DOCKER, "app:8080")), admin)
        application { projectLocator(locator) }
        for ((host, appHost) in listOf("itzephir.com" to "app.itzephir.com", "dev.itzephir.com" to "app.dev.itzephir.com")) {
            for (path in listOf("/dev/project/app", "/dev/projects/app", "/dev/project/app/")) {
                val result = client.get("/api/project-locator/v1/gateway/app") {
                    header("X-Project-Host", host); header("X-Project-Entry", "true"); header("X-Original-URI", path)
                }
                assertEquals(HttpStatusCode.Forbidden, result.status)
                assertEquals("https://$appHost/", result.headers[HttpHeaders.Location])
            }
            val nested = client.get("/api/project-locator/v1/gateway/app") {
                header("X-Project-Host", host); header("X-Project-Entry", "true"); header("X-Original-URI", "/dev/project/app/docs/a%20b?q=a%2Fb")
            }
            assertEquals("https://$appHost/docs/a%20b?q=a%2Fb", nested.headers[HttpHeaders.Location])
        }
    }

    @Test fun `proxy and legacy decisions preserve upstream path and hide pending or revoked projects`() = testApplication {
        val admin = locator.authenticate(TEST_ADMIN)!!
        val registration = locator.register(ProjectRequest("app", "app", ProjectRouting(RoutingMode.PROXY, "https://example.org/base/")), admin)
        locator.register(ProjectRequest("old", "old_slug"), admin)
        application { projectLocator(locator) }
        val resolved = client.get("/api/project-locator/v1/gateway/app") {
            header("X-Project-Host", "app.dev.itzephir.com"); header("X-Original-URI", "/assets/a.js?v=2")
        }
        assertEquals(HttpStatusCode.NoContent, resolved.status)
        assertEquals("https://example.org/base", resolved.headers["X-Project-Upstream"])
        assertEquals("/assets/a.js?v=2", resolved.headers["X-Project-URI"])
        assertEquals("example.org", resolved.headers["X-Project-Upstream-Host"])
        assertEquals("example.org", resolved.headers["X-Project-TLS-Name"])
        val old = client.get("/api/project-locator/v1/gateway/old_slug") {
            header("X-Project-Host", "dev.itzephir.com"); header("X-Project-Entry", "true"); header("X-Original-URI", "/dev/projects/old_slug/a")
        }
        assertEquals("http://127.0.0.1:18081", old.headers["X-Project-Upstream"])
        assertEquals("/__staging-project/old_slug/a", old.headers["X-Project-URI"])
        val upgrade = client.get("/api/project-locator/v1/gateway/old_slug") {
            header("X-Project-Host", "dev.itzephir.com"); header("X-Project-Entry", "true")
            header("X-Project-Upgrade", "websocket"); header("X-Original-URI", "/dev/project/old_slug/socket")
        }
        assertEquals("http://127.0.0.1:18101", upgrade.headers["X-Project-Upstream"])
        assertEquals("/dev/project/old_slug/socket", upgrade.headers["X-Project-URI"])
        val id = UUID.fromString(registration.project.id)
        val changed = locator.update(id, ProjectUpdate("app", "app", 0, ProjectRouting(RoutingMode.DOCKER, "new-app:80")), registration.identity)
        assertEquals(ProjectStatus.PENDING, changed.status)
        assertNull(locator.resolve("app"))
        val hidden = client.get("/api/project-locator/v1/gateway/app") {
            header("X-Project-Host", "app.itzephir.com"); header("X-Original-URI", "/")
        }
        assertEquals(HttpStatusCode.Forbidden, hidden.status)
        assertNull(hidden.headers["X-Project-Upstream"])
        locator.review(id, ProjectStatus.APPROVED, admin, changed.revision)
        assertEquals("new-app:80", locator.resolve("app")!!.routing.target)
        // Older metadata-only API clients must not erase routing settings.
        val current = locator.get(id, admin)
        val renamed = locator.update(id, ProjectUpdate("renamed", "app", current.revision), admin)
        assertEquals(current.routing, renamed.routing)
        locator.revoke(id, admin)
        assertNull(locator.resolve("app"))
    }

    @Test fun `external redirects are exact and unknown hosts or projects fail closed`() = testApplication {
        val admin = locator.authenticate(TEST_ADMIN)!!
        locator.register(ProjectRequest("external", "external", ProjectRouting(RoutingMode.REDIRECT, "https://example.org/page?q=1#about")), admin)
        application { projectLocator(locator) }
        val response = client.get("/api/project-locator/v1/gateway/external") {
            header("X-Project-Host", "itzephir.com"); header("X-Project-Entry", "true"); header("X-Original-URI", "/dev/project/external/?private=secret")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("https://example.org/page?q=1#about", response.headers[HttpHeaders.Location])
        for (slug in listOf("external", "missing")) {
            val denied = client.get("/api/project-locator/v1/gateway/$slug") {
                header("X-Project-Host", "attacker.example"); header("X-Original-URI", "/")
            }
            assertEquals(HttpStatusCode.Forbidden, denied.status)
            assertNull(denied.headers[HttpHeaders.Location])
        }
    }
}
