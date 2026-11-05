package gr.wsol.billablecalls.core

/** Viber and WhatsApp: the apps whose calls are logged from their notifications (when on). */
object Voip {
    val APPS = mapOf("com.viber.voip" to "Viber", "com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business")

    /** A call log id of their own, far above the phone's call log ids. */
    fun id(now: Long) = 4_000_000_000_000L + now

    fun key(app: String, name: String) = "voip:" + app.lowercase() + ":" + Matcher.norm(name)
}
