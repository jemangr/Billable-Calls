package gr.wsol.billablecalls

import gr.wsol.billablecalls.core.Billing
import gr.wsol.billablecalls.core.Call
import gr.wsol.billablecalls.core.Company
import gr.wsol.billablecalls.core.Data
import gr.wsol.billablecalls.core.Direction
import gr.wsol.billablecalls.core.Export
import gr.wsol.billablecalls.core.Hours
import gr.wsol.billablecalls.core.How
import gr.wsol.billablecalls.core.MatchMode
import gr.wsol.billablecalls.core.Matcher
import gr.wsol.billablecalls.core.NamePosition
import gr.wsol.billablecalls.core.Ops
import gr.wsol.billablecalls.core.Period
import gr.wsol.billablecalls.core.Phone
import gr.wsol.billablecalls.core.ReminderSettings
import gr.wsol.billablecalls.core.Reminders
import gr.wsol.billablecalls.core.Schedule
import gr.wsol.billablecalls.core.Separator
import gr.wsol.billablecalls.core.Settings
import gr.wsol.billablecalls.core.Sheets
import gr.wsol.billablecalls.core.Window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class MatcherTest {
    private val abg = Company("a", "ΑΒΓ Α.Ε.", listOf("Φούρνος Νίκου"))
    private val delta = Company("d", "ΔΕΛΤΑ ΕΠΕ")
    private val dm = Company("m", "Δέλτα Μάρκετ ΙΚΕ")
    private val all = listOf(abg, delta, dm)
    private val s = Settings()

    @Test fun normIgnoresAccentsCaseAndLegalForm() {
        assertEquals("ΑΒΓ", Matcher.norm("Α.Β.Γ. Α.Ε."))
        assertEquals("ΑΒΓ", Matcher.norm("αβγ αε"))
        assertEquals("ΔΕΛΤΑ ΜΑΡΚΕΤ", Matcher.norm("Δέλτα  Μάρκετ Ι.Κ.Ε."))
        assertEquals("ACME", Matcher.norm("Acme Ltd."))
    }

    @Test fun companyFieldWins() {
        val m = Matcher.match("Νίκος Παπάς", "ΑΒΓ ΑΕ", all, s)
        assertEquals("a", m.companyId); assertEquals(How.COMPANY_FIELD, m.how)
    }

    @Test fun nameBeforeWithSeparator() {
        assertEquals("a", Matcher.match("ΑΒΓ - Νίκος", null, all, s).companyId)
        assertEquals("a", Matcher.match("ΑΒΓ | Νίκος", null, all, s).companyId)
        assertEquals("a", Matcher.match("Φούρνος Νίκου / Μαρία", null, all, s).companyId)
    }

    @Test fun nameAfter() {
        val after = s.copy(namePosition = NamePosition.AFTER)
        assertEquals("a", Matcher.match("Νίκος - ΑΒΓ", null, all, after).companyId)
        assertEquals("a", Matcher.match("Νίκος ΑΒΓ", null, all, after).companyId)
        assertNull(Matcher.match("ΑΒΓ Νίκος", null, all, after).companyId)
    }

    @Test fun nameWithSpaceTakesTheLongestCompany() {
        // «Δέλτα Μάρκετ Γιώργος»: Δέλτα Μάρκετ, not Δέλτα
        assertEquals("m", Matcher.match("Δέλτα Μάρκετ Γιώργος", null, all, s).companyId)
        assertEquals("d", Matcher.match("Δέλτα Γιώργος", null, all, s).companyId)
    }

    @Test fun onlyWhenSure() {
        val twin = all + Company("x", "ΑΒΓ ΕΠΕ") // same key as ΑΒΓ Α.Ε.
        assertNull(Matcher.match("ΑΒΓ - Νίκος", null, twin, s).companyId)
        assertNull(Matcher.match("Νίκος Παπαδόπουλος", null, all, s).companyId)
    }

    @Test fun modes() {
        assertNull(Matcher.match("ΑΒΓ - Νίκος", null, all, s.copy(matchMode = MatchMode.COMPANY_FIELD)).companyId)
        assertNull(Matcher.match("Νίκος", "ΑΒΓ", all, s.copy(matchMode = MatchMode.NAME)).companyId)
    }

    @Test fun separatorSetting() {
        val pipe = s.copy(separator = Separator.PIPE)
        assertEquals("a", Matcher.match("ΑΒΓ|Νίκος", null, all, pipe).companyId)
        assertEquals("a", Matcher.match("(ΑΒΓ) Νίκος", null, all, s.copy(separator = Separator.PAREN)).companyId)
    }

    @Test fun suggestions() {
        val m = Matcher.match("Νίκος Δέλτα-λογιστήριο", null, listOf(delta, dm), s.copy(matchMode = MatchMode.COMPANY_FIELD))
        assertTrue(m.suggestions.containsAll(listOf("d", "m")))
    }

    @Test fun phoneKeys() {
        assertEquals("6971234567", Phone.key("+30 697 123 4567"))
        assertEquals("6971234567", Phone.key("00306971234567"))
        assertEquals("2101234567", Phone.key("210-1234567"))
        assertEquals("00447700900123", Phone.key("+44 7700 900123"))
    }
}

