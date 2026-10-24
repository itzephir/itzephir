package com.itzephir.locator

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Each operation owns one short transaction; JDBC never runs on a Ktor event loop. */
class PostgresLocator(private val dataSource: DataSource) {
    private val dispatcher = Dispatchers.IO.limitedParallelism(4)

    suspend fun initialize(adminToken: String) {
        require(adminToken.length in 32..256 && adminToken.all { it.code in 33..126 }) {
            "LOCATOR_ADMIN_TOKEN must contain 32-256 printable non-whitespace characters"
        }
        transaction { connection ->
            connection.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(538550832341)") }
            connection.createStatement().use {
                it.execute("CREATE TABLE IF NOT EXISTS locator_schema_migrations (version integer PRIMARY KEY, checksum char(64) NOT NULL, installed_at timestamptz NOT NULL DEFAULT now())")
            }
            listOf("001-initial.sql", "002-panel.sql", "003-routing.sql", "004-catalog.sql").forEachIndexed { index, name ->
                val version = index + 1
                val migration = checkNotNull(javaClass.getResource("/db/locator/$name")).readText()
                val checksum = Tokens.hash(migration)
                val installed = connection.prepareStatement("SELECT checksum FROM locator_schema_migrations WHERE version = ?").use { query ->
                    query.setInt(1, version)
                    query.executeQuery().use { if (it.next()) it.getString(1) else null }
                }
                if (installed == null) {
                    connection.createStatement().use { it.execute(migration) }
                    connection.prepareStatement("INSERT INTO locator_schema_migrations (version, checksum) VALUES (?, ?)").use {
                        it.setInt(1, version); it.setString(2, checksum); it.executeUpdate()
                    }
                } else {
                    check(installed == checksum) { "Previously applied locator migration $version was modified" }
                }
            }
            // A stable admin identity survives restarts; changing the environment token rotates its credential.
            connection.prepareStatement("""
                INSERT INTO locator_identities (id, role, token_hash) VALUES (?, 'admin', ?)
                ON CONFLICT (id) DO UPDATE SET token_hash = EXCLUDED.token_hash, revoked_at = NULL
            """.trimIndent()).use {
                it.setObject(1, ADMIN_ID); it.setString(2, Tokens.hash(adminToken)); it.executeUpdate()
            }
        }
    }

    suspend fun authenticate(token: String): Identity? {
        if (token.length !in 32..256) return null
        return transaction { connection ->
            connection.prepareStatement("""
                SELECT i.id, i.role, p.id AS project_id FROM locator_identities i
                LEFT JOIN locator_projects p ON p.identity_id = i.id
                WHERE i.token_hash = ? AND i.revoked_at IS NULL
            """.trimIndent()).use {
                it.setString(1, Tokens.hash(token))
                it.executeQuery().use { rows ->
                    if (rows.next()) Identity(rows.getString("id"), IdentityRole.valueOf(rows.getString("role").uppercase()), rows.getString("project_id")) else null
                }
            }
        }
    }

    suspend fun register(request: ProjectRequest, actor: Identity?): Registration {
        request.validate()
        if (actor != null) requireAdmin(actor)
        val token = Tokens.issue()
        val identityId = UUID.randomUUID()
        val projectId = UUID.randomUUID()
        val status = if (actor == null) ProjectStatus.PENDING else ProjectStatus.APPROVED
        val project = transaction { connection ->
            connection.prepareStatement("INSERT INTO locator_identities (id, role, token_hash) VALUES (?, 'project', ?)").use {
                it.setObject(1, identityId); it.setString(2, Tokens.hash(token)); it.executeUpdate()
            }
            connection.prepareStatement("""
                INSERT INTO locator_projects (id, project, slug, identity_id, status, reviewed_at, reviewed_by, routing_mode, routing_target, description, readiness)
                VALUES (?, ?, ?, ?, ?, CASE WHEN ? = 'approved' THEN now() ELSE NULL END, ?, ?, ?, ?, ?) RETURNING *
            """.trimIndent()).use {
                it.setObject(1, projectId); it.setString(2, request.project); it.setString(3, request.slug)
                it.setObject(4, identityId); it.setString(5, status.databaseValue); it.setString(6, status.databaseValue)
                it.setObject(7, actor?.let { identity -> UUID.fromString(identity.id) })
                it.setString(8, request.routing.mode.databaseValue); it.setString(9, request.routing.target)
                it.setString(10, request.description); it.setString(11, request.readiness.databaseValue)
                it.executeQuery().use { rows -> check(rows.next()); rows.project() }
            }
        }
        return Registration(project, Identity(identityId.toString(), IdentityRole.PROJECT, projectId.toString()), token)
    }

