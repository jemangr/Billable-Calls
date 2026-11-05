@file:OptIn(ExperimentalLayoutApi::class)

package gr.wsol.billablecalls.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gr.wsol.billablecalls.core.Billing
import gr.wsol.billablecalls.core.Company
import gr.wsol.billablecalls.core.Export
import gr.wsol.billablecalls.core.Ops
import gr.wsol.billablecalls.core.Period
import gr.wsol.billablecalls.core.Store
import gr.wsol.billablecalls.core.t
import gr.wsol.billablecalls.sys.Alarms
import gr.wsol.billablecalls.sys.CallSync
import gr.wsol.billablecalls.sys.Drive
import gr.wsol.billablecalls.sys.Files
import gr.wsol.billablecalls.sys.Format
import gr.wsol.billablecalls.sys.Sims
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Title(text: String, onBack: (() -> Unit)? = null, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = if (onBack == null) 16.dp else 4.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Πίσω", "Back")) } }
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        action()
    }
}

/** Today: billable time and calls per company, the calls of the day, the missed ones waiting. */
@Composable
fun TodayScreen(onCall: (Long) -> Unit, onMissed: () -> Unit) {
    val d by Store.flow.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = Period.today()
    val calls = d.calls.filter { p.contains(it.start) }.sortedByDescending { it.start }
    val totals = Export.totals(d, calls)
    val waiting = d.reminders.count { !it.stopped }
    val unreviewed = calls.filter { it.answered && !it.reviewed && !it.personal }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Title(t("Σήμερα", "Today")) {
                TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { CallSync.run(ctx, showCard = false) } } }) { Text(t("Ανανέωση", "Refresh")) }
            }
            SectionCard(t("Χρεώσιμος χρόνος: ", "Billable time: ") + Billing.nice(totals.sumOf { it.billableSec }),
                t("${calls.count { it.business }} εταιρικές κλήσεις από ${calls.size}", "${calls.count { it.business }} business calls of ${calls.size}")) {
                totals.forEach { tt ->
                    Row { Text(tt.company?.name.orEmpty(), Modifier.weight(1f)); Text("${tt.calls} · " + Billing.hms(tt.billableSec), fontWeight = FontWeight.SemiBold) }
                }
            }
            if (waiting > 0) SectionCard(t("$waiting αναπάντητες περιμένουν", "$waiting missed calls waiting")) {
                TextButton(onClick = onMissed) { Text(t("Δες τις", "See them")) }
            }
            if (unreviewed.isNotEmpty()) SectionCard(t("${unreviewed.size} κλήσεις χωρίς κάρτα", "${unreviewed.size} calls without a card"), t("Πάτα μια κλήση για εταιρεία, θέμα και σημείωση.", "Tap a call for company, topic and note.")) {}
            if (calls.isEmpty()) Muted(t("Καμία κλήση σήμερα ακόμα.", "No calls today yet."), Modifier.padding(16.dp))
        }
        items(calls, key = { it.id }) { c -> CallRow(c, d, false, onClick = { onCall(c.id) }, onLong = { onCall(c.id) }) }
    }
}

