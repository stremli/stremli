package com.stremli.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A real event signed by nostr-tools with a throwaway key. */
class NostrVerifyTest {
    private val pubkey = "0a3fa1f6d4c92480c898b7f2110a3a5c15e7a6e13fa7750a4e4659d5ef63eb81"
    private val otherKey = "3839ee53deed9970d4c0603d48921d73c6b359b74eb82c3cd03adffe2458b601"
    private val id = "022131e83d4d2df0738b8d723a6350dcbc0acd2de4cfe6ee2761c97113aa0b51"
    private val signature = "af96507efe61f1bcd97d5779ce6b9b3b0c9066f4533f3e67c81ca62a44691e4034188b8830850e9362e983da5281756c4df9117e9d00a504da144e024615b478"
    private val createdAt = 1794000000L
    private val kind = 30078
    private val tags = listOf(listOf("d", "stremli-update"), listOf("t", "a\"b\\c"))
    private val content = "{\"repo\":\"someone/stremli\",\"version\":\"1.2.3\",\"notes\":\"Line one\\nLine \\\"two\\\"\\ttabbed é ☃\"}"

    @Test fun idMatchesSerialization() = assertEquals(id, NostrVerify.eventId(pubkey, createdAt, kind, tags, content))

    @Test fun acceptsRealSignature() = assertTrue(NostrVerify.verify(id, pubkey, signature))

    @Test fun changedContentGivesAnotherId() {
        val changed = NostrVerify.eventId(pubkey, createdAt, kind, tags, content.replace("someone", "attacker"))
        assertFalse(changed == id)
        assertFalse(NostrVerify.verify(changed, pubkey, signature))
    }

    @Test fun rejectsAnotherKey() = assertFalse(NostrVerify.verify(id, otherKey, signature))

    @Test fun rejectsChangedSignature() {
        val flipped = (if (signature.last() == '0') '1' else '0')
        assertFalse(NostrVerify.verify(id, pubkey, signature.dropLast(1) + flipped))
    }

    @Test fun rejectsMalformedInput() {
        assertFalse(NostrVerify.verify("xyz", pubkey, signature))
        assertFalse(NostrVerify.verify(id, pubkey, "00"))
        assertFalse(NostrVerify.verify(id, "f".repeat(64), signature))
        assertFalse(NostrVerify.verify(id, pubkey, "f".repeat(128)))
    }
}
