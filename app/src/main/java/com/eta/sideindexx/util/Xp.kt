package com.eta.sideindexx.util

import android.content.SharedPreferences
import de.robv.android.xposed.XposedHelpers as LegacyXposedHelpers
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * LibXposed API 102 适配层（从 `de.robv.android.xposed` 老 API 迁移过来的唯一桥）。
 *
 * 为什么要有这一层：新 API（`io.github.libxposed.api`，模块声明在 `META-INF/xposed/`）**不再提供
 * `XposedHelpers` / `XposedBridge` / `XSharedPreferences`** —— 它只给
 * `hook(Executable).intercept(Hooker)`、`log()`、`getRemotePreferences()` 这几样。
 * 而本模块的业务代码（ForegroundApp / BackGestureGate / OverlayController …）大量依赖
 * `XposedHelpers` 那套"按名字反射调用"的写法。
 *
 * 于是这里按**同名同签名**重建那套接口，业务代码只把 import 从 `de.robv.android.xposed.X` 换成
 * `com.eta.sideindexx.util.X`，调用点一行都不用改。换的是底层框架，不是模块结构。
 *
 * 隐藏 API：老 `XposedHelpers` 能调 system 隐藏方法，新 API 没有对应工具。模块跑在 SystemUI 进程，
 * LSPosed 会给模块的 ClassLoader 开 hidden API 豁免，所以普通反射够用（欧泡小窗优化同样是自己写
 * 反射工具 `Ref`）。真遇到被拦的成员，用 [XposedHelpers.deoptimize] 兜。
 */
object Xp {

    @Volatile private var api: XposedInterface? = null

    /** 由模块入口在 onModuleLoaded / onPackageLoaded 里调用 */
    fun attach(instance: XposedInterface) {
        api = instance
    }

    fun api(): XposedInterface? = api

    fun log(msg: String) {
        try {
            api?.log(android.util.Log.INFO, com.eta.sideindexx.Const.LOG_TAG, msg)
        } catch (_: Throwable) {
        }
    }

    fun log(t: Throwable) {
        try {
            api?.log(android.util.Log.ERROR, com.eta.sideindexx.Const.LOG_TAG, t.toString(), t)
        } catch (_: Throwable) {
        }
    }

    /** 模块自己 SharedPreferences 的跨进程视图（替代 XSharedPreferences） */
    fun remotePreferences(name: String): SharedPreferences? = try {
        api?.getRemotePreferences(name)
    } catch (t: Throwable) {
        null
    }

    // ---------------- hook 原语 ----------------

    fun hook(executable: Executable, callback: XC_MethodHook): Any? = try {
        api?.hook(executable)?.intercept(XC_MethodHook.HookerImpl(callback))
    } catch (t: Throwable) {
        XLog.d("hook ${executable.name} 失败：${t.javaClass.simpleName}")
        null
    }

    fun hookAllConstructors(clazz: Class<*>, callback: XC_MethodHook): Set<Any?> {
        val out = HashSet<Any?>()
        try {
            for (c in clazz.declaredConstructors) {
                hook(c, callback)?.let { out.add(it) }
            }
        } catch (t: Throwable) {
            XLog.d("hook 构造器失败：${t.javaClass.simpleName}")
        }
        return out
    }
}

/**
 * 老 `XC_MethodHook` 的同名替身：业务代码里的匿名子类原样能用。
 * 内部把新 API 的 [XposedInterface.Chain] 适配成老式的 `MethodHookParam`。
 */
abstract class XC_MethodHook {

    open fun beforeHookedMethod(param: MethodHookParam) {}
    open fun afterHookedMethod(param: MethodHookParam) {}

    class MethodHookParam {
        var thisObject: Any? = null
        var args: Array<Any?> = emptyArray()
        var result: Any? = null

        /** 置 true 表示跳过原方法，直接把 [result] 返回 */
        var returnEarly: Boolean = false
        var throwable: Throwable? = null
        val methodName: String get() = method?.name ?: "?"

        internal var method: Executable? = null
    }

    internal class HookerImpl(private val cb: XC_MethodHook) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val param = MethodHookParam()
            param.thisObject = chain.thisObject
            param.args = chain.args.toTypedArray()
            param.method = chain.executable
            try {
                cb.beforeHookedMethod(param)
            } catch (t: Throwable) {
                XLog.w("hook before 异常（${param.methodName}）", t)
            }
            if (param.returnEarly) return param.result
            try {
                param.result = chain.proceed(param.args)
            } catch (t: Throwable) {
                param.throwable = t
                try {
                    cb.afterHookedMethod(param)
                } catch (_: Throwable) {
                }
                // 原方法自己抛的异常必须原样放出去，否则会把 SystemUI 的行为改掉
                throw t
            }
            try {
                cb.afterHookedMethod(param)
            } catch (t: Throwable) {
                XLog.w("hook after 异常（${param.methodName}）", t)
            }
            return param.result
        }
    }
}

/** 老 `XposedBridge` 的同名替身 */
object XposedBridge {

    fun log(msg: String) = Xp.log(msg)

