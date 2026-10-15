package com.itzephir.locator

import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal data class WebUser(val id: String, val username: String, val role: IdentityRole, val hasPassword: Boolean, val githubLogin: String?) {
    fun identity() = Identity(id, role)
}
internal data class OAuthState(val verifier: String, val linkUserId: String?)

internal class WebAccounts(private val locator: PostgresLocator) {
    suspend fun bootstrapAdmin(username: String, password: String, githubId: Long?) {
        validateUsername(username); Passwords.validate(password)
        if (githubId != null) require(githubId > 0) { "Invalid admin GitHub ID" }
        transaction { connection ->
            connection.prepareStatement("INSERT INTO locator_users (id, username, password_hash, github_id, role) VALUES (?, ?, ?, ?, 'admin') ON CONFLICT (id) DO NOTHING").use {
                it.setObject(1, ADMIN); it.setString(2, username); it.setString(3, Passwords.hash(password)); it.setObject(4, githubId); it.executeUpdate()
            }
        }
    }

    suspend fun passwordLogin(username: String, password: String): WebUser? = transaction { connection ->
        if (password.length > 256 || username.length > 64) return@transaction null
        val name = username.lowercase()
        val key = Tokens.hash(name)
        connection.prepareStatement("INSERT INTO locator_login_attempts (name_hash) VALUES (?) ON CONFLICT DO NOTHING").use { it.setString(1, key); it.executeUpdate() }
        val blocked = connection.prepareStatement("SELECT failures >= 10 AND window_started_at > now() - interval '15 minutes' FROM locator_login_attempts WHERE name_hash = ? FOR UPDATE").use {
            it.setString(1, key); it.executeQuery().use { rows -> rows.next(); rows.getBoolean(1) }
        }
        if (blocked) throw LocatorException(429, "login_limited", "Too many login attempts")
        val account = connection.prepareStatement("SELECT * FROM locator_users WHERE username = ?").use {
            it.setString(1, name); it.executeQuery().use { rows -> if (rows.next()) rows.user() to rows.getString("password_hash") else null }
        }
        if (Passwords.verify(password, account?.second ?: DUMMY_HASH) && account?.second != null) {
            connection.prepareStatement("DELETE FROM locator_login_attempts WHERE name_hash = ?").use { it.setString(1, key); it.executeUpdate() }
            account.first
        } else {
            connection.prepareStatement("UPDATE locator_login_attempts SET failures = CASE WHEN window_started_at <= now() - interval '15 minutes' THEN 1 ELSE failures + 1 END, window_started_at = CASE WHEN window_started_at <= now() - interval '15 minutes' THEN now() ELSE window_started_at END WHERE name_hash = ?").use { it.setString(1, key); it.executeUpdate() }
            null
        }
    }

    suspend fun createOwner(username: String, password: String, actor: Identity): WebUser {
        requireAdmin(actor); validateUsername(username); Passwords.validate(password)
        return transaction { connection ->
            val id = UUID.randomUUID()
            insertIdentity(connection, id)
            connection.prepareStatement("INSERT INTO locator_users (id, username, password_hash, role) VALUES (?, ?, ?, 'owner') RETURNING *").use {
                it.setObject(1, id); it.setString(2, username); it.setString(3, Passwords.hash(password))
                it.executeQuery().use { rows -> rows.next(); rows.user() }
            }
        }
    }

    suspend fun users(actor: Identity): List<WebUser> {
        requireAdmin(actor)
        return transaction { connection -> connection.createStatement().use { query ->
            query.executeQuery("SELECT * FROM locator_users ORDER BY username LIMIT 1000").use { rows -> buildList { while (rows.next()) add(rows.user()) } }
        } }
    }

    suspend fun user(id: String): WebUser = transaction { connection -> user(connection, id) }

    suspend fun owners(projectId: UUID, actor: Identity): Set<String> {
        requireAdmin(actor)
        return transaction { connection -> connection.prepareStatement("SELECT user_id FROM locator_project_owners WHERE project_id = ?").use {
            it.setObject(1, projectId); it.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        } }
    }

