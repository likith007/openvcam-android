package com.openvcam.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Constructor
import java.lang.reflect.Method

object HookUtils {

    fun findClass(name: String, loader: ClassLoader?): Class<*>? = try {
        Class.forName(name, false, loader ?: HookUtils::class.java.classLoader)
    } catch (_: Throwable) {
        null
    }

    fun declaredMethods(clazz: Class<*>): List<Method> {
        val result = mutableListOf<Method>()
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            result.addAll(c.declaredMethods)
            c = c.superclass
        }
        return result
    }

    fun hook(
        method: Method?,
        callback: XC_MethodHook,
        tag: String
    ): Boolean {
        if (method == null) return false
        return try {
            XposedBridge.hookMethod(method, callback)
            true
        } catch (t: Throwable) {
            XposedBridge.log("[OpenVCam] hook failed $tag: ${t.message}")
            false
        }
    }

    fun hook(
        constructor: Constructor<*>?,
        callback: XC_MethodHook,
        tag: String
    ): Boolean {
        if (constructor == null) return false
        return try {
            XposedBridge.hookMethod(constructor, callback)
            true
        } catch (t: Throwable) {
            XposedBridge.log("[OpenVCam] hook failed $tag: ${t.message}")
            false
        }
    }

    fun hookAllNamed(
        clazz: Class<*>?,
        names: Collection<String>,
        callback: XC_MethodHook,
        tag: String
    ) {
        if (clazz == null) return
        val seen = mutableSetOf<String>()
        declaredMethods(clazz).forEach { m ->
            if (m.name in names) {
                val key = m.name + m.parameterTypes.joinToString { it.name }
                if (seen.add(key)) hook(m, callback, "$tag.${m.name}")
            }
        }
    }

    fun call(method: Method?, target: Any?, vararg args: Any?): Any? = try {
        method?.isAccessible = true
        method?.invoke(target, *args)
    } catch (_: Throwable) {
        null
    }
}
