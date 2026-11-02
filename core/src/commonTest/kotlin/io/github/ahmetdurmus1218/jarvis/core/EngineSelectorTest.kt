package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.llm.ChatMessage
import io.github.ahmetdurmus1218.jarvis.core.llm.DeltaTracker
import io.github.ahmetdurmus1218.jarvis.core.llm.EngineSelector
import io.github.ahmetdurmus1218.jarvis.core.llm.GenerationConfig
import io.github.ahmetdurmus1218.jarvis.core.llm.LlmEngine
import io.github.ahmetdurmus1218.jarvis.core.llm.MockLlmEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineSelectorTest {
    private class Named(override val id: String) : LlmEngine {
        override fun stream(messages: List<ChatMessage>, config: GenerationConfig): Flow<String> = flowOf(id)
    }

    @Test
    fun usesRealEngineOnlyWhenAvailable() = runTest {
        var real: LlmEngine? = null
        val sel = EngineSelector(MockLlmEngine(0)) { real }
        assertEquals("mock", sel.id)
        real = Named("gemma")
        assertEquals("gemma", sel.id)
        assertEquals(listOf("gemma"), sel.stream(emptyList(), GenerationConfig()).toList())
    }

    @Test
    fun deltaTrackerHandlesBothStreamingStyles() {
        val acc = DeltaTracker()
        assertEquals(listOf("Mer", "haba", " efendim"), listOf("Mer", "Merhaba", "Merhaba efendim").map(acc::next))
        val delta = DeltaTracker()
        assertEquals(listOf("Mer", "haba", " efendim"), listOf("Mer", "haba", " efendim").map(delta::next))
    }
}
