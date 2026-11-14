package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.assistant.Emotion
import io.github.ahmetdurmus1218.jarvis.core.assistant.EmotionCue
import kotlin.test.Test
import kotlin.test.assertEquals

class EmotionCueTest {
    @Test
    fun thanksAndGoodNewsBringJoy() {
        assertEquals(Emotion.JOY, EmotionCue.of("Çok teşekkür ederim Rick"))
        assertEquals(Emotion.JOY, EmotionCue.of("Sınavı kazandım!"))
        assertEquals(Emotion.JOY, EmotionCue.of("Harika iş çıkardın"))
    }

    @Test
    fun aGreetingIsASmile() {
        assertEquals(Emotion.HAPPY, EmotionCue.of("Günaydın"))
        assertEquals(Emotion.HAPPY, EmotionCue.of("Merhaba Rick, nasılsın?"))
    }

    @Test
    fun sadNewsAndHurtfulWordsAreSad() {
        assertEquals(Emotion.SAD, EmotionCue.of("Bugün moralim bozuk"))
        assertEquals(Emotion.SAD, EmotionCue.of("Dedem vefat etti"))
        assertEquals(Emotion.SAD, EmotionCue.of("Sen tam bir aptalsın"))
    }

    @Test
    fun surpriseOnlyAsAWholeWord() {
        assertEquals(Emotion.SURPRISED, EmotionCue.of("Vay be, gerçekten mi?"))
        assertEquals(Emotion.SURPRISED, EmotionCue.of("İnanılmaz bir şey"))
        assertEquals(Emotion.NONE, EmotionCue.of("Kavaya git"))
    }

    @Test
    fun theAnswerSpeaksWhenTheQuestionDoesNot() {
        assertEquals(Emotion.SAD, EmotionCue.of("Mars'ın uydusu kaç tane", "Maalesef bu bilgi kütüphanemde yok."))
        assertEquals(Emotion.HAPPY, EmotionCue.of("Yarın tatile çıkıyorum", "Ne güzel, iyi tatiller dilerim."))
        assertEquals(Emotion.NONE, EmotionCue.of("Saat kaç", "Saat 14:05."))
    }

    @Test
    fun theUserComesFirst() {
        assertEquals(Emotion.JOY, EmotionCue.of("Teşekkürler", "Maalesef yapamıyorum."))
    }
}
