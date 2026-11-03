package io.github.ahmetdurmus1218.jarvis.core.memory

import io.github.ahmetdurmus1218.jarvis.core.text.Turkish
import kotlinx.coroutines.sync.withLock

data class Fact(val id: Long, val text: String, val createdAtMillis: Long)

/**
 * Long-term memory (section 5.5). The user can see, delete and disable it. Implementations:
 * [InMemoryMemoryStore] (tests) and [PersistentMemoryStore] (the app: a file that the platform
 * encrypts with AES-GCM; see docs/DECISIONS.md).
 */
interface MemoryStore {
    suspend fun enabled(): Boolean
    suspend fun setEnabled(value: Boolean)
    suspend fun list(): List<Fact>
    /** Adds a fact unless an equivalent one exists. Returns true if stored. */
    suspend fun add(text: String): Boolean
    suspend fun remove(id: Long)
    suspend fun removeMatching(query: String): Int
    suspend fun clear()

    companion object {
        const val MAX_FACTS = 500

        /** Normalized key used for de-duplication. */
        fun dedupeKey(text: String): String = Turkish.normalize(text).replace(Regex("[^a-z0-9 ]"), "").trim()
    }
}

class InMemoryMemoryStore(private val clock: () -> Long = { 0L }) : MemoryStore {
    private val facts = mutableListOf<Fact>()
    private var nextId = 1L
    private var on = true

    override suspend fun enabled() = on
    override suspend fun setEnabled(value: Boolean) { on = value }
    override suspend fun list(): List<Fact> = facts.toList()

    override suspend fun add(text: String): Boolean {
        val t = text.trim()
        if (!on || t.isEmpty()) return false
        val key = MemoryStore.dedupeKey(t)
        if (facts.any { MemoryStore.dedupeKey(it.text) == key }) return false
        facts += Fact(nextId++, t, clock())
        while (facts.size > MemoryStore.MAX_FACTS) facts.removeAt(0)
        return true
    }

    override suspend fun remove(id: Long) { facts.removeAll { it.id == id } }

    override suspend fun removeMatching(query: String): Int {
        val q = MemoryStore.dedupeKey(query)
        val before = facts.size
        facts.removeAll { MemoryStore.dedupeKey(it.text).contains(q) }
        return before - facts.size
    }

    override suspend fun clear() { facts.clear() }
}

/**
 * Memory kept in one JSON document through [store] (the app's encrypted files). Same rules as
 * [InMemoryMemoryStore]: de-duplicated, at most [MemoryStore.MAX_FACTS] facts, can be switched off.
 */
class PersistentMemoryStore(
    private val store: io.github.ahmetdurmus1218.jarvis.core.knowledge.TextStore,
    private val clock: () -> Long = { 0L },
) : MemoryStore {
    @kotlinx.serialization.Serializable
    private data class Saved(val on: Boolean = true, val nextId: Long = 1, val facts: List<SavedFact> = emptyList())

    @kotlinx.serialization.Serializable
    private data class SavedFact(val id: Long, val text: String, val at: Long)

    private val lock = kotlinx.coroutines.sync.Mutex()
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private var state: Saved = store.read(FILE)?.let { runCatching { json.decodeFromString(Saved.serializer(), it) }.getOrNull() } ?: Saved()

    private fun save() = store.write(FILE, json.encodeToString(Saved.serializer(), state))

    override suspend fun enabled() = lock.withLock { state.on }
    override suspend fun setEnabled(value: Boolean) = lock.withLock { state = state.copy(on = value); save() }
    override suspend fun list(): List<Fact> = lock.withLock { state.facts.map { Fact(it.id, it.text, it.at) } }

    override suspend fun add(text: String): Boolean = lock.withLock {
        val t = text.trim()
        if (!state.on || t.isEmpty()) return@withLock false
        val key = MemoryStore.dedupeKey(t)
        if (state.facts.any { MemoryStore.dedupeKey(it.text) == key }) return@withLock false
        state = state.copy(nextId = state.nextId + 1, facts = (state.facts + SavedFact(state.nextId, t, clock())).takeLast(MemoryStore.MAX_FACTS))
        save()
        true
    }

    override suspend fun remove(id: Long) = lock.withLock { state = state.copy(facts = state.facts.filter { it.id != id }); save() }

    override suspend fun removeMatching(query: String): Int = lock.withLock {
        val q = MemoryStore.dedupeKey(query)
        val before = state.facts.size
        state = state.copy(facts = state.facts.filterNot { MemoryStore.dedupeKey(it.text).contains(q) })
        save()
        before - state.facts.size
    }

    override suspend fun clear() = lock.withLock { state = state.copy(facts = emptyList()); save() }

    private companion object { const val FILE = "memory.json" }
}
