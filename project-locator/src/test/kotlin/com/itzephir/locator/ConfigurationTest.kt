package com.itzephir.locator

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ConfigurationTest {
    @Test fun `no configuration leaves optional feature disabled`() { assertNull(locatorFromEnvironment(emptyMap())) }
    @Test fun `partial configuration fails closed`() {
        assertFailsWith<IllegalArgumentException> { locatorFromEnvironment(mapOf("LOCATOR_DATABASE_URL" to "jdbc:postgresql://127.0.0.1/database")) }
    }
}
