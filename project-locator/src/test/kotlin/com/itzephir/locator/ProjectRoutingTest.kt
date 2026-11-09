package com.itzephir.locator

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProjectRoutingTest {
    @Test fun `old redirect slugs without a valid DNS label never produce a broken launch URL`() {
        val project = Project("id", "old", "under_score", "identity", ProjectStatus.APPROVED, "",
            routing = ProjectRouting(RoutingMode.REDIRECT, "https://example.org"))
        assertNull(project.productionUrl)
        assertNull(project.stageUrl)
    }

    @Test fun `routing accepts container ports and HTTP targets but rejects ambiguous or unsafe addresses`() {
        ProjectRouting(RoutingMode.DOCKER, "minor-web:8080").validate("minor-web")
        ProjectRouting(RoutingMode.PROXY, "https://example.org/base/").validate("minor-web")
        ProjectRouting(RoutingMode.PROXY, "http://127.0.0.1:8080").validate("minor-web")
        ProjectRouting(RoutingMode.REDIRECT, "https://example.org/page?q=1#about").validate("minor-web")
        ProjectRouting().validate("legacy_slug")
        listOf("app", "app:0", "app:65536", "app:abc", "http://app:80", "app:80/path", "app:80\n").forEach {
            assertFailsWith<LocatorException>(it) { ProjectRouting(RoutingMode.DOCKER, it).validate("safe") }
        }
        listOf("//example.org", "javascript:alert(1)", "ftp://example.org", "https://user:password@example.org", "https://example.org:0", "https://example.org:65536", "https://example.org:", "https://example.org/\n").forEach {
            assertFailsWith<LocatorException>(it) { ProjectRouting(RoutingMode.REDIRECT, it).validate("safe") }
        }
        listOf("https://itzephir.com", "https://dev.itzephir.com", "https://app.itzephir.com./", "https://example.org/?q=1", "https://example.org/#x").forEach {
            assertFailsWith<LocatorException>(it) { ProjectRouting(RoutingMode.PROXY, it).validate("safe") }
        }
        listOf("under_score", "trailing-", "dev", "www", "opendesign", "a".repeat(64)).forEach {
            for (mode in listOf(RoutingMode.DOCKER, RoutingMode.PROXY, RoutingMode.REDIRECT)) {
                val target = if (mode == RoutingMode.DOCKER) "app:80" else "https://example.org"
                assertFailsWith<LocatorException>(it) { ProjectRouting(mode, target).validate(it) }
            }
        }
        assertEquals("http://app:80", ProjectRouting(RoutingMode.DOCKER, "app:80").upstream())
    }

    @Test fun `public projects cannot claim the private design workspace hostname`() {
        assertFailsWith<LocatorException> {
            ProjectRouting(RoutingMode.PROXY, "https://example.org").validate("opendesign")
        }
    }
}
