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
    /** A support contract: minutes included per month (null = none). */
    val packageMin: Int? = null,
    /** Where the company is (for «on site» from the phone's location). */
    val lat: Double? = null,
    val lng: Double? = null,
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
    /** When it was marked as invoiced (null = not yet). */
    val billedAt: Long? = null,
    /** When the record was marked «completed» (null = open). */
    val doneAt: Long? = null,
    /** Joined to an earlier call of the same support (its id): billed as one, with that one. */
    val mergedInto: Long? = null,
    /** On site (at the customer's): billed × the on-site factor (when on). */
    val onsite: Boolean = false,
) {
    val end: Long get() = start + durationSec * 1000L
    val answered: Boolean get() = (direction == Direction.IN || direction == Direction.OUT) && durationSec > 0
    val business: Boolean get() = companyId != null && !personal
    val label: String get() = contact?.takeIf { it.isNotBlank() } ?: number
}

/** A reminder you set yourself (from the after-call card): at a date and time, about a company. */
@Serializable
data class FollowUp(
    val id: String,
    val companyId: String? = null,
    val callId: Long? = null,
    /** To call back from the notification. */
    val number: String? = null,
    val label: String = "",
    /** What has to be done («send an offer», «open port 443»). */
    val text: String = "",
    /** When to be reminded (null = no reminder, only on the to-do list). */
    val at: Long? = null,
    val notified: Boolean = false,
    val doneAt: Long? = null,
    val created: Long = 0,
)

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

/** How the after-call card shows: a window over the screen, or a notification with buttons. */
@Serializable
enum class CardStyle { OVERLAY, NOTIFICATION }

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
    /** The notification's sound: on/off and which one (null = the phone's default notification sound). */
    val sound: Boolean = true,
    val soundUri: String? = null,
    val vibrate: Boolean = true,
    /** Android doesn't let an app change a channel's sound, so each change gets a new channel. */
    val channelVer: Int = 1,
)

@Serializable
data class Settings(
    val english: Boolean = false,
    /** «dark» (black, the default), «light» or «system». */
    val look: String = "dark",
    val onboarded: Boolean = false,
    val matchMode: MatchMode = MatchMode.BOTH,
    val namePosition: NamePosition = NamePosition.BEFORE,
    val separator: Separator = Separator.ANY,
    /** The SIMs (phone account ids) that count; empty = all. */
    val sims: List<String> = emptyList(),
    val minSec: Int = 60,
    val roundMin: Int = 15,
    val cardMode: CardMode = CardMode.ALL,
    val cardStyle: CardStyle = CardStyle.OVERLAY,
    /** While it rings: a small card with the company, its package, last topics and open actions. */
    val hudOn: Boolean = true,
    val instantCard: Boolean = false,
    /** The topic a new business call gets (and the card starts with); "" = none. */
    val defaultCategory: String = "Υποστήριξη",
    /** Billable or not by the call's topic (only the topics in [billableCategories] count). */
    val billByCategory: Boolean = true,
    val billableCategories: List<String> = DEFAULT_CATEGORIES,
    /** The «billable yes/no» choice on the card (off: by topic only). */
    val cardBillable: Boolean = false,
    // ---- professional options (each can be turned on and off) ----
    /** Support contracts: hours included per month, per company. */
    val packagesOn: Boolean = false,
    /** Out of hours: calls outside [workHours] count × [overtimeFactor], with a tag. */
    val overtimeOn: Boolean = false,
    val workHours: Schedule = Schedule(weekdays = Window(true, 9 * 60, 18 * 60), saturday = Window(false, 10 * 60, 14 * 60), sunday = Window(false, 10 * 60, 14 * 60)),
    val overtimeFactor: Double = 1.5,
    /** Invoiced or not: «only not invoiced» in the export, then marked as invoiced. */
    val billedOn: Boolean = false,
    /** The PDF as a timesheet with your logo and details. */
    val timesheetOn: Boolean = false,
    val sheetName: String = "",
    val sheetDetails: String = "",
    /** A POST (JSON) to your own n8n (or any) URL after every card. */
    val webhookOn: Boolean = false,
    val webhookUrl: String = "",
    /** Viber / WhatsApp calls, from their notifications (approximate). */
    val voipOn: Boolean = false,
    /** Lock with fingerprint or PIN. */
    val lockOn: Boolean = false,
    /** Greeklish counts as Greek when matching («Papadopoulos» = «Παπαδόπουλος»). */
    val greeklish: Boolean = true,
    /** Calls of the same number within [mergeMin] minutes: the card offers to bill them as one. */
    val mergeOn: Boolean = true,
    val mergeMin: Int = 20,
    /** On-site work: × [onsiteFactor]; with [onsiteAuto], from the phone's location near the company. */
    val onsiteOn: Boolean = false,
    val onsiteFactor: Double = 1.5,
    val onsiteAuto: Boolean = false,
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
    /** Shake the phone to open the bug report. */
    val shakeReport: Boolean = true,
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
    val followUps: List<FollowUp> = emptyList(),
    /** Calls waiting to be sent to the webhook (sent again if it failed). */
    val pendingHooks: List<Long> = emptyList(),
    /** company id → the month («2026-11») its package alert was shown. */
    val packageAlerted: Map<String, String> = emptyMap(),
) {
    fun company(id: String?) = id?.let { i -> companies.firstOrNull { it.id == i } }
    fun rule(key: String) = rules.firstOrNull { it.key == key }
}
