package dev.riftwalk.utils.network.abstracts

class CachedEntry<T>(val value: T?, val timestamp: Long = System.currentTimeMillis())