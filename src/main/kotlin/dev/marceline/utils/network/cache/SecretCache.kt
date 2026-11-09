@file:Suppress("DEPRECATION")

package dev.marceline.utils.network.cache

import dev.marceline.utils.network.abstracts.CacheResult
import dev.marceline.utils.network.abstracts.CachedEntry
import dev.marceline.utils.network.abstracts.NetworkCache
import com.google.common.cache.CacheBuilder
import java.util.concurrent.*

object SecretCache: NetworkCache<String, Long> {
    private val entries = CacheBuilder.newBuilder().expireAfterAccess(1, TimeUnit.MINUTES).build<String, CachedEntry<Long>>()

    override fun get(key: String): CacheResult<Long> {
        val entry = entries.getIfPresent(key.lowercase()) ?: return CacheResult.NotFound
        return if (entry.value == null) CacheResult.Failed else CacheResult.Success(entry.value)
    }

    override fun addToCache(key: String, value: Long) = entries.put(key.lowercase(), CachedEntry(value))
    override fun addFailedToCache(key: String) = entries.put(key.lowercase(), CachedEntry(null))
}