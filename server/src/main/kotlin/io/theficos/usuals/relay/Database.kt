package io.theficos.usuals.relay

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.theficos.usuals.sync.Wire
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

class Database(val source: DataSource) {
    private fun migrations() = listOf("001_relay.sql", "002_web_identity.sql", "003_local_identity.sql", "004_web_household.sql", "005_connection_ai.sql", "006_pairing_v2.sql").mapIndexed { index, name ->
        (index + 1) to checkNotNull(javaClass.getResourceAsStream("/db/$name")).use { it.readBytes() }
    }
    fun <T> transaction(block: (Connection) -> T): T = source.connection.use { c ->
        c.autoCommit = false
        c.createStatement().use { it.execute("SET LOCAL statement_timeout = '5s'"); it.execute("SET LOCAL lock_timeout = '2s'") }
        try { block(c).also { c.commit() } } catch (e: Throwable) { c.rollback(); throw e }
    }
    fun <T> readSnapshot(block: (Connection) -> T): T = source.connection.use { c ->
        c.autoCommit = false; c.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ; c.isReadOnly = true
        c.createStatement().use { it.execute("SET LOCAL statement_timeout = '15s'"); it.execute("SET LOCAL lock_timeout = '2s'") }
        try { block(c).also { c.commit() } } catch (e: Throwable) { c.rollback(); throw e }
    }
    fun checkSchema() = readSnapshot { c ->
        val expected = migrations().map { (version, sql) -> version to Wire.hash(sql) }
        val versions = c.query("SELECT version,checksum FROM schema_migration ORDER BY version") { it.getInt(1) to it.getString(2) }
        check(versions == expected) { "migration_mismatch" }
    }
    fun migrate() = transaction { c ->
        c.query("SELECT pg_advisory_xact_lock(740520261016)") { Unit }
        c.execute("CREATE TABLE IF NOT EXISTS schema_migration (version integer PRIMARY KEY, checksum text NOT NULL)")
        val migrations = migrations()
        val versions = c.query("SELECT version,checksum FROM schema_migration ORDER BY version") { it.getInt(1) to it.getString(2) }
        check(versions == migrations.take(versions.size).map { (version, sql) -> version to Wire.hash(sql) }) { "migration_mismatch" }
        for ((version, sql) in migrations.drop(versions.size)) {
            c.createStatement().use { it.execute(sql.toString(Charsets.UTF_8)) }
            c.execute("INSERT INTO schema_migration VALUES (?,?)", version, Wire.hash(sql))
        }
    }
    fun fence(serverId: String, fence: String) = transaction { c ->
        require(fence.isNotBlank())
        c.query("SELECT pg_advisory_xact_lock(740520261017)") { Unit }
        val existing = c.query("SELECT server_id,fence FROM relay_meta WHERE singleton=1") { Unit }
        if (existing.isEmpty()) {
            val tables = listOf("household", "principal", "bootstrap_token", "auth_challenge", "auth_session", "invitation", "join_request", "shared_list", "manifest", "operation", "snapshot_candidate", "published_snapshot", "key_package", "rate_bucket", "web_user", "web_login", "web_session", "web_device", "web_device_session", "local_credential", "local_login", "web_created_household", "connection_ticket", "native_session", "ai_preference", "ai_usage", "ai_vision_check")
            val occupied = tables.joinToString(" OR ") { "EXISTS(SELECT 1 FROM $it)" }
            check(c.query("SELECT $occupied") { it.getBoolean(1) }.single().not()) { "missing_restore_metadata" }
            c.execute("INSERT INTO relay_meta VALUES (1,?,?)", Wire.decode(serverId, 32), fence)
        }
        val metadata = c.query("SELECT server_id,fence FROM relay_meta WHERE singleton=1 FOR UPDATE") { Wire.encode(it.getBytes(1)) to it.getString(2) }.single()
        check(metadata.first == serverId) { "identity_mismatch" }
        if (metadata.second != fence) {
            c.execute("UPDATE household SET frozen=true")
            c.execute("UPDATE auth_session SET revoked=true")
            c.execute("UPDATE web_session SET revoked=true")
            c.execute("DELETE FROM native_session")
            c.execute("DELETE FROM connection_ticket")
            c.execute("DELETE FROM ai_vision_check")
            c.execute("DELETE FROM web_login")
            c.execute("DELETE FROM local_login")
            c.execute("UPDATE local_credential SET password_hash=NULL,salt=NULL,setup_hash=NULL,setup_expires=NULL")
            c.execute("DELETE FROM bootstrap_token")
            c.execute("DELETE FROM auth_challenge")
            c.execute("UPDATE invitation SET consumed=true")
            c.execute("UPDATE join_request SET denied=true")
            c.execute("DELETE FROM snapshot_candidate")
            c.execute("UPDATE relay_meta SET fence=? WHERE singleton=1", fence)
        }
    }
    fun ready() = runCatching { transaction { it.query("SELECT 1") { row -> row.getInt(1) }.single() == 1 } }.getOrDefault(false)
    companion object {
        fun pool(url: String, user: String, password: String) = HikariDataSource(HikariConfig().apply {
            jdbcUrl = url; username = user; this.password = password
            maximumPoolSize = 4; minimumIdle = 1; connectionTimeout = 2000; validationTimeout = 1000
            isAutoCommit = false; poolName = "relay"
        })
    }
}
internal fun Connection.execute(sql: String, vararg values: Any?): Int = prepareStatement(sql).use { q ->
    values.forEachIndexed { i, value -> q.setObject(i + 1, value) }; q.executeUpdate()
}
internal fun <T> Connection.query(sql: String, vararg values: Any?, read: (ResultSet) -> T): List<T> = prepareStatement(sql).use { q ->
    values.forEachIndexed { i, value -> q.setObject(i + 1, value) }
    q.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
}
internal fun id(text: String): UUID = Wire.uuid(text)
