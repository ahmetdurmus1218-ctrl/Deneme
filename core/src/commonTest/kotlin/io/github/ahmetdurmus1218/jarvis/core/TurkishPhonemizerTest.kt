package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.tts.TurkishNumberWords
import io.github.ahmetdurmus1218.jarvis.core.tts.TurkishPhonemizer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurkishPhonemizerTest {
    private fun w(s: String) = TurkishPhonemizer.word(s)

    @Test
    fun wordsMatchEspeakNg() {
        // Reference output of espeak-ng 1.51 (-v tr --ipa), which the Piper voice was trained on.
        assertEquals("doːɾˈʊ", w("doğru"))
        assertEquals("dʒevabˈɯ", w("cevabı"))
        assertEquals("ɟyzˈæl", w("güzel"))
        assertEquals("tʃaɫɯʃmaɫɯsˈɯn", w("çalışmalısın"))
        assertEquals("ˈœndʒɛ", w("önce"))
        assertEquals("ɯʃɯkɫˈar", w("Işıklar"))
        assertEquals("ʒarvˈɪs", w("Jarvis"))
        assertEquals("aːʊstˈɔs", w("Ağustos"))
        assertEquals("myzijˈɪ", w("müziği"))
        assertEquals("røzɟˈar", w("rüzgâr"))
    }

    @Test
    fun spellingRules() {
        assertEquals("ɟɪtːˈɪk", w("gittik"), "double consonants are long")
        assertEquals("ˈankaɾa", w("Ankara"), "stress exception")
        assertEquals("ˈankaɾadɯr", w("Ankara'dır"), "suffix after the apostrophe keeps the stress on the name")
        assertEquals("mɯ", w("mı"), "the question particle is never stressed")
        assertEquals("te be me me", w("TBMM"), "abbreviations are spelled")
        assertEquals("ɪstˈanbʊɫ", w("İstanbul"), "dotted capital İ is i")
    }

    @Test
    fun sentencesAndPunctuation() {
        val s = TurkishPhonemizer.sentences("Merhaba efendim, Jarvis hazır. Nasılsın?")
        assertEquals(2, s.size)
        assertEquals("mˈɛrhaba efˈændɪm, ʒarvˈɪs hazˈɯr.", s[0])
        assertTrue(s[1].endsWith("?"))
        assertTrue(TurkishPhonemizer.sentences("— … 🙂").isEmpty(), "nothing speakable")
    }

    @Test
    fun codepointsOnePerCharacter() {
        val rows = TurkishPhonemizer.codepoints("Evet.")
        assertEquals(1, rows.size)
        assertContentEquals(TurkishPhonemizer.sentences("Evet.")[0].map { it.code }.toIntArray(), rows[0])
    }

    @Test
    fun numbersAreReadOut() {
        assertEquals("bin dokuz yüz yirmi üç", TurkishNumberWords.words(1923))
        assertEquals("bin", TurkishNumberWords.words(1000))
        assertEquals("yüz bir", TurkishNumberWords.words(101))
        assertEquals("sıfır", TurkishNumberWords.words(0))
        assertEquals("beş milyon dokuz yüz on bin üç yüz yirmi kişi", TurkishNumberWords.spellOut("5.910.320 kişi"))
        assertEquals("sekiz bin sekiz yüz kırk sekiz virgül seksen altı", TurkishNumberWords.spellOut("8.848,86"))
        assertEquals("üç virgül sıfır beş", TurkishNumberWords.spellOut("3,05"))
        assertEquals("yüzde elli", TurkishNumberWords.spellOut("%50"))
    }
}
