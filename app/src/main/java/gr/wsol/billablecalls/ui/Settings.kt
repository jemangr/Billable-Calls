@file:OptIn(ExperimentalLayoutApi::class)

package gr.wsol.billablecalls.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.Identity
import gr.wsol.billablecalls.BuildConfig
import gr.wsol.billablecalls.core.CardMode
import gr.wsol.billablecalls.core.Data
import gr.wsol.billablecalls.core.Export
import gr.wsol.billablecalls.core.MatchMode
import gr.wsol.billablecalls.core.Matcher
import gr.wsol.billablecalls.core.NamePosition
import gr.wsol.billablecalls.core.Ops
import gr.wsol.billablecalls.core.Report
import gr.wsol.billablecalls.core.ReminderSettings
import gr.wsol.billablecalls.core.Separator
import gr.wsol.billablecalls.core.Sheets
import gr.wsol.billablecalls.core.Store
import gr.wsol.billablecalls.core.Window
import gr.wsol.billablecalls.core.t
import gr.wsol.billablecalls.sys.Backup
import gr.wsol.billablecalls.sys.Card
import gr.wsol.billablecalls.sys.Drive
import gr.wsol.billablecalls.sys.Files
import gr.wsol.billablecalls.sys.InstantService
import gr.wsol.billablecalls.sys.Jobs
import gr.wsol.billablecalls.sys.Sims
import gr.wsol.billablecalls.update.Installer
import gr.wsol.billablecalls.update.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

/** Google sign-in for Drive: returns a function that connects and then runs [then] with a token (off the main thread). */
@Composable
fun rememberDrive(then: suspend (String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val tok = runCatching { Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(res.data).accessToken }.getOrNull()
        if (tok == null) toast(ctx, t("Η σύνδεση με Google δεν ολοκληρώθηκε.", "Google sign-in did not finish."))
        else scope.launch { withContext(Dispatchers.IO) { then(tok) } }
    }
    return {
        scope.launch {
            when (val a = withContext(Dispatchers.IO) { Drive.authorize(ctx) }) {
                is Drive.Auth.Token -> withContext(Dispatchers.IO) { then(a.token) }
                is Drive.Auth.NeedsUser -> launcher.launch(IntentSenderRequest.Builder(a.intent.intentSender).build())
                is Drive.Auth.Failed -> toast(ctx, if (a.why == "oauth") t("Η σύνδεση Google δεν έχει ρυθμιστεί ακόμα (OAuth client). Δες τις οδηγίες στο README.", "Google sign-in is not set up yet (OAuth client). See the README.") else t("Σφάλμα Google: ", "Google error: ") + a.why)
            }
        }
    }
}

/** Saves the folder id after connecting. */
suspend fun driveFolder(tok: String) {
    val f = Drive.folder(tok, Store.settings.driveFolderId)
    Store.settings { it.copy(driveFolderId = f) }
}

