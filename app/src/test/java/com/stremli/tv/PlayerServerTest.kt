package com.stremli.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerServerTest {
    private val sub = PlayerServer("megaplay", "MegaPlay", "Subtitled", version = "sub")
    private val dub = PlayerServer("megaplay", "MegaPlay · dub", "Dubbed", version = "dub")
    private val gotaku = PlayerServer("gotaku", "Gotaku", "Subtitled")

    @Test fun switchingMegaPlayCutsKeepsEachCutsOwnResumePoint() {
        assertNotEquals(sub.key, dub.key)
        assertNull(sub.handoffResumeKey(dub, 12, 5))
        assertNull(dub.handoffResumeKey(sub, 12, 5))
        assertEquals(sub.key, sub.copy(detail = "720p").key)
    }

    @Test fun crossProviderSwitchCarriesPositionToTheChosenCut() {
        assertEquals("megaplay:12:5:sub", sub.handoffResumeKey(gotaku, 12, 5))
        assertEquals("megaplay:12:5:dub", dub.handoffResumeKey(gotaku, 12, 5))
        assertEquals("gotaku:12:5", gotaku.handoffResumeKey(dub, 12, 5))
    }

    @Test fun reanimeHandoffUsesTheExistingNamedServerHistory() {
        val host = ReanimeServer("Server A", "dub", "fixture", "soft", "fixture")
        val option = PlayerServer("reanime", "Server A", "Dubbed", server = host)
        assertEquals("reanime:12:5:Server A:dub", option.handoffResumeKey(sub, 12, 5))
        assertNull(option.handoffResumeKey(option.copy(detail = "Soft subtitles"), 12, 5))
    }
}