    fun log(t: Throwable) = Xp.log(t)

    fun hookMethod(member: java.lang.reflect.Member, callback: XC_MethodHook): Any? =
        if (member is Executable) Xp.hook(member, callback) else null

    fun hookAllConstructors(clazz: Class<*>, callback: XC_MethodHook): Set<Any?> =
        Xp.hookAllConstructors(clazz, callback)
    /** 按方法名 hook（含父类同名方法），返回成功数量 */
    fun hookAllMethods(clazz: Class<*>, methodName: String, callback: XC_MethodHook): Int {
        var n = 0
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (m.name != methodName) continue
                try {
                    Xp.hook(m, callback)
                    n++
                } catch (t: Throwable) {
                }
            }
            c = c.superclass
        }
        return n
    }

}

/**
 * 老 `XposedHelpers` 的同名替身：按名字反射调用 / 取字段 / 找类 / 挂钩。
 * 全部做了方法缓存（老 XposedHelpers 也缓存），并且**任何失败都返回 null / 默认值**，
 * 不让异常冒到 SystemUI —— 模块异常 = 系统界面崩溃。
 */
object XposedHelpers {

    /**
     * 框架侧那个老 `XposedHelpers`（由 LSPosed 提供）能不能用。
     *
     * **能用就一律转发过去**：它的反射走的是框架自己的 hidden API 豁免通道；模块内自建反射
     * 在个别成员（典型是 `ActivityTaskManager.getService`）上会被拦，而前台判定一旦拿不到顶层应用，
     * `allowedHere()` 就会把"判不出来"当成放行 —— 表现就是"没开全局索引、别的应用里也能呼出"。
     *
     * 编译期靠 xposed-api-82.jar 的 stub，运行期由框架给实现；框架不提供这个类时
     * （NoClassDefFoundError）自动退回自建反射，功能不受影响、只是可靠性略降。
     */
    private val legacyAvailable: Boolean = try {
        Class.forName(
            "de.robv.android.xposed.XposedHelpers", false, XposedHelpers::class.java.classLoader
        )
        true
    } catch (t: Throwable) {
        XLog.i("框架未提供老 XposedHelpers，反射走自建实现")
        false
    }

    private val methodCache = ConcurrentHashMap<String, OptionalMethod>()
    private val fieldCache = ConcurrentHashMap<String, OptionalField>()

    private class OptionalMethod(val m: Method?)
    private class OptionalField(val f: Field?)

    fun findClassIfExists(name: String, classLoader: ClassLoader?): Class<*>? {
        if (legacyAvailable) {
            try {
                return LegacyXposedHelpers.findClassIfExists(name, classLoader)
            } catch (t: Throwable) {
                // 落到自建实现
            }
        }
        // 多候选依次试：给进来的那个不一定能看见目标类。
        // system_server 里尤其如此 —— `com.android.server.wm.*` 来自 services.jar，
        // 而回调给我们的 classLoader 是"被加载的那个包"的，未必把它包含在内。
        for (cl in listOfNotNull(classLoader, java.lang.ClassLoader.getSystemClassLoader())) {
            try {
                return Class.forName(name, false, cl)
            } catch (t: Throwable) {
                // 换下一个
            }
        }
        return try {
            Class.forName(name)
        } catch (t: Throwable) {
            null
        }
    }

    fun callStaticMethod(clazz: Class<*>, name: String, vararg args: Any?): Any? {
        if (legacyAvailable) {
            try {
                return LegacyXposedHelpers.callStaticMethod(clazz, name, *args)
            } catch (t: Throwable) {
            }
        }
        return ownCallStaticMethod(clazz, name, args)
    }

    /** 参数写成可空：老 XposedHelpers 的入参是 Object，业务代码里存在传 null 的调用点 */
    fun callMethod(obj: Any?, name: String, vararg args: Any?): Any? {
        if (obj == null) return null
        if (legacyAvailable) {
            try {
                return LegacyXposedHelpers.callMethod(obj, name, *args)
            } catch (t: Throwable) {
            }
        }
        return ownCallMethod(obj, name, args)
    }

    fun getObjectField(obj: Any?, name: String): Any? {
        if (obj == null) return null
        if (legacyAvailable) {
            try {
                return LegacyXposedHelpers.getObjectField(obj, name)
            } catch (t: Throwable) {
            }
        }
        return ownGetObjectField(obj, name)
    }

    fun getBooleanField(obj: Any?, name: String): Boolean {
        if (obj == null) return false
        if (legacyAvailable) {
            try {
                return LegacyXposedHelpers.getBooleanField(obj, name)
            } catch (t: Throwable) {
            }
        }
        return ownGetBooleanField(obj, name)
    }

    fun getIntField(obj: Any?, name: String): Int {
        if (obj == null) return 0
        if (legacyAvailable) {
            try {
                return LegacyXposedHelpers.getIntField(obj, name)
            } catch (t: Throwable) {
            }
        }
        return ownGetIntField(obj, name)
    }

