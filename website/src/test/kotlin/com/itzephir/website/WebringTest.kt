package com.itzephir.website

import java.util.concurrent.CompletableFuture
import kotlin.test.*

class WebringTest {
    @Test
    fun `refresh never blocks a page and an outage retains the last known neighbors`() {
        var time = 1_000L
        var calls = 0
        var request = CompletableFuture<WebringNeighbors>()
        val directory = WebringDirectory(fetch = { calls++; request }, now = { time })
        assertEquals(WebringNeighbors(), directory.neighbors())
        repeat(10) { directory.neighbors() }
        assertEquals(1, calls)
        request.complete(WebringNeighbors("previous", "next"))
        assertEquals(WebringNeighbors("previous", "next"), directory.neighbors())
        time += 300_001
        request = CompletableFuture()
        assertEquals(WebringNeighbors("previous", "next"), directory.neighbors())
        request.completeExceptionally(IllegalStateException("offline"))
        repeat(10) { assertEquals(WebringNeighbors("previous", "next"), directory.neighbors()) }
        assertEquals(2, calls)
        time += 60_001
        directory.neighbors()
        assertEquals(3, calls)
    }

    @Test
    fun `only the registered member response supplies complete valid names`() {
        val json = """{"prev":{"name":"CMD/OPT/S"},"curr":{"slug":"itzephir"},"next":{"name":"badinteger"}}"""
        assertEquals(WebringNeighbors(), parseWebringNeighbors(json))
        assertFails { parseWebringNeighbors(json.replace("itzephir", "another-member")) }
        assertFails { parseWebringNeighbors(json.replace("CMD/OPT/S", "")) }
        assertFails { parseWebringNeighbors("not json") }
    }
}