@Composable
fun ListSection(d: Data) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(false) }
    val s = d.settings
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) { Files.read(ctx, uri)?.let { b -> Files.loadList(ctx, b) } ?: Result.failure(Exception(t("Δεν διαβάζεται το αρχείο.", "Can't read the file."))) }
            if (r.isSuccess) Store.settings { it.copy(listSource = if (it.listSource == "drive") "drive" else "file", listName = uri.lastPathSegment.orEmpty()) }
            toast(ctx, r.fold({ t("$it εταιρείες από τη λίστα.", "$it companies from the list.") }, { it.message ?: "Error" }))
        }
    }
    val toDrive = rememberDrive { tok ->
        runCatching {
            driveFolder(tok)
            val rows = listOf(listOf(t("Επωνυμία", "Name"), t("Συνώνυμα", "Aliases"))) + Store.data.companies.map { listOf(it.name, it.aliases.joinToString(" | ")) }
            val id = Drive.upload(tok, Store.settings.driveFolderId, t("Πελάτες", "Customers"), "text/csv", Sheets.csv(rows), Drive.SHEET_MIME)
            Store.settings { it.copy(listSource = "drive", driveSheetId = id) }
        }.onFailure { Report.note("drive", it.toString()) }.let { r ->
            withContext(Dispatchers.Main) { toast(ctx, if (r.isSuccess) t("Έγινε το Sheet «Πελάτες» στο Drive, φάκελος «Billable Calls».", "Made the «Customers» sheet on Drive, folder «Billable Calls».") else t("Δεν έγινε: ", "Failed: ") + r.exceptionOrNull()?.message) }
        }
    }
    SectionCard(t("Λίστα πελατών", "Customer list"),
        t("Στήλη Α η επωνυμία, οι επόμενες στήλες συνώνυμα (ή στο ίδιο κελί χωρισμένα με «|»). CSV ή Excel.", "Column A the name, the next columns aliases (or in one cell split by «|»). CSV or Excel.")) {
        val src = when (s.listSource) { "file" -> t("αρχείο ", "file ") + s.listName; "url" -> "link"; "drive" -> "Google Drive"; else -> t("με το χέρι", "by hand") }
        Muted(t("Τώρα: ", "Now: ") + src + " · " + t("${d.companies.size} εταιρείες", "${d.companies.size} companies") + (if (s.listSynced > 0) " · " + dayHm(s.listSynced) else ""))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pick.launch(arrayOf("text/*", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "application/octet-stream")) }) { Text(t("Από αρχείο", "From a file")) }
            OutlinedButton(onClick = { url = true }) { Text(t("Από link", "From a link")) }
            OutlinedButton(onClick = toDrive) { Text(if (s.driveSheetId.isEmpty()) t("Sheet στο Drive", "Sheet on Drive") else t("Νέο Sheet στο Drive", "New sheet on Drive")) }
        }
        if (s.listSource == "url" || s.listSource == "drive") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { val r = withContext(Dispatchers.IO) { Files.loadList(ctx) }; toast(ctx, r.fold({ t("$it εταιρείες.", "$it companies.") }, { it.message ?: "Error" })) } }) { Text(t("Ανανέωση τώρα", "Refresh now")) }
            if (s.listSource == "drive" && s.driveSheetId.isNotEmpty()) TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Drive.sheetLink(s.driveSheetId)))) } }) { Text(t("Άνοιγμα Sheet", "Open sheet")) }
        }
        if (s.listSource == "drive") Muted(t("Το Sheet το διορθώνεις από τον υπολογιστή· η εφαρμογή το ξαναδιαβάζει κάθε μέρα και με το «Ανανέωση».", "Edit the sheet on your computer; the app reads it every day and with «Refresh»."))
    }
    if (url) TextDialog(t("Link της λίστας", "List link"), s.listUrl, t("CSV, Excel ή Google Sheet (κοινόχρηστο με link)", "CSV, Excel or Google Sheet (shared by link)"), { url = false }) { u ->
        url = false
        Store.settings { it.copy(listSource = "url", listUrl = u.trim()) }
        scope.launch { val r = withContext(Dispatchers.IO) { Files.loadList(ctx) }; toast(ctx, r.fold({ t("$it εταιρείες από το link.", "$it companies from the link.") }, { it.message ?: "Error" })) }
    }
}

@Composable
fun MatchSection(d: Data) {
    val s = d.settings
    SectionCard(t("Πώς βρίσκω την εταιρεία", "How the company is found"), t("Από την επαφή του τηλεφώνου.", "From the phone's contact.")) {
        Choice(listOf(MatchMode.BOTH to t("Πεδίο «Εταιρεία» και όνομα", "Company field and name"), MatchMode.COMPANY_FIELD to t("Μόνο πεδίο «Εταιρεία»", "Company field only"), MatchMode.NAME to t("Μόνο όνομα", "Name only")), s.matchMode) { v -> Store.update { Ops.rematch(it.copy(settings = it.settings.copy(matchMode = v))) } }
        if (s.matchMode != MatchMode.COMPANY_FIELD) {
            Text(t("Στο όνομα, η εταιρεία είναι", "In the name, the company is"), style = MaterialTheme.typography.titleSmall)
            Choice(listOf(NamePosition.BEFORE to t("πριν (ΑΒΓ - Νίκος)", "before (ABC - Nick)"), NamePosition.AFTER to t("μετά (Νίκος - ΑΒΓ)", "after (Nick - ABC)")), s.namePosition) { v -> Store.update { Ops.rematch(it.copy(settings = it.settings.copy(namePosition = v))) } }
            Text(t("Διαχωριστικό", "Separator"), style = MaterialTheme.typography.titleSmall)
            Choice(listOf(Separator.ANY to t("Οποιοδήποτε", "Any"), Separator.DASH to "-", Separator.SPACE to t("κενό", "space"), Separator.PIPE to "|", Separator.SLASH to "/", Separator.COMMA to ",", Separator.PAREN to "( )"), s.separator) { v -> Store.update { Ops.rematch(it.copy(settings = it.settings.copy(separator = v))) } }
        }
        val sample = d.calls.filter { !it.contact.isNullOrBlank() }.distinctBy { it.key }.takeLast(6)
        if (sample.isNotEmpty() && d.companies.isNotEmpty()) {
            Muted(t("Δείγμα από τις κλήσεις σου:", "Sample from your calls:"))
            sample.forEach { c ->
                val m = Matcher.match(c.contact, c.org, d.companies, s)
                Muted("${c.contact}${c.org?.let { " [$it]" }.orEmpty()} → ${d.company(m.companyId)?.name ?: t("χωρίς εταιρεία", "no company")}")
            }
        }
    }
}

