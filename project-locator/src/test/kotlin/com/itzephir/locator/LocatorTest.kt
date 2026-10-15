package com.itzephir.locator

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ROOT = "/api/project-locator/v1"
private const val ADMIN = "local-test-admin-token-not-used-in-any-deployment-0001"

/** Real PostgreSQL, including constraints, transactions, persistence and concurrent requests. */
class LocatorTest {
    private lateinit var source: PGSimpleDataSource
    private lateinit var locator: PostgresLocator
    private lateinit var schema: String

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
        runBlocking { locator.initialize(ADMIN) }
    }

    @AfterEach
    fun cleanup() {
        if (::source.isInitialized && ::schema.isInitialized) {
            source.connection.use { it.createStatement().use { statement -> statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
        }
    }

    @Test
    fun `registration reserves project and slug but needs administrator approval`() = testApplication {
        application { projectLocator(locator) }
        val response = client.register()
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val registration = response.json()
        val project = registration.getValue("project").jsonObject
        val token = registration.string("token")
        val id = project.string("id")
        assertEquals("pending", project.string("status"))
        assertEquals("minor_web", project.string("slug"))
        assertEquals("project", registration.getValue("identity").jsonObject.string("role"))
        assertEquals(HttpStatusCode.NotFound, client.get("$ROOT/resolve/minor_web").status)
        val own = client.get("$ROOT/projects/$id") { bearer(token) }
        assertEquals(HttpStatusCode.OK, own.status)
        assertFalse(own.bodyAsText().contains(token))
        assertFalse(own.bodyAsText().contains("token_hash"))
        source.connection.use { connection ->
            connection.prepareStatement("SELECT token_hash FROM locator_identities WHERE id = ?").use {
                it.setObject(1, UUID.fromString(project.string("identityId")))
                it.executeQuery().use { row -> assertTrue(row.next()); assertEquals(Tokens.hash(token), row.getString(1)); assertNotEquals(token, row.getString(1)) }
            }
        }
    }

    @Test
    fun `admin can approve a request and create without confirmation`() = testApplication {
        application { projectLocator(locator) }
        val registration = client.register().json()
        val id = registration.getValue("project").jsonObject.string("id")
        val reviewed = client.post("$ROOT/projects/$id/approve") { bearer(ADMIN) }
        assertEquals(HttpStatusCode.OK, reviewed.status)
        val project = reviewed.json()
        assertEquals("approved", project.string("status"))
        assertEquals("00000000-0000-0000-0000-000000000001", project.string("reviewedBy"))
        assertTrue(project.string("reviewedAt").isNotBlank())
        assertEquals(project, client.post("$ROOT/projects/$id/approve") { bearer(ADMIN) }.json())
        val resolved = client.get("$ROOT/resolve/minor_web").json()
        assertEquals("https://itzephir.com/dev/project/minor_web/", resolved.string("productionUrl"))
        assertEquals("https://dev.itzephir.com/dev/project/minor_web/", resolved.string("stageUrl"))
        val created = client.register("second-service", "second_service", ADMIN)
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("approved", created.json().getValue("project").jsonObject.string("status"))
        assertEquals(HttpStatusCode.OK, client.get("$ROOT/resolve/second_service").status)
    }

    @Test
    fun `project identity cannot approve list or inspect another project`() = testApplication {
        application { projectLocator(locator) }
        val registration = client.register().json()
        val token = registration.string("token")
        val id = registration.getValue("project").jsonObject.string("id")
        val other = client.register("second", "second").json().getValue("project").jsonObject.string("id")
        assertEquals(HttpStatusCode.Forbidden, client.post("$ROOT/projects/$id/approve") { bearer(token) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("$ROOT/projects/$id/reject") { bearer(token) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("$ROOT/projects") { bearer(token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("$ROOT/projects/$other") { bearer(token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("$ROOT/projects/$other/identity/rotate") { bearer(token) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.register("third", "third", token).status)
    }

    @Test
    fun `missing and invalid credentials fail closed including on registration`() = testApplication {
        application { projectLocator(locator) }
        val missing = client.get("$ROOT/identity")
        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertTrue(missing.headers[HttpHeaders.WWWAuthenticate]!!.startsWith("Bearer"))
        assertEquals(HttpStatusCode.Unauthorized, client.get("$ROOT/identity") { bearer("invalid") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.register(token = "invalid").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("$ROOT/identity") { header(HttpHeaders.Authorization, "Basic nonsense") }.status)
    }

    @Test
    fun `conflicts roll back newly created identities`() = testApplication {
        application { projectLocator(locator) }
        client.register()
        assertEquals(HttpStatusCode.Conflict, client.register().status)
        assertEquals(HttpStatusCode.Conflict, client.register("other-name", "minor_web").status)
        assertEquals(HttpStatusCode.Conflict, client.register("minor-website", "other_slug").status)
        source.connection.use { connection ->
            connection.createStatement().use { it.executeQuery("SELECT count(*) FROM locator_identities").use { row -> row.next(); assertEquals(2, row.getInt(1)) } }
        }
    }

    @Test
    fun `rejected projects remain private and decisions cannot be overwritten`() = testApplication {
        application { projectLocator(locator) }
        val registration = client.register().json()
        val id = registration.getValue("project").jsonObject.string("id")
        val response = client.post("$ROOT/projects/$id/reject") { bearer(ADMIN) }
        assertEquals("rejected", response.json().string("status"))
        assertEquals(HttpStatusCode.NotFound, client.get("$ROOT/resolve/minor_web").status)
        assertEquals(HttpStatusCode.Conflict, client.post("$ROOT/projects/$id/approve") { bearer(ADMIN) }.status)
        assertEquals(HttpStatusCode.OK, client.get("$ROOT/projects/$id") { bearer(registration.string("token")) }.status)
    }

    @Test
    fun `rotation invalidates old token and revocation disables authentication and resolution`() = testApplication {
        application { projectLocator(locator) }
        val registration = client.register(token = ADMIN).json()
        val token = registration.string("token")
        val id = registration.getValue("project").jsonObject.string("id")
        val rotated = client.post("$ROOT/projects/$id/identity/rotate") { bearer(token) }
        assertEquals(HttpStatusCode.OK, rotated.status)
        val next = rotated.json().string("token")
        assertNotEquals(token, next)
        assertEquals(HttpStatusCode.Unauthorized, client.get("$ROOT/identity") { bearer(token) }.status)
        assertEquals(HttpStatusCode.OK, client.get("$ROOT/identity") { bearer(next) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("$ROOT/projects/$id/identity") { bearer(next) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("$ROOT/projects/$id/identity") { bearer(ADMIN) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("$ROOT/identity") { bearer(next) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("$ROOT/resolve/minor_web").status)
        val restored = client.post("$ROOT/projects/$id/identity/rotate") { bearer(ADMIN) }.json().string("token")
        assertEquals(HttpStatusCode.OK, client.get("$ROOT/identity") { bearer(restored) }.status)
    }

    @Test
    fun `input cannot smuggle privileges or unsafe slugs`() = testApplication {
        application { projectLocator(locator) }
        listOf("../admin", "a/b", "hello world", "UPPER", "", "a".repeat(65)).forEach { slug ->
            assertEquals(HttpStatusCode.BadRequest, client.register(slug = slug).status, slug)
        }
        val escalated = client.post("$ROOT/projects") { contentType(ContentType.Application.Json); setBody("""{"project":"safe","slug":"safe","role":"admin","status":"approved"}""") }
        assertEquals(HttpStatusCode.BadRequest, escalated.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$ROOT/projects?status=unknown") { bearer(ADMIN) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$ROOT/projects?limit=99999") { bearer(ADMIN) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$ROOT/projects/not-a-uuid") { bearer(ADMIN) }.status)
    }

    @Test
    fun `admin can filter pending requests without receiving credentials`() = testApplication {
        application { projectLocator(locator) }
        val pending = client.register().json()
        client.register("approved", "approved", ADMIN)
        val response = client.get("$ROOT/projects?status=pending&limit=1&offset=0") { bearer(ADMIN) }
        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        val list = Json.parseToJsonElement(text).jsonArray
        assertEquals(1, list.size)
        assertEquals("minor-website", list.single().jsonObject.string("project"))
        assertFalse(text.contains(pending.string("token")))
    }

    @Test
    fun `data survives reinitialization and admin credential rotates without changing identity`() = runBlocking {
        val registration = locator.register(ProjectRequest("minor-website", "minor_web"), null)
        val another = PostgresLocator(source)
        val newAdmin = "new-local-admin-token-only-for-this-test-00000000002"
        another.initialize(newAdmin)
        assertNull(another.authenticate(ADMIN))
        val admin = assertNotNull(another.authenticate(newAdmin))
        assertEquals(IdentityRole.ADMIN, admin.role)
        assertEquals(registration.project, another.get(UUID.fromString(registration.project.id), admin))
        assertEquals(registration.identity, another.authenticate(registration.token))
        another.initialize(newAdmin)
    }

    @Test
    fun `simultaneous requests cannot reserve the same slug or leave orphan identities`() = runBlocking {
        val results = (1..8).map { number -> async {
            runCatching { locator.register(ProjectRequest("project-$number", "one_slug"), null) }
        } }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        results.filter { it.isFailure }.forEach { assertEquals(409, (it.exceptionOrNull() as LocatorException).status) }
        source.connection.use { connection ->
            connection.createStatement().use { it.executeQuery("SELECT count(*) FROM locator_identities").use { row -> row.next(); assertEquals(2, row.getInt(1)) } }
        }
    }

    private suspend fun HttpClient.register(project: String = "minor-website", slug: String = "minor_web", token: String? = null): HttpResponse = post("$ROOT/projects") {
        contentType(ContentType.Application.Json)
        if (token != null) bearer(token)
        setBody("""{"project":"$project","slug":"$slug"}""")
    }
}

private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) { header(HttpHeaders.Authorization, "Bearer $token") }
private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
