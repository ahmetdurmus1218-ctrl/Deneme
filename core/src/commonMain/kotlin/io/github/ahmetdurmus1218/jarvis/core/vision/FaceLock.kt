package io.github.ahmetdurmus1218.jarvis.core.vision

/**
 * The face HUD lock: the largest (closest) face is "locked" after a short amber LOCKING phase; every
 * other face is marked unidentified. This is face DETECTION, not recognition: Jarvis does not know
 * who it is, keeps no biometric data, and the label is just the name from the settings.
 */
class FaceLock(private val lockMs: Long = 900, private val greetEveryMs: Long = 60_000, private val lostMs: Long = 1_500) {
    enum class State { NONE, LOCKING, LOCKED }

    data class Label(val face: FaceBox, val primary: Boolean, val state: State, val progress: Float, val text: String)

    private var since = -1L
    private var lastSeen = -1L
    private var locked = false
    private var lastGreet = Long.MIN_VALUE / 2

    /**
     * Updates with this frame's faces. Returns the labels to draw and whether Jarvis should greet now
     * (once when the lock closes, at most once per [greetEveryMs], and never while it is busy).
     */
    fun update(now: Long, faces: List<FaceBox>, name: String, busy: Boolean): Pair<List<Label>, Boolean> {
        val primary = faces.maxByOrNull { (it.right - it.left) * (it.bottom - it.top) }
        if (primary == null) {
            if (lastSeen >= 0 && now - lastSeen > lostMs) { since = -1; locked = false }
            return emptyList<Label>() to false
        }
        if (since < 0) since = now
        lastSeen = now
        var greet = false
        val progress = ((now - since).toFloat() / lockMs).coerceIn(0f, 1f)
        if (!locked && progress >= 1f) {
            locked = true
            if (!busy && now - lastGreet >= greetEveryMs) {
                greet = true
                lastGreet = now
            }
        }
        val who = name.trim().ifEmpty { "KULLANICI" }.uppercase(TR)
        val labels = faces.map { f ->
            if (f === primary) {
                val st = if (locked) State.LOCKED else State.LOCKING
                Label(f, true, st, progress, if (locked) "$who · TANINDI · YETKİ: TAM" else "KİLİTLENİYOR")
            } else {
                Label(f, false, State.NONE, 0f, "KİŞİ · TANIMLANMADI")
            }
        }
        return labels to greet
    }

    companion object {
        private val TR = io.github.ahmetdurmus1218.jarvis.core.vision.TurkishUpper

        const val GREETING = "Yüz tanındı. Hoş geldiniz efendim, tam yetki verildi."
    }
}

/** Turkish upper-casing (i → İ, ı → I) without platform locales. */
object TurkishUpper

fun String.uppercase(@Suppress("UNUSED_PARAMETER") tr: TurkishUpper): String =
    buildString(length) { for (c in this@uppercase) append(if (c == 'i') 'İ' else if (c == 'ı') 'I' else c.uppercaseChar()) }
