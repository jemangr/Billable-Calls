@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package gr.wsol.billablecalls.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gr.wsol.billablecalls.core.Company
import gr.wsol.billablecalls.core.Matcher
import gr.wsol.billablecalls.core.t
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun hm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(ms))
fun dayHm(ms: Long): String = SimpleDateFormat("dd/MM HH:mm", Locale.ROOT).format(Date(ms))
fun day(ms: Long): String = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).format(Date(ms))
fun clock(min: Int) = "%02d:%02d".format(min / 60, min % 60)

@Composable
fun SectionCard(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, sub: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange, enabled = enabled)
    }
}

/** A row of chips: one choice. */
@Composable
fun <T> Choice(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, l) -> FilterChip(selected = v == selected, onClick = { onPick(v) }, label = { Text(l) }) }
    }
}

/** Minutes/seconds presets with a «custom» chip that opens a number field. */
@Composable
fun Presets(values: List<Int>, selected: Int, label: (Int) -> String, customTitle: String, onPick: (Int) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { v -> FilterChip(selected = v == selected, onClick = { onPick(v) }, label = { Text(label(v)) }) }
        FilterChip(selected = selected !in values, onClick = { custom = true }, label = { Text(if (selected !in values) label(selected) + " ✎" else t("Άλλο…", "Custom…")) })
    }
    if (custom) NumberDialog(customTitle, selected, { custom = false }) { onPick(it); custom = false }
}

@Composable
fun NumberDialog(title: String, value: Int, onDismiss: () -> Unit, onOk: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(text, { text = it.filter { c -> c.isDigit() }.take(4) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) },
        confirmButton = { TextButton(onClick = { text.toIntOrNull()?.let(onOk) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Άκυρο", "Cancel")) } })
}

@Composable
fun TextDialog(title: String, value: String, label: String? = null, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = label?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onOk(text) }, enabled = text.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Άκυρο", "Cancel")) } })
}

/** «09:00 – 17:00» with a time picker for each end. */
@Composable
fun TimeRange(from: Int, to: Int, onChange: (Int, Int) -> Unit) {
    var which by remember { mutableStateOf(0) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { which = 1 }) { Text(t("από ", "from ") + clock(from)) }
        TextButton(onClick = { which = 2 }) { Text(t("έως ", "to ") + clock(to)) }
    }
    if (which != 0) {
        val start = if (which == 1) from else to
        val st = rememberTimePickerState(start / 60, start % 60, is24Hour = true)
        AlertDialog(onDismissRequest = { which = 0 },
            confirmButton = { TextButton(onClick = { val m = st.hour * 60 + st.minute; if (which == 1) onChange(m, to) else onChange(from, m); which = 0 }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { which = 0 }) { Text(t("Άκυρο", "Cancel")) } },
            text = { TimePicker(st) })
    }
}

/** The company list with search, and «new company». */
@Composable
fun CompanyPicker(companies: List<Company>, selected: String?, suggestions: List<String> = emptyList(), onAdd: (String) -> Unit, onPick: (Company) -> Unit) {
    var q by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val key = Matcher.norm(q)
    val shown = companies.filter { c -> key.isBlank() || Matcher.norm(c.name).contains(key) || c.aliases.any { Matcher.norm(it).contains(key) } }
        .sortedWith(compareByDescending<Company> { it.id == selected }.thenByDescending { it.id in suggestions }.thenBy { Matcher.norm(it.name) })
    Column {
        OutlinedTextField(q, { q = it }, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text(t("Αναζήτηση εταιρείας", "Search company")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
            items(shown, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text((if (c.id == selected) "✓ " else if (c.id in suggestions) "• " else "") + c.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (c.id == selected) FontWeight.Bold else FontWeight.Normal)
                }
                HorizontalDivider()
            }
        }
        TextButton(onClick = { adding = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(t("Νέα εταιρεία", "New company") + (q.takeIf { it.isNotBlank() && shown.isEmpty() }?.let { ": «$it»" } ?: "")) }
    }
    if (adding) TextDialog(t("Νέα εταιρεία", "New company"), q, t("Επωνυμία", "Name"), { adding = false }) { onAdd(it.trim()); adding = false }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) = Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** «When to remind»: none, in an hour, tomorrow 10:00, or a date and time. */
@Composable
fun ReminderPicker(at: Long?, onPick: (Long?) -> Unit) {
    var date by remember { mutableStateOf(false) }
    var day by remember { mutableStateOf<Long?>(null) }
    val now = System.currentTimeMillis()
    val tomorrow10 = java.time.LocalDate.now().plusDays(1).atTime(10, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(at == null, { onPick(null) }, label = { Text(t("Χωρίς υπενθύμιση", "No reminder")) })
        FilterChip(false, { onPick(now + 3600_000L) }, label = { Text(t("Σε 1 ώρα", "In 1 hour")) })
        FilterChip(false, { onPick(tomorrow10) }, label = { Text(t("Αύριο 10:00", "Tomorrow 10:00")) })
        FilterChip(false, { date = true }, label = { Text(t("Ημερομηνία…", "Date…")) })
    }
    at?.let { Muted("⏰ " + SimpleDateFormat("EEE dd/MM/yyyy HH:mm", if (gr.wsol.billablecalls.core.Lang.english) Locale.UK else Locale("el")).format(Date(it))) }
    if (date) {
        val st = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = (at ?: now))
        androidx.compose.material3.DatePickerDialog(onDismissRequest = { date = false },
            confirmButton = { TextButton(onClick = { day = st.selectedDateMillis; date = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { date = false }) { Text(t("Άκυρο", "Cancel")) } }) { androidx.compose.material3.DatePicker(st) }
    }
    day?.let { d0 ->
        val ts = rememberTimePickerState(10, 0, is24Hour = true)
        AlertDialog(onDismissRequest = { day = null },
            confirmButton = { TextButton(onClick = {
                // the picker gives the day at 00:00 UTC
                val ld = java.time.Instant.ofEpochMilli(d0).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                onPick(ld.atTime(ts.hour, ts.minute).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()); day = null
            }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { day = null }) { Text(t("Άκυρο", "Cancel")) } },
            text = { TimePicker(ts) })
    }
}
