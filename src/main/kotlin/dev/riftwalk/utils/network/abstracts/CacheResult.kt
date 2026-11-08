package dev.riftwalk.utils.network.abstracts

sealed interface CacheResult<out T> {
    data class Success<T>(val data: T): CacheResult<T>
    object Failed: CacheResult<Nothing>
    object NotFound: CacheResult<Nothing>
}