package com.itzephir.locator

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

private const val PANEL = "/dev/panel"
private const val PASSWORD = "test-password-only-strong-enough"
private const val JWT_KEY = "test-jwt-signing-secret-at-least-32-characters"

class WebPanelTest : LocatorDatabaseTest() {
    private lateinit var accounts: WebAccounts
    private lateinit var auth: PanelAuth
    private lateinit var admin: Identity
    private lateinit var owner: WebUser

    @BeforeEach fun accounts() = runBlocking {
        accounts = WebAccounts(locator)
        accounts.bootstrapAdmin("admin", PASSWORD, 1234)
        admin = locator.authenticate(TEST_ADMIN)!!
        owner = accounts.createOwner("owner", PASSWORD, admin)
        auth = PanelAuth(accounts, JWT_KEY)
    }

    @Test fun `login uses a secure JWT cookie and logout revokes the session`() = testApplication {
        application { projectLocator(locator, secureCookies = true, panelAuth = auth) }
        val http = createClient { followRedirects = false }
        val login = http.get(PANEL)
        val page = Jsoup.parse(login.bodyAsText())
        assertNotNull(page.selectFirst("input[name=username]"))
        assertNotNull(page.selectFirst("input[name=password][type=password]"))
        assertTrue(login.headers["Content-Security-Policy"]!!.contains("form-action 'self'"))
        val csrf = page.selectFirst("input[name=csrf]")!!.attr("value")
        val response = http.form("$PANEL/login", mapOf("csrf" to csrf, "username" to "owner", "password" to PASSWORD), login.cookie("itzephir_locator_login"))
        assertEquals(HttpStatusCode.SeeOther, response.status)
        val setCookie = response.headers.getAll(HttpHeaders.SetCookie)!!.first { it.startsWith("$PANEL_COOKIE=") }
        assertTrue(setCookie.contains("HttpOnly", true)); assertTrue(setCookie.contains("Secure", true)); assertTrue(setCookie.contains("SameSite=Strict"))
        val cookie = response.cookie(PANEL_COOKIE)
        val jwt = cookie.substringAfter('=')
        assertEquals(3, jwt.split('.').size)
        assertEquals(owner.id, JWT.decode(jwt).subject)
        assertEquals("owner", JWT.decode(jwt).getClaim("role").asString())
        val home = http.get(PANEL) { header(HttpHeaders.Cookie, cookie) }
        assertTrue(home.bodyAsText().contains("Мои проекты"))
        assertFalse(home.bodyAsText().contains(jwt))
        assertEquals(HttpStatusCode.Unauthorized, http.get("/api/project-locator/v1/identity") { header(HttpHeaders.Cookie, cookie) }.status)
        val logout = http.form("$PANEL/logout", mapOf("csrf" to home.formToken()), cookie)
        assertEquals(HttpStatusCode.SeeOther, logout.status)
        assertNull(auth.user(jwt))
    }

    @Test fun `owners see and edit only assigned projects while administrators see all`() = testApplication {
        val first = locator.register(ProjectRequest("first", "first"), admin).project
        val second = locator.register(ProjectRequest("second", "second"), admin).project
        val privateProject = locator.register(ProjectRequest("private", "private"), null).project
        accounts.setOwners(UUID.fromString(first.id), setOf(UUID.fromString(owner.id)), first.revision, admin)
        accounts.setOwners(UUID.fromString(second.id), setOf(UUID.fromString(owner.id)), second.revision, admin)
        application { projectLocator(locator, false, auth) }
        val http = createClient { followRedirects = false }
        val cookie = http.login("owner")
        val home = http.get(PANEL) { header(HttpHeaders.Cookie, cookie) }
        assertTrue(home.bodyAsText().contains("first")); assertTrue(home.bodyAsText().contains("second")); assertFalse(home.bodyAsText().contains(privateProject.id))
        assertEquals(HttpStatusCode.Forbidden, http.get("$PANEL/users") { header(HttpHeaders.Cookie, cookie) }.status)
        assertEquals(HttpStatusCode.NotFound, http.get("$PANEL/${privateProject.id}/edit") { header(HttpHeaders.Cookie, cookie) }.status)
        val edit = http.get("$PANEL/${first.id}/edit") { header(HttpHeaders.Cookie, cookie) }
        val revision = Jsoup.parse(edit.bodyAsText()).selectFirst("input[name=revision]")!!.attr("value")
        val updated = http.form("$PANEL/${first.id}/edit", mapOf("csrf" to edit.formToken(), "project" to "first-renamed", "slug" to "renamed", "revision" to revision), cookie)
        assertEquals(HttpStatusCode.SeeOther, updated.status)
        val saved = locator.get(UUID.fromString(first.id), admin)
        assertEquals("renamed", saved.slug); assertEquals(ProjectStatus.PENDING, saved.status)
        assertEquals(HttpStatusCode.Forbidden, http.form("$PANEL/${first.id}/approve", mapOf("csrf" to edit.formToken(), "revision" to saved.revision.toString()), cookie).status)
        val adminCookie = http.login("admin")
        assertTrue(http.get(PANEL) { header(HttpHeaders.Cookie, adminCookie) }.bodyAsText().contains(privateProject.id))
        accounts.setOwners(UUID.fromString(second.id), emptySet(), 1, admin)
        assertEquals(HttpStatusCode.NotFound, http.get("$PANEL/${second.id}/edit") { header(HttpHeaders.Cookie, cookie) }.status)
    }