    fun getStaticObjectField(clazz: Class<*>, name: String): Any? {
        if (legacyAvailable) {
            try {
                return LegacyXposedHelpers.getStaticObjectField(clazz, name)
            } catch (t: Throwable) {
            }
        }
        return ownGetStaticObjectField(clazz, name)
    }

    // ---------------- 自建反射（框架不提供老 API 时的兜底） ----------------

    private fun ownCallStaticMethod(clazz: Class<*>, name: String, args: Array<out Any?>): Any? = try {
        val m = findMethod(clazz, name, true, args) ?: return null
        m.invoke(null, *args)
    } catch (t: Throwable) {
        null
    }

    private fun ownCallMethod(obj: Any, name: String, args: Array<out Any?>): Any? = try {
        val m = findMethod(obj.javaClass, name, false, args) ?: return null
        m.invoke(obj, *args)
    } catch (t: Throwable) {
        null
    }

    private fun ownGetObjectField(obj: Any, name: String): Any? = try {
        findField(obj.javaClass, name)?.get(obj)
    } catch (t: Throwable) {
        null
    }

    private fun ownGetBooleanField(obj: Any, name: String): Boolean = try {
        findField(obj.javaClass, name)?.getBoolean(obj) ?: false
    } catch (t: Throwable) {
        false
    }

    private fun ownGetIntField(obj: Any, name: String): Int = try {
        findField(obj.javaClass, name)?.getInt(obj) ?: 0
    } catch (t: Throwable) {
        0
    }

    private fun ownGetStaticObjectField(clazz: Class<*>, name: String): Any? = try {
        findField(clazz, name)?.get(null)
    } catch (t: Throwable) {
        null
    }

    /** 老 API 的 `findAndHookMethod(clazz, name, callback)`（无参方法版） */
    fun findAndHookMethod(clazz: Class<*>, name: String, callback: XC_MethodHook): Any? {
        val m = findMethod(clazz, name, false, emptyArray()) ?: run {
            XLog.d("findAndHookMethod：找不到 ${clazz.simpleName}.$name()")
            return null
        }
        return Xp.hook(m, callback)
    }

    /** 带参数类型的版本：hook 有参方法（比如 `onTouchEvent(MotionEvent)`） */
    fun findAndHookMethod(
        clazz: Class<*>,
        name: String,
        parameterTypes: Array<Class<*>>,
        callback: XC_MethodHook
    ): Any? {
        val m = try {
            clazz.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
        } catch (t: Throwable) {
            XLog.d("findAndHookMethod：找不到 ${clazz.simpleName}.$name/${parameterTypes.size} 参数")
            return null
        }
        return Xp.hook(m, callback)
    }

    /** 该成员是否需要去优化（个别被 inline/JIT 掉的隐藏方法调用不生效时用） */
    fun deoptimize(executable: Executable): Boolean = try {
        Xp.api()?.deoptimize(executable) ?: false
    } catch (t: Throwable) {
        false
    }

    // ---------------- 内部 ----------------

    private fun findMethod(clazz: Class<*>, name: String, static: Boolean, args: Array<out Any?>): Method? {
        val key = clazz.name + "#" + name + "/" + args.size + (if (static) ":s" else ":i")
        methodCache[key]?.let { return it.m }
        var c: Class<*>? = clazz
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name != name) continue
                if (m.parameterTypes.size != args.size) continue
                if (Modifier.isStatic(m.modifiers) != static) continue
                if (!paramsMatch(m.parameterTypes, args)) continue
                try {
                    m.isAccessible = true
                } catch (_: Throwable) {
                }
                methodCache[key] = OptionalMethod(m)
                return m
            }
            c = c.superclass
        }
        methodCache[key] = OptionalMethod(null)
        return null
    }

    private fun findField(clazz: Class<*>, name: String): Field? {
        val key = clazz.name + "#" + name
        fieldCache[key]?.let { return it.f }
        var c: Class<*>? = clazz
        while (c != null) {
            for (f in c.declaredFields) {
                if (f.name != name) continue
                try {
                    f.isAccessible = true
                } catch (_: Throwable) {
                }
                fieldCache[key] = OptionalField(f)
                return f
            }
            c = c.superclass
        }
        fieldCache[key] = OptionalField(null)
        return null
    }

    /** 宽松匹配参数：null 兼容任何非基本类型；包装类型与基本类型互通 */
    private fun paramsMatch(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
        for (i in types.indices) {
            val t = types[i]
            val a = args[i] ?: if (t.isPrimitive) return false else continue
            if (t.isInstance(a)) continue
            val boxed = boxOf(t) ?: return false
            if (!boxed.isInstance(a)) return false
        }
        return true
    }

    private fun boxOf(t: Class<*>): Class<*>? = when (t) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> null
    }

    /** 备用：按构造器签名取构造器（个别钩子需要） */
    fun findConstructorIfExists(clazz: Class<*>, vararg paramTypes: Class<*>): Constructor<*>? = try {
        clazz.getDeclaredConstructor(*paramTypes).also { it.isAccessible = true }
    } catch (t: Throwable) {
        null
    }
}
