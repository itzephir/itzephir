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
enum class ProjectReadiness {
    @SerialName("planning") PLANNING,
    @SerialName("development") DEVELOPMENT;

    val databaseValue get() = name.lowercase()
    val canOpen get() = this == DEVELOPMENT
}

@Serializable
enum class IdentityRole {
    @SerialName("project") PROJECT,
    @SerialName("owner") OWNER,
    @SerialName("admin") ADMIN;

    val databaseValue get() = name.lowercase()
}

@Serializable
data class ProjectRequest(
    val project: String,
    val slug: String,
    val routing: ProjectRouting = ProjectRouting(),
    val description: String = "",
    val readiness: ProjectReadiness = ProjectReadiness.DEVELOPMENT,
) {
    fun validate() {
        routing.validate(slug)
        validateDescription(description)
        if (!IDENTIFIER.matches(project) || !IDENTIFIER.matches(slug)) {
            throw LocatorException(400, "invalid_identifier", "project and slug must be 1-64 lowercase letters, digits, underscores or hyphens, starting with a letter or digit")
        }
    }

    companion object { private val IDENTIFIER = Regex("[a-z0-9][a-z0-9_-]{0,63}") }
}

@Serializable
data class ProjectUpdate(
    val project: String,
    val slug: String,
    val revision: Int,
    val routing: ProjectRouting? = null,
    val description: String? = null,
    val readiness: ProjectReadiness? = null,
) {
    fun validate() {
        ProjectRequest(project, slug, routing ?: ProjectRouting()).validate()
        description?.let(::validateDescription)
        if (revision < 0) throw LocatorException(400, "invalid_revision", "Invalid project revision")
    }
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
    val revision: Int = 0,
    val routing: ProjectRouting = ProjectRouting(),
    val description: String = "",
    val readiness: ProjectReadiness = ProjectReadiness.DEVELOPMENT,
) {
    val productionUrl get() = "https://itzephir.com/dev/project/$slug/"
    val stageUrl get() = "https://dev.itzephir.com/dev/project/$slug/"
    val productionAppUrl get() = "https://$slug.itzephir.com/"
    val stageAppUrl get() = "https://$slug.dev.itzephir.com/"
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

private fun validateDescription(description: String) {
    if (description.length > 500) throw LocatorException(400, "invalid_description", "Описание должно содержать не более 500 символов.")
}

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