    @Test fun `forms reject cross site mutations and stale approval`() = testApplication {
        val pending = locator.register(ProjectRequest("pending", "pending"), null)
        application { projectLocator(locator, false, auth) }
        val http = createClient { followRedirects = false }
        assertEquals(HttpStatusCode.Forbidden, http.form("$PANEL/login", mapOf("username" to "admin", "password" to PASSWORD)).status)
        val cookie = http.login("admin")
        val home = http.get(PANEL) { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.Forbidden, http.form("$PANEL/${pending.project.id}/approve", mapOf("revision" to "0"), cookie).status)
        locator.update(UUID.fromString(pending.project.id), ProjectUpdate("changed", "changed", 0), pending.identity)
        assertEquals(HttpStatusCode.Conflict, http.form("$PANEL/${pending.project.id}/approve", mapOf("revision" to "0", "csrf" to home.formToken()), cookie).status)
        assertNull(locator.resolve("changed"))
        val response = http.form("$PANEL/${pending.project.id}/approve", mapOf("revision" to "1", "csrf" to home.formToken()), cookie)
        assertEquals(HttpStatusCode.SeeOther, response.status)
        assertNotNull(locator.resolve("changed"))
    }

    @Test fun `public development landing contains only approved and non revoked projects`() = testApplication {
        val approved = locator.register(ProjectRequest("public-project", "public_slug"), admin)
        locator.register(ProjectRequest("pending-secret", "pending-secret"), null)
        val rejected = locator.register(ProjectRequest("rejected-secret", "rejected-secret"), null)
        locator.review(UUID.fromString(rejected.project.id), ProjectStatus.REJECTED, admin)
        application { projectLocator(locator, false, auth) }
        val html = client.get("/dev").bodyAsText()
        assertTrue(html.contains("public-project")); assertTrue(html.contains("/dev/project/public_slug/"))
        assertFalse(html.contains("pending-secret")); assertFalse(html.contains("rejected-secret")); assertFalse(html.contains(approved.project.identityId))
        locator.revoke(UUID.fromString(approved.project.id), admin)
        assertFalse(client.get("/dev").bodyAsText().contains("public-project"))
    }

    @Test fun `password hashing rate limits and password change invalidate old sessions`() = runBlocking {
        val jwt = auth.passwordLogin("owner", PASSWORD)!!
        assertNull(accounts.passwordLogin("owner", "incorrect"))
        source.connection.use { connection -> connection.prepareStatement("SELECT password_hash FROM locator_users WHERE id = ?").use {
            it.setObject(1, UUID.fromString(owner.id)); it.executeQuery().use { rows -> rows.next(); assertTrue(rows.getString(1).startsWith("pbkdf2-sha256$600000$")); assertFalse(rows.getString(1).contains(PASSWORD)) }
        } }
        accounts.changePassword(owner.id, PASSWORD, "changed-password-long-enough")
        assertNull(auth.user(jwt)); assertNull(auth.passwordLogin("owner", PASSWORD)); assertNotNull(auth.passwordLogin("owner", "changed-password-long-enough"))
        repeat(10) { assertNull(accounts.passwordLogin("owner", "incorrect")) }
        val limited = runCatching { accounts.passwordLogin("owner", "changed-password-long-enough") }.exceptionOrNull()
        assertEquals(429, (limited as LocatorException).status)
    }

