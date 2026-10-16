package com.itzephir.locator

import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.postgresql.ds.PGSimpleDataSource

internal const val TEST_ADMIN = "local-test-admin-token-not-used-in-any-deployment-0001"

abstract class LocatorDatabaseTest {
    protected lateinit var source: PGSimpleDataSource
    protected lateinit var locator: PostgresLocator
    protected lateinit var schema: String

    @BeforeEach
    fun setup() {
        val url = System.getenv("LOCATOR_TEST_DATABASE_URL")
        if (System.getenv("LOCATOR_REQUIRE_DATABASE_TESTS") == "true") requireNotNull(url) { "CI must provide PostgreSQL" }
        assumeTrue(url != null, "Set LOCATOR_TEST_DATABASE_URL to run PostgreSQL integration tests")
        schema = "locator_test_" + UUID.randomUUID().toString().replace("-", "")
        source = PGSimpleDataSource().apply {
            setURL(url!!)
            user = System.getenv("LOCATOR_TEST_DATABASE_USER") ?: "locator"
            password = System.getenv("LOCATOR_TEST_DATABASE_PASSWORD") ?: ""
            connectTimeout = 5
            socketTimeout = 10
        }
        source.connection.use { it.createStatement().use { statement -> statement.execute("CREATE SCHEMA $schema") } }
        source.currentSchema = schema
        locator = PostgresLocator(source)
        runBlocking { locator.initialize(TEST_ADMIN) }
    }

    @AfterEach
    fun cleanup() {
        if (::source.isInitialized && ::schema.isInitialized) {
            source.connection.use { it.createStatement().use { statement -> statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
        }
    }

}
