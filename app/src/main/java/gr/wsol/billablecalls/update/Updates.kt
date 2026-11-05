package gr.wsol.billablecalls.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Is there a newer version on GitHub? One request to GitHub's public API (no account, nothing
 * of the user's sent), at most every few hours, and it can be turned off in the settings.
 */
object Updates {
    const val LATEST = "https://api.github.com/repos/jemangr/Billable-Calls/releases/latest"
    /** The APK of the latest release, under a fixed name. */
    const val APK = "https://github.com/jemangr/Billable-Calls/releases/latest/download/BillableCalls.apk"
    const val EVERY_MS = 6 * 60 * 60 * 1000L
    /** When the app is opened: at most once an hour (GitHub allows 60 requests an hour without an account). */
    const val OPEN_EVERY_MS = 60 * 60 * 1000L

    data class Release(val version: String, val notes: String, val apk: String, val page: String)

    /** "1.10.0" > "1.9.2"; a leading "v" and anything after "-" (e.g. "-beta") are ignored. */
    fun newer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): Release? {
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val apk = assets.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == "BillableCalls.apk" }
            ?: assets.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true }
        val url = apk?.get("browser_download_url")?.jsonPrimitive?.contentOrNull ?: APK
        return Release(
            tag.removePrefix("v"),
            o["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(1500),
            url,
            o["html_url"]?.jsonPrimitive?.contentOrNull ?: "https://github.com/jemangr/Billable-Calls/releases",
        )
    }

    /** The latest release if it is newer than [current]; null if not, or if GitHub can't be reached. */
    suspend fun check(current: String): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(LATEST).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "BillableCalls-app")
            try { if (c.responseCode == 200) parse(c.inputStream.bufferedReader().readText()) else null } finally { c.disconnect() }
        }.getOrNull()?.takeIf { newer(it.version, current) }
    }
}
