package gr.wsol.billablecalls.sys

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import gr.wsol.billablecalls.core.Billing
import gr.wsol.billablecalls.core.Call
import gr.wsol.billablecalls.core.Company
import gr.wsol.billablecalls.core.Data
import gr.wsol.billablecalls.core.Export
import gr.wsol.billablecalls.core.Ops
import gr.wsol.billablecalls.core.Period
import gr.wsol.billablecalls.core.Report
import gr.wsol.billablecalls.core.Sheets
import gr.wsol.billablecalls.core.Store
import gr.wsol.billablecalls.core.t
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

enum class Format(val ext: String, val mime: String) {
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    CSV("csv", "text/csv"),
    PDF("pdf", "application/pdf"),
    HTML("html", "text/html"),
}

/** Exports, sharing, the customer list from a file or a link. */
object Files {
    fun export(d: Data, p: Period, company: Company?, f: Format, sims: Map<String, String> = emptyMap(), onlyUnbilled: Boolean = false, logo: android.graphics.Bitmap? = null): ByteArray {
        val calls = Export.calls(d, p, company?.id, onlyUnbilled = onlyUnbilled)
        return when (f) {
            Format.CSV -> Sheets.csv(Export.rows(d, calls, sims))
            Format.XLSX -> Sheets.xlsx(listOf(t("Κλήσεις", "Calls") to Export.rows(d, calls, sims), t("Σύνολα", "Totals") to Export.totalRows(d, calls)))
            Format.PDF -> Pdf.report(d, p, company, calls, logo = logo)
            Format.HTML -> Html.report(d, p, company, calls)
        }
    }

    /** The timesheet logo (picked in Settings), kept in the app's files. */
    fun logoFile(context: Context) = File(context.filesDir, "logo.png")
    fun logo(context: Context): android.graphics.Bitmap? = logoFile(context).takeIf { it.exists() }?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it.path) }.getOrNull() }
    fun saveLogo(context: Context, bytes: ByteArray): Boolean = runCatching {
        val b = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        val scale = minOf(1f, 400f / maxOf(b.width, b.height))
        val s = android.graphics.Bitmap.createScaledBitmap(b, (b.width * scale).toInt().coerceAtLeast(1), (b.height * scale).toInt().coerceAtLeast(1), true)
        logoFile(context).outputStream().use { s.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        true
    }.getOrDefault(false)

    /** Writes [bytes] to the cache and opens Android's share sheet (email, Drive, Viber…). */
    fun share(context: Context, name: String, mime: String, bytes: ByteArray) {
        val dir = File(context.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val f = File(dir, name).apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun write(context: Context, uri: Uri, bytes: ByteArray) = runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) } }.isSuccess
    fun read(context: Context, uri: Uri): ByteArray? = runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull()

    /** The rows of a customer list file: Excel if it is a zip, otherwise CSV. */
    fun rows(bytes: ByteArray): List<List<String>> =
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) Sheets.readXlsx(bytes) else Sheets.readCsv(bytes.toString(Charsets.UTF_8))

    /** A Google Sheets link becomes its CSV export link (the sheet must be shared «anyone with the link»). */
    fun csvLink(url: String): String {
        val m = Regex("docs\\.google\\.com/spreadsheets/d/([\\w-]+)").find(url) ?: return url.trim()
        if (url.contains("/export") || url.contains("output=csv") || url.contains("/pub")) return url.trim()
        val gid = Regex("[#&?]gid=(\\d+)").find(url)?.groupValues?.get(1)
        return "https://docs.google.com/spreadsheets/d/${m.groupValues[1]}/export?format=csv" + (gid?.let { "&gid=$it" } ?: "")
    }

    fun download(url: String): ByteArray {
        var u = URL(csvLink(url)); var hops = 0
        while (true) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "BillableCalls-app")
            try {
                val code = c.responseCode
                if (code in 300..399 && hops++ < 6) { u = URL(u, c.getHeaderField("Location")); continue }
                if (code != 200) throw IllegalStateException("HTTP $code")
                return c.inputStream.use { it.readBytes() }
            } finally { c.disconnect() }
        }
    }

    /** Loads the customer list: from the bytes given, or from the link / Drive sheet of the settings. */
    fun loadList(context: Context, bytes: ByteArray? = null): Result<Int> = runCatching {
        val s = Store.settings
        val b = bytes ?: when (s.listSource) {
            "url" -> download(s.listUrl)
            "drive" -> { val tok = Drive.token(context) ?: error(t("Χρειάζεται σύνδεση με Google.", "Google sign-in needed.")); Drive.exportCsv(tok, s.driveSheetId) }
            else -> error(t("Δεν έχει οριστεί λίστα.", "No list set."))
        }
        val list = Sheets.customers(rows(b))
        if (list.isEmpty()) error(t("Η λίστα είναι άδεια ή δεν διαβάζεται.", "The list is empty or can't be read."))
        Store.update { Ops.importList(it, list).copy(settings = it.settings.copy(listSynced = System.currentTimeMillis())) }
        list.size
    }.onFailure { Report.note("list", it.toString()) }
}

