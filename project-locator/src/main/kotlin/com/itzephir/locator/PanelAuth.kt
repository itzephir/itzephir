package com.itzephir.locator

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class PanelAuth internal constructor(internal val accounts: WebAccounts, secret: String, internal val github: GitHubLogin? = null) {
    private val algorithm = Algorithm.HMAC256(secret.also { require(it.toByteArray().size >= 32) { "LOCATOR_JWT_SECRET must contain at least 32 bytes" } })
    private val verifier = JWT.require(algorithm).withIssuer(ISSUER).withAudience(AUDIENCE).withClaim("kind", "web-panel").build()

    internal suspend fun passwordLogin(username: String, password: String): String? = accounts.passwordLogin(username, password)?.let { issue(it) }
    internal suspend fun issue(user: WebUser): String {
        val session = accounts.createSession(user.id)
        return JWT.create().withIssuer(ISSUER).withAudience(AUDIENCE).withSubject(user.id).withJWTId(session)
            .withClaim("kind", "web-panel").withClaim("role", user.role.databaseValue)
            .withIssuedAt(Instant.now()).withExpiresAt(Instant.now().plusSeconds(8 * 60 * 60)).sign(algorithm)
    }
    internal suspend fun user(jwt: String): WebUser? {
        val decoded = decode(jwt) ?: return null
        val session = decoded.id ?: return null
        return accounts.sessionUser(session)?.takeIf { it.id == decoded.subject }
    }
    internal suspend fun logout(jwt: String) { decode(jwt)?.id?.let { accounts.closeSession(it) } }
    private fun decode(jwt: String) = if (jwt.length > 4096) null else try { verifier.verify(jwt) } catch (_: JWTVerificationException) { null } catch (_: IllegalArgumentException) { null }

    companion object {
        internal const val ISSUER = "itzephir"
        internal const val AUDIENCE = "itzephir:web-panel"
    }
}

internal data class GitHubConfig(val clientId: String, val clientSecret: String, val callbackUrl: String)
internal data class GitHubUser(val id: Long, val login: String)

internal class GitHubLogin(val config: GitHubConfig, private val client: HttpClient = HttpClient(CIO) {
    expectSuccess = true
    followRedirects = false
    install(HttpTimeout) { requestTimeoutMillis = 10_000; connectTimeoutMillis = 5_000 }
}) {
    fun authorizationUrl(state: String, verifier: String): String = URLBuilder("https://github.com/login/oauth/authorize").apply {
        parameters.append("client_id", config.clientId); parameters.append("redirect_uri", config.callbackUrl)
        parameters.append("scope", ""); parameters.append("state", state)
        parameters.append("code_challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())))
        parameters.append("code_challenge_method", "S256")
    }.buildString()

    suspend fun exchange(code: String, verifier: String): GitHubUser {
        if (code.length !in 1..512) throw LocatorException(401, "github_failed", "Invalid GitHub code")
        return try {
            val response = client.submitForm("https://github.com/login/oauth/access_token", Parameters.build {
                append("client_id", config.clientId); append("client_secret", config.clientSecret)
                append("redirect_uri", config.callbackUrl); append("code", code); append("code_verifier", verifier)
            }) { header(HttpHeaders.Accept, "application/json") }
            val token = Json.parseToJsonElement(response.bodyAsText()).jsonObject["access_token"]?.jsonPrimitive?.content
                ?: throw LocatorException(401, "github_failed", "GitHub did not authorize the request")
            val profile = client.get("https://api.github.com/user") {
                header(HttpHeaders.Authorization, "Bearer $token"); header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, "itzephir-project-locator")
            }
            val fields = Json.parseToJsonElement(profile.bodyAsText()).jsonObject
            GitHubUser(fields.getValue("id").jsonPrimitive.long, fields.getValue("login").jsonPrimitive.content)
        } catch (failure: CancellationException) { throw failure }
        catch (failure: LocatorException) { throw failure }
        catch (_: Exception) { throw LocatorException(502, "github_failed", "GitHub sign in failed") }
    }
    fun close() = client.close()
}

fun panelAuthFromEnvironment(locator: PostgresLocator, environment: Map<String, String> = System.getenv()): PanelAuth? {
    val names = listOf("LOCATOR_JWT_SECRET", "LOCATOR_ADMIN_USERNAME", "LOCATOR_ADMIN_PASSWORD", "LOCATOR_GITHUB_CLIENT_ID", "LOCATOR_GITHUB_CLIENT_SECRET", "LOCATOR_GITHUB_CALLBACK_URL", "LOCATOR_ADMIN_GITHUB_ID")
    if (names.none { environment[it] != null }) return null
    fun required(name: String) = requireNotNull(environment[name]?.takeIf(String::isNotBlank)) { "Missing $name" }
    val secret = required("LOCATOR_JWT_SECRET")
    require(secret.toByteArray().size >= 32) { "LOCATOR_JWT_SECRET must contain at least 32 bytes" }
    val accounts = WebAccounts(locator)
    val github = if (names.slice(3..5).any { environment[it] != null }) {
        val callback = required("LOCATOR_GITHUB_CALLBACK_URL")
        val uri = URI(callback)
        require((uri.scheme == "https" || (uri.scheme == "http" && uri.host in listOf("localhost", "127.0.0.1"))) && uri.path == "/dev/panel/github/callback" && uri.rawQuery == null && uri.fragment == null && uri.userInfo == null) { "Invalid GitHub callback URL" }
        GitHubLogin(GitHubConfig(required("LOCATOR_GITHUB_CLIENT_ID"), required("LOCATOR_GITHUB_CLIENT_SECRET"), callback))
    } else null
    val githubId = environment["LOCATOR_ADMIN_GITHUB_ID"]?.let { requireNotNull(it.toLongOrNull()?.takeIf { number -> number > 0 }) { "Invalid admin GitHub ID" } }
    runBlocking { accounts.bootstrapAdmin(environment["LOCATOR_ADMIN_USERNAME"] ?: "itzephir", required("LOCATOR_ADMIN_PASSWORD"), githubId) }
    return PanelAuth(accounts, secret, github)
}
