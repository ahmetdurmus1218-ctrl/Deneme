package io.github.ahmetdurmus1218.jarvis.ui

import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.ModelLock
import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.core.models.Platform
import io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelInstallerTest {
    private val good = "a".repeat(64)
    private val item = LockedItem(
        "m", "llm", listOf("android"), "Model", "g", "https://huggingface.co/x/resolve/r/m.litertlm",
        "huggingface.co", "m.litertlm", 100, good, "r", "o",
    )

    private class MemFiles : AppFiles {
        val m = mutableMapOf<String, String>()
        override fun read(name: String) = m[name]
        override fun write(name: String, text: String) { m[name] = text }
        override fun append(name: String, line: String) { m[name] = (m[name].orEmpty()) + line + "\n" }
    }

    private class FakeStt : SpeechInput {
        var packRequests = 0
        override val engineName = "fake"
        override fun isAvailable() = true
        override fun start(listener: SpeechInput.Listener) {}
        override fun stop() {}
        override fun cancel() {}
        override suspend fun languagePack() = SpeechInput.LanguagePack.DOWNLOADABLE
        override suspend fun downloadLanguagePack(): Boolean {
            packRequests++
            return true
        }
    }

    private class FakeBridge(private val returnSha: String, private val approve: Boolean = true, private val failFirst: Int = 0) : PlatformBridge {
        val stt = FakeStt()
        val committed = mutableListOf<String>()
        val deletedTemp = mutableListOf<String>()
        var downloads = 0
        override val platform = Platform.ANDROID
        override val appVersion = "test"
        override fun deviceInfo() = DeviceInfo("test", 12.0, 1L shl 40)
        override val files: AppFiles = MemFiles()
        override val speechInput: SpeechInput = stt
        override val speechOutput: SpeechOutput? = null
        override val approver = object : Approver {
            override suspend fun confirm(title: String, subtitle: String) =
                if (approve) ApprovalResult.Approved(ApprovalMethod.BIOMETRIC) else ApprovalResult.Rejected("no")
        }
        override val downloader = object : Downloader {
            override val state: StateFlow<DownloadState> = MutableStateFlow(DownloadState.Ready("fake"))
            override fun refresh() {}
            override suspend fun download(item: LockedItem, onProgress: (DownloadProgress) -> Unit): DownloadOutcome {
                downloads++
                if (downloads <= failFirst) return DownloadOutcome.Failed("Software caused connection abort", 3)
                onProgress(DownloadProgress(item.size, item.size))
                return DownloadOutcome.Done("/tmp/${item.fileName}.part", item.size, returnSha, item.host)
            }
            override fun cancel() {}
        }
        override val models = object : ModelStorage {
            override fun isInstalled(item: LockedItem) = item.id in committed
            override fun pathOf(item: LockedItem) = "/models/${item.fileName}"
            override fun commit(item: LockedItem, tempPath: String) { committed += item.id }
            override fun delete(item: LockedItem) { committed -= item.id }
            override fun deleteTemp(tempPath: String) { deletedTemp += tempPath }
            override fun importedUnverifiedFiles() = emptyList<String>()
            override fun keepUnverified(tempPath: String, fileName: String) {}
            override suspend fun pickAndImport(): ImportOutcome = ImportOutcome.Cancelled
        }
        override suspend fun execute(command: QuickCommand): String? = null
    }

    private fun installer(bridge: FakeBridge): Triple<ModelInstaller, NetworkLogStore, SettingsStore> {
        val settings = SettingsStore(bridge.files)
        val log = NetworkLogStore(bridge.files)
        return Triple(ModelInstaller(bridge, ModelRegistry(ModelLock(1, items = listOf(item))), settings, log), log, settings)
    }

    @Test
    fun verifiedDownloadIsCommittedAndLogged() = runTest {
        val bridge = FakeBridge(good)
        val (inst, log) = installer(bridge)
        val job = launch { inst.approval.first { it != null }; inst.answerApproval(true) }
        assertTrue(inst.install(listOf(item), "test"))
        job.join()
        assertEquals(listOf("m"), bridge.committed)
        assertIs<ItemStatus.Done>(inst.status.value["m"])
        assertEquals("tamam", log.events.value.single().result)
    }

    @Test
    fun hashMismatchDeletesFileAndShowsRedWarning() = runTest {
        val bridge = FakeBridge("b".repeat(64))
        val (inst, log) = installer(bridge)
        val job = launch { inst.approval.first { it != null }; inst.answerApproval(true) }
        assertFalse(inst.install(listOf(item), "test"))
        job.join()
        assertTrue(bridge.committed.isEmpty())
        assertEquals(listOf("/tmp/m.litertlm.part"), bridge.deletedTemp)
        val st = inst.status.value["m"]
        assertIs<ItemStatus.Failed>(st)
        assertTrue(st.hashMismatch)
        assertEquals("hash uyuşmadı", log.events.value.single().result)
    }

    @Test
    fun speechPackIsRequestedOnlyAfterApproval() = runTest {
        val bridge = FakeBridge(good)
        val (inst, log) = installer(bridge)
        val shown = mutableListOf<ApprovalRequest>()
        val job = launch { shown += inst.approval.first { it != null }!!; inst.answerApproval(true) }
        val text = inst.downloadSpeechPack()
        job.join()
        assertEquals(1, bridge.stt.packRequests)
        assertTrue("Android" in shown.single().reason, "the approval says the system downloads it")
        assertTrue("iletildi" in text)
        assertEquals("istendi", log.events.value.single().result)
    }

    @Test
    fun rejectedSpeechPackStaysOffline() = runTest {
        val bridge = FakeBridge(good)
        val (inst, log) = installer(bridge)
        val job = launch { inst.approval.first { it != null }; inst.answerApproval(false) }
        inst.downloadSpeechPack()
        job.join()
        assertEquals(0, bridge.stt.packRequests)
        assertEquals("reddedildi", log.events.value.single().result)
    }

    @Test
    fun speechPackRespectsFullOfflineMode() = runTest {
        val bridge = FakeBridge(good)
        val (inst, _, settings) = installer(bridge)
        settings.update { it.copy(fullOffline = true) }
        assertTrue("offline" in inst.downloadSpeechPack())
        assertEquals(0, bridge.stt.packRequests)
    }

    @Test
    fun rejectedApprovalMakesNoRequest() = runTest {
        val bridge = FakeBridge(good)
        val (inst, log) = installer(bridge)
        val job = launch { inst.approval.first { it != null }; inst.answerApproval(false) }
        assertFalse(inst.install(listOf(item), "test"))
        job.join()
        assertEquals(0, bridge.downloads)
        assertEquals("reddedildi", log.events.value.single().result)
    }

    @Test
    fun fullOfflineModeBlocksDownloads() = runTest {
        val bridge = FakeBridge(good)
        val (inst, _, settings) = installer(bridge)
        settings.update { it.copy(fullOffline = true) }
        assertFalse(inst.install(listOf(item), "test"))
        assertEquals(0, bridge.downloads)
    }

    @Test
    fun droppedConnectionIsRetriedAndResumes() = runTest {
        val bridge = FakeBridge(good, failFirst = 2)
        val (inst, log) = installer(bridge)
        val job = launch { inst.approval.first { it != null }; inst.answerApproval(true) }
        assertTrue(inst.install(listOf(item), "test"))
        job.join()
        assertEquals(3, bridge.downloads)
        assertIs<ItemStatus.Done>(inst.status.value["m"])
        assertEquals("tamam", log.events.value.single().result)
    }

    @Test
    fun givesUpAfterTheLastAttempt() = runTest {
        val bridge = FakeBridge(good, failFirst = 100)
        val (inst, _) = installer(bridge)
        val job = launch { inst.approval.first { it != null }; inst.answerApproval(true) }
        assertFalse(inst.install(listOf(item), "test"))
        job.join()
        assertEquals(ModelInstaller.MAX_ATTEMPTS, bridge.downloads)
        assertIs<ItemStatus.Failed>(inst.status.value["m"])
    }

    @Test
    fun twoButtonsForTheSameGroupDownloadOnce() = runTest {
        val bridge = FakeBridge(good)
        val (inst, _) = installer(bridge)
        val approver = launch {
            while (true) {
                inst.approval.first { it != null }
                inst.answerApproval(true)
                inst.approval.first { it == null }
            }
        }
        val a = async { inst.install(listOf(item), "a") }
        val b = async { inst.install(listOf(item), "b") }
        assertTrue(a.await())
        assertTrue(b.await())
        approver.cancel()
        assertEquals(1, bridge.downloads)
        assertEquals(listOf("m"), bridge.committed)
    }
}