@Composable
fun BillingSection(d: Data) {
    val s = d.settings
    SectionCard(t("Χρεώσιμος χρόνος", "Billable time"), t("Αλλάζει και ανά εταιρεία.", "Can be changed per company too.")) {
        Text(t("Η κλήση μετράει όταν είναι πάνω από", "A call counts when it is longer than"), style = MaterialTheme.typography.titleSmall)
        MinPicker(s.minSec, general = false) { v -> Store.settings { it.copy(minSec = v ?: 60) } }
        Text(t("Στρογγύλεμα προς τα πάνω σε", "Rounded up to"), style = MaterialTheme.typography.titleSmall)
        RoundPicker(s.roundMin, general = false) { v -> Store.settings { it.copy(roundMin = v ?: 0) } }
        Muted(t("Π.χ. με 15′: κλήση 4′ = 15′, 16′ = 30′.", "E.g. with 15′: a 4′ call = 15′, 16′ = 30′."))
    }
}

@Composable
fun CardSection(d: Data) {
    val ctx = LocalContext.current
    val s = d.settings
    SectionCard(t("Κάρτα μετά την κλήση", "After-call card"), t("«Εταιρική;», εταιρεία, θέμα και σημείωση.", "«Business?», company, topic and note.")) {
        Choice(listOf(CardMode.ALL to t("Σε κάθε κλήση", "Every call"), CardMode.UNMATCHED to t("Μόνο χωρίς εταιρεία", "Only without company"), CardMode.NEVER to t("Ποτέ", "Never")), s.cardMode) { v -> Store.settings { it.copy(cardMode = v) } }
        if (!Card.canOverlay(ctx)) {
            Muted(t("Για να βγαίνει πάνω από την οθόνη, δώσε «Εμφάνιση πάνω από άλλες εφαρμογές». Αλλιώς βγαίνει ειδοποίηση.", "To open over the screen, allow «Display over other apps». Otherwise a notification opens it."))
            OutlinedButton(onClick = { Card.overlaySettings(ctx) }) { Text(t("Άδεια εμφάνισης", "Allow display")) }
        } else Muted(t("✓ Βγαίνει πάνω από την οθόνη.", "✓ Opens over the screen."))
        SwitchRow(t("Άμεση κάρτα", "Instant card"), s.instantCard, t("Με μόνιμη ειδοποίηση, για κινητά που καθυστερούν την κάρτα.", "With an ongoing notification, for phones that delay the card.")) { on ->
            Store.settings { it.copy(instantCard = on) }; if (on) InstantService.start(ctx) else InstantService.stop(ctx)
        }
    }
}

