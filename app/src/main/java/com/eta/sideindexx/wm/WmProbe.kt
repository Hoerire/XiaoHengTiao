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
        "FlexibleTaskScaleManager",
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
                if (c.simpleName == "FlexibleTaskScaleManager" && isScaleTouch(m)) {
                    try {
                        XposedBridge.hookMethod(m, scaleMarkHook(c.simpleName, m.name))
                        n++
                    } catch (t: Throwable) {
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
                if (!imeVisible && (scaledRecently(task) || shrunkVsMax(task, vis))) {
                    XLog.d("$TAG：点($x,$y) 缩放态 → 交系统")
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
                // 调用栈只在 verbose 时才构造（new Throwable 在 system_server 里不便宜）
                val trace = if (XLog.isVerbose()) " | 栈: ${frames()}" else ""
                XLog.i("$TAG：点($x,$y) 窗外 vis=$vis → 待确认（单击才关）$trace")
                // 系统把边缘滑动手势也当作 tap-outside 通知过来，先挂起：
                // 由 touchHook 观测位移，若判定为滑动（返回手势）则放弃；否则 200ms 后按单击关闭。
                schedulePendingClose(ctl, task, x, y)
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

    /** 单击最大时长 */
    private const val CLICK_MAX_MS = 320L
    /** 位移超过该值即视为滑动（返回手势） */
    private const val CLICK_SLOP_DP = 24
    /** 边缘起点宽度（返回手势区） */
    private const val EDGE_START_DP = 24
    /** 挂起后多久按单击处理 */
    private const val PENDING_CONFIRM_MS = 200L

    @Volatile private var downX = 0f
    @Volatile private var downY = 0f
    @Volatile private var downAt = 0L
    @Volatile private var maxDist = 0f
    @Volatile private var tracking = false

    private class PendingClose(val ctl: Any, val task: Any, val x: Int, val y: Int, val at: Long)
    @Volatile private var pending: PendingClose? = null

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

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

    /** 挂起关闭：等 200ms，期间若判定为滑动则放弃 */
    private fun schedulePendingClose(ctl: Any, task: Any, x: Int, y: Int) {
        pending = PendingClose(ctl, task, x, y, android.os.SystemClock.uptimeMillis())
        mainHandler.postDelayed({
            val p = pending ?: return@postDelayed
            pending = null
            val dur = android.os.SystemClock.uptimeMillis() - p.at
            val moved = maxDist
            if (moved > px(CLICK_SLOP_DP)) {
                XLog.i("$TAG：点($x,$y) 判定滑动（位移 ${moved.toInt()}px）→ 不关闭")
                return@postDelayed
            }
            if (downAt > 0 && p.at - downAt > CLICK_MAX_MS) {
                XLog.i("$TAG：点($x,$y) 手势过长（${p.at - downAt}ms）→ 不关闭")
                return@postDelayed
            }
            XLog.i("$TAG：点($x,$y) 判定单击（位移 ${moved.toInt()}px, 时长 ${dur}ms）→ 关闭")
            cancelCurrentTouch(p.ctl)
            invoke(p.ctl, "exitFlexibleTask", p.task, false, 0, 0)
        }, PENDING_CONFIRM_MS)
    }

    /** task 实例（弱引用）→ 最后一次手动缩放的时刻 */
    private val scaledAt = java.util.WeakHashMap<Any, Long>()

    /** task 实例（弱引用）→ 见过的最大可见面积（IME 不可见时才采样），用于兜底识别"用户缩小" */
    private val maxArea = java.util.WeakHashMap<Any, Long>()

    /**
     * 只认**手势驱动**的缩放方法：参数里必须带 MotionEvent。
     * 不能按 Task 参数筛：`resetFlexibleTaskScaleState`（关窗重置）、
     * `startSameRatioScaleSpringAnimation`（打开/布局动画）都带 Task，会把开关窗误判成用户缩放。
     */
    private fun isScaleTouch(m: java.lang.reflect.Method): Boolean {
        if (m.isSynthetic || m.name.startsWith("lambda")) return false
        return m.parameterTypes.any { it.name == "android.view.MotionEvent" }
    }

    /** 缩放链路任意方法被调用 → 标记该 task 正在被用户缩放 */
    private fun scaleMarkHook(cls: String, name: String): XC_MethodHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
            try {
                val task = param.args.firstOrNull { arg ->
                    arg != null && arg.javaClass.name == "com.android.server.wm.Task"
                } ?: param.thisObject?.let { self ->
                    fieldOf(self, "mTask")
                } ?: return
                scaledAt[task] = android.os.SystemClock.uptimeMillis()
                if (seen.merge("scale#$cls#$name", 1) { a, _ -> a + 1 } == 1) {
                    XLog.i("$TAG：缩放标记生效来源 $cls#$name")
                }
            } catch (t: Throwable) {
            }
        }
    }

    /** 兜底：当前可见面积小于该 task 见过的最大面积 97% → 视为用户缩小过 */
    private fun shrunkVsMax(task: Any, vis: Rect?): Boolean {
        if (vis == null || vis.isEmpty) return false
        val area = vis.width().toLong() * vis.height().toLong()
        val prev = maxArea[task]
        if (prev == null || area > prev) {
            maxArea[task] = area
            return false
        }
        return area < prev * 97L / 100L
    }

    /** 缩放手势后多久内视为「用户还要继续看」 */
    private const val SCALED_KEEP_MS = 30_000L

    private fun scaledRecently(task: Any): Boolean {
        val at = scaledAt[task] ?: return false
        return android.os.SystemClock.uptimeMillis() - at < SCALED_KEEP_MS
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
