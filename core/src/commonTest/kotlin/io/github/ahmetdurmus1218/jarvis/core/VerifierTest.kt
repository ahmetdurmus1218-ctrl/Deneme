package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.scene.Gorsel
import io.github.ahmetdurmus1218.jarvis.core.scene.GorselTip
import io.github.ahmetdurmus1218.jarvis.core.scene.Reply
import io.github.ahmetdurmus1218.jarvis.core.scene.Sahne
import io.github.ahmetdurmus1218.jarvis.core.verify.NumberCanon
import io.github.ahmetdurmus1218.jarvis.core.verify.QuoteMatcher
import io.github.ahmetdurmus1218.jarvis.core.verify.Verifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerifierTest {

    @Test
    fun turkishAndEnglishNumberNotationsAreEquivalent() {
        assertEquals(setOf("5910320"), NumberCanon.readings("5.910.320"))
        assertEquals(setOf("5910320"), NumberCanon.readings("5,910,320"))
        assertEquals(setOf("8848.86"), NumberCanon.readings("8.848,86"))
        assertEquals(setOf("8848.86"), NumberCanon.readings("8,848.86"))
        assertEquals(setOf("8848.86"), NumberCanon.readings("8848,86"))
        assertEquals(setOf("8848.86"), NumberCanon.readings("8848.86"))
        assertEquals(setOf("5.91", "5910"), NumberCanon.readings("5.910"))
        assertEquals(setOf("1923"), NumberCanon.readings("1923"))
        assertEquals(setOf("0.5"), NumberCanon.readings("0,50"))
    }

    @Test
    fun malformedGroupingIsRejected() {
        assertTrue(NumberCanon.readings("5.91.320").isEmpty())
        assertTrue(NumberCanon.readings("12,34.5").isEmpty())
    }

    @Test
    fun numberVisualPassesWhenValueIsInEvidenceInAnyNotation() {
        val evidence = listOf("Ankara'nın nüfusu 5,910,320 kişidir (2024). Everest 8848.86 m.")
        val s1 = Sahne("Ankara'nın nüfusu 5.910.320.", Gorsel(GorselTip.SAYI, deger = "5.910.320", etiket = "Ankara nüfusu"))
        val v1 = Verifier.verifyScene(s1, evidence, requireSources = true)
        assertFalse(v1.downgraded)
        assertFalse(v1.kaynakYok)
        assertEquals(GorselTip.SAYI, v1.sahne.gorsel?.tip)

        val s2 = Sahne("Everest 8.848,86 metre.", Gorsel(GorselTip.SAYI, deger = "8.848,86", birim = "m"))
        assertFalse(Verifier.verifyScene(s2, evidence, true).downgraded)
    }

    @Test
    fun numberVisualNotInEvidenceIsDowngradedToTitleCard() {
        val evidence = listOf("Ankara'nın nüfusu 5.910.320 kişidir.")
        val s = Sahne("Nüfus 6 milyon.", Gorsel(GorselTip.SAYI, deger = "6.000.000", etiket = "Ankara nüfusu"))
        val v = Verifier.verifyScene(s, evidence, requireSources = true)
        assertTrue(v.downgraded)
        assertEquals(GorselTip.BASLIK, v.sahne.gorsel?.tip)
        assertEquals("Ankara nüfusu", v.sahne.gorsel?.baslik)
        assertTrue(v.kaynakYok)
    }

    @Test
    fun quoteMustAppearVerbatimIgnoringCaseAndQuotes() {
        val evidence = listOf("Atatürk: \"Yurtta sulh, cihanda sulh.\" demiştir.")
        val ok = Sahne("Ünlü söz.", Gorsel(GorselTip.ALINTI, metin = "“yurtta sulh, cihanda sulh”"))
        assertFalse(Verifier.verifyScene(ok, evidence, true).downgraded)
        val bad = Sahne("Ünlü söz.", Gorsel(GorselTip.ALINTI, metin = "Yurtta barış, dünyada barış"))
        assertTrue(Verifier.verifyScene(bad, evidence, true).downgraded)
        assertTrue(QuoteMatcher.contains("İSTANBUL Boğazı", "istanbul boğazı"))
    }

    @Test
    fun spokenNumbersWithoutSourceGetKaynakYokLabel() {
        val v = Verifier.verifyScene(Sahne("Nüfus 12 milyon civarında."), emptyList(), requireSources = true)
        assertTrue(v.kaynakYok)
        val chat = Verifier.verifyScene(Sahne("Size 3 öneri vereyim."), emptyList(), requireSources = false)
        assertFalse(chat.kaynakYok)
    }

    @Test
    fun spokenNumbersBackedByEvidenceAreFine() {
        val v = Verifier.verifyScene(Sahne("Nüfusu 5.910.320."), listOf("nüfus: 5910320"), requireSources = true)
        assertFalse(v.kaynakYok)
    }

    @Test
    fun verifyWholeReplyKeepsOrderAndSources() {
        val reply = Reply(
            sahneler = listOf(Sahne("Bir."), Sahne("İki 2.", Gorsel(GorselTip.SAYI, deger = "2"))),
            detay = "d",
        )
        val r = Verifier.verify(reply, listOf("2"), requireSources = false)
        assertEquals(2, r.scenes.size)
        assertFalse(r.scenes[1].downgraded)
        assertEquals("d", r.detay)
    }

    @Test
    fun unknownVisualTypeBecomesNone() {
        val v = Verifier.verifyScene(Sahne("x", Gorsel(tip = "grafik")), emptyList(), false)
        assertEquals(GorselTip.YOK, v.sahne.gorsel?.tip)
    }
}