@Composable
fun ReminderSection(d: Data) {
    val r = d.settings.reminders
    val sc = r.schedule
    fun set(change: (ReminderSettings) -> ReminderSettings) = Store.settings { it.copy(reminders = change(it.reminders)) }
    SectionCard(t("Υπενθυμίσεις αναπάντητων", "Missed-call reminders")) {
        SwitchRow(t("Αναπάντητες εισερχόμενες", "Missed incoming"), r.incoming) { v -> set { it.copy(incoming = v) } }
        SwitchRow(t("Εξερχόμενες χωρίς απάντηση", "Outgoing not answered"), r.outgoing) { v -> set { it.copy(outgoing = v) } }
        SwitchRow(t("Από πελάτες", "From customers"), r.customers) { v -> set { it.copy(customers = v) } }
        SwitchRow(t("Από άγνωστους αριθμούς", "From unknown numbers"), r.unknown, t("Όχι από επαφές που δεν είναι πελάτες.", "Not from contacts that aren't customers.")) { v -> set { it.copy(unknown = v) } }
        Text(t("Ωράριο", "Hours"), style = MaterialTheme.typography.titleSmall)
        Choice(listOf(false to t("Δευ–Παρ, Σάβ., Κυρ. χωριστά", "Mon–Fri, Sat, Sun apart"), true to t("Κάθε μέρα ίδιο", "Same every day")), sc.sameEveryDay) { v -> set { it.copy(schedule = it.schedule.copy(sameEveryDay = v)) } }
        @Composable fun day(label: String, w: Window, put: (Window) -> Unit) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(w.on, { put(w.copy(on = it)) }); Text(label, Modifier.weight(1f))
                if (w.on) TimeRange(w.from, w.to) { f, tt -> put(w.copy(from = f, to = tt)) } else Muted(t("κλειστό", "off"))
            }
        }
        if (sc.sameEveryDay) day(t("Κάθε μέρα", "Every day"), sc.everyDay) { w -> set { it.copy(schedule = it.schedule.copy(everyDay = w)) } }
        else {
            day(t("Δευ–Παρ", "Mon–Fri"), sc.weekdays) { w -> set { it.copy(schedule = it.schedule.copy(weekdays = w)) } }
            day(t("Σάββατο", "Saturday"), sc.saturday) { w -> set { it.copy(schedule = it.schedule.copy(saturday = w)) } }
            day(t("Κυριακή", "Sunday"), sc.sunday) { w -> set { it.copy(schedule = it.schedule.copy(sunday = w)) } }
        }
        Text(t("Ώρες χωρίς υπενθυμίσεις (μέσα στο ωράριο)", "Quiet hours (inside the hours)"), style = MaterialTheme.typography.titleSmall)
        sc.quiet.forEachIndexed { i, q ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeRange(q.from, q.to) { f, tt -> set { it.copy(schedule = it.schedule.copy(quiet = it.schedule.quiet.toMutableList().also { l -> l[i] = q.copy(from = f, to = tt) })) } }
                TextButton(onClick = { set { it.copy(schedule = it.schedule.copy(quiet = it.schedule.quiet.filterIndexed { j, _ -> j != i })) } }) { Text("✕") }
            }
        }
        TextButton(onClick = { set { it.copy(schedule = it.schedule.copy(quiet = it.schedule.quiet + Window(true, 14 * 60, 15 * 60))) } }) { Text(t("+ ώρες χωρίς υπενθυμίσεις", "+ quiet hours")) }
        Text(t("Κάθε πόσο", "How often"), style = MaterialTheme.typography.titleSmall)
        Presets(listOf(15, 30, 60, 120), sc.intervalMin, { if (it >= 60 && it % 60 == 0) t("${it / 60} ώρ.", "${it / 60}h") else "$it′" }, t("Λεπτά", "Minutes")) { v -> set { it.copy(schedule = it.schedule.copy(intervalMin = v.coerceAtLeast(5))) } }
        Text(t("Σταματάει (όποιο έρθει πρώτο)", "Stops (whichever comes first)"), style = MaterialTheme.typography.titleSmall)
        SwitchRow(t("Όταν μιλήσετε", "When you talk"), r.stopTalked) { v -> set { it.copy(stopTalked = v) } }
        SwitchRow(t("Μετά από ${r.maxCount} υπενθυμίσεις", "After ${r.maxCount} reminders"), r.stopCount) { v -> set { it.copy(stopCount = v) } }
        if (r.stopCount) Presets(listOf(1, 2, 3, 5), r.maxCount, { "$it" }, t("Υπενθυμίσεις", "Reminders")) { v -> set { it.copy(maxCount = v.coerceAtLeast(1)) } }
        SwitchRow(t("Μετά από χρόνο από την κλήση", "After a time from the call"), r.stopTime) { v -> set { it.copy(stopTime = v) } }
        if (r.stopTime) Presets(listOf(2, 8, 24, 72), r.maxMinutes / 60, { if (it >= 24 && it % 24 == 0) t("${it / 24} μέρ.", "${it / 24}d") else t("$it ώρ.", "${it}h") }, t("Ώρες", "Hours")) { v ->
            set { it.copy(maxMinutes = v.coerceAtLeast(1) * 60) }
        }
        Muted(t("Και πάντα με το «Αγνόησε» στην ειδοποίηση.", "And always with «Ignore» on the notification."))
        if (!r.stopTalked && !r.stopCount && !r.stopTime) Text(t("Προσοχή: χωρίς καμία συνθήκη οι υπενθυμίσεις σταματούν μόνο με το «Αγνόησε».", "Careful: with no condition, reminders stop only with «Ignore»."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Text(t("«Αργότερα» σημαίνει", "«Later» means"), style = MaterialTheme.typography.titleSmall)
        Presets(listOf(15, 30, 60, 120), r.snoozeMin, { if (it >= 60 && it % 60 == 0) t("${it / 60} ώρ.", "${it / 60}h") else "$it′" }, t("Λεπτά", "Minutes")) { v -> set { it.copy(snoozeMin = v.coerceAtLeast(5)) } }
    }
}

