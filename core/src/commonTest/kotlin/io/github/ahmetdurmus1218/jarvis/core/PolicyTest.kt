package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.core.models.Platform
import io.github.ahmetdurmus1218.jarvis.core.net.DownloadPolicy
import io.github.ahmetdurmus1218.jarvis.core.net.DownloadPolicy.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PolicyTest {
    @Test
    fun hostAllowlist() {
        listOf("huggingface.co", "cdn-lfs.huggingface.co", "us.aws.cdn.hf.co", "cas-bridge.xethub.hf.co",
            "download.kiwix.org", "mirror.download.kiwix.org", "github.com",
            "objects.githubusercontent.com", "release-assets.githubusercontent.com").forEach {
            assertTrue(DownloadPolicy.hostAllowed(it), it)
        }
        listOf("evil.com", "huggingface.co.evil.com", "nothf.co", "githubusercontent.com", "raw.githubusercontent.com",
            "kiwix.org", "wi.mirror.driftle.ss").forEach {
            assertTrue(!DownloadPolicy.hostAllowed(it), it)
        }
    }

    @Test
    fun urlChecks() {
        assertIs<Verdict.Allowed>(DownloadPolicy.checkUrl("https://huggingface.co/a/b/resolve/abc/model.gguf"))
        assertIs<Verdict.Denied>(DownloadPolicy.checkUrl("http://huggingface.co/a"))
        assertIs<Verdict.Denied>(DownloadPolicy.checkUrl("https://huggingface.co:8443/a"))
        assertIs<Verdict.Denied>(DownloadPolicy.checkUrl("https://user@huggingface.co/a"))
        assertIs<Verdict.Denied>(DownloadPolicy.checkUrl("https://evil.com/huggingface.co/x.gguf"))
        assertIs<Verdict.Denied>(DownloadPolicy.checkUrl("ftp://github.com/x"))
    }

    @Test
    fun extensionRules() {
        assertIs<Verdict.Allowed>(DownloadPolicy.checkFileName("model.litertlm", Platform.ANDROID, "llm"))
        assertIs<Verdict.Allowed>(DownloadPolicy.checkFileName("voice.tar.bz2", Platform.ANDROID, "tts"))
        assertIs<Verdict.Denied>(DownloadPolicy.checkFileName("app.apk", Platform.ANDROID, "llm"))
        assertIs<Verdict.Denied>(DownloadPolicy.checkFileName("lib.so", Platform.ANDROID, "llm"))
        assertIs<Verdict.Denied>(DownloadPolicy.checkFileName("tool.exe", Platform.DESKTOP, "binary"))
        assertIs<Verdict.Denied>(DownloadPolicy.checkFileName("llama.zip", Platform.ANDROID, "binary"))
        assertIs<Verdict.Denied>(DownloadPolicy.checkFileName("llama.zip", Platform.DESKTOP, "llm"))
        assertIs<Verdict.Allowed>(DownloadPolicy.checkFileName("llama.zip", Platform.DESKTOP, "binary"))
        assertIs<Verdict.Denied>(DownloadPolicy.checkFileName("../x.gguf", Platform.DESKTOP, "llm"))
    }

    private fun item(url: String, name: String, platforms: List<String> = listOf("android"), size: Long = 10, sha: String = "a".repeat(64)) =
        LockedItem("x", "llm", platforms, "X", "g", url, "h", name, size, sha, "r", "o")

    @Test
    fun itemChecks() {
        assertIs<Verdict.Allowed>(DownloadPolicy.check(item("https://huggingface.co/x/m.gguf", "m.gguf"), Platform.ANDROID))
        assertIs<Verdict.Denied>(DownloadPolicy.check(item("https://huggingface.co/x/m.gguf", "m.gguf", sha = "XYZ"), Platform.ANDROID))
        assertIs<Verdict.Denied>(DownloadPolicy.check(item("https://huggingface.co/x/m.gguf", "m.gguf", size = 0), Platform.ANDROID))
        assertIs<Verdict.Denied>(DownloadPolicy.check(item("https://huggingface.co/x/m.gguf", "m.gguf", platforms = listOf("desktop")), Platform.ANDROID))
    }

    @Test
    fun bundledLockFilePassesPolicy() {
        val reg = ModelRegistry.bundled
        assertEquals(1, reg.lock.schemaVersion)
        for (it in reg.lock.items) {
            for (p in Platform.entries.filter { p -> p.key in it.platforms }) {
                assertIs<Verdict.Allowed>(DownloadPolicy.check(it, p), "${it.id} on $p")
            }
        }
    }

    @Test
    fun recommendedBundleDependsOnRam() {
        val lock = """{"schemaVersion":1,"items":[
          {"id":"a","kind":"llm","platforms":["android"],"title":"E4B","group":"llm-android-e4b","url":"https://huggingface.co/a","host":"huggingface.co","fileName":"a.litertlm","size":1,"sha256":"${"a".repeat(64)}","revision":"r","origin":"o"},
          {"id":"b","kind":"llm","platforms":["android"],"title":"E2B","group":"llm-android-e2b","url":"https://huggingface.co/b","host":"huggingface.co","fileName":"b.litertlm","size":1,"sha256":"${"b".repeat(64)}","revision":"r","origin":"o"},
          {"id":"w","kind":"zim","platforms":["android","desktop"],"title":"W","group":"wiki-tr","url":"https://download.kiwix.org/w","host":"download.kiwix.org","fileName":"w.zim","size":1,"sha256":"${"c".repeat(64)}","revision":"r","origin":"o"}
        ]}"""
        val reg = ModelRegistry.parse(lock)
        assertEquals(listOf("a", "w"), reg.recommendedBundle(Platform.ANDROID, 12.0).map { it.id })
        assertEquals(listOf("b", "w"), reg.recommendedBundle(Platform.ANDROID, 8.0).map { it.id })
        assertEquals("w", reg.bySha256("C".repeat(64))?.id)
    }
}
