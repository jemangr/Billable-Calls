package gr.wsol.billablecalls.core

import kotlinx.serialization.Serializable

/** A customer company from the list (or added by hand). Its own billing rules are optional. */
@Serializable
data class Company(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    /** Calls must be longer than this to count (seconds); null = the general setting. */
    val minSec: Int? = null,
    /** Rounding block in minutes (0 = exact); null = the general setting. */
    val roundMin: Int? = null,
    /** "list" (from the customer list) or "manual". */
    val source: String = "manual",
)

@Serializable
enum class Direction { IN, OUT, MISSED, OUT_NO_ANSWER, REJECTED }

/** How a call got its company (or lost it). */
@Serializable
enum class How { NONE, COMPANY_FIELD, NAME, RULE, MANUAL }

@Serializable
data class Call(
    /** The call log's _ID. */
    val id: Long,
    /** The number as a lookup key (digits, no +30). */
    val key: String,
    /** The number as the phone shows it. */
    val number: String,
    /** The contact's name, if the number is in the contacts. */
    val contact: String? = null,
    /** The contact's «Company» field. */
    val org: String? = null,
    /** The phone account (SIM) id. */
    val sim: String? = null,
    val direction: Direction,
    /** Start (ms). For outgoing calls Android counts from dialling, so the ringing is included. */
    val start: Long,
    /** Talk time in seconds. */
    val durationSec: Int,
    val companyId: String? = null,
    /** Marked as a personal call (by hand or by a number rule). */
    val personal: Boolean = false,
    val how: How = How.NONE,
    /** Manual «billable yes/no»; null = by the rules. */
    val billable: Boolean? = null,
    val category: String? = null,
    val note: String = "",
    /** The after-call card was answered (or skipped) for this call. */
    val reviewed: Boolean = false,
) {
    val end: Long get() = start + durationSec * 1000L
    val answered: Boolean get() = (direction == Direction.IN || direction == Direction.OUT) && durationSec > 0
    val business: Boolean get() = companyId != null && !personal
    val label: String get() = contact?.takeIf { it.isNotBlank() } ?: number
}

/** Where the company of a number comes from: a choice that also counts for the next calls. */
@Serializable
data class NumberRule(val key: String, val companyId: String? = null, val personal: Boolean = false)

@Serializable
data class Reminder(
    /** The number's key: one reminder per number, even after several missed calls. */
    val key: String,
    val number: String,
    val label: String,
    val companyId: String? = null,
    val direction: Direction,
    /** The (latest) missed call. */
    val callStart: Long,
    /** The first missed call of this reminder. */
    val firstStart: Long = callStart,
    val count: Int = 0,
    val nextAt: Long,
    val stopped: Boolean = false,
    /** "talked", "count", "time", "dismissed". */
    val reason: String? = null,
    val stoppedAt: Long? = null,
)

@Serializable
enum class MatchMode { COMPANY_FIELD, NAME, BOTH }

/** Where the company is in the contact's name. */
@Serializable
enum class NamePosition { BEFORE, AFTER }

@Serializable
enum class Separator(val chars: String) { ANY(""), DASH("-"), SPACE(" "), PIPE("|"), SLASH("/"), COMMA(","), PAREN("(") }

@Serializable
enum class CardMode { ALL, UNMATCHED, NEVER }

/** A time range in minutes of the day [from, to). */
@Serializable
data class Window(val on: Boolean = true, val from: Int = 9 * 60, val to: Int = 17 * 60)

@Serializable
data class Schedule(
    /** true: the same hours every day ([everyDay]); false: weekdays, Saturday and Sunday apart. */
    val sameEveryDay: Boolean = false,
    val weekdays: Window = Window(true, 9 * 60, 17 * 60),
    val saturday: Window = Window(false, 10 * 60, 14 * 60),
    val sunday: Window = Window(false, 10 * 60, 14 * 60),
    val everyDay: Window = Window(true, 9 * 60, 21 * 60),
    /** Hours without reminders inside the working hours (e.g. 14:00–15:00). */
    val quiet: List<Window> = emptyList(),
    val intervalMin: Int = 30,
)

@Serializable
data class ReminderSettings(
    val incoming: Boolean = true,
    val outgoing: Boolean = true,
    val customers: Boolean = true,
    val unknown: Boolean = true,
    val schedule: Schedule = Schedule(),
    /** Stop conditions: any combination; the reminder stops at the first one that happens. */
    val stopTalked: Boolean = true,
    val stopCount: Boolean = true,
    val maxCount: Int = 3,
    val stopTime: Boolean = false,
    /** Minutes after the missed call. */
    val maxMinutes: Int = 24 * 60,
    val snoozeMin: Int = 60,
)

@Serializable
data class Settings(
    val english: Boolean = false,
    val onboarded: Boolean = false,
    val matchMode: MatchMode = MatchMode.BOTH,
    val namePosition: NamePosition = NamePosition.BEFORE,
    val separator: Separator = Separator.ANY,
    /** The SIMs (phone account ids) that count; empty = all. */
    val sims: List<String> = emptyList(),
    val minSec: Int = 60,
    val roundMin: Int = 15,
    val cardMode: CardMode = CardMode.ALL,
    val instantCard: Boolean = false,
    val reminders: ReminderSettings = ReminderSettings(),
    val categories: List<String> = DEFAULT_CATEGORIES,
    /** Customer list: "file", "url", "drive" or "" (by hand). */
    val listSource: String = "",
    val listUrl: String = "",
    val listName: String = "",
    val listSynced: Long = 0,
    val driveEmail: String = "",
    val driveFolderId: String = "",
    val driveSheetId: String = "",
    val autoBackup: Boolean = false,
    val lastBackup: Long = 0,
    val updateCheck: Boolean = true,
    val lastUpdateCheck: Long = 0,
    /** Calls before this moment are history: no cards and no reminders for them. */
    val since: Long = 0,
    /** The highest call log _ID read so far. */
    val lastCallId: Long = 0,
) {
    companion object {
        val DEFAULT_CATEGORIES = listOf("Υποστήριξη", "Προσφορά", "Τιμολόγηση", "Ραντεβού", "Άλλο")
    }
}

@Serializable
data class Data(
    val companies: List<Company> = emptyList(),
    val calls: List<Call> = emptyList(),
    val rules: List<NumberRule> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val settings: Settings = Settings(),
) {
    fun company(id: String?) = id?.let { i -> companies.firstOrNull { it.id == i } }
    fun rule(key: String) = rules.firstOrNull { it.key == key }
}
