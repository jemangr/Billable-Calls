@file:OptIn(ExperimentalLayoutApi::class)

package gr.wsol.billablecalls.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import gr.wsol.billablecalls.MainActivity
import gr.wsol.billablecalls.core.Data
import gr.wsol.billablecalls.core.Settings
import gr.wsol.billablecalls.core.Store
import gr.wsol.billablecalls.core.Window
import gr.wsol.billablecalls.core.t
import gr.wsol.billablecalls.sys.Battery
import gr.wsol.billablecalls.sys.CallSync
import gr.wsol.billablecalls.sys.Files
import gr.wsol.billablecalls.sys.VoipListener
import gr.wsol.billablecalls.sys.Webhook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Battery: the exemption, and what this phone's maker needs on top. */
@Composable
fun BatterySection() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val ok = remember(tick) { Battery.exempt(ctx) }
    SectionCard(t("Μπαταρία", "Battery"), t("Για να βγαίνει η κάρτα και οι υπενθυμίσεις στην ώρα τους σε κάθε κινητό.", "So the card and the reminders come on time on every phone.")) {
        if (ok) Muted(t("✓ Εξαιρείται από τη βελτιστοποίηση μπαταρίας.", "✓ Exempt from battery optimisation."))
        else OutlinedButton(onClick = { Battery.ask(ctx); tick++ }) { Text(t("Εξαίρεση από τη βελτιστοποίηση", "Exempt from optimisation")) }
        Muted(Battery.tips())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { Battery.appSettings(ctx) }) { Text(t("Ρυθμίσεις εφαρμογής", "App settings")) }
            TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Battery.site())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("dontkillmyapp.com") }
            TextButton(onClick = { tick++ }) { Text(t("Έλεγχος ξανά", "Check again")) }
        }
    }
}


