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
    @Test fun `project labels select one namespace and Docker preserves the public host`() = testApplication {
        val admin = locator.authenticate(TEST_ADMIN)!!
        val registration = locator.register(ProjectRequest("anyq", "anyq", ProjectRouting(RoutingMode.DOCKER, "anyq-router:8080")), admin)
        locator.register(ProjectRequest("other", "other", ProjectRouting(RoutingMode.DOCKER, "other-router:8080")), admin)
        locator.register(ProjectRequest("planning", "planning", ProjectRouting(RoutingMode.DOCKER, "planning:80"), readiness = ProjectReadiness.PLANNING), admin)
        application { projectLocator(locator) }
        for (domain in listOf("itzephir.com", "dev.itzephir.com")) {
            for (prefix in listOf("", "dev.", "pr-42.", "other.")) {
                val host = "${prefix}anyq.$domain"
                val resolved = client.get("/api/project-locator/v1/gateway/anyq") {
                    header("X-Project-Host", host); header("X-Original-URI", "/socket/a%2Fb?x=1&x=2")
                    header("X-Project-Upgrade", "websocket")
                }
                assertEquals(HttpStatusCode.NoContent, resolved.status, host)
                assertEquals("http://anyq-router:8080", resolved.headers["X-Project-Upstream"])
                assertEquals(host, resolved.headers["X-Project-Upstream-Host"])
                assertEquals("anyq-router", resolved.headers["X-Project-TLS-Name"])
                assertEquals("/socket/a%2Fb?x=1&x=2", resolved.headers["X-Project-URI"])
            }
        }
        for (host in listOf("dev.other.itzephir.com", "anyq.other.itzephir.com", "anyq.itzephir.com.evil.org",
            "otheranyq.itzephir.com", "a.b.anyq.itzephir.com", "-bad.anyq.itzephir.com", "a_b.anyq.itzephir.com", "${"a".repeat(64)}.anyq.itzephir.com")) {
            val denied = client.get("/api/project-locator/v1/gateway/anyq") {
                header("X-Project-Host", host); header("X-Original-URI", "/")
            }
            assertEquals(HttpStatusCode.Forbidden, denied.status, host)
            assertNull(denied.headers["X-Project-Upstream"])
        }
        for (slug in listOf("planning", "missing")) {
            val denied = client.get("/api/project-locator/v1/gateway/$slug") {
                header("X-Project-Host", "pr-42.$slug.dev.itzephir.com"); header("X-Original-URI", "/")
            }
            assertEquals(HttpStatusCode.Forbidden, denied.status)
        }
        val id = UUID.fromString(registration.project.id)
        locator.update(id, ProjectUpdate("anyq", "anyq", 0, ProjectRouting(RoutingMode.DOCKER, "replacement:80")), registration.identity)
        for (revoked in listOf(false, true)) {
            if (revoked) {
                locator.review(id, ProjectStatus.APPROVED, admin, 1)
                locator.revoke(id, admin)
            }
            val denied = client.get("/api/project-locator/v1/gateway/anyq") {
                header("X-Project-Host", "pr-42.anyq.dev.itzephir.com"); header("X-Original-URI", "/")
            }
            assertEquals(HttpStatusCode.Forbidden, denied.status)
            assertNull(denied.headers["X-Project-Upstream"])
        }
    }

    @Test fun `namespace proxies retain the upstream virtual host and redirects remain exact`() = testApplication {
        val admin = locator.authenticate(TEST_ADMIN)!!
        locator.register(ProjectRequest("proxy", "proxy", ProjectRouting(RoutingMode.PROXY, "https://example.org:8443/base/")), admin)
        locator.register(ProjectRequest("external", "external", ProjectRouting(RoutingMode.REDIRECT, "https://example.org/exact?q=1")), admin)
        application { projectLocator(locator) }
        val proxy = client.get("/api/project-locator/v1/gateway/proxy") {
            header("X-Project-Host", "pr-42.proxy.dev.itzephir.com"); header("X-Original-URI", "/a?q=1")
        }
        assertEquals(HttpStatusCode.NoContent, proxy.status)
        assertEquals("example.org:8443", proxy.headers["X-Project-Upstream-Host"])
        assertEquals("example.org", proxy.headers["X-Project-TLS-Name"])
        assertEquals("https://example.org:8443/base", proxy.headers["X-Project-Upstream"])
        val redirect = client.get("/api/project-locator/v1/gateway/external") {
            header("X-Project-Host", "dev.external.itzephir.com"); header("X-Original-URI", "/private?secret=1")
        }
        assertEquals("https://example.org/exact?q=1", redirect.headers[HttpHeaders.Location])
    }

    @Test fun `retired path entry hosts are denied for every routing mode`() = testApplication {
        val admin = locator.authenticate(TEST_ADMIN)!!
        locator.register(ProjectRequest("app", "app", ProjectRouting(RoutingMode.DOCKER, "app:8080")), admin)
        application { projectLocator(locator) }
        for (host in listOf("itzephir.com", "dev.itzephir.com")) {
            val denied = client.get("/api/project-locator/v1/gateway/app") {
                header("X-Project-Host", host); header("X-Project-Entry", "true")
                header("X-Original-URI", "/dev/project/app/docs?q=1")
            }
            assertEquals(HttpStatusCode.Forbidden, denied.status)
            assertNull(denied.headers[HttpHeaders.Location])
            assertNull(denied.headers["X-Project-Upstream"])
        }
    }

    @Test fun `proxy decisions preserve upstream paths while legacy pending and revoked projects are denied`() = testApplication {
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
        for (host in listOf("dev.itzephir.com", "old_slug.dev.itzephir.com")) {
            val old = client.get("/api/project-locator/v1/gateway/old_slug") {
                header("X-Project-Host", host); header("X-Original-URI", "/")
            }
            assertEquals(HttpStatusCode.Forbidden, old.status)
            assertNull(old.headers["X-Project-Upstream"])
        }
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
            header("X-Project-Host", "external.itzephir.com"); header("X-Original-URI", "/?private=secret")
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