/** The companies with this month's totals; add, open. */
@Composable
fun CompaniesScreen(onOpen: (String) -> Unit) {
    val d by Store.flow.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var q by rememberSaveable { mutableStateOf("") }
    val month = Period.month()
    val totals = Export.totals(d, d.calls.filter { month.contains(it.start) }).associateBy { it.company?.id }
    val key = gr.wsol.billablecalls.core.Matcher.norm(q)
    val list = d.companies.filter { key.isBlank() || gr.wsol.billablecalls.core.Matcher.norm(it.name).contains(key) }
        .sortedWith(compareByDescending<Company> { totals[it.id]?.billableSec ?: 0 }.thenBy { gr.wsol.billablecalls.core.Matcher.norm(it.name) })
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Title(t("Εταιρείες", "Companies"))
                OutlinedTextField(q, { q = it }, placeholder = { Text(t("Αναζήτηση", "Search")) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                Muted(t("${d.companies.size} εταιρείες · σύνολα του μήνα", "${d.companies.size} companies · this month's totals"), Modifier.padding(16.dp, 6.dp))
                if (d.companies.isEmpty()) Muted(t("Πρόσθεσε εταιρείες με το «+» ή φέρε τη λίστα από τις Ρυθμίσεις → Λίστα πελατών.", "Add companies with «+» or load the list in Settings → Customer list."), Modifier.padding(16.dp))
            }
            items(list, key = { it.id }) { c ->
                val tt = totals[c.id]
                Row(Modifier.fillMaxWidth().clickable { onOpen(c.id) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, fontWeight = FontWeight.SemiBold)
                        if (c.aliases.isNotEmpty()) Muted(c.aliases.joinToString(" · "))
                    }
                    val st = gr.wsol.billablecalls.core.Packages.state(d, c)
                    if (tt != null || st != null) Column(horizontalAlignment = Alignment.End) {
                        Text(Billing.hms(tt?.billableSec ?: 0), fontWeight = FontWeight.Bold)
                        if (st != null) Muted(if (st.overSec > 0) t("πακέτο +${Billing.nice(st.overSec)}", "package +${Billing.nice(st.overSec)}") else t("απομένουν ${Billing.nice(st.leftSec)}", "${Billing.nice(st.leftSec)} left"))
                        else Muted(t("${tt?.calls ?: 0} κλήσεις", "${tt?.calls ?: 0} calls"))
                    }
                }
                HorizontalDivider()
            }
            item { Box(Modifier.padding(40.dp)) }
        }
        ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(t("Εταιρεία", "Company")) }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
    if (adding) TextDialog(t("Νέα εταιρεία", "New company"), "", t("Επωνυμία", "Name"), { adding = false }) { name -> Store.update { Ops.addCompany(it, name).first }; adding = false }
}