class BillingTest {
    private val s = Settings(minSec = 60, roundMin = 15, billByCategory = false)
    private val c = Company("a", "ΑΒΓ")
    private fun call(sec: Int, dir: Direction = Direction.IN, billable: Boolean? = null) = Call(1, "1", "1", direction = dir, start = 0, durationSec = sec, companyId = "a", billable = billable)

    @Test fun thresholdAndRounding() {
        assertEquals(0, Billing.seconds(call(60), c, s)) // «πάνω από 1′»
        assertEquals(15 * 60, Billing.seconds(call(61), c, s))
        assertEquals(15 * 60, Billing.seconds(call(4 * 60), c, s))
        assertEquals(30 * 60, Billing.seconds(call(16 * 60), c, s))
        assertEquals(30 * 60, Billing.seconds(call(30 * 60), c, s))
    }

    @Test fun perCompanyAndExact() {
        val own = c.copy(minSec = 300, roundMin = 0)
        assertEquals(0, Billing.seconds(call(299), own, s))
        assertEquals(301, Billing.seconds(call(301), own, s))
    }

    @Test fun manualAndMissed() {
        assertEquals(15 * 60, Billing.seconds(call(30, billable = true), c, s))
        assertEquals(0, Billing.seconds(call(3600, billable = false), c, s))
        assertEquals(0, Billing.seconds(call(0, Direction.MISSED), c, s))
        assertEquals(0, Billing.seconds(call(600).copy(companyId = null), null, s))
        assertEquals(0, Billing.seconds(call(600).copy(personal = true), c, s))
    }

    @Test fun byTopic() {
        val t = Settings(minSec = 60, roundMin = 15, categories = listOf("Υποστήριξη", "Άλλο"), billableCategories = listOf("Υποστήριξη"))
        assertEquals(15 * 60, Billing.seconds(call(240).copy(category = "Υποστήριξη"), c, t))
        assertEquals(0, Billing.seconds(call(240).copy(category = "Άλλο"), c, t))
        assertEquals(0, Billing.seconds(call(240), c, t))
        // a manual yes still wins
        assertEquals(15 * 60, Billing.seconds(call(240, billable = true).copy(category = "Άλλο"), c, t))
    }

    @Test fun formats() {
        assertEquals("4:05", Billing.hms(245))
        assertEquals("1:00:00", Billing.hms(3600))
    }
}

