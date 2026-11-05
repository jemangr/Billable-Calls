package gr.wsol.billablecalls

import android.provider.CallLog
import gr.wsol.billablecalls.core.CardMode
import gr.wsol.billablecalls.core.Company
import gr.wsol.billablecalls.core.Data
import gr.wsol.billablecalls.core.Direction
import gr.wsol.billablecalls.core.Settings
import gr.wsol.billablecalls.core.Store
import gr.wsol.billablecalls.sys.Backup
import gr.wsol.billablecalls.sys.CallSource
import gr.wsol.billablecalls.sys.CallSync
import gr.wsol.billablecalls.sys.Contact
import gr.wsol.billablecalls.sys.Files
import gr.wsol.billablecalls.sys.RawCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncTest {
    private val contacts = mapOf("+306971111111" to Contact("ΑΒΓ - Νίκος", null), "6972222222" to Contact("Μαμά", null), "2103333333" to Contact("Γιώργος", "Δέλτα ΕΠΕ"))
    private val src = object : CallSource {
        override fun calls(afterId: Long) = emptyList<RawCall>()
        override fun contact(number: String) = contacts[number]
    }
    private val now = 1_800_000_000_000L
    private val base = Data(companies = listOf(Company("a", "ΑΒΓ ΑΕ"), Company("d", "ΔΕΛΤΑ")), settings = Settings(since = now - 3600_000))

    @Test fun mergeMatchesRemindsAndCards() {
        val raw = listOf(
            RawCall(10, "+306971111111", CallLog.Calls.INCOMING_TYPE, now - 600_000, 300, "sim1"),
            RawCall(11, "2103333333", CallLog.Calls.OUTGOING_TYPE, now - 500_000, 0, "sim1"),
            RawCall(12, "6972222222", CallLog.Calls.MISSED_TYPE, now - 400_000, 0, "sim1"),
            RawCall(13, "6999999999", CallLog.Calls.MISSED_TYPE, now - 300_000, 0, "sim1"),
            RawCall(14, "6999999999", CallLog.Calls.BLOCKED_TYPE, now - 200_000, 0, "sim1"),
            RawCall(15, "6971111111", CallLog.Calls.INCOMING_TYPE, now - 7200_000, 60, "sim1"), // before «since»: history
        )
        val r = CallSync.merge(base, raw, src, now)
        val d = r.data
        assertEquals(5, d.calls.size)
        assertEquals(15L, d.settings.lastCallId)
        assertEquals("a", d.calls.first { it.id == 10L }.companyId)
        assertEquals("d", d.calls.first { it.id == 11L }.companyId)
        assertEquals(Direction.OUT_NO_ANSWER, d.calls.first { it.id == 11L }.direction)
        // reminders: the customer's unanswered outgoing call and the unknown number, not «Μαμά»
        assertEquals(setOf("2103333333", "6999999999"), d.reminders.map { it.key }.toSet())
        // card: only the answered call after «since»
        assertEquals(listOf(10L), r.forCard.map { it.id })
        // the same log again: nothing new
        assertEquals(0, CallSync.merge(d, raw, src, now).added.size)
    }

    @Test fun defaultTopicOnNewBusinessCalls() {
        val raw = listOf(RawCall(10, "+306971111111", CallLog.Calls.INCOMING_TYPE, now - 600_000, 300, null), RawCall(11, "699", CallLog.Calls.INCOMING_TYPE, now - 500_000, 300, null))
        val d = CallSync.merge(base, raw, src, now).data
        assertEquals("Υποστήριξη", d.calls.first { it.id == 10L }.category)
        assertEquals(null, d.calls.first { it.id == 11L }.category) // not a business call
        // added to a company later: gets it too
        val d2 = gr.wsol.billablecalls.core.Ops.assign(d, setOf(11L), "a", forNumbers = false)
        assertEquals("Υποστήριξη", d2.calls.first { it.id == 11L }.category)
    }

    @Test fun notificationButtons() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val raw = listOf(RawCall(10, "+306971111111", CallLog.Calls.INCOMING_TYPE, now - 600_000, 300, null), RawCall(11, "699", CallLog.Calls.INCOMING_TYPE, now - 500_000, 300, null))
        Store.inMemory(CallSync.merge(base, raw, src, now).data)
        val r = gr.wsol.billablecalls.sys.CardReceiver()
        fun send(action: String, id: Long, note: String? = null) {
            val i = android.content.Intent(action).putExtra("id", id)
            if (note != null) androidx.core.app.RemoteInput.addResultsToIntent(arrayOf(androidx.core.app.RemoteInput.Builder(gr.wsol.billablecalls.sys.Card.KEY_NOTE).build()), i, android.os.Bundle().apply { putCharSequence(gr.wsol.billablecalls.sys.Card.KEY_NOTE, note) })
            r.onReceive(ctx, i)
        }
        send(gr.wsol.billablecalls.sys.Card.ACTION_CONFIRM, 10)
        Store.data.calls.first { it.id == 10L }.let { assertTrue(it.reviewed && it.business && it.category == "Υποστήριξη") }
        assertEquals("a", Store.data.rule("6971111111")?.companyId)
        send(gr.wsol.billablecalls.sys.Card.ACTION_NOTE, 11, "Μίλησα για το ΦΠΑ")
        assertEquals("Μίλησα για το ΦΠΑ", Store.data.calls.first { it.id == 11L }.note)
        send(gr.wsol.billablecalls.sys.Card.ACTION_PERSONAL, 11)
        assertTrue(Store.data.calls.first { it.id == 11L }.personal)
    }

    @Test fun cardModesAndSims() {
        val raw = listOf(RawCall(10, "+306971111111", CallLog.Calls.INCOMING_TYPE, now - 600_000, 300, "sim2"), RawCall(11, "699", CallLog.Calls.INCOMING_TYPE, now - 500_000, 300, "sim1"))
        assertEquals(listOf(11L), CallSync.merge(base.copy(settings = base.settings.copy(cardMode = CardMode.UNMATCHED)), raw, src, now).forCard.map { it.id })
        assertEquals(0, CallSync.merge(base.copy(settings = base.settings.copy(cardMode = CardMode.NEVER)), raw, src, now).forCard.size)
        assertEquals(listOf(11L), CallSync.merge(base.copy(settings = base.settings.copy(sims = listOf("sim1"))), raw, src, now).added.map { it.id })
    }

    @Test fun talkingStopsTheReminder() {
        val missed = listOf(RawCall(10, "2103333333", CallLog.Calls.MISSED_TYPE, now - 600_000, 0, null))
        val d1 = CallSync.merge(base, missed, src, now - 590_000).data
        assertTrue(d1.reminders.single().let { !it.stopped })
        val back = listOf(RawCall(11, "2103333333", CallLog.Calls.OUTGOING_TYPE, now - 100_000, 90, null))
        val d2 = CallSync.merge(d1, back, src, now).data
        assertEquals("talked", d2.reminders.single().reason)
    }

    @Test fun backupRestores() {
        val raw = listOf(RawCall(10, "+306971111111", CallLog.Calls.INCOMING_TYPE, now - 600_000, 300, "sim1"))
        Store.inMemory(CallSync.merge(base, raw, src, now).data)
        val bytes = Backup.bytes()
        Store.inMemory(Data())
        assertTrue(Backup.restore(bytes))
        assertEquals(1, Store.data.calls.size)
        assertTrue(Store.settings.onboarded)
    }

    @Test fun sheetsLinks() {
        assertEquals("https://docs.google.com/spreadsheets/d/AbC-1/export?format=csv&gid=7", Files.csvLink("https://docs.google.com/spreadsheets/d/AbC-1/edit#gid=7"))
        assertEquals("https://x.gr/list.csv", Files.csvLink(" https://x.gr/list.csv "))
    }

    @Test fun excelExportOpens() {
        val raw = listOf(RawCall(10, "+306971111111", CallLog.Calls.INCOMING_TYPE, now - 600_000, 300, "sim1"))
        val d = CallSync.merge(base, raw, src, now).data
        val bytes = Files.export(d, gr.wsol.billablecalls.core.Period.ALL, null, gr.wsol.billablecalls.sys.Format.XLSX)
        val rows = gr.wsol.billablecalls.core.Sheets.readXlsx(bytes)
        assertEquals("ΑΒΓ ΑΕ", rows[1][0])
        // the PDF needs Android's PdfDocument, which Robolectric doesn't have: it is checked on the phone
    }
}