    suspend fun setOwners(projectId: UUID, users: Set<UUID>, revision: Int, actor: Identity) {
        requireAdmin(actor)
        if (users.size > 50) throw LocatorException(400, "invalid_owners", "Too many owners")
        transaction { connection ->
            val current = connection.prepareStatement("SELECT revision FROM locator_projects WHERE id = ? FOR UPDATE").use {
                it.setObject(1, projectId); it.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else null }
            } ?: throw LocatorException(404, "project_not_found", "Project not found")
            if (current != revision) throw LocatorException(409, "stale_project", "Project changed")
            connection.prepareStatement("DELETE FROM locator_project_owners WHERE project_id = ?").use { it.setObject(1, projectId); it.executeUpdate() }
            users.forEach { id -> connection.prepareStatement("INSERT INTO locator_project_owners (project_id, user_id) SELECT ?, id FROM locator_users WHERE id = ? AND role = 'owner'").use {
                it.setObject(1, projectId); it.setObject(2, id)
                if (it.executeUpdate() != 1) throw LocatorException(400, "invalid_owners", "Unknown project owner")
            } }
            connection.prepareStatement("UPDATE locator_projects SET revision = revision + 1 WHERE id = ?").use { it.setObject(1, projectId); it.executeUpdate() }
        }
    }

    suspend fun changePassword(userId: String, current: String, next: String) {
        Passwords.validate(next)
        transaction { connection ->
            val old = connection.prepareStatement("SELECT password_hash FROM locator_users WHERE id = ? FOR UPDATE").use {
                it.setObject(1, UUID.fromString(userId)); it.executeQuery().use { rows -> if (!rows.next()) throw LocatorException(401, "invalid_session", "Unknown user"); rows.getString(1) }
            }
            if (old != null && !Passwords.verify(current, old)) throw LocatorException(400, "wrong_password", "Incorrect current password")
            connection.prepareStatement("UPDATE locator_users SET password_hash = ?, credential_version = credential_version + 1 WHERE id = ?").use {
                it.setString(1, Passwords.hash(next)); it.setObject(2, UUID.fromString(userId)); it.executeUpdate()
            }
        }
    }

    suspend fun createSession(userId: String): String = transaction { connection ->
        val token = Tokens.issue().replaceFirst("itzp_", "itzs_")
        connection.createStatement().use { it.executeUpdate("DELETE FROM locator_panel_sessions WHERE expires_at <= now()") }
        connection.prepareStatement("INSERT INTO locator_panel_sessions (token_hash, user_id, credential_version) SELECT ?, id, credential_version FROM locator_users WHERE id = ?").use {
            it.setString(1, Tokens.hash(token)); it.setObject(2, UUID.fromString(userId)); check(it.executeUpdate() == 1)
        }
        token
    }

    suspend fun sessionUser(token: String): WebUser? = transaction { connection ->
        connection.prepareStatement("""
            SELECT u.* FROM locator_panel_sessions s JOIN locator_users u ON u.id = s.user_id AND u.credential_version = s.credential_version
            WHERE s.token_hash = ? AND s.expires_at > now()
        """.trimIndent()).use {
            it.setString(1, Tokens.hash(token)); it.executeQuery().use { rows -> if (rows.next()) rows.user() else null }
        }
    }

    suspend fun closeSession(token: String) { transaction { connection ->
        connection.prepareStatement("DELETE FROM locator_panel_sessions WHERE token_hash = ?").use { it.setString(1, Tokens.hash(token)); it.executeUpdate() }
    } }

    suspend fun oauthState(state: String, verifier: String, linkUserId: String?) { transaction { connection ->
        connection.createStatement().use { it.executeUpdate("DELETE FROM locator_oauth_states WHERE expires_at <= now()") }
        connection.prepareStatement("INSERT INTO locator_oauth_states (state_hash, verifier, link_user_id) VALUES (?, ?, ?)").use {
            it.setString(1, Tokens.hash(state)); it.setString(2, verifier); it.setObject(3, linkUserId?.let(UUID::fromString)); it.executeUpdate()
        }
    } }

    suspend fun consumeOAuthState(state: String): OAuthState? = transaction { connection ->
        connection.prepareStatement("DELETE FROM locator_oauth_states WHERE state_hash = ? AND expires_at > now() RETURNING verifier, link_user_id").use {
            it.setString(1, Tokens.hash(state)); it.executeQuery().use { rows -> if (rows.next()) OAuthState(rows.getString(1), rows.getString(2)) else null }
        }
    }

    suspend fun githubUser(id: Long, login: String, linkUserId: String?): WebUser = transaction { connection ->
        if (id <= 0) throw LocatorException(401, "github_failed", "Invalid GitHub identity")
        if (linkUserId != null) {
            connection.prepareStatement("UPDATE locator_users SET github_id = ?, github_login = ?, credential_version = credential_version + 1 WHERE id = ?").use {
                it.setLong(1, id); it.setString(2, login); it.setObject(3, UUID.fromString(linkUserId)); check(it.executeUpdate() == 1)
            }
            return@transaction user(connection, linkUserId)
        }
        val found = connection.prepareStatement("SELECT * FROM locator_users WHERE github_id = ?").use {
            it.setLong(1, id); it.executeQuery().use { rows -> if (rows.next()) rows.user() else null }
        }
        if (found != null) {
            connection.prepareStatement("UPDATE locator_users SET github_login = ? WHERE id = ?").use {
                it.setString(1, login); it.setObject(2, UUID.fromString(found.id)); it.executeUpdate()
            }
            return@transaction found.copy(githubLogin = login)
        }
        val userId = UUID.randomUUID()
        insertIdentity(connection, userId)
        connection.prepareStatement("INSERT INTO locator_users (id, username, github_id, github_login, role) VALUES (?, ?, ?, ?, 'owner') RETURNING *").use {
            it.setObject(1, userId); it.setString(2, "github-$id"); it.setLong(3, id); it.setString(4, login)
            it.executeQuery().use { rows -> rows.next(); rows.user() }
        }
    }

    private suspend fun <T> transaction(block: (Connection) -> T): T = try {
        locator.transaction(block)
    } catch (error: LocatorException) {
        if (error.code == "project_conflict") throw LocatorException(409, "account_conflict", "Username or GitHub account is already in use")
        throw error
    }

    private fun insertIdentity(connection: Connection, id: UUID) {
        connection.prepareStatement("INSERT INTO locator_identities (id, role) VALUES (?, 'owner')").use {
            it.setObject(1, id); it.executeUpdate()
        }
    }
    private fun user(connection: Connection, id: String): WebUser = connection.prepareStatement("SELECT * FROM locator_users WHERE id = ?").use {
        it.setObject(1, UUID.fromString(id)); it.executeQuery().use { rows -> if (rows.next()) rows.user() else throw LocatorException(401, "invalid_session", "Unknown user") }
    }
    private fun ResultSet.user() = WebUser(getString("id"), getString("username"), IdentityRole.valueOf(getString("role").uppercase()), getString("password_hash") != null, getString("github_login"))
    private fun validateUsername(value: String) {
        if (!Regex("[a-z0-9][a-z0-9_.-]{2,63}").matches(value)) throw LocatorException(400, "invalid_username", "Invalid username")
    }
    companion object {
        private val ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val DUMMY_HASH by lazy { Passwords.hash("never-a-valid-account-password") }
    }
}

internal object Passwords {
    private val random = SecureRandom()
    fun validate(value: String) { if (value.length !in 12..256) throw LocatorException(400, "weak_password", "Password must contain 12-256 characters") }
    fun hash(value: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return listOf("pbkdf2-sha256", "600000", Base64.getEncoder().encodeToString(salt), Base64.getEncoder().encodeToString(derive(value, salt))).joinToString("$")
    }
    fun verify(value: String, encoded: String): Boolean {
        if (value.length > 256) return false
        val parts = encoded.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2-sha256" || parts[1] != "600000") return false
        return runCatching { MessageDigest.isEqual(derive(value, Base64.getDecoder().decode(parts[2])), Base64.getDecoder().decode(parts[3])) }.getOrDefault(false)
    }
    private fun derive(value: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(value.toCharArray(), salt, 600_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
}
