package com.stremli.tv

import android.content.SharedPreferences

/** A ready episode with no saved seek point still belongs in Continue Watching, even after a quick exit. */
internal fun SharedPreferences.recordPlaybackStart(
    animeId: Int,
    episode: Int,
    source: String,
    variant: String?,
    resumeKey: String?
) {
    val editor = edit()
        .putInt("last:$animeId", episode)
        .putLong("lastAt:$animeId", System.currentTimeMillis())
        .putString("lastSource:$animeId", source)
        .putString("lastServer:$animeId", variant)
    // Completion removes this key. Zero means the first seconds of the episode, and keeps
    // an existing cut-specific seek point intact when the same episode is opened again.
    if (resumeKey != null && !contains("resume:$resumeKey")) editor.putLong("resume:$resumeKey", 0L)
    editor.apply()
}
