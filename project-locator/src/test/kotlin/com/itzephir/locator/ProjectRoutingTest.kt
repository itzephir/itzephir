package com.itzephir.locator

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProjectRoutingTest {
    @Test fun `routing accepts container ports and HTTP targets but rejects ambiguous or unsafe addresses`() {
        ProjectRouting(RoutingMode.DOCKER, "minor-web:8080").validate("minor-web")
        ProjectRouting(RoutingMode.PROXY, "https://example.org/base/").validate("minor-web")
        ProjectRouting(RoutingMode.PROXY, "http://127.0.0.1:8080").validate("minor-web")
        ProjectRouting(RoutingMode.REDIRECT, "https://example.org/page?q=1#about").validate("minor_web")
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
        listOf("under_score", "trailing-", "dev", "www", "a".repeat(64)).forEach {
            assertFailsWith<LocatorException>(it) { ProjectRouting(RoutingMode.DOCKER, "app:80").validate(it) }
        }
        assertEquals("http://app:80", ProjectRouting(RoutingMode.DOCKER, "app:80").upstream())
    }
}
