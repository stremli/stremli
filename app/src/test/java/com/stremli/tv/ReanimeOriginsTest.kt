package com.stremli.tv

import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ReanimeOriginsTest {
    private var record: String? = null
    private var now = 0L
    private var checks = 0
    private val saved = mutableListOf<String>()
    private val visits = mutableListOf<String>()
    private val origins = ReanimeOrigins("reanime.to", { checks++; record }, { saved.add(it) }, { now })

    @Test fun permanentMoveIsRememberedOnlyAfterDiscoverySucceeds() {
        fun fetch(host: String): List<String> {
            visits.add(host)
            if (host == "reanime.to") throw ReanimeMove("reanime.cz")
            return listOf("HD-1")
        }
        assertEquals(listOf("HD-1"), origins.lookup(::fetch))
        assertEquals(listOf("HD-1"), origins.lookup(::fetch))
        assertEquals(listOf("reanime.to", "reanime.cz", "reanime.cz"), visits)
        assertEquals(listOf("reanime.cz"), saved)
        assertEquals(0, checks)
    }

    @Test fun deadDomainUsesPublishedHostEvenAfterNegativeCacheExpires() {
        fun fetch(host: String): List<String> {
            visits.add(host)
            if (host == "reanime.to") throw IOException("Domain no longer exists")
            return listOf("HD-1")
        }
        assertThrows(IOException::class.java) { origins.lookup(::fetch) }
        record = "newbrand.tv"
        now += 14 * 60_000L
        assertThrows(IOException::class.java) { origins.lookup(::fetch) }
        assertEquals(1, checks)
        now += 60_000L
        assertEquals(listOf("HD-1"), origins.lookup(::fetch))
        origins.lookup(::fetch)
        assertEquals(listOf("newbrand.tv"), saved)
        assertEquals(listOf("reanime.to", "reanime.to", "reanime.to", "newbrand.tv", "newbrand.tv"), visits)
        assertEquals(2, checks)
    }

    @Test fun repeatedCyclesStopAndBogusRecordCannotBecomeAnOrigin() {
        record = "127.0.0.1"
        assertThrows(IOException::class.java) {
            origins.lookup { host ->
                visits.add(host)
                throw ReanimeMove(if (host == "reanime.to") "reanime.cz" else "reanime.to")
            }
        }
        assertEquals(listOf("reanime.to", "reanime.cz"), visits)
        assertTrue(saved.isEmpty())
    }

    @Test fun tooManyRedirectsStopAfterTwoHops() {
        val chain = mapOf("reanime.to" to "reanime.cz", "reanime.cz" to "reanime.tv", "reanime.tv" to "reanime.jp")
        assertThrows(IOException::class.java) {
            origins.lookup { host -> visits.add(host); throw ReanimeMove(chain.getValue(host)) }
        }
        assertEquals(listOf("reanime.to", "reanime.cz", "reanime.tv"), visits)
        assertTrue(saved.isEmpty())
    }

    @Test fun emptyEpisodeListingDoesNotTriggerRecovery() {
        assertTrue(origins.lookup { emptyList<String>() }.isEmpty())
        assertEquals(0, checks)
    }

    @Test fun migrationRequiresTheSameHttpsEpisodeLookupOnASameBrandHost() {
        val suffix = "/api/flix/21/1?mal=21"
        val old = "https://reanime.to$suffix".toHttpUrl()
        assertEquals("reanime.cz", migrationHost(old, "https://reanime.cz$suffix".toHttpUrl()))
        for (prefix in listOf("http://reanime.cz", "https://other.cz", "https://reanime.cz.example.com",
            "https://reanime.local", "https://reanime.cz:8443", "https://user:pass@reanime.cz")) {
            assertNull(prefix, migrationHost(old, "$prefix$suffix".toHttpUrl()))
        }
        for (path in listOf("/api/flix/21/2?mal=21", "/api/flix/21/1?mal=22", "$suffix#player")) {
            assertNull(path, migrationHost(old, "https://reanime.cz$path".toHttpUrl()))
        }
        for (host in listOf("localhost", "app.local", "app.home.arpa", "app.lan", "0.0.0.0", "::1",
            "user@newbrand.tv", "newbrand.tv/", "https://newbrand.tv", "app..tv")) {
            assertFalse(host, validPublishedHost(host))
        }
    }
}