    @Test fun `JWT signature issuer audience expiry subject and live role are checked`() = runBlocking {
        val jwt = auth.passwordLogin("owner", PASSWORD)!!
        val original = JWT.decode(jwt)
        assertNull(auth.user(jwt.dropLast(4) + "aaaa"))
        fun signed(issuer: String = PanelAuth.ISSUER, audience: String = PanelAuth.AUDIENCE, subject: String = owner.id, expiry: Instant = Instant.now().plusSeconds(60)) = JWT.create().withIssuer(issuer).withAudience(audience).withSubject(subject).withJWTId(original.id).withClaim("kind", "web-panel").withClaim("role", "admin").withExpiresAt(expiry).sign(Algorithm.HMAC256(JWT_KEY))
        assertNull(auth.user(signed(issuer = "other"))); assertNull(auth.user(signed(audience = "other"))); assertNull(auth.user(signed(subject = admin.id)))
        assertNull(auth.user(signed(expiry = Instant.now().minusSeconds(1))))
        assertEquals(IdentityRole.OWNER, auth.user(signed())!!.role)
        source.connection.use { it.createStatement().use { query -> query.executeUpdate("UPDATE locator_panel_sessions SET expires_at = now() - interval '1 second'") } }
        assertNull(auth.user(jwt))
    }

    @Test fun `GitHub OAuth uses PKCE one time state and never trusts login for admin rights`() = testApplication {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/login/oauth/access_token" -> respond("""{"access_token":"mock-provider-token"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                "/user" -> { assertEquals("Bearer mock-provider-token", request.headers[HttpHeaders.Authorization]); respond("""{"id":9876,"login":"itzephir"}""", headers = headersOf(HttpHeaders.ContentType, "application/json")) }
                else -> error("Unexpected GitHub URL")
            }
        }
        val provider = GitHubLogin(GitHubConfig("test-client", "test-secret", "https://itzephir.com/dev/panel/github/callback"), HttpClient(engine))
        val oauth = PanelAuth(accounts, JWT_KEY, provider)
        application { projectLocator(locator, false, oauth) }
        val http = createClient { followRedirects = false }
        val login = http.get(PANEL)
        assertTrue(login.bodyAsText().contains("Войти через GitHub"))
        val start = http.form("$PANEL/github", mapOf("csrf" to login.formToken()), login.cookie("itzephir_locator_login"))
        assertEquals(HttpStatusCode.SeeOther, start.status)
        val url = io.ktor.http.Url(start.headers[HttpHeaders.Location]!!)
        assertEquals("S256", url.parameters["code_challenge_method"])
        assertEquals(43, url.parameters["code_challenge"]!!.length)
        val state = url.parameters["state"]!!
        val stateCookie = start.cookie("itzephir_locator_github")
        assertEquals(HttpStatusCode.Forbidden, http.get("$PANEL/github/callback?state=wrong&code=test") { header(HttpHeaders.Cookie, stateCookie) }.status)
        val callback = http.get("$PANEL/github/callback?state=$state&code=test") { header(HttpHeaders.Cookie, stateCookie) }
        assertEquals(HttpStatusCode.SeeOther, callback.status)
        val jwt = callback.cookie(PANEL_COOKIE).substringAfter('=')
        assertEquals(IdentityRole.OWNER, oauth.user(jwt)!!.role)
        assertEquals(HttpStatusCode.Forbidden, http.get("$PANEL/github/callback?state=$state&code=test") { header(HttpHeaders.Cookie, stateCookie) }.status)
        assertTrue(accounts.users(admin).any { it.username == "github-9876" })
    }

    @Test fun `GitHub linking cannot take over another account and supports setting a password`() = runBlocking {
        val github = accounts.githubUser(9999, "somebody", null)
        assertEquals(IdentityRole.OWNER, github.role)
        assertFalse(github.hasPassword)
        accounts.changePassword(github.id, "", PASSWORD)
        assertEquals(github.id, accounts.passwordLogin(github.username, PASSWORD)!!.id)
        val conflict = runCatching { accounts.githubUser(9999, "somebody", owner.id) }.exceptionOrNull()
        assertEquals("account_conflict", (conflict as LocatorException).code)
        assertNull(accounts.user(owner.id).githubLogin)
        assertEquals(admin.id, accounts.githubUser(1234, "renamed-root", null).id)
    }

    private suspend fun HttpClient.login(username: String): String {
        val page = get(PANEL)
        val response = form("$PANEL/login", mapOf("username" to username, "password" to PASSWORD, "csrf" to page.formToken()), page.cookie("itzephir_locator_login"))
        assertEquals(HttpStatusCode.SeeOther, response.status)
        return response.cookie(PANEL_COOKIE)
    }
}

private suspend fun HttpResponse.formToken() = Jsoup.parse(bodyAsText()).selectFirst("input[name=csrf]")!!.attr("value")
private fun HttpResponse.cookie(name: String) = headers.getAll(HttpHeaders.SetCookie)!!.first { it.startsWith("$name=") }.substringBefore(';')
private suspend fun HttpClient.form(path: String, values: Map<String, String>, cookie: String? = null) = post(path) {
    if (cookie != null) header(HttpHeaders.Cookie, cookie)
    setBody(FormDataContent(Parameters.build { values.forEach { (key, value) -> append(key, value) } }))
}
