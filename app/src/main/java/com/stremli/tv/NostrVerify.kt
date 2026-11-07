package com.stremli.tv

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Checks that a Nostr event is whole and was signed by a given key: its NIP-01 id and BIP-340
 * signature on secp256k1. This only verifies, so the app holds no private key. It is plain Kotlin
 * with no Android classes, so unit tests can run it.
 */
object NostrVerify {
    private val P = BigInteger("fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f", 16)
    private val N = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
    private val G = Point(
        BigInteger("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", 16),
        BigInteger("483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8", 16)
    )
    private val THREE = BigInteger.valueOf(3)
    private val SEVEN = BigInteger.valueOf(7)
    private val HEX64 = Regex("[0-9a-f]{64}")
    private val HEX128 = Regex("[0-9a-f]{128}")

    private class Point(val x: BigInteger, val y: BigInteger)

    /** The event's id: the SHA-256 of its NIP-01 serialization. */
    fun eventId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
        val text = buildString {
            append("[0,\"").append(pubkey).append("\",").append(createdAt).append(',').append(kind).append(",[")
            tags.forEachIndexed { i, tag ->
                if (i > 0) append(',')
                append('[')
                tag.forEachIndexed { j, value -> if (j > 0) append(','); quote(value) }
                append(']')
            }
            append("],")
            quote(content)
            append(']')
        }
        return sha256(text.toByteArray(Charsets.UTF_8)).toHex()
    }

    /** True when [signature] is [pubkey]'s BIP-340 signature of the event id [id]. All three are lowercase hex. */
    fun verify(id: String, pubkey: String, signature: String): Boolean {
        if (!HEX64.matches(id) || !HEX64.matches(pubkey) || !HEX128.matches(signature)) return false
        val r = BigInteger(signature.substring(0, 64), 16)
        val s = BigInteger(signature.substring(64), 16)
        if (r >= P || s >= N) return false
        val public = liftX(BigInteger(pubkey, 16)) ?: return false
        val tag = sha256("BIP0340/challenge".toByteArray(Charsets.UTF_8))
        val e = BigInteger(1, sha256(tag + tag + signature.substring(0, 64).fromHex() + pubkey.fromHex() + id.fromHex())).mod(N)
        val point = add(multiply(G, s), multiply(public, N.subtract(e))) ?: return false
        return !point.y.testBit(0) && point.x == r
    }

    private fun liftX(x: BigInteger): Point? {
        if (x >= P) return null
        val c = x.modPow(THREE, P).add(SEVEN).mod(P)
        val y = c.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        if (y.modPow(BigInteger.TWO, P) != c) return null
        return Point(x, if (y.testBit(0)) P.subtract(y) else y)
    }

    private fun add(a: Point?, b: Point?): Point? {
        if (a == null) return b
        if (b == null) return a
        val slope = if (a.x == b.x) {
            if (a.y != b.y || a.y.signum() == 0) return null
            a.x.multiply(a.x).multiply(THREE).multiply(a.y.shiftLeft(1).modInverse(P))
        } else {
            b.y.subtract(a.y).multiply(b.x.subtract(a.x).modInverse(P))
        }.mod(P)
        val x = slope.multiply(slope).subtract(a.x).subtract(b.x).mod(P)
        return Point(x, slope.multiply(a.x.subtract(x)).subtract(a.y).mod(P))
    }

    private fun multiply(point: Point, scalar: BigInteger): Point? {
        var result: Point? = null
        var doubled: Point? = point
        for (i in 0 until scalar.bitLength()) {
            if (scalar.testBit(i)) result = add(result, doubled)
            doubled = add(doubled, doubled)
        }
        return result
    }

    /** Writes [value] the way JSON.stringify does, which is what NIP-01 specifies. */
    private fun StringBuilder.quote(value: String) {
        append('"')
        for (ch in value) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch == '\b' -> append("\\b")
            ch == '\u000C' -> append("\\f")
            ch < ' ' -> append("\\u").append(ch.code.toString(16).padStart(4, '0'))
            else -> append(ch)
        }
        append('"')
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.fromHex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
