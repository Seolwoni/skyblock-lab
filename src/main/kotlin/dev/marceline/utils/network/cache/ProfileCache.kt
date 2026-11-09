@file:Suppress("DEPRECATION")

package dev.marceline.utils.network.cache

import dev.marceline.utils.network.abstracts.CacheResult
import dev.marceline.utils.network.abstracts.CachedEntry
import dev.marceline.utils.network.abstracts.NetworkCache
import dev.marceline.utils.network.data.DungeonStats
import com.google.common.cache.CacheBuilder
import java.util.concurrent.*

object ProfileCache: NetworkCache<String, DungeonStats> {
    private val storage = CacheBuilder.newBuilder().expireAfterAccess(2, TimeUnit.HOURS).build<String, CachedEntry<DungeonStats>>()

    override fun addToCache(key: String, value: DungeonStats) = storage.put(key.lowercase(), CachedEntry(value))
    override fun addFailedToCache(key: String) = storage.put(key.lowercase(), CachedEntry(null))

    override fun get(key: String): CacheResult<DungeonStats> {
        val entry = storage.getIfPresent(key.lowercase()) ?: return CacheResult.NotFound
        return if (entry.value == null) CacheResult.Failed else CacheResult.Success(entry.value)
    }
}