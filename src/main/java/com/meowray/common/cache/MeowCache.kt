package com.meowray.common.cache

import com.github.benmanes.caffeine.cache.Caffeine
import com.meowray.common.extension.CaffeineCacheAdapter
import com.meowray.common.extension.tryRun
import net.sf.cglib.proxy.Enhancer
import net.sf.cglib.proxy.MethodInterceptor
import net.sf.cglib.proxy.MethodProxy
import java.lang.IllegalStateException
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * @author  MeowRay
 * @date  2021/6/21 17:14
 * @version 1.0
 */

/*
* 被缓存对象和方法需要 OPEN ALL,并且非 FINAL
* */
class MeowCache<T : Any> private constructor(
    val clazz: Class<T>,
    val cacheAdapter: CaffeineCacheAdapter<Any, Optional<Any>>,
    val ignoreNull: Boolean,
    val cacheKeyGenerate: MeowCacheKeyGenerate
) {

    lateinit var raw: T
    lateinit var cache: T

    fun clearAllCache() {
        cacheAdapter.invalidateAll()
    }

    class Builder<T : Any> private constructor(private val clazz: Class<T>) {
        var cache = {
            CaffeineCacheAdapter(
                Caffeine.newBuilder().expireAfterWrite(10, TimeUnit.MINUTES).build<Any, Optional<Any>>()
            )
        }
        var ignoreNull = false
        var keyGenerator: MeowCacheKeyGenerate = DEF_KEYGEN

        var constructorClasses: Array<Class<*>> = emptyArray()
        var args: Array<Any?> = emptyArray()

        companion object {

            private val DEF_KEYGEN: MeowCacheKeyGenerate = { any, method, arrayOfAnys ->
                method to arrayOfAnys
            }

            fun <T : Any> newBuilder(clazz: Class<T>): Builder<T> = Builder(clazz)
        }

        fun build(): MeowCache<T> {
            val meowCache =
                MeowCache(
                    clazz = clazz,
                    cacheAdapter = cache(),
                    ignoreNull = ignoreNull,
                    cacheKeyGenerate = this.keyGenerator
                )
            meowCache.apply {
                cache = Enhancer().apply {
                    setCallback(MeowCacheProxy(meowCache))
                    setSuperclass(clazz)
                }.create(constructorClasses, args) as T
                raw = Enhancer().apply {
                    setCallback(MeowRawProxy(meowCache))
                    setSuperclass(clazz)
                }.create(constructorClasses, args) as T
            }
            return meowCache
        }


        fun buildOriginal(): T {
            val meow = build()
            val rawField = tryRun { clazz.getField("raw") ?: clazz.getDeclaredField("raw") }
            val cacheField = tryRun { clazz.getField("cache") ?: clazz.getDeclaredField("cache") }
            rawField?.let {
                if (it.type.isAssignableFrom(clazz)) {
                    it.isAccessible = true
                    it.set(meow.cache, meow.raw)
                }
            }
            cacheField?.let {
                if (it.type.isAssignableFrom(clazz)) {
                    it.isAccessible = true
                    it.set(meow.raw, meow.cache)
                    return meow.raw
                }
            }
            return meow.cache
        }
    }

}

fun <T : Any> buildMeowCache(clazz: Class<T>, builder: MeowCache.Builder<T>.() -> Unit= {}): MeowCache<T> {
    return MeowCache.Builder.newBuilder(clazz).also(builder).build()
}

fun <T : Any> buildMeowOriginalCache(clazz: Class<T>, builder: MeowCache.Builder<T>.() -> Unit = {}): T {
    return MeowCache.Builder.newBuilder(clazz).also(builder).buildOriginal()
}

typealias MeowCacheKeyGenerate = (Any, Method, Array<out Any?>) -> Any

private class MeowCacheProxy(val meowCache: MeowCache<*>) :
    MethodInterceptor {
    override fun intercept(obj: Any, method: Method, args: Array<out Any?>, proxy: MethodProxy): Any? {
        if (method.returnType == Void.TYPE) {
            return proxy.invokeSuper(obj, args)
        }
        val key = meowCache.cacheKeyGenerate(obj, method, args)

        return try {
            meowCache.cacheAdapter.get(key) {
                val v = Optional.ofNullable(proxy.invokeSuper(obj, args))
                if (meowCache.ignoreNull && v.isPresent.not()) {
                    throw NullValueCacheException
                }
                v
            }.orElse(null)
        } catch (e: NullValueCacheException) {
            meowCache.cacheAdapter.invalidate(key)
            null
        }
    }

}

object NullValueCacheException : IllegalStateException()

private class MeowRawProxy(val meowCache: MeowCache<*>) :
    MethodInterceptor {

    override fun intercept(obj: Any, method: Method, args: Array<out Any?>, proxy: MethodProxy): Any? {
        if (method.returnType == Void.TYPE) {
            return proxy.invokeSuper(obj, args)
        }
        val invokeSuper = proxy.invokeSuper(obj, args)
        if (invokeSuper != null || meowCache.ignoreNull.not()) {
            meowCache.cacheAdapter.put(meowCache.cacheKeyGenerate(obj, method, args), Optional.ofNullable(invokeSuper))
        }
        return invokeSuper
    }

}
