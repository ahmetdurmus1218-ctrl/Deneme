package io.github.ahmetdurmus1218.jarvis.core.memory

import io.github.ahmetdurmus1218.jarvis.core.text.Turkish

data class Fact(val id: Long, val text: String, val createdAtMillis: Long)

/**
 * Long-term memory (section 5.5). The user can see, delete and disable it. Implementations:
 * [InMemoryMemoryStore] (v0.1); encrypted SQLCipher stores arrive in v0.4.
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