class HoursTest {
    private val z = ZoneId.of("Europe/Athens")
    private val s = Schedule(quiet = listOf(Window(true, 14 * 60, 15 * 60)), saturday = Window(true, 10 * 60, 13 * 60))
    // 6 Nov 2026 is a Friday
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m)

    @Test fun insideAndQuiet() {
        assertTrue(Hours.allowed(s, at(6, 9)))
        assertFalse(Hours.allowed(s, at(6, 14, 30)))
        assertFalse(Hours.allowed(s, at(6, 17)))
        assertEquals(at(6, 15), Hours.next(s, at(6, 14, 10)))
        assertEquals(at(6, 11, 20), Hours.next(s, at(6, 11, 20)))
    }

    @Test fun eveningGoesToTheNextWorkingStart() {
        assertEquals(at(7, 10), Hours.next(s, at(6, 18))) // Saturday 10:00
        assertEquals(at(9, 9), Hours.next(s, at(7, 13, 30))) // Sunday off → Monday 09:00
    }

    @Test fun sameEveryDay() {
        val e = Schedule(sameEveryDay = true, everyDay = Window(true, 9 * 60, 21 * 60))
        assertEquals(at(8, 9), Hours.next(e, at(7, 22))) // Sunday 09:00
    }

    @Test fun nothingAllowed() {
        val off = Schedule(weekdays = Window(false), saturday = Window(false), sunday = Window(false))
        assertNull(Hours.next(off, at(6, 9)))
    }

    private fun ms(t: LocalDateTime) = t.atZone(z).toInstant().toEpochMilli()

    @Test fun remindersStopAtTheFirstCondition() {
        val missed = Call(1, "697", "697", direction = Direction.MISSED, start = ms(at(6, 9)), durationSec = 0, companyId = "a")
        var d = Data(companies = listOf(Company("a", "ΑΒΓ")), calls = listOf(missed), settings = Settings(reminders = ReminderSettings(stopCount = true, maxCount = 2, stopTime = true, maxMinutes = 24 * 60)))
        assertTrue(Reminders.wanted(missed, d))
        d = d.copy(reminders = Reminders.add(missed, d, ms(at(6, 9)), z))
        val r0 = d.reminders.single()
        assertEquals(ms(at(6, 9, 30)), r0.nextAt)
        val r1 = Reminders.afterRing(r0, d, ms(at(6, 9, 30)), z)
        assertFalse(r1.stopped); assertEquals(ms(at(6, 10)), r1.nextAt)
        val r2 = Reminders.afterRing(r1, d, ms(at(6, 10)), z)
        assertTrue(r2.stopped); assertEquals("count", r2.reason)
        // talked: an answered call after the missed one
        val talked = d.copy(calls = d.calls + missed.copy(id = 2, direction = Direction.OUT, start = ms(at(6, 9, 40)), durationSec = 120))
        assertEquals("talked", Reminders.stopReason(r1, talked, ms(at(6, 10))))
        // time: a day later
        assertEquals("time", Reminders.stopReason(r0, d.copy(settings = d.settings.copy(reminders = d.settings.reminders.copy(stopCount = false))), ms(at(7, 9, 1))))
    }

    @Test fun whoGetsAReminder() {
        val d = Data(settings = Settings())
        val unknown = Call(1, "1", "1", direction = Direction.MISSED, start = 0, durationSec = 0)
        val friend = unknown.copy(contact = "Μαμά")
        val outNo = unknown.copy(direction = Direction.OUT_NO_ANSWER, companyId = "a")
        assertTrue(Reminders.wanted(unknown, d))
        assertFalse(Reminders.wanted(friend, d))
        assertTrue(Reminders.wanted(outNo, d))
        assertFalse(Reminders.wanted(outNo, d.copy(settings = Settings(reminders = ReminderSettings(outgoing = false)))))
        assertFalse(Reminders.wanted(unknown.copy(personal = true), d))
    }

    @Test fun twoMissedCallsOneReminder() {
        val a = Call(1, "697", "697", direction = Direction.MISSED, start = ms(at(6, 9)), durationSec = 0)
        var d = Data(calls = listOf(a))
        d = d.copy(reminders = Reminders.add(a, d, ms(at(6, 9)), z))
        val b = a.copy(id = 2, start = ms(at(6, 9, 10)))
        d = d.copy(reminders = Reminders.add(b, d, ms(at(6, 9, 10)), z))
        assertEquals(1, d.reminders.size); assertEquals(ms(at(6, 9, 10)), d.reminders.single().callStart)
    }
}

class OpsTest {
    private val a = Company("a", "ΑΒΓ ΑΕ")
    private fun call(id: Long, key: String, contact: String?) = Call(id, key, key, contact = contact, direction = Direction.IN, start = id * 1000, durationSec = 120)

    @Test fun assignOnlyTheseOrTheNumber() {
        var d = Data(companies = listOf(a), calls = listOf(call(1, "111", "Κώστας"), call(2, "111", "Κώστας"), call(3, "222", null)))
        d = Ops.assign(d, setOf(1), "a", forNumbers = false)
        assertEquals(listOf("a", null, null), d.calls.map { it.companyId })
        d = Ops.assign(d, setOf(1), "a", forNumbers = true)
        assertEquals(listOf("a", "a", null), d.calls.map { it.companyId })
        assertEquals("a", d.rule("111")?.companyId)
        // a new call of the number takes the rule
        assertEquals("a", Ops.classify(call(4, "111", "Κώστας"), d).companyId)
        // remove from business: personal, and the rule says personal
        d = Ops.assign(d, setOf(2), null, forNumbers = true)
        assertTrue(d.calls.filter { it.key == "111" }.all { it.personal && it.companyId == null })
        assertTrue(Ops.classify(call(5, "111", "Κώστας"), d).personal)
    }

