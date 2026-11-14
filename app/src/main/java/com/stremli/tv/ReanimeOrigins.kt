package com.stremli.tv

import okhttp3.HttpUrl
import java.io.IOException

internal class ReanimeMove(val host: String) : IOException("Re:Anime moved to another host")

private val dnsLabel = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
private val dnsSuffix = Regex("[a-z]{2,63}")
private val reservedSuffixes = setOf("localhost", "localdomain", "local", "internal", "intranet", "lan", "home", "corp", "test", "example", "invalid", "onion", "arpa")

/** A published record contains a DNS hostname only, never a URL, IP literal or local name. */
internal fun validPublishedHost(host: String): Boolean {
    if (host.length !in 4..253) return false
    val labels = host.split('.')
    val suffix = labels.last()
    return labels.size >= 2 && labels.all(dnsLabel::matches) &&
        dnsSuffix.matches(suffix) && suffix !in reservedSuffixes
}

/** Only a same-brand move directly under a TLD is automatic. Other names require a published record. */
internal fun validMigrationHost(host: String): Boolean =
    validPublishedHost(host) && host.startsWith("reanime.") && host.count { it == '.' } == 1

internal fun migrationHost(original: HttpUrl, target: HttpUrl): String? {
    if (original.scheme != "https" || target.scheme != "https" || target.port != 443 || target.username.isNotEmpty() ||
        target.password.isNotEmpty() || target.fragment != null || target.host == original.host ||
        target.encodedPath != original.encodedPath || target.encodedQuery != original.encodedQuery
    ) return null
    return target.host.takeIf(::validMigrationHost)
}

internal class ReanimeOrigins(
    initialHost: String,
    private val readPublishedHost: () -> String?,
    private val onWorkingHost: (String) -> Unit,
    private val nowMillis: () -> Long,
) {
    @Volatile private var workingHost = initialHost.also { require(validPublishedHost(it)) }
    private val recordLock = Any()
    private var checkedAt: Long? = null
    private var publishedHost: String? = null

    /** An empty, valid episode listing counts as a successful request. Only I/O failures try another origin. */
    fun <T> lookup(fetch: (String) -> T): T {
        val startingHost = workingHost
        var host = startingHost
        var moves = 0
        var triedRecord = false
        val attempted = mutableSetOf<String>()
        while (true) {
            attempted.add(host)
            val listing = try {
                fetch(host)
            } catch (error: IOException) {
                if (error is ReanimeMove && moves < 2 && validMigrationHost(error.host) && error.host !in attempted) {
                    host = error.host
                    moves++
                    continue
                }
                if (triedRecord) throw error
                triedRecord = true
                val replacement = record()
                if (replacement == null || replacement in attempted) throw error
                host = replacement
                continue
            }
            if (host != startingHost) remember(startingHost, host)
            return listing
        }
    }

    @Synchronized
    private fun remember(startingHost: String, host: String) {
        if (workingHost != startingHost) return
        workingHost = host
        onWorkingHost(host)
    }

    // Only failed discovery requests contend for this lock. Cache missing records and transport failures too.
    private fun record(): String? = synchronized(recordLock) {
        val now = nowMillis()
        if (checkedAt?.let { now - it in 0L until 15 * 60_000L } != true) {
            publishedHost = try {
                readPublishedHost()?.takeIf(::validPublishedHost)
            } catch (_: IOException) {
                null
            }
            checkedAt = now
        }
        publishedHost
    }
}
