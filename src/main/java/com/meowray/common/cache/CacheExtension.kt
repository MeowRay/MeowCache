package com.meowray.common.cache

import com.github.benmanes.caffeine.cache.Cache

/**
 * @author  MeowRay
 * @date  2021/6/22 16:55
 * @version 1.0
 */


interface CacheAdapter<K, V> {

    fun put(key: K, value: V)

    fun getIfPresent(key: K): V?

    fun get(key: K, loader: CacheLoader<in K, out V>): V

    fun invalidate(key: K)

    fun invalidateAll()

}
typealias CacheLoader <K, V> = (K) -> V

open class CaffeineCacheAdapter<K, V>( val cache: Cache<K, V>) : CacheAdapter<K, V> {

    override fun put(key: K, value: V) {
        cache.put(key, value)
    }


    override fun get(key: K, loader: CacheLoader<in K, out V>): V {
        return cache.get(key, loader)
    }

    override fun getIfPresent(key: K): V? {
        return cache.getIfPresent(key)
    }

    override fun invalidate(key: K) {
        cache.invalidate(key)
    }

    override fun invalidateAll() {
        cache.invalidateAll()
    }


}