/** The professional options, each with its own switch. */
@Composable
fun ProSection(d: Data) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = d.settings
    fun set(change: (Settings) -> Settings) = Store.settings(change)
    var url by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val ok = Files.read(ctx, uri)?.let { Files.saveLogo(ctx, it) } == true
        toast(ctx, if (ok) t("Το λογότυπο μπήκε.", "Logo added.") else t("Δεν διαβάζεται η εικόνα.", "Can't read the image."))
    }
    SectionCard(t("Επαγγελματικά", "Professional"), t("Το καθένα ανοίγει και κλείνει χωριστά.", "Each one turns on and off on its own.")) {
        SwitchRow(t("Συμβόλαια (πακέτο ωρών)", "Contracts (hour packages)"), s.packagesOn, t("Ώρες τον μήνα ανά εταιρεία (στην εταιρεία), υπόλοιπο και ειδοποίηση όταν ξεπεραστεί.", "Hours per month per company (in the company), what's left, and a notification when exceeded.")) { v -> set { it.copy(packagesOn = v) } }
        SwitchRow(t("Εκτός ωραρίου", "Out of hours"), s.overtimeOn, t("Κλήσεις εκτός ωραρίου χρεώνονται ×${s.overtimeFactor} και σημειώνονται ⏰.", "Calls out of hours are billed ×${s.overtimeFactor} and marked ⏰.")) { v -> set { it.copy(overtimeOn = v) } }
        if (s.overtimeOn) {
            Choice(listOf(1.25 to "×1.25", 1.5 to "×1.5", 2.0 to "×2"), s.overtimeFactor) { v -> set { it.copy(overtimeFactor = v) } }
            val w = s.workHours
            @Composable fun day(label: String, win: Window, put: (Window) -> Unit) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(win.on, { put(win.copy(on = it)) }); Text(label, Modifier.weight(1f))
                    if (win.on) TimeRange(win.from, win.to) { f, tt -> put(win.copy(from = f, to = tt)) } else Muted(t("εκτός ωραρίου", "out of hours"))
                }
            }
            Muted(t("Το ωράριο εργασίας:", "Working hours:"))
            day(t("Δευ–Παρ", "Mon–Fri"), w.weekdays) { x -> set { it.copy(workHours = it.workHours.copy(weekdays = x)) } }
            day(t("Σάββατο", "Saturday"), w.saturday) { x -> set { it.copy(workHours = it.workHours.copy(saturday = x)) } }
            day(t("Κυριακή", "Sunday"), w.sunday) { x -> set { it.copy(workHours = it.workHours.copy(sunday = x)) } }
        }
        SwitchRow(t("Συνένωση διαδοχικών κλήσεων", "Join back-to-back calls"), s.mergeOn, t("Ίδιος αριθμός ή εταιρεία μέσα σε ${s.mergeMin}′: η κάρτα προτείνει να χρεωθούν μαζί, με ένα στρογγύλεμα.", "Same number or company within ${s.mergeMin}′: the card offers to bill them together, rounded once.")) { v -> set { it.copy(mergeOn = v) } }
        if (s.mergeOn) Choice(listOf(10 to "10′", 15 to "15′", 20 to "20′", 30 to "30′"), s.mergeMin) { v -> set { it.copy(mergeMin = v) } }
        SwitchRow(t("Επιτόπια εργασία", "On-site work"), s.onsiteOn, t("Διακόπτης «Επιτόπια» στην κάρτα, χρέωση ×${s.onsiteFactor} (📍).", "An «On site» switch on the card, billed ×${s.onsiteFactor} (📍).")) { v -> set { it.copy(onsiteOn = v) } }
        if (s.onsiteOn) {
            Choice(listOf(1.0 to "×1", 1.25 to "×1.25", 1.5 to "×1.5", 2.0 to "×2"), s.onsiteFactor) { v -> set { it.copy(onsiteFactor = v) } }
            val askLoc = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> if (r.values.any { it }) set { it.copy(onsiteAuto = true) } }
            SwitchRow(t("Αυτόματα από την τοποθεσία", "Automatically from the location"), s.onsiteAuto, t("Αν το κινητό είναι ως 300 μ. από την τοποθεσία της εταιρείας (στην εταιρεία: «📍 Είμαι εδώ τώρα»). Μόνο η τελευταία γνωστή θέση, όταν ανοίγει η κάρτα.", "If the phone is within 300 m of the company's place (in the company: «📍 I'm here now»). Only the last known position, when the card opens.")) { v ->
                if (v && !gr.wsol.billablecalls.sys.Place.allowed(ctx)) askLoc.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)) else set { it.copy(onsiteAuto = v) }
            }
        }
        SwitchRow(t("Τιμολογημένες κλήσεις", "Invoiced calls"), s.billedOn, t("Στο export: «μόνο μη τιμολογημένες» και σήμανση «τιμολογήθηκαν» (🧾).", "In the export: «only not invoiced» and mark «invoiced» (🧾).")) { v -> set { it.copy(billedOn = v) } }
        SwitchRow(t("PDF ως timesheet", "PDF as a timesheet"), s.timesheetOn, t("Με το λογότυπο και τα στοιχεία σου επάνω.", "With your logo and details on top.")) { v -> set { it.copy(timesheetOn = v) } }
        if (s.timesheetOn) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { name = true }) { Text(s.sheetName.ifBlank { t("Επωνυμία", "Business name") }) }
            OutlinedButton(onClick = { details = true }) { Text(if (s.sheetDetails.isBlank()) t("Στοιχεία (ΑΦΜ, διεύθυνση…)", "Details (VAT no., address…)") else t("Στοιχεία ✓", "Details ✓")) }
            OutlinedButton(onClick = { pickLogo.launch(arrayOf("image/*")) }) { Text(if (Files.logoFile(ctx).exists()) t("Λογότυπο ✓", "Logo ✓") else t("Λογότυπο", "Logo")) }
            if (Files.logoFile(ctx).exists()) TextButton(onClick = { Files.logoFile(ctx).delete(); set { it.copy() } }) { Text(t("Χωρίς λογότυπο", "No logo")) }
        }
        SwitchRow("Webhook (n8n)", s.webhookOn, t("POST JSON στο URL σου μετά από κάθε κάρτα εταιρικής κλήσης. Τα δεδομένα φεύγουν από το κινητό μόνο προς αυτό το URL.", "A POST (JSON) to your URL after every business call's card. Data leaves the phone only to that URL.")) { v -> set { it.copy(webhookOn = v) } }
        if (s.webhookOn) Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { url = true }) { Text(s.webhookUrl.ifBlank { t("Βάλε το URL", "Set the URL") }, maxLines = 1) }
            if (s.webhookUrl.isNotBlank()) TextButton(onClick = {
                scope.launch {
                    val code = withContext(Dispatchers.IO) { runCatching { Webhook.post(s.webhookUrl, """{"test":true,"app":"Billable Calls"}""") }.getOrElse { -1 } }
                    toast(ctx, if (code in 200..299) t("✓ Το webhook απάντησε $code", "✓ The webhook answered $code") else t("Το webhook δεν απάντησε σωστά ($code)", "The webhook didn't answer right ($code)"))
                }
            }) { Text(t("Δοκιμή", "Test")) }
            if (d.pendingHooks.isNotEmpty()) Muted(t("${d.pendingHooks.size} περιμένουν", "${d.pendingHooks.size} waiting"))
        }
        SwitchRow(t("Κλήσεις Viber / WhatsApp", "Viber / WhatsApp calls"), s.voipOn, t("Από τις ειδοποιήσεις τους: διάρκεια κατά προσέγγιση, μόνο το όνομα (όχι αριθμός).", "From their notifications: approximate duration, the name only (no number).")) { v ->
            set { it.copy(voipOn = v) }; if (v && !VoipListener.allowed(ctx)) VoipListener.openSettings(ctx)
        }
        if (s.voipOn && !VoipListener.allowed(ctx)) OutlinedButton(onClick = { VoipListener.openSettings(ctx) }) { Text(t("Δώσε «Πρόσβαση στις ειδοποιήσεις»", "Allow «Notification access»")) }
        SwitchRow(t("Κλείδωμα με δαχτυλικό / PIN", "Lock with fingerprint / PIN"), s.lockOn, t("Η κάρτα μετά την κλήση δεν κλειδώνει.", "The after-call card isn't locked.")) { v ->
            if (v && !MainActivity.canLock(ctx)) toast(ctx, t("Βάλε πρώτα κλείδωμα οθόνης ή δαχτυλικό στο κινητό.", "Set a screen lock or fingerprint on the phone first."))
            else set { it.copy(lockOn = v) }
        }
    }
    if (url) TextDialog("Webhook URL", s.webhookUrl, "https://…", { url = false }) { u -> set { it.copy(webhookUrl = u.trim()) }; url = false }
    if (name) TextDialog(t("Επωνυμία στο timesheet", "Name on the timesheet"), s.sheetName, null, { name = false }) { n -> set { it.copy(sheetName = n.trim()) }; name = false }
    if (details) {
        var text by remember { mutableStateOf(s.sheetDetails) }
        androidx.compose.material3.AlertDialog(onDismissRequest = { details = false }, title = { Text(t("Στοιχεία στο timesheet", "Details on the timesheet")) },
            text = { OutlinedTextField(text, { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth(), placeholder = { Text(t("ΑΦΜ, ΔΟΥ, διεύθυνση, τηλέφωνο, email", "VAT no., address, phone, email")) }) },
            confirmButton = { TextButton(onClick = { set { it.copy(sheetDetails = text.trim()) }; details = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { details = false }) { Text(t("Άκυρο", "Cancel")) } })
    }
}

/** «Read the call history again». */
@Composable
fun HistorySection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    SectionCard(t("Ιστορικό κλήσεων", "Call history"), t("Διαβάζει ξανά όλο το ιστορικό του κινητού. Ό,τι υπάρχει ήδη μένει όπως είναι· οι κλήσεις που λείπουν μπαίνουν (χωρίς κάρτα και υπενθύμιση).", "Reads the phone's whole history again. What's here stays as it is; missing calls are added (no card, no reminder).")) {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            scope.launch { val n = withContext(Dispatchers.IO) { CallSync.reread(ctx) }; busy = false; toast(ctx, t("Μπήκαν $n κλήσεις.", "Added $n calls.")) }
        }) { Text(if (busy) t("Διαβάζει…", "Reading…") else t("Ξαναδιάβασε το ιστορικό", "Read the history again")) }
    }
}