@Composable
fun SimSection(d: Data) {
    val ctx = LocalContext.current
    val sims = remember { Sims.list(ctx) }
    if (sims.size < 2) return
    SectionCard(t("SIM που μετράνε", "SIMs that count"), t("Καμία επιλογή = όλες.", "None selected = all.")) {
        sims.forEachIndexed { i, (id, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(id in d.settings.sims, { on -> Store.settings { s -> s.copy(sims = if (on) (s.sims + id).distinct() else s.sims - id) } })
                Text("SIM ${i + 1} · $label")
            }
        }
    }
}

@Composable
fun CategorySection(d: Data) {
    var add by remember { mutableStateOf(false) }
    SectionCard(t("Θέματα κλήσεων", "Call topics"), t("Πάτα για αφαίρεση.", "Tap to remove.")) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            d.settings.categories.forEach { c -> FilterChip(true, { Store.settings { it.copy(categories = it.categories - c) } }, label = { Text("$c ✕") }) }
            FilterChip(false, { add = true }, label = { Text(t("+ θέμα", "+ topic")) })
        }
    }
    if (add) TextDialog(t("Νέο θέμα", "New topic"), "", null, { add = false }) { c -> Store.settings { it.copy(categories = (it.categories + c.trim()).distinct()) }; add = false }
}

@Composable
fun BackupSection(d: Data) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<ByteArray?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        toast(ctx, if (Files.write(ctx, uri, Backup.bytes())) t("Το backup αποθηκεύτηκε.", "Backup saved.") else t("Δεν αποθηκεύτηκε.", "Not saved."))
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { Files.read(ctx, it) }?.let { confirm = it } }
    val connect = rememberDrive { tok ->
        val r = runCatching { driveFolder(tok) }
        withContext(Dispatchers.Main) { toast(ctx, if (r.isSuccess) t("Συνδέθηκε το Google Drive (φάκελος «Billable Calls»).", "Google Drive connected (folder «Billable Calls»).") else t("Σφάλμα: ", "Error: ") + r.exceptionOrNull()?.message) }
        Jobs.schedule(ctx)
    }
    val s = d.settings
    SectionCard("Google Drive", t("Η εφαρμογή βλέπει μόνο τον δικό της φάκελο «Billable Calls».", "The app sees only its own «Billable Calls» folder.")) {
        if (s.driveFolderId.isEmpty()) Button(onClick = connect) { Text(t("Σύνδεση με Google", "Sign in with Google")) }
        else {
            Muted(t("✓ Συνδεδεμένο", "✓ Connected") + (if (s.lastBackup > 0) t(" · τελευταίο backup ", " · last backup ") + dayHm(s.lastBackup) else ""))
            SwitchRow(t("Αυτόματο backup κάθε μέρα", "Automatic daily backup"), s.autoBackup, t("Κρατά τα τελευταία 30.", "Keeps the last 30.")) { v -> Store.settings { it.copy(autoBackup = v) }; Jobs.schedule(ctx) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { val r = withContext(Dispatchers.IO) { Backup.toDrive(ctx) }; toast(ctx, if (r.isSuccess) t("Backup στο Drive.", "Backed up to Drive.") else r.exceptionOrNull()?.message ?: "Error") } }) { Text(t("Backup τώρα", "Back up now")) }
                OutlinedButton(onClick = { scope.launch { val r = withContext(Dispatchers.IO) { Backup.latestFromDrive(ctx) }; r.onSuccess { confirm = it }.onFailure { toast(ctx, it.message ?: "Error") } } }) { Text(t("Επαναφορά από Drive", "Restore from Drive")) }
                TextButton(onClick = connect) { Text(t("Ξανά σύνδεση", "Reconnect")) }
            }
        }
    }
    SectionCard(t("Backup σε αρχείο", "Backup to a file")) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { save.launch(Backup.name()) }) { Text(t("Αποθήκευση", "Save")) }
            OutlinedButton(onClick = { open.launch(arrayOf("application/json", "*/*")) }) { Text(t("Επαναφορά", "Restore")) }
        }
    }
    confirm?.let { bytes ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(t("Επαναφορά backup;", "Restore backup?")) },
            text = { Text(t("Τα τωρινά δεδομένα της εφαρμογής αντικαθίστανται από το backup.", "The app's current data is replaced by the backup.")) },
            confirmButton = { Button(onClick = { toast(ctx, if (Backup.restore(bytes)) t("Έγινε επαναφορά.", "Restored.") else t("Δεν είναι backup της εφαρμογής.", "Not a backup of this app.")); confirm = null }) { Text(t("Επαναφορά", "Restore")) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(t("Άκυρο", "Cancel")) } })
    }
}

