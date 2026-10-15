package com.itzephir.locator

import kotlinx.coroutines.runBlocking
import org.postgresql.ds.PGSimpleDataSource

/** No database configuration means the optional API is not mounted. Partial configuration fails closed. */
fun locatorFromEnvironment(environment: Map<String, String> = System.getenv()): PostgresLocator? {
    val names = listOf("LOCATOR_DATABASE_URL", "LOCATOR_DATABASE_USER", "LOCATOR_DATABASE_PASSWORD", "LOCATOR_ADMIN_TOKEN")
    if (names.none { environment[it] != null }) return null
    val values = names.associateWith { name -> requireNotNull(environment[name]?.takeIf(String::isNotBlank)) { "Missing $name" } }
    require(values.getValue("LOCATOR_DATABASE_URL").startsWith("jdbc:postgresql://")) { "Locator requires a PostgreSQL JDBC URL" }
    val source = PGSimpleDataSource().apply {
        setURL(values.getValue("LOCATOR_DATABASE_URL"))
        user = values.getValue("LOCATOR_DATABASE_USER")
        password = values.getValue("LOCATOR_DATABASE_PASSWORD")
        connectTimeout = 5
        socketTimeout = 10
        applicationName = "itzephir-project-locator"
    }
    return PostgresLocator(source).also { runBlocking { it.initialize(values.getValue("LOCATOR_ADMIN_TOKEN")) } }
}