/** The PDF report: one page (or more) with the totals and the calls of the period. */
object Pdf {
    fun report(d: Data, p: Period, company: Company?, calls: List<Call>, zone: ZoneId = ZoneId.systemDefault(), logo: android.graphics.Bitmap? = null): ByteArray {
        val doc = PdfDocument()
        val W = 595; val H = 842; val M = 40
        val title = Paint().apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val bold = Paint().apply { textSize = 10f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val text = Paint().apply { textSize = 10f; isAntiAlias = true }
        val small = Paint().apply { textSize = 8f; isAntiAlias = true; color = 0xFF666666.toInt() }
        val line = Paint().apply { color = 0xFFDDDDDD.toInt(); strokeWidth = 0.7f }
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M.toFloat()
            page!!.canvas.drawText("Billable Calls · ${p.label}", M.toFloat(), H - 20f, small)
            page!!.canvas.drawText("$pageNo", W - M.toFloat(), H - 20f, small)
        }
        fun fit(s: String, paint: Paint, w: Float): String { var x = s; while (x.isNotEmpty() && paint.measureText(x) > w) x = x.dropLast(1); return if (x.length < s.length) x.dropLast(1) + "…" else x }
        newPage()
        val c = page!!.canvas
        val s = d.settings
        if (s.timesheetOn) {
            // timesheet: your logo and details on top, then «Timesheet · client»
            var x = M.toFloat()
            logo?.let { b -> val h = 56f; val w = h * b.width / b.height; c.drawBitmap(b, null, android.graphics.RectF(x, y, x + w, y + h), null); x += w + 14 }
            var ty = y + 14
            if (s.sheetName.isNotBlank()) { c.drawText(s.sheetName, x, ty, bold); ty += 13 }
            s.sheetDetails.lines().filter { it.isNotBlank() }.take(5).forEach { l -> c.drawText(fit(l, small, W - M - x), x, ty, small); ty += 11 }
            y = maxOf(y + 64, ty + 6)
            c.drawLine(M.toFloat(), y, (W - M).toFloat(), y, line); y += 22
            c.drawText("Timesheet · " + (company?.name ?: t("όλοι οι πελάτες", "all clients")), M.toFloat(), y + 6, title); y += 26
        } else { c.drawText(company?.name ?: t("Κλήσεις πελατών", "Customer calls"), M.toFloat(), y + 18, title); y += 30 }
        c.drawText(t("Περίοδος: ", "Period: ") + p.label, M.toFloat(), y, text); y += 14
        c.drawText(t("Δημιουργήθηκε: ", "Created: ") + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ROOT).format(Date()), M.toFloat(), y, small); y += 22
        val totals = Export.totals(d, calls)
        c.drawText(t("Σύνολα", "Totals"), M.toFloat(), y, bold); y += 14
        for (tt in totals) {
            if (y > H - 80) newPage()
            page!!.canvas.drawText(fit(tt.company?.name.orEmpty(), text, 260f), M.toFloat(), y, text)
            page!!.canvas.drawText(t("${tt.calls} κλήσεις", "${tt.calls} calls"), M + 270f, y, text)
            page!!.canvas.drawText(t("ομιλία ", "talk ") + Billing.hms(tt.talkSec), M + 350f, y, text)
            page!!.canvas.drawText(t("χρέωση ", "billable ") + Billing.hms(tt.billableSec), M + 440f, y, bold)
            y += 14
        }
        if (totals.size > 1) {
            page!!.canvas.drawText(t("Σύνολο: ", "Total: ") + Billing.hms(totals.sumOf { it.billableSec }) + t(" χρεώσιμος χρόνος", " billable"), M.toFloat(), y + 4, bold); y += 18
        }
        // the support package of the company (when on): included, used, over
        company?.let { co -> gr.wsol.billablecalls.core.Packages.state(d, co, calls.lastOrNull()?.start ?: System.currentTimeMillis(), zone) }?.let { st ->
            page!!.canvas.drawText(t("Πακέτο μήνα: ", "Monthly package: ") + Billing.hms(st.includedSec) + t(" · χρήση ", " · used ") + Billing.hms(st.usedSec) +
                (if (st.overSec > 0) t(" · πάνω από το πακέτο ", " · over the package ") + Billing.hms(st.overSec) else t(" · απομένουν ", " · left ") + Billing.hms(st.leftSec)), M.toFloat(), y + 4, text); y += 16
        }
        if (s.overtimeOn && calls.any { gr.wsol.billablecalls.core.Overtime.isOut(it, s, zone) }) {
            page!!.canvas.drawText(t("⏰ = εκτός ωραρίου, χρεώνεται ×${s.overtimeFactor}", "⏰ = out of hours, billed ×${s.overtimeFactor}"), M.toFloat(), y + 4, small); y += 14
        }
        y += 14
        val cols = floatArrayOf(0f, 62f, 96f, 130f, 280f, 330f, 375f)
        val heads = listOf(t("Ημέρα", "Date"), t("Έναρξη", "Start"), t("Λήξη", "End"), t("Επαφή / εταιρεία", "Contact / company"), t("Διάρκ.", "Dur."), t("Χρέωση", "Billable"), t("Θέμα / σημείωση", "Topic / note"))
        fun header() { heads.forEachIndexed { i, h -> page!!.canvas.drawText(h, M + cols[i], y, bold) }; y += 5; page!!.canvas.drawLine(M.toFloat(), y, (W - M).toFloat(), y, line); y += 11 }
        if (y > H - 100) newPage()
        header()
        val day = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy"); val hm = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        for (call in calls) {
            if (y > H - 50) { newPage(); header() }
            val s = java.time.Instant.ofEpochMilli(call.start).atZone(zone); val e = java.time.Instant.ofEpochMilli(call.end).atZone(zone)
            val who = call.label + if (company == null) d.company(call.companyId)?.name?.let { " · $it" }.orEmpty() else ""
            val topic = (if (gr.wsol.billablecalls.core.Overtime.isOut(call, d.settings, zone)) "⏰ " else "") + listOfNotNull(call.category, call.note.takeIf { it.isNotBlank() }).joinToString(" · ")
            val cells = listOf(s.format(day), s.format(hm), e.format(hm), who, Billing.hms(call.durationSec), Billing.hms(Billing.seconds(call, d)), topic)
            val widths = floatArrayOf(60f, 32f, 32f, 148f, 48f, 43f, W - 2f * M - 375f)
            cells.forEachIndexed { i, v -> page!!.canvas.drawText(fit(v, text, widths[i]), M + cols[i], y, text) }
            y += 13
        }
        page?.let { doc.finishPage(it) }
        val out = ByteArrayOutputStream(); doc.writeTo(out); doc.close()
        return out.toByteArray()
    }
}