/** One company: name, aliases, its own billing rules, its calls, export, remove. */
@Composable
fun CompanyScreen(id: String, onCall: (Long) -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    val d by Store.flow.collectAsState()
    val ctx = LocalContext.current
    val c = d.company(id) ?: run { androidx.compose.runtime.LaunchedEffect(id) { onBack() }; return }
    var rename by remember { mutableStateOf(false) }
    var alias by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var export by remember { mutableStateOf(false) }
    var range by rememberSaveable { mutableStateOf(Range.MONTH) }
    var openOnly by rememberSaveable { mutableStateOf(false) }
    val calls = d.calls.filter { it.companyId == id && range.period().contains(it.start) && (!openOnly || it.doneAt == null) }.sortedByDescending { it.start }
    val tasks = d.followUps.filter { it.companyId == id && it.doneAt == null }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Title(c.name, onBack) { TextButton(onClick = { export = true }) { Text("Export") } }
            SectionCard(t("Στοιχεία", "Details")) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(c.name, Modifier.weight(1f)); TextButton(onClick = { rename = true }) { Text(t("Αλλαγή", "Edit")) } }
                Muted(t("Συνώνυμα (π.χ. διακριτικός τίτλος): ", "Aliases (e.g. trade name): ") + c.aliases.ifEmpty { listOf("-") }.joinToString(" · "))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    c.aliases.forEach { a -> FilterChip(true, { Store.update { Ops.saveCompany(it, c.copy(aliases = c.aliases - a)) } }, label = { Text("$a ✕") }) }
                    FilterChip(false, { alias = true }, label = { Text(t("+ συνώνυμο", "+ alias")) })
                }
            }
            SectionCard(t("Χρεώσιμος χρόνος για αυτή την εταιρεία", "Billable time for this company"), t("«Γενικό» = όπως στις Ρυθμίσεις.", "«General» = as in Settings.")) {
                Text(t("Μετράει πάνω από", "Counts above"), style = MaterialTheme.typography.titleSmall)
                MinPicker(c.minSec, general = true) { v -> Store.update { Ops.saveCompany(it, c.copy(minSec = v)) } }
                Text(t("Στρογγύλεμα προς τα πάνω", "Round up to"), style = MaterialTheme.typography.titleSmall)
                RoundPicker(c.roundMin, general = true) { v -> Store.update { Ops.saveCompany(it, c.copy(roundMin = v)) } }
            }
            if (d.settings.onsiteOn && d.settings.onsiteAuto) SectionCard(t("Τοποθεσία (για «επιτόπια»)", "Location (for «on site»)"), if (c.lat != null) t("✓ Αποθηκευμένη. Κλήσεις ως 300 μ. από εδώ προτείνονται ως επιτόπιες.", "✓ Saved. Calls within 300 m of it are suggested as on site.") else t("Πάτα όταν είσαι στον πελάτη.", "Tap when you're at the customer's.")) {
                val askLoc = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        if (!gr.wsol.billablecalls.sys.Place.allowed(ctx)) askLoc.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                        else gr.wsol.billablecalls.sys.Place.last(ctx)?.let { l -> Store.update { Ops.saveCompany(it, c.copy(lat = l.latitude, lng = l.longitude)) } }
                            ?: toast(ctx, t("Δεν υπάρχει πρόσφατη τοποθεσία. Άνοιξε για λίγο τους χάρτες και ξαναπάτα.", "No recent location. Open the maps for a moment and tap again."))
                    }) { Text(t("📍 Είμαι εδώ τώρα", "📍 I'm here now")) }
                    if (c.lat != null) TextButton(onClick = { Store.update { Ops.saveCompany(it, c.copy(lat = null, lng = null)) } }) { Text(t("Διαγραφή", "Clear")) }
                }
            }
            if (d.settings.packagesOn) SectionCard(t("Συμβόλαιο: ώρες τον μήνα", "Contract: hours per month"), t("Ο χρεώσιμος χρόνος αφαιρείται από το πακέτο· ειδοποίηση όταν ξεπεραστεί.", "Billable time comes off the package; a notification when it's exceeded.")) {
                Presets(listOf(0, 2, 5, 10, 20), (c.packageMin ?: 0) / 60, { if (it == 0) t("Χωρίς", "None") else t("$it ώρ.", "${it}h") }, t("Ώρες", "Hours")) { h ->
                    Store.update { Ops.saveCompany(it, c.copy(packageMin = (h * 60).takeIf { m -> m > 0 })) }
                }
                gr.wsol.billablecalls.core.Packages.state(d, c)?.let { st ->
                    Text(t("Αυτόν τον μήνα: ", "This month: ") + Billing.nice(st.usedSec) + t(" από ", " of ") + Billing.nice(st.includedSec) +
                        if (st.overSec > 0) t(" · πάνω από το πακέτο ", " · over by ") + Billing.nice(st.overSec) else t(" · απομένουν ", " · left ") + Billing.nice(st.leftSec),
                        color = if (st.overSec > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Range.MONTH, Range.LAST_MONTH, Range.ALL).forEach { r -> FilterChip(range == r, { range = r }, label = { Text(r.label()) }) }
            }
            if (tasks.isNotEmpty()) Text(t("Ενέργειες", "Actions"), Modifier.padding(16.dp, 8.dp, 16.dp, 0.dp), style = MaterialTheme.typography.titleMedium)
        }
        items(tasks, key = { "f" + it.id }) { f -> FollowUpRow(f) }
        item {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(!openOnly, { openOnly = false }, label = { Text(t("Όλες", "All")) })
                FilterChip(openOnly, { openOnly = true }, label = { Text(t("Ανοιχτές", "Open")) })
            }
            Muted(t("${calls.size} καταγραφές · χρεώσιμος χρόνος ", "${calls.size} records · billable ") + Billing.nice(calls.sumOf { Billing.seconds(it, d) }) + t(" · ✓ = ολοκληρώθηκε", " · ✓ = completed"), Modifier.padding(16.dp, 4.dp))
        }
        items(calls, key = { it.id }) { cl ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(cl.doneAt != null, { v -> Store.update { Ops.callDone(it, cl.id, v) } })
                Box(Modifier.weight(1f)) { CallRow(cl, d, false, onClick = { onCall(cl.id) }, onLong = { onCall(cl.id) }) }
            }
        }
        item {
            OutlinedButton(onClick = { remove = true }, modifier = Modifier.padding(16.dp)) { Text(t("Αφαίρεση εταιρείας", "Remove company"), color = MaterialTheme.colorScheme.error) }
        }
    }
    if (rename) TextDialog(t("Επωνυμία", "Name"), c.name, null, { rename = false }) { n -> Store.update { Ops.saveCompany(it, c.copy(name = n.trim())) }; rename = false }
    if (alias) TextDialog(t("Νέο συνώνυμο", "New alias"), "", null, { alias = false }) { a -> Store.update { Ops.saveCompany(it, c.copy(aliases = (c.aliases + a.trim()).distinct())) }; alias = false }
    if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text(t("Αφαίρεση «${c.name}»;", "Remove «${c.name}»?")) },
        text = { Text(t("Οι κλήσεις της μένουν, αλλά πάνε στις «Άλλες» ως «Χωρίς εταιρεία». Οι κανόνες των αριθμών της σβήνονται.", "Its calls stay, but move to «Other» as «No company». The rules of its numbers are removed.")) },
        confirmButton = { Button(onClick = { Store.update { Ops.removeCompany(it, c.id) }; remove = false; onBack() }) { Text(t("Αφαίρεση", "Remove")) } },
        dismissButton = { TextButton(onClick = { remove = false }) { Text(t("Άκυρο", "Cancel")) } })
    if (export) ExportDialog(c, range) { export = false }
}

