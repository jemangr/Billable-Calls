package gr.wsol.billablecalls.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** CSV and Excel (.xlsx), read and written without libraries. */
object Sheets {
    // ---------- reading ----------

    /** Rows of a CSV: the delimiter (; , or tab) is found from the first line; quotes are handled. */
    fun readCsv(text: String): List<List<String>> {
        val t = text.removePrefix("﻿")
        val first = t.lineSequence().firstOrNull().orEmpty()
        val delim = listOf(';', '\t', ',').maxBy { c -> first.count { it == c } }.takeIf { c -> first.count { it == c } > 0 } ?: ';'
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        while (i < t.length) {
            val ch = t[i]
            when {
                quoted && ch == '"' && i + 1 < t.length && t[i + 1] == '"' -> { cell.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                !quoted && ch == delim -> { row += cell.toString(); cell.clear() }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && i + 1 < t.length && t[i + 1] == '\n') i++
                    row += cell.toString(); cell.clear(); rows += row; row = mutableListOf()
                }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows.map { r -> r.map { it.trim() } }.filter { r -> r.any { it.isNotBlank() } }
    }

    /** Rows of the first sheet of an .xlsx file. */
    fun readXlsx(bytes: ByteArray): List<List<String>> {
        val files = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name == "xl/sharedStrings.xml" || e.name.startsWith("xl/worksheets/sheet") || e.name == "xl/workbook.xml" || e.name == "xl/_rels/workbook.xml.rels") files[e.name] = z.readBytes().toString(Charsets.UTF_8)
                e = z.nextEntry
            }
        }
        val shared = files["xl/sharedStrings.xml"]?.let { s ->
            Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(s).map { si -> Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(si.groupValues[1]).joinToString("") { unxml(it.groupValues[1]) } }.toList()
        }.orEmpty()
        val sheetName = firstSheet(files) ?: files.keys.filter { it.startsWith("xl/worksheets/sheet") }.minOrNull() ?: return emptyList()
        val sheet = files[sheetName] ?: return emptyList()
        val rows = mutableListOf<List<String>>()
        for (r in Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(sheet)) {
            val cells = sortedMapOf<Int, String>()
            for (c in Regex("<c ([^>]*?)(/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL).findAll(r.groupValues[1])) {
                val attrs = c.groupValues[1]; val body = c.groupValues[3]
                val ref = Regex("r=\"([A-Z]+)\\d*\"").find(attrs)?.groupValues?.get(1)
                val col = ref?.let { colIndex(it) } ?: cells.size
                val type = Regex("t=\"(\\w+)\"").find(attrs)?.groupValues?.get(1)
                val v = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                val value = when (type) {
                    "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(body).joinToString("") { unxml(it.groupValues[1]) }
                    else -> v?.let { unxml(it) }.orEmpty()
                }
                cells[col] = value.trim()
            }
            if (cells.isEmpty()) continue
            val n = cells.lastKey() + 1
            rows += List(n) { cells[it].orEmpty() }
        }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    private fun firstSheet(files: Map<String, String>): String? {
        val wb = files["xl/workbook.xml"] ?: return null
        val rid = Regex("<sheet [^>]*r:id=\"([^\"]+)\"").find(wb)?.groupValues?.get(1) ?: return null
        val rels = files["xl/_rels/workbook.xml.rels"] ?: return null
        val target = Regex("<Relationship [^>]*Id=\"$rid\"[^>]*Target=\"([^\"]+)\"").find(rels)?.groupValues?.get(1)
            ?: Regex("<Relationship [^>]*Target=\"([^\"]+)\"[^>]*Id=\"$rid\"").find(rels)?.groupValues?.get(1) ?: return null
        return "xl/" + target.removePrefix("/xl/").removePrefix("/")
    }

    private fun colIndex(letters: String) = letters.fold(0) { a, ch -> a * 26 + (ch - 'A' + 1) } - 1

    private fun unxml(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }

    /**
     * The customer list from rows: column A the name, the other columns aliases (a cell may hold
     * several, split by «|»). A first row of titles («Επωνυμία», «Name»…) is skipped.
     */
    fun customers(rows: List<List<String>>): List<Pair<String, List<String>>> {
        val titles = listOf("ΕΠΩΝΥΜΙΑ", "ΕΤΑΙΡΕΙΑ", "ΕΤΑΙΡΙΑ", "ΠΕΛΑΤΗΣ", "ΟΝΟΜΑ", "NAME", "COMPANY", "CUSTOMER")
        val body = if (rows.isNotEmpty() && Matcher.norm(rows[0].firstOrNull().orEmpty()).let { h -> titles.any { h.startsWith(it) } }) rows.drop(1) else rows
        return body.mapNotNull { r ->
            val name = r.firstOrNull()?.trim().orEmpty()
            if (name.isBlank()) null else name to r.drop(1).flatMap { it.split('|') }.map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    // ---------- writing ----------

    fun csv(rows: List<List<Any?>>): ByteArray {
        val sb = StringBuilder("﻿")
        for (r in rows) {
            sb.append(r.joinToString(";") { v ->
                val s = when (v) { null -> ""; is Double -> "%.2f".format(java.util.Locale.ROOT, v).replace('.', ','); else -> v.toString() }
                if (s.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
            })
            sb.append("\r\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** An .xlsx with one sheet per entry: (name, rows). The first row of each sheet is bold. */
    fun xlsx(sheets: List<Pair<String, List<List<Any?>>>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
                sheets.indices.joinToString("") { """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" } + "</Types>")
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
                sheets.mapIndexed { i, (n, _) -> """<sheet name="${xml(n.take(31))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("") + "</sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                sheets.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" } +
                """<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="3"><xf/><xf fontId="1" applyFont="1"/><xf numFmtId="2" applyNumberFormat="1"/></cellXfs></styleSheet>""")
            sheets.forEachIndexed { i, (_, rows) ->
                val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
                rows.forEachIndexed { ri, r ->
                    sb.append("<row r=\"${ri + 1}\">")
                    r.forEachIndexed { ci, v ->
                        val ref = colName(ci) + (ri + 1)
                        when (v) {
                            null -> {}
                            is Number -> sb.append("<c r=\"$ref\"${if (v is Double) " s=\"2\"" else ""}><v>${v}</v></c>")
                            else -> sb.append("<c r=\"$ref\" t=\"inlineStr\"${if (ri == 0) " s=\"1\"" else ""}><is><t xml:space=\"preserve\">${xml(v.toString())}</t></is></c>")
                        }
                    }
                    sb.append("</row>")
                }
                sb.append("</sheetData></worksheet>")
                put("xl/worksheets/sheet${i + 1}.xml", sb.toString())
            }
        }
        return out.toByteArray()
    }

    private fun colName(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val r = (n - 1) % 26; sb.insert(0, ('A' + r)); n = (n - 1) / 26 }; return sb.toString() }
}
