package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.assistant.Assistant
import io.github.ahmetdurmus1218.jarvis.core.assistant.AssistantEvent
import io.github.ahmetdurmus1218.jarvis.core.assistant.DefaultTools
import io.github.ahmetdurmus1218.jarvis.core.llm.ChatMessage
import io.github.ahmetdurmus1218.jarvis.core.llm.EngineSelector
import io.github.ahmetdurmus1218.jarvis.core.llm.GenerationConfig
import io.github.ahmetdurmus1218.jarvis.core.llm.LlmEngine
import io.github.ahmetdurmus1218.jarvis.core.llm.MockLlmEngine
import io.github.ahmetdurmus1218.jarvis.core.llm.Role
import io.github.ahmetdurmus1218.jarvis.core.llm.VisionLlm
import io.github.ahmetdurmus1218.jarvis.core.memory.InMemoryMemoryStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisionTest {
    private class FakeVision(private val reply: String, private val sees: Boolean = true) : LlmEngine, VisionLlm {
        var image: ByteArray? = null
        var messages: List<ChatMessage> = emptyList()
        override val id = "vision"
        override fun stream(messages: List<ChatMessage>, config: GenerationConfig): Flow<String> = flowOf("{}")
        override fun canSee() = sees
        override fun streamWithImage(messages: List<ChatMessage>, jpeg: ByteArray, config: GenerationConfig): Flow<String> {
            image = jpeg
            this.messages = messages
            // Streamed in two pieces, like a real model.
            return flowOf(reply.take(reply.length / 2), reply.drop(reply.length / 2))
        }
    }

    private fun assistant(engine: LlmEngine): Assistant {
        val memory = InMemoryMemoryStore()
        return Assistant(engine, DefaultTools.create(memory), memory)
    }

    @Test
    fun thePictureGoesToTheModelWithTheQuestion() = runTest {
        val vision = FakeVision("""{"sahneler":[{"ses":"Masada bir kahve fincanı var.","gorsel":{"tip":"baslik","baslik":"Kahve fincanı"}}]}""")
        val jpeg = byteArrayOf(1, 2, 3)
        val events = assistant(EngineSelector(MockLlmEngine(0)) { vision }).askAboutImage("Bu ne?", jpeg).toList()
        assertTrue(vision.image.contentEquals(jpeg))
        assertEquals(Role.SYSTEM, vision.messages.first().role)
        assertTrue("görüntü" in vision.messages.first().content)
        assertEquals("Bu ne?", vision.messages.last().content)
        val done = events.filterIsInstance<AssistantEvent.Completed>().single()
        assertEquals("Masada bir kahve fincanı var.", done.reply.scenes.single().sahne.ses)
        assertTrue(events.any { it is AssistantEvent.SceneReady }, "streamed scene by scene")
    }

    @Test
    fun withoutAVisionModelJarvisSaysSo() = runTest {
        val noModel = assistant(EngineSelector(MockLlmEngine(0)) { null }).askAboutImage("Bu ne?", byteArrayOf(1)).toList()
        assertEquals(Assistant.NO_VISION_TEXT, noModel.filterIsInstance<AssistantEvent.Completed>().single().reply.scenes.single().sahne.ses)
        val blind = FakeVision("{}", sees = false)
        val r = assistant(EngineSelector(MockLlmEngine(0)) { blind }).askAboutImage("Oku", byteArrayOf(1)).toList()
        assertEquals(Assistant.NO_VISION_TEXT, r.filterIsInstance<AssistantEvent.Completed>().single().reply.scenes.single().sahne.ses)
        assertEquals(null, blind.image, "a model without its vision part never gets the picture")
    }

    @Test
    fun numbersReadFromAPictureAreNotShownAsVerifiedFacts() = runTest {
        val vision = FakeVision("""{"sahneler":[{"ses":"Fişte toplam 245 lira yazıyor.","gorsel":{"tip":"sayi","deger":"245","birim":"TL","etiket":"Toplam"}}]}""")
        val done = assistant(vision).askAboutImage("Oku", byteArrayOf(9)).toList().filterIsInstance<AssistantEvent.Completed>().single()
        val scene = done.reply.scenes.single()
        assertTrue(scene.downgraded, "a number card needs a source; a picture is not one")
    }
}