@Composable
fun MinPicker(value: Int?, general: Boolean, onPick: (Int?) -> Unit) {
    Column {
        if (general) FilterChip(value == null, { onPick(null) }, label = { Text(t("Γενικό (${Store.settings.minSec / 60}′)", "General (${Store.settings.minSec / 60}′)")) })
        Presets(listOf(1, 5, 10, 15), value?.let { it / 60 } ?: -1, { "$it′" }, t("Λεπτά", "Minutes")) { m -> onPick(m * 60) }
    }
}

@Composable
fun RoundPicker(value: Int?, general: Boolean, onPick: (Int?) -> Unit) {
    val presets = listOf(0, 5, 10, 15, 30)
    Column {
        if (general) FilterChip(value == null, { onPick(null) }, label = { Text(t("Γενικό (${Store.settings.roundMin}′)", "General (${Store.settings.roundMin}′)")) })
        Presets(presets, value ?: -1, { if (it == 0) t("Ακριβής", "Exact") else "$it′" }, t("Λεπτά", "Minutes")) { onPick(it) }
    }
}

/** Export: period, format, and share or straight to Drive. */
@Composable
fun ExportDialog(company: Company?, start: Range = Range.MONTH, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var range by remember { mutableStateOf(start) }
    var fmt by remember { mutableStateOf(Format.XLSX) }
    var busy by remember { mutableStateOf(false) }
    val d = Store.data
    // invoiced or not (when on): only the calls not invoiced yet, then mark them invoiced
    var unbilled by remember { mutableStateOf(d.settings.billedOn) }
    var mark by remember { mutableStateOf(d.settings.billedOn) }
    fun ids(p: Period) = Export.calls(Store.data, p, company?.id, onlyUnbilled = unbilled).map { it.id }.toSet()
    fun done(p: Period, list: Set<Long>) { if (d.settings.billedOn && mark) Store.update { Export.markBilled(it, list) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Export · " + (company?.name ?: t("όλες οι εταιρείες", "all companies"))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(listOf(Range.TODAY, Range.WEEK, Range.MONTH, Range.LAST_MONTH, Range.ALL).map { it to it.label() }, range) { range = it }
                Choice(listOf(Format.XLSX to "Excel", Format.CSV to "CSV", Format.PDF to "PDF", Format.HTML to t("HTML για τον πελάτη", "HTML for the client")), fmt) { fmt = it }
                if (d.settings.billedOn) {
                    SwitchRow(t("Μόνο μη τιμολογημένες", "Only not invoiced"), unbilled) { unbilled = it }
                    SwitchRow(t("Σημείωσέ τες «τιμολογήθηκαν»", "Mark them «invoiced»"), mark) { mark = it }
                }
                Muted(t("${ids(range.period()).size} εταιρικές κλήσεις", "${ids(range.period()).size} business calls"))
                if (busy) Muted(t("Ετοιμάζεται…", "Working…"))
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val p = range.period()
                    val sims = Sims.list(ctx).mapIndexed { i, (id, label) -> id to "SIM ${i + 1} ($label)" }.toMap()
                    val list = ids(p)
                    val bytes = withContext(Dispatchers.Default) { Files.export(Store.data, p, company, fmt, sims, unbilled, Files.logo(ctx)) }
                    Files.share(ctx, Export.fileName(p, company, fmt.ext), fmt.mime, bytes); done(p, list); busy = false; onDismiss()
                }
            }) { Text(t("Κοινοποίηση", "Share")) }
        },
        dismissButton = {
            Row {
                if (d.settings.driveFolderId.isNotEmpty()) TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val p = range.period()
                        val r = withContext(Dispatchers.IO) {
                            runCatching {
                                val tok = Drive.token(ctx) ?: error(t("Σύνδεσε ξανά το Google (Ρυθμίσεις).", "Sign in to Google again (Settings)."))
                                val folder = Drive.folder(tok, Store.settings.driveFolderId)
                                val list = ids(p)
                                Drive.upload(tok, folder, Export.fileName(p, company, fmt.ext), fmt.mime, Files.export(Store.data, p, company, fmt, emptyMap(), unbilled, Files.logo(ctx))).also { done(p, list) }
                            }
                        }
                        Toast.makeText(ctx, if (r.isSuccess) t("Αποθηκεύτηκε στο Drive, φάκελος «Billable Calls».", "Saved to Drive, folder «Billable Calls».") else r.exceptionOrNull()?.message ?: "Error", Toast.LENGTH_LONG).show()
                        busy = false; if (r.isSuccess) onDismiss()
                    }
                }) { Text("Drive") }
                TextButton(onClick = onDismiss) { Text(t("Άκυρο", "Cancel")) }
            }
        })
}

