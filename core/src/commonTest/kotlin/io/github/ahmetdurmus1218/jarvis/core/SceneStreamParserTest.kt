package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.scene.GorselTip
import io.github.ahmetdurmus1218.jarvis.core.scene.Sahne
import io.github.ahmetdurmus1218.jarvis.core.scene.SceneStreamParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SceneStreamParserTest {
    private val json = """```json
{"sahneler":[{"ses":"Birinci sahne.","gorsel":{"tip":"sayi","deger":"8.848,86","birim":"m"}},
{"ses":"İkinci \"tırnaklı\" } sahne.","gorsel":{"tip":"liste","ogeler":["a","b{"]}}],
"detay":"**detay**","kaynaklar":[{"baslik":"Everest","tur":"wikipedia"}]}
```"""

    @Test
    fun emitsEachSceneAsSoonAsItCloses() {
        val scenes = mutableListOf<Sahne>()
        val parser = SceneStreamParser { scenes += it }
        // "}}," = end of the visual object, end of the first scene, separator
        val firstSceneClose = json.indexOf("}},") + 1
        for ((i, c) in json.withIndex()) {
            parser.feed(c.toString())
            if (i == firstSceneClose - 1) assertEquals(0, scenes.size, "not before the scene's closing brace")
            if (i == firstSceneClose) assertEquals(1, scenes.size, "right after the scene's closing brace")
        }
        assertEquals(2, scenes.size)
        assertEquals("Birinci sahne.", scenes[0].ses)
        assertEquals(GorselTip.SAYI, scenes[0].gorsel?.tip)
        assertEquals("İkinci \"tırnaklı\" } sahne.", scenes[1].ses)
        assertEquals(listOf("a", "b{"), scenes[1].gorsel?.ogeler)
        val outcome = parser.finish()
        assertIs<SceneStreamParser.Outcome.Parsed>(outcome)
        assertEquals("**detay**", outcome.reply.detay)
        assertEquals("Everest", outcome.reply.kaynaklar.single().baslik)
    }

    @Test
    fun wholeReplyInOneChunkWorks() {
        val scenes = mutableListOf<Sahne>()
        val parser = SceneStreamParser { scenes += it }
        parser.feed(json)
        assertEquals(2, scenes.size)
    }

    @Test
    fun brokenJsonIsReported() {
        val parser = SceneStreamParser { }
        parser.feed("{\"sahneler\":[{\"ses\":\"yarım")
        val outcome = parser.finish()
        assertIs<SceneStreamParser.Outcome.Broken>(outcome)
        assertEquals(0, outcome.scenesEmitted)
    }

    @Test
    fun plainTextFallbackStripsJsonNoise() {
        val s = SceneStreamParser.plainTextScene("{\"sahneler\": [ {\"ses\": \"Merhaba efendim")
        assertTrue(s.ses.contains("Merhaba efendim"))
        assertTrue(!s.ses.contains("{"))
    }

    @Test
    fun keysInsideNestedObjectsDoNotOpenSceneArray() {
        val scenes = mutableListOf<Sahne>()
        val parser = SceneStreamParser { scenes += it }
        parser.feed("{\"x\":{\"sahneler\":[{\"ses\":\"yanlış\"}]},\"sahneler\":[{\"ses\":\"doğru\"}]}")
        assertEquals(listOf("doğru"), scenes.map { it.ses })
    }
}
