package gr.wsol.billablecalls.sys

import gr.wsol.billablecalls.core.Billing
import gr.wsol.billablecalls.core.Call
import gr.wsol.billablecalls.core.Company
import gr.wsol.billablecalls.core.Data
import gr.wsol.billablecalls.core.Export
import gr.wsol.billablecalls.core.Overtime
import gr.wsol.billablecalls.core.Period
import gr.wsol.billablecalls.core.t
import gr.wsol.billablecalls.core.topic
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The report for the client: one HTML file that opens offline in any browser (phone or PC), with
 * search, and totals per person (contact) and per day. Everything is inside the file.
 */
object Html {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun report(d: Data, p: Period, company: Company?, calls: List<Call>, zone: ZoneId = ZoneId.systemDefault()): ByteArray {
        val day = DateTimeFormatter.ofPattern("dd/MM/yyyy"); val hm = DateTimeFormatter.ofPattern("HH:mm")
        val rows: JsonArray = buildJsonArray {
            calls.forEach { c ->
                val s = Instant.ofEpochMilli(c.start).atZone(zone); val e = Instant.ofEpochMilli(c.end).atZone(zone)
                add(buildJsonObject {
                    put("day", s.format(day)); put("sort", c.start); put("from", s.format(hm)); put("to", e.format(hm))
                    put("company", d.company(c.companyId)?.name.orEmpty()); put("who", c.contact ?: c.number)
                    put("dir", Export.direction(c.direction)); put("talk", c.durationSec); put("bill", Billing.seconds(c, d))
                    put("topic", c.category?.let(::topic).orEmpty()); put("note", c.note); put("ooh", Overtime.isOut(c, d.settings, zone)); put("onsite", d.settings.onsiteOn && c.onsite)
                })
            }
        }
        val s = d.settings
        val title = (if (s.timesheetOn && s.sheetName.isNotBlank()) s.sheetName + " · " else "") + "Timesheet · " + (company?.name ?: t("όλοι οι πελάτες", "all clients"))
        val L = mapOf(
            "search" to t("Αναζήτηση (επαφή, θέμα, σημείωση…)", "Search (contact, topic, note…)"), "calls" to t("Κλήσεις", "Calls"),
            "talk" to t("Ομιλία", "Talk"), "bill" to t("Χρεώσιμο", "Billable"), "byWho" to t("Ανά υπάλληλο", "Per person"), "byDay" to t("Ανά μέρα", "Per day"),
            "date" to t("Ημέρα", "Date"), "time" to t("Ώρα", "Time"), "who" to t("Επαφή", "Contact"), "company" to t("Εταιρεία", "Company"),
            "topic" to t("Θέμα / σημείωση", "Topic / note"), "period" to t("Περίοδος", "Period"), "ooh" to t("εκτός ωραρίου", "out of hours"), "onsite" to t("επιτόπια", "on site"),
        )
        val html = """<!doctype html><html lang="${if (gr.wsol.billablecalls.core.Lang.english) "en" else "el"}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title><style>
:root{--bg:#f6f7fb;--card:#fff;--text:#1b1d22;--muted:#667;--line:#e3e6ee;--brand:#1F5EFF}
@media(prefers-color-scheme:dark){:root{--bg:#111318;--card:#1b1e25;--text:#eceef3;--muted:#9aa;--line:#2b2f38;--brand:#8FB0FF}}
*{box-sizing:border-box}body{margin:0;font:15px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:var(--bg);color:var(--text)}
main{max-width:1000px;margin:0 auto;padding:16px}h1{font-size:22px;margin:4px 0}.muted{color:var(--muted)}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px;margin:14px 0}.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:12px}
.card b{display:block;font-size:22px;color:var(--brand)}input{width:100%;padding:10px 12px;border:1px solid var(--line);border-radius:10px;background:var(--card);color:var(--text);font:inherit}
table{width:100%;border-collapse:collapse;background:var(--card);border:1px solid var(--line);border-radius:14px;overflow:hidden;margin:10px 0}th,td{padding:8px 10px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}
th{font-size:13px;color:var(--muted);font-weight:600}td.n{text-align:right;white-space:nowrap}.tag{font-size:12px;color:var(--muted)}
.two{display:grid;grid-template-columns:1fr 1fr;gap:12px}@media(max-width:640px){.two{grid-template-columns:1fr}.hide{display:none}}
</style></head><body><main>
<h1>${esc(title)}</h1><div class="muted">${esc(L["period"]!!)}: ${esc(p.label)}${if (s.timesheetOn && s.sheetDetails.isNotBlank()) "<br>" + esc(s.sheetDetails).replace("\n", " · ") else ""}</div>
<div class="cards"><div class="card">${L["calls"]}<b id="c"></b></div><div class="card">${L["talk"]}<b id="t"></b></div><div class="card">${L["bill"]}<b id="b"></b></div></div>
<input id="q" placeholder="${esc(L["search"]!!)}">
<div class="two"><table><thead><tr><th>${L["byWho"]}</th><th class="n">${L["calls"]}</th><th class="n">${L["bill"]}</th></tr></thead><tbody id="w"></tbody></table>
<table><thead><tr><th>${L["byDay"]}</th><th class="n">${L["calls"]}</th><th class="n">${L["bill"]}</th></tr></thead><tbody id="d"></tbody></table></div>
<table><thead><tr><th>${L["date"]}</th><th>${L["time"]}</th><th>${L["who"]}</th>${if (company == null) "<th class=\"hide\">${L["company"]}</th>" else ""}<th class="hide">${L["topic"]}</th><th class="n">${L["talk"]}</th><th class="n">${L["bill"]}</th></tr></thead><tbody id="r"></tbody></table>
<p class="muted">Billable Calls</p></main>
<script>
const R=${rows};const ALL=${company == null};
const f=s=>{s=Math.round(s);const h=Math.floor(s/3600),m=Math.floor(s%3600/60),x=s%60;return h?h+':'+String(m).padStart(2,'0')+':'+String(x).padStart(2,'0'):m+':'+String(x).padStart(2,'0')};
const e=s=>String(s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const fold=s=>String(s).toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g,'');
function group(list,key){const m=new Map();list.forEach(r=>{const k=r[key];const g=m.get(k)||{n:0,b:0};g.n++;g.b+=r.bill;m.set(k,g)});return [...m.entries()]}
function draw(){const q=fold(document.getElementById('q').value);const L=R.filter(r=>!q||fold([r.who,r.company,r.topic,r.note,r.day].join(' ')).includes(q));
document.getElementById('c').textContent=L.length;document.getElementById('t').textContent=f(L.reduce((a,r)=>a+r.talk,0));document.getElementById('b').textContent=f(L.reduce((a,r)=>a+r.bill,0));
document.getElementById('w').innerHTML=group(L,'who').sort((a,b)=>b[1].b-a[1].b).map(([k,g])=>'<tr><td>'+e(k)+'</td><td class="n">'+g.n+'</td><td class="n">'+f(g.b)+'</td></tr>').join('');
document.getElementById('d').innerHTML=group([...L].sort((a,b)=>a.sort-b.sort),'day').map(([k,g])=>'<tr><td>'+e(k)+'</td><td class="n">'+g.n+'</td><td class="n">'+f(g.b)+'</td></tr>').join('');
document.getElementById('r').innerHTML=L.map(r=>'<tr><td>'+e(r.day)+'</td><td>'+e(r.from)+'–'+e(r.to)+'</td><td>'+e(r.who)+'</td>'+(ALL?'<td class="hide">'+e(r.company)+'</td>':'')+'<td class="hide">'+e([r.topic,r.note].filter(Boolean).join(' · '))+(r.ooh?' <span class="tag">⏰ ${L["ooh"]}</span>':'')+(r.onsite?' <span class="tag">📍 ${L["onsite"]}</span>':'')+'</td><td class="n">'+f(r.talk)+'</td><td class="n">'+f(r.bill)+'</td></tr>').join('')}
document.getElementById('q').addEventListener('input',draw);draw();
</script></body></html>"""
        return html.toByteArray(Charsets.UTF_8)
    }
}