/** One action (to do), with ✓ done, its reminder, and remove. */
@Composable
fun FollowUpRow(f: gr.wsol.billablecalls.core.FollowUp) {
    val d by Store.flow.collectAsState()
    val ctx = LocalContext.current
    var edit by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(f.doneAt != null, { v -> Store.update { Ops.followUpDone(it, f.id, v) }; gr.wsol.billablecalls.sys.Alarms.schedule(ctx) })
        Column(Modifier.weight(1f).clickable { edit = true }) {
            Text(f.text.ifBlank { t("(χωρίς κείμενο)", "(no text)") }, fontWeight = FontWeight.SemiBold,
                textDecoration = if (f.doneAt != null) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
            Muted(listOfNotNull(d.company(f.companyId)?.name ?: f.label.takeIf { it.isNotBlank() }, f.at?.let { "⏰ " + dayHm(it) }, f.doneAt?.let { t("ολοκληρώθηκε ", "done ") + dayHm(it) }).joinToString(" · "))
        }
        TextButton(onClick = { Store.update { Ops.removeFollowUp(it, f.id) }; gr.wsol.billablecalls.sys.Alarms.schedule(ctx) }) { Text("✕") }
    }
    HorizontalDivider()
    if (edit) {
        var text by remember { mutableStateOf(f.text) }
        var at by remember { mutableStateOf(f.at) }
        AlertDialog(onDismissRequest = { edit = false }, title = { Text(t("Ενέργεια", "Action")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth()); ReminderPicker(at) { at = it } } },
            confirmButton = { TextButton(onClick = { Store.update { Ops.addFollowUp(it, f.copy(text = text.trim(), at = at, notified = at == f.at && f.notified)) }; gr.wsol.billablecalls.sys.Alarms.schedule(ctx); edit = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { edit = false }) { Text(t("Άκυρο", "Cancel")) } })
    }
}

