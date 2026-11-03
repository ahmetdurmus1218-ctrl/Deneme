package io.github.ahmetdurmus1218.jarvis.core.assistant

import io.github.ahmetdurmus1218.jarvis.core.knowledge.BelgeAraTool
import io.github.ahmetdurmus1218.jarvis.core.knowledge.DocumentIndex
import io.github.ahmetdurmus1218.jarvis.core.knowledge.WikiAraTool
import io.github.ahmetdurmus1218.jarvis.core.knowledge.WikiOkuTool
import io.github.ahmetdurmus1218.jarvis.core.knowledge.Wikipedia
import io.github.ahmetdurmus1218.jarvis.core.memory.MemoryStore
import io.github.ahmetdurmus1218.jarvis.core.time.NowProvider
import io.github.ahmetdurmus1218.jarvis.core.tools.BirimCevirTool
import io.github.ahmetdurmus1218.jarvis.core.tools.HafizaEkleTool
import io.github.ahmetdurmus1218.jarvis.core.tools.HafizaListeleTool
import io.github.ahmetdurmus1218.jarvis.core.tools.HafizaSilTool
import io.github.ahmetdurmus1218.jarvis.core.tools.HesaplaTool
import io.github.ahmetdurmus1218.jarvis.core.tools.TarihSaatTool
import io.github.ahmetdurmus1218.jarvis.core.tools.Tool
import io.github.ahmetdurmus1218.jarvis.core.tools.ToolRegistry

/** Tool set shared by both platforms. [wiki] is null until a verified Wikipedia ZIM is installed. */
object DefaultTools {
    fun create(
        memory: MemoryStore,
        now: NowProvider = NowProvider.System,
        extra: List<Tool> = emptyList(),
        wiki: Wikipedia? = null,
        documents: DocumentIndex? = null,
        library: io.github.ahmetdurmus1218.jarvis.core.library.Library? = null,
    ): ToolRegistry =
        ToolRegistry(
            listOf(
                WikiAraTool(wiki),
                WikiOkuTool(wiki),
                BelgeAraTool(documents),
                HesaplaTool(),
                BirimCevirTool(),
                TarihSaatTool(now),
                HafizaEkleTool(memory),
                HafizaSilTool(memory),
                HafizaListeleTool(memory),
            ) + listOfNotNull(library?.let { io.github.ahmetdurmus1218.jarvis.core.library.KitaplikTool(it) }) + extra,
        )
}