/** The backup: one JSON file with everything (no Google token: there is none to keep). */
object Backup {
    fun name() = "BillableCalls-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date()) + ".json"

    fun bytes(): ByteArray = Store.encode().toByteArray(Charsets.UTF_8)

    /** Restores from a backup file; false if it isn't one. */
    fun restore(bytes: ByteArray): Boolean = runCatching {
        val d = Store.decode(bytes.toString(Charsets.UTF_8))
        // the call log ids belong to the phone: keep this phone's position, so no call is skipped
        Store.update { now -> d.copy(settings = d.settings.copy(onboarded = true, lastCallId = now.settings.lastCallId, since = maxOf(d.settings.since, now.settings.since))) }
        true
    }.getOrDefault(false)

    /** To Drive now; keeps the last 30 backups there. */
    fun toDrive(context: Context): Result<String> = runCatching {
        val tok = Drive.token(context) ?: error(t("Χρειάζεται σύνδεση με Google.", "Google sign-in needed."))
        val folder = Drive.folder(tok, Store.settings.driveFolderId)
        if (folder != Store.settings.driveFolderId) Store.settings { it.copy(driveFolderId = folder) }
        val id = Drive.upload(tok, folder, name(), "application/json", bytes())
        Drive.list(tok, "'$folder' in parents and name contains 'BillableCalls-backup-' and trashed = false").drop(30).forEach { Drive.delete(tok, it.id) }
        Store.settings { it.copy(lastBackup = System.currentTimeMillis()) }
        id
    }.onFailure { Report.note("backup", it.toString()) }

    fun autoToDrive(context: Context) {
        val s = Store.settings
        if (!s.autoBackup || System.currentTimeMillis() - s.lastBackup < 20 * 3600_000L) return
        if (toDrive(context).isFailure) Notify.info(context, 9, t("Το backup στο Drive δεν έγινε", "Drive backup failed"), t("Άνοιξε την εφαρμογή και σύνδεσε ξανά το Google.", "Open the app and sign in to Google again."))
    }

    fun latestFromDrive(context: Context): Result<ByteArray> = runCatching {
        val tok = Drive.token(context) ?: error(t("Χρειάζεται σύνδεση με Google.", "Google sign-in needed."))
        val folder = Drive.folder(tok, Store.settings.driveFolderId)
        val f = Drive.list(tok, "'$folder' in parents and name contains 'BillableCalls-backup-' and trashed = false").firstOrNull() ?: error(t("Δεν βρέθηκε backup στο Drive.", "No backup found on Drive."))
        Drive.download(tok, f.id)
    }
}
