package gr.wsol.billablecalls.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** When reminders may ring: the working hours of each day, minus the quiet hours. */
object Hours {
    fun windowOf(s: Schedule, day: LocalDate): Window = when {
        s.sameEveryDay -> s.everyDay
        day.dayOfWeek == DayOfWeek.SATURDAY -> s.saturday
        day.dayOfWeek == DayOfWeek.SUNDAY -> s.sunday
        else -> s.weekdays
    }

    /** The allowed ranges of a day, in minutes [from, to). */
    fun ranges(s: Schedule, day: LocalDate): List<IntRange> {
        val w = windowOf(s, day)
        if (!w.on || w.to <= w.from) return emptyList()
        var out = listOf(w.from until w.to)
        for (q in s.quiet.filter { it.on && it.to > it.from }) {
            out = out.flatMap { r ->
                if (q.to <= r.first || q.from > r.last) listOf(r)
                else listOfNotNull((r.first until q.from).takeIf { !it.isEmpty() }, (q.to..r.last).takeIf { !it.isEmpty() })
            }
        }
        return out
    }

    fun allowed(s: Schedule, t: LocalDateTime): Boolean {
        val m = t.hour * 60 + t.minute
        return ranges(s, t.toLocalDate()).any { m in it }
    }

    /** The first allowed moment at or after [t]; null if there is none in the next 8 days. */
    fun next(s: Schedule, t: LocalDateTime): LocalDateTime? {
        for (d in 0..8) {
            val day = t.toLocalDate().plusDays(d.toLong())
            val now = if (d == 0) t.hour * 60 + t.minute else -1
            for (r in ranges(s, day)) {
                if (r.last < now) continue
                val m = maxOf(r.first, now)
                return if (d == 0 && m == now) t else day.atStartOfDay().plusMinutes(m.toLong())
            }
        }
        return null
    }

    fun next(s: Schedule, ms: Long, zone: ZoneId = ZoneId.systemDefault()): Long? =
        next(s, LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone))?.atZone(zone)?.toInstant()?.toEpochMilli()
}

/**
 * The reminders' rules, without Android: who gets one, when the next one rings, and when they stop
 * (the user picks any combination of the stop conditions; the first one that happens stops it).
 */
object Reminders {
    /** Is this call one to remind about? Missed incoming, or outgoing that wasn't answered. */
    fun wanted(call: Call, d: Data): Boolean {
        val r = d.settings.reminders
        val kind = when (call.direction) {
            Direction.MISSED, Direction.REJECTED -> r.incoming
            Direction.OUT_NO_ANSWER -> r.outgoing
            else -> false
        }
        if (!kind || call.personal) return false
        return when {
            call.companyId != null -> r.customers
            call.contact.isNullOrBlank() -> r.unknown
            else -> false // a contact that is not a customer: personal
        }
    }

    /** A new missed call: a new reminder, or the existing one of the number updated. */
    fun add(call: Call, d: Data, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Reminder> {
        val s = d.settings.reminders.schedule
        val existing = d.reminders.firstOrNull { it.key == call.key && !it.stopped }
        val next = Hours.next(s, maxOf(now, call.start) + s.intervalMin * 60_000L, zone) ?: Long.MAX_VALUE
        val others = d.reminders.filterNot { it === existing }
        val r = existing?.copy(callStart = maxOf(existing.callStart, call.start), direction = call.direction, companyId = call.companyId ?: existing.companyId, label = call.label)
            ?: Reminder(call.key, call.number, call.label, call.companyId, call.direction, call.start, call.start, 0, next)
        return others + r
    }

    /** Why the reminder should stop now, or null if it goes on. */
    fun stopReason(r: Reminder, d: Data, now: Long): String? {
        val s = d.settings.reminders
        if (s.stopTalked && d.calls.any { it.key == r.key && it.answered && it.start >= r.callStart }) return "talked"
        if (s.stopCount && r.count >= s.maxCount) return "count"
        if (s.stopTime && now >= r.firstStart + s.maxMinutes * 60_000L) return "time"
        return null
    }

    /** After a reminder rang: the next moment, or stopped. */
    fun afterRing(r: Reminder, d: Data, now: Long, zone: ZoneId = ZoneId.systemDefault()): Reminder {
        val rung = r.copy(count = r.count + 1)
        stopReason(rung, d, now)?.let { return rung.copy(stopped = true, reason = it, stoppedAt = now) }
        val s = d.settings.reminders.schedule
        val next = Hours.next(s, now + s.intervalMin * 60_000L, zone) ?: Long.MAX_VALUE
        return rung.copy(nextAt = next)
    }

    fun snooze(r: Reminder, d: Data, now: Long, zone: ZoneId = ZoneId.systemDefault()): Reminder {
        val s = d.settings.reminders
        return r.copy(nextAt = Hours.next(s.schedule, now + s.snoozeMin * 60_000L, zone) ?: Long.MAX_VALUE)
    }

    /** Stops every reminder that has a reason to stop (e.g. after a call was answered). */
    fun sweep(d: Data, now: Long): List<Reminder> = d.reminders.map { r ->
        if (r.stopped) r else stopReason(r, d, now)?.let { r.copy(stopped = true, reason = it, stoppedAt = now) } ?: r
    }.filter { !it.stopped || (it.stoppedAt ?: 0) > now - 14L * 24 * 3600 * 1000 }

    fun dismiss(r: Reminder, now: Long) = r.copy(stopped = true, reason = "dismissed", stoppedAt = now)
}