    suspend fun get(id: UUID, actor: Identity): Project = transaction { ownedProject(it, id, actor) }

    suspend fun list(actor: Identity, status: ProjectStatus?, limit: Int, offset: Int): List<Project> {
        return transaction { connection ->
            connection.prepareStatement("SELECT * FROM locator_projects WHERE (?::text IS NULL OR status = ?) AND (? OR identity_id = ? OR EXISTS (SELECT 1 FROM locator_project_owners o WHERE o.project_id = locator_projects.id AND o.user_id = ?)) ORDER BY created_at DESC, id LIMIT ? OFFSET ?").use {
                it.setString(1, status?.databaseValue); it.setString(2, status?.databaseValue)
                it.setBoolean(3, actor.role == IdentityRole.ADMIN); it.setObject(4, UUID.fromString(actor.id))
                it.setObject(5, UUID.fromString(actor.id)); it.setInt(6, limit); it.setInt(7, offset)
                it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.project()) } }
            }
        }
    }

    suspend fun review(id: UUID, status: ProjectStatus, actor: Identity, revision: Int? = null): Project {
        requireAdmin(actor)
        require(status != ProjectStatus.PENDING)
        return transaction { connection ->
            val project = ownedProject(connection, id, actor, lock = true)
            if (revision != null && project.revision != revision) throw LocatorException(409, "stale_project", "Project changed; reload before reviewing")
            if (project.status == status) return@transaction project
            if (project.status != ProjectStatus.PENDING) throw LocatorException(409, "already_reviewed", "This request has already been reviewed")
            connection.prepareStatement("UPDATE locator_projects SET status = ?, reviewed_at = now(), reviewed_by = ?, revision = revision + 1 WHERE id = ? RETURNING *").use {
                it.setString(1, status.databaseValue); it.setObject(2, UUID.fromString(actor.id)); it.setObject(3, id)
                it.executeQuery().use { rows -> check(rows.next()); rows.project() }
            }
        }
    }

    suspend fun update(id: UUID, request: ProjectUpdate, actor: Identity): Project {
        request.validate()
        return transaction { connection ->
            val before = ownedProject(connection, id, actor, lock = true)
            if (before.revision != request.revision) throw LocatorException(409, "stale_project", "Project changed; reload before editing")
            val routing = request.routing ?: before.routing
            val description = request.description ?: before.description
            val readiness = request.readiness ?: before.readiness
            routing.validate(request.slug)
            if (before.project == request.project && before.slug == request.slug && before.routing == routing &&
                before.description == description && before.readiness == readiness) return@transaction before
            val needsReview = actor.role != IdentityRole.ADMIN
            connection.prepareStatement("""
                UPDATE locator_projects SET project = ?, slug = ?, routing_mode = ?, routing_target = ?, description = ?, readiness = ?, revision = revision + 1,
                status = CASE WHEN ? THEN 'pending' ELSE status END,
                reviewed_at = CASE WHEN ? THEN NULL ELSE reviewed_at END,
                reviewed_by = CASE WHEN ? THEN NULL ELSE reviewed_by END
                WHERE id = ? RETURNING *
            """.trimIndent()).use {
                it.setString(1, request.project); it.setString(2, request.slug)
                it.setString(3, routing.mode.databaseValue); it.setString(4, routing.target)
                it.setString(5, description); it.setString(6, readiness.databaseValue)
                it.setBoolean(7, needsReview); it.setBoolean(8, needsReview); it.setBoolean(9, needsReview); it.setObject(10, id)
                it.executeQuery().use { rows -> check(rows.next()); rows.project() }
            }
        }
    }

    suspend fun publicProjects(limit: Int, offset: Int): List<Project> = transaction { connection ->
        connection.prepareStatement("""
            SELECT p.* FROM locator_projects p JOIN locator_identities i ON i.id = p.identity_id
            WHERE p.status = 'approved' AND i.revoked_at IS NULL ORDER BY p.created_at DESC, p.id LIMIT ? OFFSET ?
        """.trimIndent()).use {
            it.setInt(1, limit); it.setInt(2, offset)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.project()) } }
        }
    }

    suspend fun resolve(slug: String): Project? = transaction { connection ->
        connection.prepareStatement("""
            SELECT p.* FROM locator_projects p JOIN locator_identities i ON i.id = p.identity_id
            WHERE p.slug = ? AND p.status = 'approved' AND i.revoked_at IS NULL
        """.trimIndent()).use {
            it.setString(1, slug)
            it.executeQuery().use { rows -> if (rows.next()) rows.project() else null }
        }
    }

    suspend fun rotate(id: UUID, actor: Identity): TokenIssued = transaction { connection ->
        val project = ownedProject(connection, id, actor, lock = true)
        val token = Tokens.issue()
        connection.prepareStatement("UPDATE locator_identities SET token_hash = ?, revoked_at = NULL WHERE id = ? AND (revoked_at IS NULL OR ?)").use {
            it.setString(1, Tokens.hash(token)); it.setObject(2, UUID.fromString(project.identityId)); it.setBoolean(3, actor.role == IdentityRole.ADMIN)
            if (it.executeUpdate() != 1) throw LocatorException(403, "identity_revoked", "Identity has been revoked")
        }
        TokenIssued(Identity(project.identityId, IdentityRole.PROJECT, project.id), token)
    }

    suspend fun revoke(id: UUID, actor: Identity) {
        requireAdmin(actor)
        transaction { connection ->
            val project = ownedProject(connection, id, actor, lock = true)
            connection.prepareStatement("UPDATE locator_identities SET revoked_at = now() WHERE id = ?").use {
                it.setObject(1, UUID.fromString(project.identityId)); it.executeUpdate()
            }
        }
    }

    private fun ownedProject(connection: Connection, id: UUID, actor: Identity, lock: Boolean = false): Project {
        val project = connection.prepareStatement("SELECT * FROM locator_projects WHERE id = ? AND (? OR identity_id = ? OR EXISTS (SELECT 1 FROM locator_project_owners o WHERE o.project_id = locator_projects.id AND o.user_id = ?))" + if (lock) " FOR UPDATE" else "").use {
            it.setObject(1, id); it.setBoolean(2, actor.role == IdentityRole.ADMIN)
            it.setObject(3, UUID.fromString(actor.id)); it.setObject(4, UUID.fromString(actor.id))
            it.executeQuery().use { rows -> if (rows.next()) rows.project() else null }
        } ?: throw LocatorException(404, "project_not_found", "Project not found")
        return project
    }

    internal suspend fun <T> transaction(block: (Connection) -> T): T = withContext(dispatcher) {
        try {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    connection.createStatement().use { it.execute("SET LOCAL statement_timeout = '5s'") }
                    block(connection).also { connection.commit() }
                } catch (failure: Throwable) {
                    connection.rollback()
                    throw failure
                }
            }
        } catch (failure: SQLException) {
            if (failure.sqlState == "23505") throw LocatorException(409, "project_conflict", "Project name or slug is already reserved")
            throw failure
        }
    }

    private fun ResultSet.project() = Project(
        getString("id"), getString("project"), getString("slug"), getString("identity_id"),
        ProjectStatus.valueOf(getString("status").uppercase()), getTimestamp("created_at").toInstant().toString(),
        getTimestamp("reviewed_at")?.toInstant()?.toString(), getString("reviewed_by"), getInt("revision"),
        ProjectRouting(RoutingMode.valueOf(getString("routing_mode").uppercase()), getString("routing_target")),
        getString("description"), ProjectReadiness.valueOf(getString("readiness").uppercase()),
    )

    companion object { private val ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001") }
}
