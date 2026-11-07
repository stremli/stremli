package com.stremli.tv

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** A viewer profile. Everything personal (My List, watch history, resume points, searches) is per profile. */
data class Profile(val id: String, val name: String, val color: Int, val shape: Int)

/**
 * Profiles, kept on this device only. The list lives in `stremli_profiles`; each profile's own data
 * lives in its own preferences file, so profiles never see each other's history. The first profile
 * uses the original `stremli` file, so watch history from before profiles existed is kept.
 * The catalogue cache and provider settings stay shared (they are not personal).
 */
object Profiles {
    const val MAX = 5
    private const val MAIN = "main"
    private const val STORE = "stremli_profiles"

    @Volatile private var current: Profile? = null

    private fun store(context: Context) = context.applicationContext.getSharedPreferences(STORE, 0)

    fun all(context: Context): List<Profile> {
        val raw = store(context).getString("list", null)
        val list = raw?.let { runCatching { JSONArray(it) }.getOrNull() }?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> Profile(o.optString("id"), o.optString("name"), o.optInt("color"), o.optInt("shape")) } }
        }.orEmpty().filter { it.id.isNotBlank() }
        if (list.isNotEmpty()) return list
        val first = Profile(MAIN, "Me", 0, 0)
        save(context, listOf(first))
        return listOf(first)
    }

    private fun save(context: Context, list: List<Profile>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color).put("shape", it.shape)) }
        store(context).edit().putString("list", a.toString()).apply()
    }

    /** The profile last used on this device (the first one if none). */
    fun lastUsed(context: Context): Profile {
        val all = all(context)
        val id = store(context).getString("current", null)
        return all.firstOrNull { it.id == id } ?: all.first()
    }

    fun select(context: Context, profile: Profile) {
        current = profile
        store(context).edit().putString("current", profile.id).apply()
    }

    /** Adds or updates [profile] (a new one gets an id). */
    fun put(context: Context, profile: Profile): Profile {
        val all = all(context)
        val saved = if (profile.id.isBlank()) profile.copy(id = "p" + System.currentTimeMillis().toString(36)) else profile
        save(context, if (all.any { it.id == saved.id }) all.map { if (it.id == saved.id) saved else it } else all + saved)
        if (current?.id == saved.id) current = saved
        return saved
    }

    /** Removes a profile and its data; the last remaining profile cannot be removed. */
    fun remove(context: Context, profile: Profile) {
        val all = all(context)
        if (all.size <= 1) return
        save(context, all.filterNot { it.id == profile.id })
        setPin(context, profile, null)
        val prefs = fileFor(profile.id)
        if (profile.id == MAIN) context.getSharedPreferences(prefs, 0).edit().clear().apply()
        else context.deleteSharedPreferences(prefs)
        // The profile's library and its synced accounts (the accounts live in the prefs above).
        TrackerSync.forget(profile.id)
        UserSettings.forget(profile.id)
        Library.delete(context, profile.id)
        if (current?.id == profile.id) current = null
    }

    // ---- PINs ----------------------------------------------------------------------------
    // Only salted SHA-256 hashes are stored, never the PINs. The master PIN is the admin's: it is
    // set at build time (BuildConfig.MASTER_PIN_HASH) and resets any profile's forgotten PIN.

    private fun hash(context: Context, scope: String, pin: String): String {
        val store = store(context)
        val salt = store.getString("salt", null) ?: java.util.UUID.randomUUID().toString().also { store.edit().putString("salt", it).apply() }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest("$salt:$scope:$pin".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun hasPin(context: Context, profile: Profile) = store(context).contains("pin:${profile.id}")
    fun checkPin(context: Context, profile: Profile, pin: String) = store(context).getString("pin:${profile.id}", null) == hash(context, profile.id, pin)
    /** Sets the profile's PIN, or removes it when [pin] is null. */
    fun setPin(context: Context, profile: Profile, pin: String?) {
        store(context).edit().apply { if (pin == null) remove("pin:${profile.id}") else putString("pin:${profile.id}", hash(context, profile.id, pin)) }.apply()
    }

    /** True when this build has an admin master PIN. */
    fun hasMaster() = BuildConfig.MASTER_PIN_HASH.isNotEmpty()

    /** Checks [pin] against the admin master PIN built into the app (same hashing as app/build.gradle.kts). */
    fun checkMaster(pin: String): Boolean {
        if (!hasMaster()) return false
        var digest = "stremli-master-v1:$pin".toByteArray(Charsets.UTF_8)
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        repeat(20_000) { digest = sha.digest(digest) }
        return digest.joinToString("") { "%02x".format(it) } == BuildConfig.MASTER_PIN_HASH
    }

    private fun fileFor(id: String) = if (id == MAIN) "stremli" else "stremli_profile_$id"

    /** A given profile's own data (for work that outlives the current screen, like syncing). */
    fun prefsFor(context: Context, profileId: String): SharedPreferences = context.applicationContext.getSharedPreferences(fileFor(profileId), 0)

    /** The id of the profile in use. */
    fun currentId(context: Context): String = (current ?: lastUsed(context).also { current = it }).id

    /** The current profile's own data. */
    fun prefs(context: Context): SharedPreferences {
        val profile = current ?: lastUsed(context).also { current = it }
        return context.applicationContext.getSharedPreferences(fileFor(profile.id), 0)
    }
}
