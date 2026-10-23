package com.eta.sideindexx.wm

import android.graphics.Rect
import com.eta.sideindexx.util.XC_MethodHook
import com.eta.sideindexx.util.XLog
import com.eta.sideindexx.util.XposedBridge
import com.eta.sideindexx.util.XposedHelpers
import java.util.concurrent.ConcurrentHashMap

/**
 * system_server 侧小窗（ColorOS 弹性窗口）探测。
 *
 * 关键事实（本机实测，Android 16 / ColorOS）：
 * - `oplus-services.jar` 自进程启动就在 `SYSTEMSERVERCLASSPATH` 里，
 *   即 system_server 自己的 PathClassLoader 从一开场就能看见 `com.android.server.wm.*`。
 * - 找不到类不是"时机太早"，而是**loader 拿错了**：
 *   `ClassLoader.getSystemClassLoader()` 的父是 boot loader，只覆盖 `BOOTCLASSPATH`，
 *   看不见 services.jar / oplus-services.jar；`onPackageLoaded("android")` 在 system_server
 *   热挂载时根本不会再回调（android 包早已加载完）。
 * - 正解：从 `ServiceManager.getService("activity")` 拿到的本地 AMS 实例取 `classLoader`，
 *   那就是 system_server 的 PathClassLoader。
 */
object WmProbe {

    private const val TAG = "小窗探测"
    private const val PKG = "com.android.server.wm."
    private const val TARGET = PKG + "FlexibleTaskController"
    private const val MAX_TRIES = 40
    private const val INTERVAL = 1500L

    /** 精简版：只保留功能必需的两类（原来还探了 5 个类、82 个方法） */
    private val CLASSES = arrayOf(
        "FlexibleTaskController",
        "FlexibleTaskController\$TouchListener"
    )


    @Volatile private var started = false
    @Volatile private var hooked = false
    @Volatile private var hint: ClassLoader? = null
    private val seen = ConcurrentHashMap<String, Int>()

