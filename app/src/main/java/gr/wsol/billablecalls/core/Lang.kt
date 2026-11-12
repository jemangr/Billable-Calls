package gr.wsol.billablecalls.core

/**
 * The app's language. Every text on screen is written as t("Ελληνικά", "English"), so
 * switching needs no lookup tables.
 */
object Lang {
    @Volatile var english = false
}

fun t(el: String, en: String): String = if (Lang.english) en else el

/** A count with its noun in the right number: n(1, "κλήση", "κλήσεις", "call", "calls") = «1 κλήση» / «1 call». */
fun n(count: Int, oneEl: String, manyEl: String, oneEn: String, manyEn: String): String =
    "$count " + if (count == 1) t(oneEl, oneEn) else t(manyEl, manyEn)

/** The built-in topics are stored in Greek; on screen and in exports they follow the app's language. Topics you add stay as you wrote them. */
private val TOPICS_EN = mapOf("Υποστήριξη" to "Support", "Προσφορά" to "Quote", "Τιμολόγηση" to "Billing", "Ραντεβού" to "Appointment", "Άλλο" to "Other")

fun topic(name: String): String = if (Lang.english) TOPICS_EN[name] ?: name else name
