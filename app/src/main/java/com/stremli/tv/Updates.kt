package com.stremli.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A newer version of the app, from the download page or the Nostr backup. */
class AppUpdate(val code: Int, val version: String, val apkUrl: String, val sha256: String, val notes: String)

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val update: AppUpdate) : UpdateStatus
    data class Downloading(val update: AppUpdate, val percent: Int) : UpdateStatus
    data class NeedsPermission(val update: AppUpdate) : UpdateStatus
    data class Failed(val message: String, val update: AppUpdate?) : UpdateStatus
}

val UpdateStatus.update: AppUpdate? get() = when (this) {
    is UpdateStatus.Available -> update
    is UpdateStatus.Downloading -> update
    is UpdateStatus.NeedsPermission -> update
    is UpdateStatus.Failed -> update
    else -> null
}

/**
 * Finds, downloads and installs new versions. They come from the latest release of a GitHub repo.
 * If that repo goes away, the Nostr pointer below names the next place: it is a signed event, and
 * only one signed by [NOSTR_PUBKEY] is believed.
 *
 * Whatever the source says, Android only installs an update signed with the same key as the
 * installed app, and this class checks that first so a bad file gives a clear message.
 */
object Updates {
    private const val DEFAULT_REPO = "stremli/stremli"
    private const val NOSTR_PUBKEY = "55c37a05f019663c951ec0298fd205a060471edc68dd1b0512043195f85db49b"
    const val NOSTR_NPUB = "npub12hph5p0sr9nre9g7cq5cl5s95psyw8kudrw3kpgjqscet7zakjds0jn9te"
    private const val NOSTR_KIND = 30078
    private const val NOSTR_NAME = "stremli-update"
    private val NOSTR_RELAYS = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom")
    private const val NOSTR_WAIT_SECONDS = 6L
    private const val MAX_EVENT_CHARS = 16 * 1024
    private const val MAX_MANIFEST_BYTES = 16 * 1024
    private const val MAX_APK_BYTES = 150L * 1024 * 1024
    private const val CHECK_EVERY_MS = 20L * 60 * 60 * 1000
    private val REPO_NAME = Regex("""[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}""")
    private val SHA256 = Regex("[0-9a-f]{64}")

    var status by mutableStateOf<UpdateStatus>(UpdateStatus.Idle)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("stremli-device", 0)

    fun autoCheck(context: Context) = prefs(context).getBoolean("updateAuto", true)
    fun setAutoCheck(context: Context, on: Boolean) = prefs(context).edit().putBoolean("updateAuto", on).apply()

    /** The version the update prompt was closed for, so it shows once per version. */
    fun dismissedCode(context: Context) = prefs(context).getInt("updateDismissed", 0)
    fun dismiss(context: Context, code: Int) = prefs(context).edit().putInt("updateDismissed", code).apply()

    /** Checks at most about once a day, quietly. Called as the app starts and when Home opens. */
    fun checkIfDue(context: Context) {
        val app = context.applicationContext
        if (!autoCheck(app) || status != UpdateStatus.Idle) return
        val now = System.currentTimeMillis()
        if (now - prefs(app).getLong("updateChecked", 0L) < CHECK_EVERY_MS) return
        scope.launch {
            val found = runCatching { withContext(Dispatchers.IO) { find(app) } }
            found.onSuccess { update ->
                prefs(app).edit().putLong("updateChecked", now).apply()
                if (update != null) status = UpdateStatus.Available(update)
            }.onFailure { Log.i("Updates", "check failed: ${it.javaClass.simpleName}") }
        }
    }

    /** The check behind Settings' button, which reports what it found. */
    fun check(context: Context) {
        val app = context.applicationContext
        if (status == UpdateStatus.Checking || status is UpdateStatus.Downloading) return
        status = UpdateStatus.Checking
        scope.launch {
            status = try {
                val update = withContext(Dispatchers.IO) { find(app) }
                prefs(app).edit().putLong("updateChecked", System.currentTimeMillis()).apply()
                if (update != null) UpdateStatus.Available(update) else UpdateStatus.UpToDate
            } catch (e: IOException) {
                Log.i("Updates", "check failed: ${e.javaClass.simpleName}")
                UpdateStatus.Failed("Couldn't check for updates. Check your connection.", null)
            }
        }
    }