    fun bootstrap(hintCl: ClassLoader?) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        hint = hintCl
        XLog.i("$TAG：system_server 侧启动探测（hint=${hintCl?.javaClass?.simpleName}）")
        Thread({
            var dumped = false
            for (i in 0 until MAX_TRIES) {
                if (hooked) return@Thread
                try {
                    val cl = pickLoader()
                    if (cl != null) {
                        hook(cl)
                        return@Thread
                    }
                    if (!dumped) {
                        dumped = true
                        dumpCandidates()
                    }
                } catch (t: Throwable) {
                    XLog.w("$TAG：探测异常", t)
                }
                try {
                    Thread.sleep(INTERVAL)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
            XLog.i("$TAG：${MAX_TRIES}×${INTERVAL}ms 仍未找到 $TARGET")
        }, "eta-wmprobe").apply {
            isDaemon = true
            start()
        }
    }

    // ---------------- loader 定位 ----------------

    private fun pickLoader(): ClassLoader? {
        for (cl in candidates()) {
            if (XposedHelpers.findClassIfExists(TARGET, cl) != null) return cl
        }
        return null
    }

    private fun candidates(): List<ClassLoader> {
        val out = LinkedHashSet<ClassLoader>()
        hint?.let { out.add(it) }
        for (svc in arrayOf("activity", "window", "package", "input")) {
            binderLoader(svc)?.let { out.add(it) }
        }
        try {
            ClassLoader.getSystemClassLoader()?.let { out.add(it) }
        } catch (_: Throwable) {
        }
        try {
            for (t in Thread.getAllStackTraces().keys) {
                try {
                    t.contextClassLoader?.let { out.add(it) }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
        return out.toList()
    }

    /** system_server 内 `ServiceManager.getService(x)` 返回本地服务实例，其 classLoader 即系统 loader */
    private fun binderLoader(svc: String): ClassLoader? = try {
        val sm = XposedHelpers.findClassIfExists("android.os.ServiceManager", null)
        if (sm == null) null
        else XposedHelpers.callStaticMethod(sm, "getService", svc)?.javaClass?.classLoader
    } catch (t: Throwable) {
        null
    }

    private fun dumpCandidates() {
        val sb = StringBuilder("$TAG：候选 loader →")
        for (cl in candidates()) {
            sb.append("\n  ").append(cl.javaClass.name).append(" @ ").append(cl)
        }
        XLog.i(sb.toString())
    }

    // ---------------- hook ----------------

    private fun hook(cl: ClassLoader) {
        if (hooked) return
        synchronized(this) {
            if (hooked) return
            hooked = true
        }
        XLog.i("$TAG：命中 loader=${cl.javaClass.name} @ ${cl}")
        var total = 0
        for (simple in CLASSES) {
            val c = XposedHelpers.findClassIfExists(PKG + simple, cl) ?: run {
                XLog.i("$TAG：缺少 $simple")
                continue
            }
            var n = 0
            for (m in c.declaredMethods) {
                if (c.simpleName == "TouchListener" && m.name == "onPointerEvent") {
                    try {
                        XposedBridge.hookMethod(m, touchHook())
                        n++
                        XLog.i("$TAG：已挂触摸跟踪（区分单击/滑动）")
                    } catch (t: Throwable) {
                        XLog.w("$TAG：挂触摸跟踪失败", t)
                    }
                    continue
                }
                if (c.simpleName == "FlexibleTaskController" && m.name == "handleTapOutsideTask") {
                    try {
                        XposedBridge.hookMethod(m, tapOutsideHook())
                        n++
                        XLog.i("$TAG：已挂点外单击→关闭")
                    } catch (t: Throwable) {
                        XLog.w("$TAG：挂点外关闭失败", t)
                    }
                    continue
                }
            }
            total += n
            XLog.i("$TAG：${c.simpleName} 挂 $n 个方法")
        }
        XLog.i("$TAG：总计 $total")
    }

    /**
     * 点外单击 → 关闭小窗。
     * 关闭不自己造：直接调 `exitFlexibleTask(Task,boolean,int,int)`（上滑关闭的收敛点，内部走 moveTaskToBack）。
     * 缩小（mini）态不关闭。
     */
    private fun tapOutsideHook(): XC_MethodHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
            val ctl = param.thisObject ?: return
            val dc = param.args.getOrNull(0)
            val x = param.args.getOrNull(1) as? Int ?: 0
            val y = param.args.getOrNull(2) as? Int ?: 0
            try {
                val task = invoke(ctl, "getTopMiniOrDefaultZoomTask")
                if (task == null) {
                    XLog.d("$TAG：点($x,$y) 无小窗 task")
                    return
                }
                // ① 缩小态（mini）：完全不干预，交系统
                if (invoke(ctl, "isTaskInMiniFlexible", task) == true) {
                    XLog.d("$TAG：点($x,$y) 缩小态 → 交系统")
                    return
                }
                val imeVisible = isImeVisible(dc)
                val vis = visibleRect(ctl, task)
                // ①b 用户刚手动缩放过（捕获到缩放手势后 60s 内）：交系统，用户还要继续看
                //     不用面积猜：taskId 会被系统复用、不同 app 默认尺寸也不同，面积基准必失准
                // 缩小判定：用 TaskExtImpl.getScale()（物理缩放系数，不受窗口位置影响、
                // 也不会被系统的 ...InAnimHandler 动画回调污染），与该 task 见过的最大 scale 比较
                val scale = extScale(task)
                if (!imeVisible && scale != null && shrunkScale(task, scale)) {
                    XLog.i("$TAG：点($x,$y) 缩小态 scale=$scale → 交系统")
                    return
                }
                // ② 点在窗内：不干预（含小窗内容区，打字/操作照常）
                val inRegion = invoke(ctl, "hasFlexibleTaskInTouchableRegion", x, y) == true
                val inside = inRegion || (vis != null && !vis.isEmpty && vis.contains(x, y))
                if (inside) {
                    XLog.d("$TAG：点($x,$y) 窗内 vis=$vis region=$inRegion ime=$imeVisible → 不关")
                    return
                }
                // ③ IME（键盘）区域：独立窗口，在小窗 bounds 外，点它是打字不是"点外"
                if (inIme(dc, x, y)) {
                    XLog.d("$TAG：点($x,$y) 键盘区 → 不关")
                    return
                }
                // 边缘起手 → 系统返回手势，完全放行（不 cancel、不关闭）
                val edgePx = px(EDGE_START_DP)
                val screenW = screenWidthPx()
                val fresh = tracking && downAt > 0 && (android.os.SystemClock.uptimeMillis() - downAt) < 400L
                val startX = if (fresh) downX else x.toFloat()
                if (startX < edgePx || startX > screenW - edgePx) {
                    XLog.i("$TAG：点($x,$y) 边缘起手(${startX.toInt()}) → 交系统返回手势")
                    return
                }
                // 其余点外：立即取消当前手势（防穿透必须在下层收到 UP 之前），再关闭
                val trace = if (XLog.isVerbose()) " | 栈: ${frames()}" else ""
                val tid = fieldOf(task, "mTaskId") ?: -1
                XLog.i("$TAG：点($x,$y) 窗外 task=$tid vis=$vis 起手=${startX.toInt()} → 关闭$trace")
                cancelCurrentTouch(ctl)
                invoke(ctl, "exitFlexibleTask", task, false, 0, 0)
                param.returnEarly = true
            } catch (t: Throwable) {
                XLog.w("$TAG：点外处理异常", t)
            }
        }
    }

    /**
     * 小窗真实可见矩形 = 系统的 `isInTouchableRegion` 口径：
     * `TaskExtImpl.getFlexibleTaskPosAfterIme()` 非空时用它，否则用 `getFlexibleTaskVisibleBounds(task)`。
     */
    private fun visibleRect(ctl: Any, task: Any): Rect? {
        try {
            val wrapper = invoke(task, "getWrapper")
            val ext = wrapper?.let { invoke(it, "getExtImpl") }
            val afterIme = ext?.let { rectOf(invoke(it, "getFlexibleTaskPosAfterIme")) }
            if (afterIme != null && !afterIme.isEmpty) return afterIme
        } catch (_: Throwable) {
        }
        return rectOf(invoke(ctl, "getFlexibleTaskVisibleBounds", task))
    }

    private fun rectOf(o: Any?): Rect? = o as? Rect

    private fun fieldOf(obj: Any?, name: String): Any? {
        var c: Class<*>? = obj?.javaClass ?: return null
        while (c != null) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }.get(obj)
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            } catch (t: Throwable) {
                return null
            }
        }
        return null
    }

    // ---------------- 单击 / 滑动 区分 ----------------

    /** 位移超过该值即视为滑动（返回手势） */
    private const val CLICK_SLOP_DP = 24
    /** 边缘起点宽度（返回手势区） */
    private const val EDGE_START_DP = 24

    @Volatile private var downX = 0f
    @Volatile private var downY = 0f
    @Volatile private var downAt = 0L
    @Volatile private var maxDist = 0f
    @Volatile private var tracking = false

    private class PendingClose(val ctl: Any, val task: Any, val x: Int, val y: Int, val at: Long)
    @Volatile private var pending: PendingClose? = null

    /** task 实例（弱引用）→ 见过的最大缩放系数（越接近全屏越大），用于识别"缩小时" */
    private val maxScale = java.util.WeakHashMap<Any, Float>()

    private fun extScale(task: Any): Float? = try {
        val wrapper = invoke(task, "getWrapper") ?: return null
        val ext = invoke(wrapper, "getExtImpl") ?: return null
        (invoke(ext, "getScale") as? Float)?.takeIf { it > 0f }
    } catch (t: Throwable) {
        null
    }

    /** 当前 scale 明显小于该 task 见过的最大 scale → 用户缩小了 */
    private fun shrunkScale(task: Any, scale: Float): Boolean {
        val prev = maxScale[task]
        if (prev == null || scale > prev) {
            maxScale[task] = scale
            return false
        }
        return scale < prev * 0.97f
    }

    /** 主屏宽度（用于边缘起手判定） */
    private fun screenWidthPx(): Float = try {
        android.content.res.Resources.getSystem().displayMetrics.widthPixels.toFloat()
    } catch (t: Throwable) {
        1080f
    }

    private fun px(dp: Int): Float = try {
        android.content.res.Resources.getSystem().displayMetrics.density * dp
    } catch (t: Throwable) {
        dp * 3f
    }

    /** 观测触摸位移：位移超过 slop → 判定为滑动（返回手势），撤销挂起的关闭 */
    private fun touchHook(): XC_MethodHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
            val e = param.args.getOrNull(0) as? android.view.MotionEvent ?: return
            val now = android.os.SystemClock.uptimeMillis()
            try {
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = e.x; downY = e.y; downAt = now; maxDist = 0f; tracking = true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (!tracking) return
                        val dx = e.x - downX
                        val dy = e.y - downY
                        val d = kotlin.math.sqrt(dx * dx + dy * dy)
                        if (d > maxDist) maxDist = d
                        if (maxDist > px(CLICK_SLOP_DP) && pending != null) {
                            XLog.d("$TAG：位移 ${maxDist.toInt()}px > 阈值 → 判定滑动，不关闭")
                            pending = null
                        }
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        if (tracking && maxDist > px(CLICK_SLOP_DP)) {
                            XLog.d("$TAG：手势结束 位移 ${maxDist.toInt()}px → 视为滑动，不关闭")
                            pending = null
                        }
                        tracking = false
                    }
                }
            } catch (t: Throwable) {
            }
        }
    }

    /**
     * 取消当前触摸手势：`controller.mAtms.mWindowManager.mInputManager.cancelCurrentTouch()`。
     * 触摸焦点窗口会收到 ACTION_CANCEL，所以下层应用不会把这次点外当成点击。
     */
    private fun cancelCurrentTouch(ctl: Any) {
        try {
            val atms = fieldOf(ctl, "mAtms") ?: return fail("mAtms")
            val wms = fieldOf(atms, "mWindowManager") ?: return fail("mWindowManager")
            val ims = fieldOf(wms, "mInputManager") ?: return fail("mInputManager")
            invoke(ims, "cancelCurrentTouch")
            XLog.i("$TAG：已取消当前触摸手势（防穿透）")
        } catch (t: Throwable) {
            XLog.w("$TAG：取消触摸失败", t)
        }
    }

    private fun fail(where: String) {
        XLog.w("$TAG：拿不到 $where，防穿透跳过")
    }

    private fun imeWindow(dc: Any?): Any? = fieldOf(dc, "mInputMethodWindow")

    private fun isImeVisible(dc: Any?): Boolean {
        val ime = imeWindow(dc) ?: return false
        return invoke(ime, "isVisible") == true
    }

    /** 点是否落在输入法窗口上（系统里 IME 是独立 WindowState，坐标在小窗之外） */
    private fun inIme(dc: Any?, x: Int, y: Int): Boolean {
        val ime = imeWindow(dc) ?: return false
        if (invoke(ime, "isVisible") != true) return false
        val f = rectOf(invoke(ime, "getFrame")) ?: rectOf(invoke(ime, "getBounds")) ?: return false
        return !f.isEmpty && f.contains(x, y)
    }

    /** 反射调用：按名字 + 实参个数/类型匹配（含父类），失败返回 null */
    private fun invoke(obj: Any, name: String, vararg args: Any?): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name != name) continue
                val ps = m.parameterTypes
                if (ps.size != args.size) continue
                var ok = true
                for (i in ps.indices) {
                    val a = args[i]
                    if (a == null) {
                        if (ps[i].isPrimitive) { ok = false; break }
                    } else if (!boxed(ps[i]).isInstance(a)) {
                        ok = false; break
                    }
                }
                if (!ok) continue
                try {
                    m.isAccessible = true
                    return m.invoke(obj, *args)
                } catch (t: Throwable) {
                    XLog.w("$TAG：调用 $name 失败", t)
                    return null
                }
            }
            c = c.superclass
        }
        XLog.d("$TAG：找不到 $name/${args.size}")
        return null
    }

    private fun boxed(c: Class<*>): Class<*> = when (c) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        Integer.TYPE -> Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> c
    }

    private fun frames(): String = try {
        Throwable().stackTrace
            .dropWhile {
                val n = it.className
                n.startsWith("com.eta.sideindexx") || n.startsWith("io.github.libxposed") ||
                    n.startsWith("de.robv") || n.startsWith("org.lsposed") || n.startsWith("java.lang.reflect")
            }
            .take(6)
            .joinToString(" <- ") { f -> f.className.substringAfterLast('.') + "#" + f.methodName }
    } catch (_: Throwable) {
        ""
    }
}
