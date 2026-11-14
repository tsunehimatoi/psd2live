package io.github.psd2live.core.modifier

import io.github.psd2live.format.model.*
import kotlin.test.*

class ModifierGeometryCacheTest {
    private fun geometry() = MeshGeometry(Floats.values(0f, 0f, 1f, 0f, 0f, 1f),
        Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2))
    private val entryBytes = 60L + 24L + ModifierGeometryCache.ENTRY_BYTES

    @Test fun identityLruChargesKeysAndValuesAndRetainsActiveArrays() {
        val cache = ModifierGeometryCache<ByteArray>(2 * entryBytes) { it.size.toLong() }
        val first = geometry()
        val second = geometry()
        val third = geometry()
        val active = cache.getOrPut(first) { ByteArray(24) { 7 } }
        cache.getOrPut(second) { ByteArray(24) }
        assertSame(active, cache.getOrPut(first) { error("identity hit") })
        cache.getOrPut(third) { ByteArray(24) }
        assertEquals(1L, cache.snapshot().evictions)
        assertEquals(2 * entryBytes, cache.snapshot().retainedBytes)
        assertSame(active, cache.getOrPut(first) { error("recent item evicted") })
        var rebuilt = false
        cache.getOrPut(second) { rebuilt = true; ByteArray(24) }
        assertTrue(rebuilt, "equal content with a different identity cannot hide eviction")
        cache.clear()
        assertEquals(0L, cache.snapshot().retainedBytes)
        assertEquals(0, cache.snapshot().entries)
        assertContentEquals(ByteArray(24) { 7 }, active, "eviction must not mutate active request buffers")
    }

    @Test fun thousandEditsStayWithinBudgetAndOversizeIsNotRetained() {
        val cache = ModifierGeometryCache<ByteArray>(entryBytes) { it.size.toLong() }
        repeat(1000) {
            cache.getOrPut(geometry()) { ByteArray(24) }
            assertEquals(entryBytes, cache.snapshot().retainedBytes)
        }
        assertEquals(999L, cache.snapshot().evictions)
        val oversized = cache.getOrPut(geometry()) { ByteArray(500) { 3 } }
        assertEquals(500, oversized.size)
        assertEquals(1L, cache.snapshot().oversized)
        assertEquals(entryBytes, cache.snapshot().retainedBytes)
    }

    @Test fun failedEncodingDoesNotPublishOrEvict() {
        val cache = ModifierGeometryCache<ByteArray>(entryBytes) { it.size.toLong() }
        val first = geometry()
        val retained = cache.getOrPut(first) { ByteArray(24) }
        assertFailsWith<java.util.concurrent.CancellationException> {
            cache.getOrPut(geometry()) { throw java.util.concurrent.CancellationException() }
        }
        assertSame(retained, cache.getOrPut(first) { error("failed encoding evicted successful entry") })
        assertEquals(entryBytes, cache.snapshot().retainedBytes)
    }
}