    /** Downloads [update], checks it, and hands it to Android's installer. */
    fun install(context: Context, update: AppUpdate) {
        val app = context.applicationContext
        if (status is UpdateStatus.Downloading) return
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            status = UpdateStatus.NeedsPermission(update)
            runCatching { open(context, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))) }
            return
        }
        status = UpdateStatus.Downloading(update, 0)
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { download(app, update) { percent -> scope.launch { status = UpdateStatus.Downloading(update, percent) } } }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
                open(context, Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                status = UpdateStatus.Available(update)
            } catch (e: IOException) {
                Log.i("Updates", "download failed: ${e.message}")
                status = UpdateStatus.Failed(e.message ?: "Couldn't download the update.", update)
            } catch (e: android.content.ActivityNotFoundException) {
                status = UpdateStatus.Failed("This TV can't open the installer.", update)
            }
        }
    }

    /** Opened from the activity, so Back returns to the app and not to the TV's home screen. */
    private fun open(context: Context, intent: Intent) {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Removes a downloaded update once it has been installed. */
    fun cleanUp(context: Context) {
        scope.launch(Dispatchers.IO) { File(context.applicationContext.cacheDir, "updates").deleteRecursively() }
    }

    // ---- Finding an update -------------------------------------------------------------------

    /** The newest version offered anywhere, if it is newer than this one. Throws when nothing answered. */
    private fun find(context: Context): AppUpdate? {
        val pointer = runCatching { fromNostr(context) }.getOrNull()
        val repo = prefs(context).getString("updateRepo", null) ?: DEFAULT_REPO
        val listed = runCatching { fromRepo(repo) }
        if (pointer == null && listed.isFailure) throw IOException("No update source answered")
        return listOfNotNull(pointer, listed.getOrNull()).maxByOrNull { it.code }?.takeIf { it.code > BuildConfig.VERSION_CODE }
    }

    private fun fromRepo(repo: String): AppUpdate {
        val request = Request.Builder().url("https://github.com/$repo/releases/latest/download/update.json").header("User-Agent", "Stremli").build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Update list unavailable: ${response.code}")
            return parse(JSONObject(readSmall(response)))
        }
    }

    private fun readSmall(response: Response): String {
        val body = response.body ?: throw IOException("Empty response")
        if (body.contentLength() > MAX_MANIFEST_BYTES) throw IOException("Update list too large")
        val bytes = body.byteStream().readNBytesCompat(MAX_MANIFEST_BYTES + 1)
        if (bytes.size > MAX_MANIFEST_BYTES) throw IOException("Update list too large")
        return bytes.toString(Charsets.UTF_8)
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun parse(json: JSONObject): AppUpdate {
        val code = json.getInt("code")
        val version = json.getString("version").take(32)
        val apk = json.getString("apk")
        val sha256 = json.getString("sha256").lowercase()
        if (code <= 0 || !apk.startsWith("https://") || !SHA256.matches(sha256)) throw IOException("Invalid update list")
        return AppUpdate(code, version, apk, sha256, json.optString("notes").take(500))
    }

    /** The newest valid pointer on the relays. A newer one than last time also moves the repo checked. */
    private fun fromNostr(context: Context): AppUpdate? {
        val seen = java.util.Collections.synchronizedMap(HashMap<String, JSONObject>())
        val finished = CountDownLatch(NOSTR_RELAYS.size)
        val filter = JSONObject().put("kinds", JSONArray().put(NOSTR_KIND)).put("authors", JSONArray().put(NOSTR_PUBKEY))
            .put("#d", JSONArray().put(NOSTR_NAME)).put("limit", 1)
        val query = JSONArray().put("REQ").put("update").put(filter).toString()
        val sockets = NOSTR_RELAYS.map { relay ->
            Http.client.newWebSocket(Request.Builder().url(relay).build(), object : WebSocketListener() {
                private var done = false
                private fun finish() { if (!done) { done = true; finished.countDown() } }
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(query) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.length > MAX_EVENT_CHARS) return
                    val message = runCatching { JSONArray(text) }.getOrNull() ?: return
                    when (message.optString(0)) {
                        "EVENT" -> message.optJSONObject(2)?.let { event -> seen[event.optString("id")] = event }
                        "EOSE" -> { webSocket.close(1000, null); finish() }
                    }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish()
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = finish()
            })
        }
        finished.await(NOSTR_WAIT_SECONDS, TimeUnit.SECONDS)
        sockets.forEach { it.cancel() }
        val events = synchronized(seen) { seen.values.toList() }.sortedByDescending { it.optLong("created_at") }
        val newest = events.firstOrNull(::trusted) ?: return null
        val createdAt = newest.getLong("created_at")
        // A replayed older pointer must not move the app back to a repo it has left.
        if (createdAt < prefs(context).getLong("updatePointerAt", 0L)) return null
        val content = JSONObject(newest.getString("content"))
        val editor = prefs(context).edit().putLong("updatePointerAt", createdAt)
        content.optString("repo").takeIf(REPO_NAME::matches)?.let { editor.putString("updateRepo", it) }
        editor.apply()
        return runCatching { parse(content) }.getOrNull()
    }

    /** True for an event that really is this app's pointer, signed by its key. */
    private fun trusted(event: JSONObject): Boolean = runCatching {
        if (event.getString("pubkey") != NOSTR_PUBKEY || event.getInt("kind") != NOSTR_KIND) return false
        val tags = event.getJSONArray("tags").let { all ->
            List(all.length()) { i -> all.getJSONArray(i).let { tag -> List(tag.length()) { j -> tag.getString(j) } } }
        }
        if (tags.none { it.size >= 2 && it[0] == "d" && it[1] == NOSTR_NAME }) return false
        val id = NostrVerify.eventId(NOSTR_PUBKEY, event.getLong("created_at"), NOSTR_KIND, tags, event.getString("content"))
        id == event.getString("id") && NostrVerify.verify(id, NOSTR_PUBKEY, event.getString("sig"))
    }.getOrDefault(false)

    // ---- Downloading -------------------------------------------------------------------------

    private fun download(context: Context, update: AppUpdate, progress: (Int) -> Unit): File {
        val folder = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(folder, "stremli.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        val request = Request.Builder().url(update.apkUrl).header("User-Agent", "Stremli").build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Couldn't download the update.")
            val body = response.body ?: throw IOException("Couldn't download the update.")
            val total = body.contentLength()
            if (total > MAX_APK_BYTES) throw IOException("The update is too large.")
            if (total > 0 && context.cacheDir.usableSpace < total * 2) throw IOException("Not enough storage. Free some space and try again.")
            var written = 0L
            var shown = -1
            file.outputStream().use { out ->
                val input = body.byteStream()
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    written += read
                    if (written > MAX_APK_BYTES) throw IOException("The update is too large.")
                    digest.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                    val percent = if (total > 0) (written * 100 / total).toInt() else 0
                    if (percent != shown) { shown = percent; progress(percent) }
                }
            }
        }
        val sum = digest.digest().joinToString("") { "%02x".format(it) }
        if (sum != update.sha256) { file.delete(); throw IOException("The download was damaged. Try again.") }
        checkApk(context, file)
        return file
    }

    /** Android would refuse these too, but only after asking; this says what is wrong first. */
    private fun checkApk(context: Context, file: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val incoming = pm.getPackageArchiveInfo(file.path, flags)
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (incoming == null || incoming.packageName != context.packageName) { file.delete(); throw IOException("That file isn't a Stremli update.") }
        if (certificates(incoming) != certificates(installed)) {
            file.delete()
            throw IOException("This copy of Stremli can't update itself. Install the latest version from the download page once.")
        }
    }

    private fun certificates(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") info.signatures
        return signatures.orEmpty().map { it.toCharsString() }.toSet()
    }
}

