package io.theficos.usuals.relay

import io.theficos.usuals.sync.Wire
import java.sql.Connection

/** Durable rate limits shared by every route, and the clock the list service records times with. */
class Auth(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    internal fun rate(c: Connection, key: String, limit: Int) {
        val window = clock() / 60000
        c.execute("DELETE FROM rate_bucket WHERE minute < ?", window)
        expect(c.query("SELECT count(*) FROM rate_bucket") { it.getLong(1) }.single() < 20000, "rate_limited", 429)
        val count = c.query("INSERT INTO rate_bucket VALUES (?,?,1) ON CONFLICT(key) DO UPDATE SET count=CASE WHEN rate_bucket.minute=EXCLUDED.minute THEN rate_bucket.count+1 ELSE 1 END, minute=EXCLUDED.minute RETURNING count", Wire.decode(Wire.hash(key.toByteArray()), 32), window) { it.getInt(1) }.single()
        expect(count <= limit, "rate_limited", 429)
    }
    fun limit(key: String, count: Int) = db.transaction { rate(it, key, count) }
    internal fun now() = clock()
}