@Composable
fun UpdateSection(d: Data) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var found by remember { mutableStateOf<Updates.Release?>(null) }
    var progress by remember { mutableStateOf<Int?>(null) }
    var report by remember { mutableStateOf(false) }
    SectionCard(t("Ενημερώσεις και σφάλματα", "Updates and bugs"), "Billable Calls ${BuildConfig.VERSION_NAME}") {
        SwitchRow(t("Αυτόματος έλεγχος για νέα έκδοση", "Check for new versions"), d.settings.updateCheck) { v -> Store.settings { it.copy(updateCheck = v) } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { val r = Updates.check(BuildConfig.VERSION_NAME); found = r; if (r == null) toast(ctx, t("Έχεις την τελευταία έκδοση.", "You have the latest version.")) } }) { Text(t("Έλεγχος τώρα", "Check now")) }
            OutlinedButton(onClick = { report = true }) { Text(t("Αναφορά σφάλματος", "Report a bug")) }
        }
        found?.let { r ->
            Text(t("Νέα έκδοση ${r.version}", "New version ${r.version}"), style = MaterialTheme.typography.titleSmall)
            if (r.notes.isNotBlank()) Muted(r.notes.take(400))
            progress?.let { Muted("$it%") }
            Button(enabled = progress == null, onClick = {
                if (!Installer.canInstall(ctx)) { Installer.allowInstalls(ctx); return@Button }
                scope.launch { progress = 0; val f = Installer.download(ctx, r.apk) { p -> progress = p }; progress = null; if (f != null) Installer.install(ctx, f) else toast(ctx, t("Η λήψη απέτυχε.", "Download failed.")) }
            }) { Text(t("Εγκατάσταση", "Install")) }
        }
    }
    if (report) ReportDialog { report = false }
}

@Composable
fun ReportDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var what by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(t("Αναφορά σφάλματος", "Bug report")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(what, { what = it }, label = { Text(t("Τι έγινε;", "What happened?")) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Muted(t("Μπαίνουν έκδοση, κινητό, ρυθμίσεις και τα τελευταία σφάλματα. Ποτέ αριθμοί, ονόματα ή σημειώσεις.", "It includes version, phone, settings and the last errors. Never numbers, names or notes."))
            }
        },
        confirmButton = { Button(onClick = { val tx = Report.text(ctx, what); if (!Report.email(ctx, tx)) Report.share(ctx, tx); onDismiss() }) { Text("Email") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Άκυρο", "Cancel")) } })
}

@Composable
fun SettingsScreen() {
    val d by Store.flow.collectAsState()
    var export by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Title(t("Ρυθμίσεις", "Settings"))
            SectionCard(t("Γλώσσα", "Language")) { Choice(listOf(false to "Ελληνικά", true to "English"), d.settings.english) { v -> Store.settings { it.copy(english = v) } } }
            SectionCard("Export", t("Excel, CSV ή PDF, για όλες τις εταιρείες. Για μία: Εταιρείες → η εταιρεία → Export.", "Excel, CSV or PDF, for all companies. For one: Companies → the company → Export.")) {
                Button(onClick = { export = true }) { Text(t("Export όλων", "Export all")) }
            }
            ListSection(d)
            MatchSection(d)
            BillingSection(d)
            CardSection(d)
            ReminderSection(d)
            SimSection(d)
            CategorySection(d)
            BackupSection(d)
            UpdateSection(d)
            SectionCard(t("Απόρρητο", "Privacy")) {
                Muted(t("Οι κλήσεις, οι εταιρείες και οι σημειώσεις μένουν μόνο στο κινητό σου (και στο Drive σου, αν το συνδέσεις). Δεν υπάρχει server, διαφημίσεις ή tracking.", "Calls, companies and notes stay only on your phone (and on your Drive, if you connect it). No server, no ads, no tracking."))
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(24.dp))
        }
    }
    if (export) ExportDialog(null) { export = false }
}