    @Test fun listImportKeepsIdsAndManualOnes() {
        var d = Data(companies = listOf(a.copy(source = "list", minSec = 300), Company("m", "Χειροκίνητη")), calls = listOf(call(1, "1", "ΑΒΓ - Νίκος")))
        d = Ops.rematch(d)
        assertEquals("a", d.calls.single().companyId)
        d = Ops.importList(d, listOf("ΑΒΓ Α.Ε." to listOf("ΑΒΓ"), "Νέα ΙΚΕ" to emptyList()))
        assertEquals(setOf("ΑΒΓ Α.Ε.", "Νέα ΙΚΕ", "Χειροκίνητη"), d.companies.map { it.name }.toSet())
        assertEquals(300, d.company("a")?.minSec)
        assertEquals("a", d.calls.single().companyId)
    }

    @Test fun removeCompany() {
        var d = Ops.rematch(Data(companies = listOf(a), calls = listOf(call(1, "1", "ΑΒΓ - Νίκος"))))
        d = Ops.removeCompany(d, "a")
        assertNull(d.calls.single().companyId)
    }
}

class SheetsTest {
    @Test fun csvRoundTrip() {
        val rows = Sheets.readCsv(Sheets.csv(listOf(listOf("Επωνυμία", "Συνώνυμα"), listOf("ΑΒΓ; ΑΕ", "x \"y\""), listOf("Δ", 1.5))).toString(Charsets.UTF_8))
        assertEquals(listOf("ΑΒΓ; ΑΕ", "x \"y\""), rows[1])
        assertEquals("1,50", rows[2][1])
    }

    @Test fun csvDelimiters() {
        assertEquals(listOf(listOf("Α", "Β"), listOf("Γ", "")), Sheets.readCsv("Α,Β\nΓ,\n"))
        assertEquals(listOf(listOf("Α", "Β")), Sheets.readCsv("﻿Α\tΒ\r\n"))
    }

    @Test fun customersFromRows() {
        val c = Sheets.customers(listOf(listOf("Επωνυμία", "Συνώνυμα"), listOf("ΑΒΓ ΑΕ", "ΑΒΓ | Φούρνος", "Άλλο"), listOf("", "x"), listOf("Δ")))
        assertEquals(listOf("ΑΒΓ ΑΕ" to listOf("ΑΒΓ", "Φούρνος", "Άλλο"), "Δ" to emptyList<String>()), c)
    }

    @Test fun xlsxRoundTrip() {
        val bytes = Sheets.xlsx(listOf("Κλήσεις" to listOf(listOf("Εταιρεία", "Λεπτά"), listOf("ΑΒΓ & <Σια>", 12.5), listOf("Δ", 3)), "Σύνολα" to listOf(listOf("x"))))
        val rows = Sheets.readXlsx(bytes)
        assertEquals(listOf("Εταιρεία", "Λεπτά"), rows[0])
        assertEquals(listOf("ΑΒΓ & <Σια>", "12.5"), rows[1])
        assertEquals("3", rows[2][1])
    }
}

class ExportTest {
    @Test fun rowsAndTotals() {
        val z = ZoneId.of("Europe/Athens")
        val start = LocalDateTime.of(2026, 11, 5, 10, 0).atZone(z).toInstant().toEpochMilli()
        val a = Company("a", "ΑΒΓ")
        val d = Data(companies = listOf(a), calls = listOf(
            Call(1, "1", "1", contact = "Νίκος", direction = Direction.IN, start = start, durationSec = 240, companyId = "a", note = "ΦΠΑ"),
            Call(2, "1", "1", direction = Direction.OUT, start = start + 3600_000, durationSec = 30, companyId = "a"),
            Call(3, "2", "2", direction = Direction.IN, start = start, durationSec = 600),
        ), settings = Settings(billByCategory = false))
        val p = Period(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30))
        val calls = Export.calls(d, p, null, zone = z)
        assertEquals(2, calls.size)
        val rows = Export.rows(d, calls, zone = z)
        assertEquals("ΑΒΓ", rows[1][0]); assertEquals("10:00", rows[1][6]); assertEquals("10:04", rows[1][7]); assertEquals(15.0, rows[1][9])
        val t = Export.totals(d, calls).single()
        assertEquals(2, t.calls); assertEquals(270, t.talkSec); assertEquals(900, t.billableSec)
        assertNotNull(Export.fileName(p, a, "xlsx"))
    }
}
