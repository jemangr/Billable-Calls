package gr.wsol.billablecalls.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import gr.wsol.billablecalls.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The bug report: what the user writes, plus the version, the phone, a few settings, counts and the
 * last errors. Never numbers, names, companies or notes. It stays on the phone until the user sends it.
 */
object Report {
    const val EMAIL = "support@jemangr.app"
    const val SUBJECT = "Bug report Billable Calls"
    /** The report goes straight to us through platebit.app (nothing kept there); the user's email only if they write it. */
    const val ENDPOINT = "https://platebit.app/report.php"
    const val APP = "billable-calls"

    /** The JSON that goes to [ENDPOINT]. */
    fun payload(text: String, email: String): String = org.json.JSONObject()
        .put("app", APP).put("version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        .put("text", text).put("email", email.trim()).put("website", "").toString()

    /** Sends the report directly, without the email app. True when it arrived. */
    suspend fun send(text: String, email: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val c = java.net.URL(ENDPOINT).openConnection() as java.net.HttpURLConnection
            try {
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 15_000
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(payload(text, email).toByteArray()) }
                c.responseCode == 200 && c.inputStream.bufferedReader().readText().contains("\"ok\":true")
            } finally { c.disconnect() }
        }.getOrDefault(false)
    }

    private val errors = ArrayDeque<String>()

    fun note(where: String, what: String) = synchronized(errors) {
        // digits of 6+ could be a phone number: never in the report
        val clean = what.take(200).replace(Regex("\\d{6,}"), "#")
        errors.addLast("${SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())} $where: $clean")
        while (errors.size > 30) errors.removeFirst()
    }

    fun recent(): List<String> = synchronized(errors) { errors.toList() }

    fun text(context: Context, what: String): String = buildString {
        appendLine("Billable Calls · bug report")
        appendLine()
        appendLine(what.trim().ifEmpty { "-" })
        appendLine()
        appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        val d = Store.data; val s = d.settings
        appendLine("Calls: ${d.calls.size} · companies: ${d.companies.size} · rules: ${d.rules.size} · reminders: ${d.reminders.count { !it.stopped }}")
        appendLine("Match: ${s.matchMode} ${s.namePosition} ${s.separator} · min ${s.minSec}s · round ${s.roundMin}′ · card ${s.cardMode} instant ${s.instantCard}")
        appendLine("SIMs: ${s.sims.size} · list: ${s.listSource.ifEmpty { "-" }} · drive: ${s.driveFolderId.isNotEmpty()} · auto backup: ${s.autoBackup} · language: ${if (s.english) "en" else "el"}")
        val r = recent()
        if (r.isNotEmpty()) { appendLine(); appendLine("Last errors:"); r.forEach { appendLine(it) } }
        Crash.last(context)?.let { appendLine(); appendLine("Last crash:"); append(it.take(4000)) }
    }.trim()

    fun email(context: Context, text: String): Boolean {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
            .putExtra(Intent.EXTRA_SUBJECT, SUBJECT)
            .putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(i) }.isSuccess
    }

    fun share(context: Context, text: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, SUBJECT).putExtra(Intent.EXTRA_TEXT, text)
        runCatching { context.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** If the app closes on an error, the technical details (no personal data) stay until the next start. */
object Crash {
    private fun file(c: Context) = File(c.filesDir, "crash.txt")

    fun install(c: Context) {
        val app = c.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { file(app).writeText(report(thread.name, e)) }
            previous?.uncaughtException(thread, e)
        }
    }

    fun report(thread: String, e: Throwable): String = buildString {
        appendLine("Billable Calls ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Thread: $thread")
        appendLine()
        append(e.stackTraceToString().take(6000).replace(Regex("\\d{6,}"), "#"))
    }

    fun last(c: Context): String? = file(c).takeIf { it.exists() }?.readText()?.ifBlank { null }
    fun clear(c: Context) { file(c).delete() }
}
