package io.github.ahmetdurmus1218.jarvis.core.device

import io.github.ahmetdurmus1218.jarvis.core.text.Turkish

/**
 * Phone calls Rick may place by itself. A misheard sentence must never cost money or reach a
 * stranger: contacts are called directly, an unknown number only opens the dialer (the user presses
 * call), paid and short service numbers are refused, emergency numbers always open the dialer.
 */
object CallSafety {
    enum class Kind { EMERGENCY, PAID, UNKNOWN_NUMBER, CONTACT }

    /** Emergency numbers (Turkey, plus the common international ones). */
    private val EMERGENCY = setOf("112", "110", "155", "156", "177", "158", "183", "122", "911", "999")

    /** [number] as dialled (digits and an optional leading +); [fromContacts]: it came from the address book. */
    fun kind(number: String, fromContacts: Boolean): Kind {
        val digits = number.filter { it.isDigit() }
        if (digits in EMERGENCY) return Kind.EMERGENCY
        val national = when {
            number.startsWith("+90") -> "0" + digits.removePrefix("90")
            digits.startsWith("0090") -> "0" + digits.removePrefix("0090")
            else -> digits
        }
        // Paid lines (0900…), and short service codes (3–6 digits) that bill per call or message.
        if (national.startsWith("0900") || national.startsWith("900") || national.length in 3..6) return Kind.PAID
        return if (fromContacts) Kind.CONTACT else Kind.UNKNOWN_NUMBER
    }
}

/** What may still run while the phone is locked: nothing that reaches a person, an app or a setting. */
object LockPolicy {
    fun allowedWhileLocked(a: DeviceAction): Boolean =
        a is DeviceAction.Torch || a is DeviceAction.Volume || a is DeviceAction.Media || a is DeviceAction.Battery

    const val LOCKED = "Telefon kilitli. Bunu kilidi açtıktan sonra yapabilirim."
}

/**
 * Apps the hands may drive while Rick runs in the background (taps, scrolls, back). Only the apps the
 * user chose, and never banking, payment, messaging, password, crypto or system-settings apps — they
 * cannot even be chosen. An allow list, because a bank cannot be recognised reliably by its name.
 */
object HandApps {
    val DEFAULT: Set<String> = setOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.spotify.music",
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.netflix.mediaclient",
    )

    private val NEVER = Regex(
        "bank|banka|finans|kredi|kart|card|wallet|cuzdan|payment|paypal|papara|ininal|tosla|paycell|paisa|" +
            "(^|[.\\s])pay([.\\s]|$)|odeme|whatsapp|telegram|signal|messag|mesaj|sms|mms|mail|" +
            "password|sifre|parola|authenticator|bitwarden|keepass|lastpass|1password|" +
            "binance|btcturk|paribu|crypto|kripto|coin|borsa|yatirim|" +
            "ziraat|vakif|halkbank|isbank|garanti|akbank|yapikredi|denizbank|qnb|enpara|kuveyt|albaraka|" +
            "sekerbank|odeabank|fibabanka|hsbc|ingbank|turkiyefinans|iscep|pozitron|ykb|teb|android\\.gm( |$)|" +
            "settings|ayarlar|packageinstaller|permissioncontroller|vending|dialer|contacts|rehber",
    )

    /** Apps that can never be driven by hand, whatever the user picks. */
    fun neverAllowed(pkg: String, label: String = ""): Boolean =
        NEVER.containsMatchIn(Turkish.normalize("$pkg $label"))

    fun allowed(pkg: String?, chosen: Set<String>): Boolean = pkg != null && pkg in chosen && !neverAllowed(pkg)
}
