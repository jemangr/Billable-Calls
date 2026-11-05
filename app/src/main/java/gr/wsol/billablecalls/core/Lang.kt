package gr.wsol.billablecalls.core

/**
 * The app's language. Every text on screen is written as t("Ελληνικά", "English"), so
 * switching needs no lookup tables.
 */
object Lang {
    @Volatile var english = false
}

fun t(el: String, en: String): String = if (Lang.english) en else el
