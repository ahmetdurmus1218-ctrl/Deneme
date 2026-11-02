package io.github.ahmetdurmus1218.jarvis.core.assistant

import io.github.ahmetdurmus1218.jarvis.core.memory.MemoryStore
import io.github.ahmetdurmus1218.jarvis.core.time.NowProvider
import io.github.ahmetdurmus1218.jarvis.core.tools.BirimCevirTool
import io.github.ahmetdurmus1218.jarvis.core.tools.HafizaEkleTool
import io.github.ahmetdurmus1218.jarvis.core.tools.HafizaListeleTool
import io.github.ahmetdurmus1218.jarvis.core.tools.HafizaSilTool
import io.github.ahmetdurmus1218.jarvis.core.tools.HesaplaTool
import io.github.ahmetdurmus1218.jarvis.core.tools.NotInstalledTool
import io.github.ahmetdurmus1218.jarvis.core.tools.TarihSaatTool
import io.github.ahmetdurmus1218.jarvis.core.tools.Tool
import io.github.ahmetdurmus1218.jarvis.core.tools.ToolRegistry

/** Tool set shared by both platforms. Knowledge tools are placeholders until v0.3. */
object DefaultTools {
    fun create(memory: MemoryStore, now: NowProvider = NowProvider.System, extra: List<Tool> = emptyList()): ToolRegistry =
        ToolRegistry(
            listOf(
                NotInstalledTool("wiki_ara", "Çevrimdışı Türkçe Wikipedia'da arama yapar.", "Wikipedia paketi"),
                NotInstalledTool("wiki_oku", "Wikipedia makalesini okur.", "Wikipedia paketi"),
                NotInstalledTool("belge_ara", "Kullanıcının içe aktardığı belgelerde arama yapar.", "Belge dizini"),
                HesaplaTool(),
                BirimCevirTool(),
                TarihSaatTool(now),
                HafizaEkleTool(memory),
                HafizaSilTool(memory),
                HafizaListeleTool(memory),
            ) + extra,
        )
}