/** What the update row in Settings says. */
fun updateSummary(status: UpdateStatus): String = "Stremli ${BuildConfig.VERSION_NAME} · " + when (status) {
    UpdateStatus.Idle -> "Checks once a day"
    UpdateStatus.Checking -> "Checking…"
    UpdateStatus.UpToDate -> "Up to date"
    is UpdateStatus.Available -> "Version ${status.update.version} is ready"
    is UpdateStatus.Downloading -> "Downloading ${status.percent}%"
    is UpdateStatus.NeedsPermission -> "Allow installs, then update again"
    is UpdateStatus.Failed -> status.message
}

/** Offers a new version once, from Home. [allowed] keeps it from covering anything else. */
@Composable
fun UpdateSheet(allowed: Boolean) {
    val context = LocalContext.current
    val status = Updates.status
    val update = status.update
    var closed by remember { mutableIntStateOf(Updates.dismissedCode(context)) }
    val c = MaterialTheme.colorScheme
    // Keeps the words on screen while the sheet slides away.
    var shown by remember { mutableStateOf<AppUpdate?>(null) }
    if (update != null) shown = update
    SideSheet(allowed && update != null && update.code != closed, onClose = { update?.let { Updates.dismiss(context, it.code); closed = it.code } }, focusKey = update?.code) { first ->
        val offer = shown
        if (offer != null) item(key = "update") {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
                Text("Update available", style = MaterialTheme.typography.headlineSmallEmphasized, color = c.onSurface)
                Text("Stremli ${offer.version} is ready. You have ${BuildConfig.VERSION_NAME}.", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                if (offer.notes.isNotBlank()) Text(offer.notes, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                when (status) {
                    is UpdateStatus.NeedsPermission -> Text(
                        "Allow Stremli to install apps. On Fire TV that is Settings, My Fire TV, Developer options, Install unknown apps. Then choose Update again.",
                        style = MaterialTheme.typography.bodyMedium, color = c.onSurface
                    )
                    is UpdateStatus.Failed -> Text(status.message, style = MaterialTheme.typography.bodyMedium, color = c.error)
                    else -> {}
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
                    PillButton(
                        if (status is UpdateStatus.Downloading) "Downloading ${status.percent}%" else "Update now",
                        { Updates.install(context, offer) }, Modifier.focusRequester(first), style = ButtonStyle.Filled
                    )
                    PillButton("Later", { Updates.dismiss(context, offer.code); closed = offer.code })
                }
            }
        }
    }
}
