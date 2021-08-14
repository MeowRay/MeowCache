package com.meowray.common.cache

import com.github.benmanes.caffeine.cache.Caffeine
import net.sf.cglib.proxy.Enhancer
import net.sf.cglib.proxy.MethodInterceptor
import net.sf.cglib.proxy.MethodProxy
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
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
class MeowCache<T : Any> internal constructor(
    val clazz: Class<T>,
    val cacheAdapter: CaffeineCacheAdapter<Any, Optional<Any>>,
    val ignoreNull: Boolean,
    val cacheKeyGenerate: MeowCacheKeyGenerate,
) {

    internal var _raw: T? = null
        set(value) {
            if (field == null) {
                field = value
                return
            }
            error("Cannot modify the initialized value")
        }

    internal var _cache: T? = null
        set(value) {
            if (field == null) {
                field = value
                return
            }
            error("Cannot modify the initialized value")
        }

    val raw: T
        get() = _raw!!

    val cache: T
        get() = _cache!!


    fun clearAllCache() {
        cacheAdapter.invalidateAll()
    }

    fun put(method: Method, args: Array<out Any?>, value: Any?) {
        if (ignoreNull && value == null) throw NullValueCacheException
        cacheAdapter.put(cacheKeyGenerate(raw, method, args), Optional.ofNullable(value))
    }

    fun remove(method: Method, args: Array<out Any?>) {
        cacheAdapter.invalidate(cacheKeyGenerate(raw, method, args))
    }


}

class MeowCacheBuilder<T : Any> private constructor(private val clazz: Class<T>) {
    var cache = {
        CaffeineCacheAdapter(
            Caffeine.newBuilder().expireAfterWrite(10, TimeUnit.MINUTES).build<Any, Optional<Any>>()
        )
    }
    var ignoreNull = false
    var keyGenerator: MeowCacheKeyGenerate = DEF_KEYGEN
    var shared : Boolean = false

    var constructorClasses: Array<Class<*>> = emptyArray()
    var args: Array<Any?> = emptyArray()

    companion object {

        private val DEF_KEYGEN: MeowCacheKeyGenerate = { any, method, arrayOfAnys ->
            method.name to if (arrayOfAnys.isEmpty()) null else arrayOfAnys.size to arrayOfAnys.toList()
        }

        fun <T : Any> newBuilder(clazz: Class<T>): MeowCacheBuilder<T> = MeowCacheBuilder(clazz)
    }

    fun build(): MeowCache<T> {
        check(Modifier.isFinal(clazz.modifiers).not()) { "Can only proxy non-final classes" }
        check(
            clazz.methods.plus(clazz.declaredMethods)
                .any { Modifier.isFinal(it.modifiers).not() }) { "Methods of classes cannot all be final" }
        val meowCache =
            MeowCache(
                clazz = clazz,
                cacheAdapter = cache(),
                ignoreNull = ignoreNull,
                cacheKeyGenerate = this.keyGenerator
            )

        val rawE = Enhancer().apply {
            setCallback(MeowRawProxy(meowCache))
            setSuperclass(clazz)
            //   setUseFactory(false)
            //  setSerialVersionUID(Random.nextLong())
            setInterceptDuringConstruction(false)
            classLoader = clazz.classLoader
        }
        val cacheE = Enhancer().apply {
            setCallback(MeowCacheProxy(meowCache))
            setSuperclass(clazz)
            //   setUseFactory(false)
            //   setSerialVersionUID(Random.nextLong())
            setInterceptDuringConstruction(false)
            classLoader = clazz.classLoader
        }

        meowCache.apply {
            val r = cacheE.create(constructorClasses, args) as T
            _cache = r

            val r0 = rawE.create(constructorClasses, args) as T
            _raw = r0
        }

        return meowCache
    }


    fun buildOriginal(): T {
        val meow = build()
        val fields = clazz.declaredFields
        val cacheFields = mutableListOf<Field>()
        val rawFields = mutableListOf<Field>()
        for (field in fields) {
            field.getDeclaredAnnotation(MeowCacheObject::class.java)?.also { cacheFields.add(field) }
            field.getDeclaredAnnotation(MeowRawObject::class.java)?.also { rawFields.add(field) }
        }
        val rawField = tryRun { clazz.getDeclaredField("raw") } ?: tryRun { clazz.getField("raw") }
        val cacheField = tryRun { clazz.getDeclaredField("cache") } ?: tryRun { clazz.getField("cache") }
        rawFields.forEach {
            it.isAccessible = true
            it.set(meow.cache, meow.raw)
            return meow.cache
        }
        cacheFields.forEach {
            it.isAccessible = true
            it.set(meow.raw, meow.cache)
            return meow.raw
        }
        rawField?.let {
            if (it.type.isAssignableFrom(clazz)) {
                it.isAccessible = true
                it.set(meow.cache, meow.raw)
                return meow.cache
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

fun <T : Any> buildMeowCache(clazz: Class<T>, builder: MeowCacheBuilder<T>.() -> Unit = {}): MeowCache<T> {
    return MeowCacheBuilder.newBuilder(clazz).also(builder).build()
}

fun <T : Any> buildMeowOriginalCache(clazz: Class<T>, builder: MeowCacheBuilder<T>.() -> Unit = {}): T {
    return MeowCacheBuilder.newBuilder(clazz).also(builder).buildOriginal()
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

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class MeowCacheObject


@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class MeowRawObject


fun <T, R> T.tryRun(block: () -> R): R? {
    return try {
        return block()
    } catch (t: Throwable) {
        return null
    }

}