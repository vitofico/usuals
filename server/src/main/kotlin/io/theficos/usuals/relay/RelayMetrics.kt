package io.theficos.usuals.relay

import com.sun.net.httpserver.HttpServer
import com.zaxxer.hikari.HikariDataSource
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

/** Aggregate operator counters only. This listener must never be routed through public ingress. */
class RelayMetrics(private val db: Database) {
    val active = AtomicLong()
    /** Idle feed waits only, after the initial read releases its request slot. */
    val waiting = AtomicLong()
    private val requests = AtomicLong()
    private val clientErrors = AtomicLong()
    private val serverErrors = AtomicLong()
    private val elapsed = AtomicLong()
    private val bounds = listOf(10L, 50L, 100L, 500L, 1000L, 2000L, 15000L)
    private val buckets = bounds.map { AtomicLong() }
    fun finished(status: Int, nanos: Long) {
        requests.incrementAndGet()
        if (status in 400..499) clientErrors.incrementAndGet()
        if (status >= 500) serverErrors.incrementAndGet()
        elapsed.addAndGet(nanos)
        bounds.forEachIndexed { index, ms -> if (nanos <= ms * 1_000_000) buckets[index].incrementAndGet() }
    }
    fun render(): String = buildString {
        appendLine("usuals_requests_total ${requests.get()}")
        appendLine("usuals_request_client_errors_total ${clientErrors.get()}")
        appendLine("usuals_request_server_errors_total ${serverErrors.get()}")
        appendLine("usuals_requests_active ${active.get()}")
        appendLine("usuals_requests_waiting ${waiting.get()}")
        bounds.forEachIndexed { index, ms -> appendLine("usuals_request_duration_seconds_bucket{le=\"${ms / 1000.0}\"} ${buckets[index].get()}") }
        appendLine("usuals_request_duration_seconds_bucket{le=\"+Inf\"} ${requests.get()}")
        appendLine("usuals_request_duration_seconds_count ${requests.get()}")
        appendLine("usuals_request_duration_seconds_sum ${elapsed.get() / 1_000_000_000.0}")
        appendLine("usuals_jvm_heap_bytes ${ManagementFactory.getMemoryMXBean().heapMemoryUsage.used}")
        (db.source as? HikariDataSource)?.hikariPoolMXBean?.let {
            appendLine("usuals_jdbc_active ${it.activeConnections}")
            appendLine("usuals_jdbc_idle ${it.idleConnections}")
            appendLine("usuals_jdbc_waiting ${it.threadsAwaitingConnection}")
        }
        val storage = runCatching { db.transaction { c ->
            c.query("SELECT pg_database_size(current_database()),coalesce((SELECT sum(octet_length(projection)) FROM list),0)") { it.getLong(1) to it.getLong(2) }.single()
        } }.getOrNull()
        appendLine("usuals_storage_probe_ok ${if (storage == null) 0 else 1}")
        storage?.let { appendLine("usuals_database_bytes ${it.first}"); appendLine("usuals_list_bytes ${it.second}") }
    }
    fun listen(host: String, port: Int): HttpServer = HttpServer.create(InetSocketAddress(host, port), 4).also { server ->
        // HttpServer's default single dispatcher bounds scrapes without another worker pool.
        server.createContext("/") { exchange ->
            exchange.use {
                val ok = it.requestMethod == "GET" && it.requestURI.toString() == "/metrics"
                val bytes = (if (ok) render() else "unavailable\n").toByteArray()
                it.responseHeaders.set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                it.sendResponseHeaders(if (ok) 200 else 404, bytes.size.toLong())
                it.responseBody.use { output -> output.write(bytes) }
            }
        }
        server.start()
    }
}
