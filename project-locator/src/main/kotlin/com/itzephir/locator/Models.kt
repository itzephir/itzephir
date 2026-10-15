package com.itzephir.locator

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ProjectStatus {
    @SerialName("pending") PENDING,
    @SerialName("approved") APPROVED,
    @SerialName("rejected") REJECTED;

    val databaseValue get() = name.lowercase()
}

@Serializable
enum class IdentityRole {
    @SerialName("project") PROJECT,
    @SerialName("admin") ADMIN;

    val databaseValue get() = name.lowercase()
}

@Serializable
data class ProjectRequest(val project: String, val slug: String) {
    fun validate() {
        if (!IDENTIFIER.matches(project) || !IDENTIFIER.matches(slug)) {
            throw LocatorException(400, "invalid_identifier", "project and slug must be 1-64 lowercase letters, digits, underscores or hyphens, starting with a letter or digit")
        }
    }

    companion object { private val IDENTIFIER = Regex("[a-z0-9][a-z0-9_-]{0,63}") }
}

@Serializable
data class Project(
    val id: String,
    val project: String,
    val slug: String,
    val identityId: String,
    val status: ProjectStatus,
    val createdAt: String,
    val reviewedAt: String? = null,
    val reviewedBy: String? = null,
) {
    val productionUrl get() = "https://itzephir.com/dev/project/$slug/"
    val stageUrl get() = "https://dev.itzephir.com/dev/project/$slug/"
}

@Serializable
data class Identity(val id: String, val role: IdentityRole, val projectId: String? = null)

@Serializable
data class Registration(val project: Project, val identity: Identity, val token: String)

@Serializable
data class TokenIssued(val identity: Identity, val token: String)

@Serializable
data class ResolvedProject(val project: Project, val productionUrl: String, val stageUrl: String)

@Serializable
data class ApiError(val code: String, val message: String)

class LocatorException(val status: Int, val code: String, override val message: String) : RuntimeException(message)

internal object Tokens {
    private val random = SecureRandom()
    fun issue(): String = "itzp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    fun hash(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

internal fun requireAdmin(identity: Identity) {
    if (identity.role != IdentityRole.ADMIN) throw LocatorException(403, "admin_required", "Administrator identity required")
}

internal fun parseId(value: String): UUID = try {
    UUID.fromString(value).also { if (it.toString() != value) throw IllegalArgumentException() }
} catch (_: IllegalArgumentException) {
    throw LocatorException(400, "invalid_id", "Expected a UUID")
}
