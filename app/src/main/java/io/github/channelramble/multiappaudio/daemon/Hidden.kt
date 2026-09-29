package io.github.channelramble.multiappaudio.daemon

import android.os.IBinder
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Reflection helpers for hidden framework APIs. The daemon runs inside `app_process` (started by
 * Shizuku), where the hidden-API denylist is not enforced, so plain reflection works there.
 * Never call these from the normal app process.
 */
object Hidden {

    fun service(name: String): IBinder? =
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, name) as IBinder?

    /** `IFoo.Stub.asInterface(ServiceManager.getService(name))` */
    fun aidl(serviceName: String, interfaceName: String): Any? {
        val binder = service(serviceName) ?: return null
        return Class.forName("$interfaceName\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
    }

    fun method(target: Any, name: String, argc: Int): Method? =
        target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == argc }

    fun hasMethod(target: Any, name: String): Boolean =
        target.javaClass.methods.any { it.name == name }

    /** Invokes a public method by name and arity, unwrapping InvocationTargetException. */
    fun call(target: Any, name: String, vararg args: Any?): Any? {
        val m = method(target, name, args.size)
            ?: throw NoSuchMethodException("${target.javaClass.name}.$name/${args.size}")
        return unwrap { m.invoke(target, *args) }
    }

    fun staticCall(className: String, name: String, vararg args: Any?): Any? {
        val m = Class.forName(className).methods
            .firstOrNull { it.name == name && it.parameterCount == args.size }
            ?: throw NoSuchMethodException("$className.$name/${args.size}")
        return unwrap { m.invoke(null, *args) }
    }

    inline fun <T> unwrap(block: () -> T): T = try {
        block()
    } catch (e: InvocationTargetException) {
        throw e.targetException ?: e
    }
}