/** The missed calls with a reminder: call, later, ignore; and the ones that stopped (and why). */
@Composable
fun MissedScreen() {
    val d by Store.flow.collectAsState()
    val ctx = LocalContext.current
    val active = d.reminders.filter { !it.stopped }.sortedByDescending { it.callStart }
    val done = d.reminders.filter { it.stopped }.sortedByDescending { it.stoppedAt ?: 0 }.take(30)
    var showDone by rememberSaveable { mutableStateOf(false) }
    val tasks = d.followUps.filter { showDone || it.doneAt == null }.sortedWith(compareBy({ it.doneAt != null }, { it.at ?: Long.MAX_VALUE }, { -it.created }))
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Title(t("Εκκρεμή", "To do"))
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("Ενέργειες", "Actions"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                FilterChip(showDone, { showDone = !showDone }, label = { Text(t("και ολοκληρωμένες", "with completed")) })
            }
            if (tasks.isEmpty()) Muted(t("Καμία ενέργεια. Στην κάρτα της κλήσης: «Απαιτείται ενέργεια / υπενθύμιση».", "No actions. On the call's card: «Action needed / reminder»."), Modifier.padding(16.dp, 4.dp))
        }
        items(tasks, key = { "f" + it.id }) { f -> FollowUpRow(f) }
        item {
            Text(t("Αναπάντητες", "Missed"), Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleMedium)
            if (active.isEmpty()) Muted(t("Καμία αναπάντητη δεν περιμένει.", "No missed call is waiting."), Modifier.padding(16.dp))
        }
        items(active, key = { "a" + it.key }) { r ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(r.label + (d.company(r.companyId)?.name?.takeIf { it != r.label }?.let { " · $it" } ?: ""), fontWeight = FontWeight.SemiBold)
                Muted("${arrow(r.direction)} ${dayHm(r.callStart)} · " + t("υπενθυμίσεις ${r.count}", "reminders ${r.count}") +
                    (if (r.nextAt != Long.MAX_VALUE) t(" · επόμενη ${dayHm(r.nextAt)}", " · next ${dayHm(r.nextAt)}") else ""))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Alarms.act(ctx, Alarms.ACTION_CALL, r.key) }) { Text(t("Κάλεσε", "Call")) }
                    OutlinedButton(onClick = { Alarms.act(ctx, Alarms.ACTION_LATER, r.key) }) { Text(t("Αργότερα", "Later")) }
                    TextButton(onClick = { Alarms.act(ctx, Alarms.ACTION_IGNORE, r.key) }) { Text(t("Αγνόησε", "Ignore")) }
                }
            }
            HorizontalDivider()
        }
        if (done.isNotEmpty()) item { Text(t("Σταμάτησαν", "Stopped"), Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleMedium) }
        items(done, key = { "d" + it.key + it.callStart }) { r ->
            val why = when (r.reason) {
                "talked" -> t("μιλήσατε", "you talked"); "count" -> t("μετά από ${r.count} υπενθυμίσεις", "after ${r.count} reminders")
                "time" -> t("πέρασε ο χρόνος", "time limit"); else -> t("αγνοήθηκε", "ignored")
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(r.label); Muted("${dayHm(r.callStart)} · $why")
            }
            HorizontalDivider()
        }
    }
}

