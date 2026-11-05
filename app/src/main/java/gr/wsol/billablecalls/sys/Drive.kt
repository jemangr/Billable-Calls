package gr.wsol.billablecalls.sys

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import gr.wsol.billablecalls.core.Report
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Google Drive with the «drive.file» permission: the app sees only the files it created itself (the
 * «Billable Calls» folder, the «Πελάτες» sheet, the exports and the backups), nothing else of yours.
 * No password or key is kept by the app; Google gives a short-lived token each time.
 */
object Drive {
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val FOLDER = "Billable Calls"
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    const val SHEET_MIME = "application/vnd.google-apps.spreadsheet"
    private val json = Json { ignoreUnknownKeys = true }

    fun request(): AuthorizationRequest = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    sealed class Auth {
        data class Token(val token: String) : Auth()
        /** The user must say yes once (Google's screen). */
        data class NeedsUser(val intent: PendingIntent) : Auth()
        data class Failed(val why: String) : Auth()
    }

    /** Off the main thread. */
    fun authorize(context: Context): Auth = runCatching {
        val r: AuthorizationResult = Tasks.await(Identity.getAuthorizationClient(context).authorize(request()), 30, TimeUnit.SECONDS)
        when {
            r.hasResolution() -> r.pendingIntent?.let { Auth.NeedsUser(it) } ?: Auth.Failed("no intent")
            r.accessToken != null -> Auth.Token(r.accessToken!!)
            else -> Auth.Failed("no token")
        }
    }.getOrElse { e ->
        Report.note("drive", e.toString())
        Auth.Failed(if (e.toString().contains("10:") || e.toString().contains("DEVELOPER_ERROR")) "oauth" else e.message ?: e.toString())
    }

    fun token(context: Context): String? = (authorize(context) as? Auth.Token)?.token

    class DriveError(val code: Int, message: String) : Exception(message)

    private fun http(method: String, url: String, token: String, body: ByteArray? = null, type: String? = null): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = if (method == "PATCH") "POST" else method
        if (method == "PATCH") c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", type ?: "application/octet-stream"); c.outputStream.use { it.write(body) } }
        try {
            val code = c.responseCode
            if (code !in 200..299) throw DriveError(code, (c.errorStream?.readBytes()?.toString(Charsets.UTF_8) ?: "").take(300))
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    private fun obj(b: ByteArray): JsonObject = json.parseToJsonElement(b.toString(Charsets.UTF_8)).jsonObject
    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    data class File(val id: String, val name: String, val created: String)

    fun list(token: String, query: String): List<File> {
        val b = http("GET", "$API/files?q=${q(query)}&orderBy=createdTime%20desc&pageSize=100&fields=${q("files(id,name,createdTime)")}", token)
        return (obj(b)["files"] as? JsonArray).orEmpty().map { it.jsonObject }.map {
            File(it["id"]!!.jsonPrimitive.content, it["name"]?.jsonPrimitive?.contentOrNull.orEmpty(), it["createdTime"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
    }

    /** The «Billable Calls» folder (made the first time). */
    fun folder(token: String, known: String): String {
        if (known.isNotEmpty() && runCatching { http("GET", "$API/files/$known?fields=id,trashed", token) }.getOrNull()?.let { obj(it)["trashed"]?.jsonPrimitive?.contentOrNull != "true" } == true) return known
        list(token, "name = '$FOLDER' and mimeType = 'application/vnd.google-apps.folder' and trashed = false").firstOrNull()?.let { return it.id }
        val meta = """{"name":"$FOLDER","mimeType":"application/vnd.google-apps.folder"}"""
        return obj(http("POST", "$API/files?fields=id", token, meta.toByteArray(), "application/json; charset=UTF-8"))["id"]!!.jsonPrimitive.content
    }

    /** Uploads a file into [folder]; with [as_] a Google type (e.g. a Sheet from a CSV), Drive converts it. */
    fun upload(token: String, folder: String, name: String, mime: String, bytes: ByteArray, asType: String? = null): String {
        val boundary = "bc" + System.nanoTime()
        val meta = buildString {
            append("{\"name\":").append(Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(name)))
            append(",\"parents\":[\"").append(folder).append("\"]")
            if (asType != null) append(",\"mimeType\":\"").append(asType).append("\"")
            append("}")
        }
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        w("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: $mime\r\n\r\n"); out.write(bytes); w("\r\n--$boundary--")
        return obj(http("POST", "$UPLOAD/files?uploadType=multipart&fields=id", token, out.toByteArray(), "multipart/related; boundary=$boundary"))["id"]!!.jsonPrimitive.content
    }

    fun download(token: String, id: String): ByteArray = http("GET", "$API/files/$id?alt=media", token)
    fun exportCsv(token: String, id: String): ByteArray = http("GET", "$API/files/$id/export?mimeType=text/csv", token)
    fun delete(token: String, id: String) { runCatching { http("DELETE", "$API/files/$id", token) } }

    fun sheetLink(id: String) = "https://docs.google.com/spreadsheets/d/$id/edit"
}
