package com.eta.sideindexx.overlay

import android.graphics.Rect
import android.view.InputEvent
import android.view.MotionEvent
import com.eta.sideindexx.util.XLog
import com.eta.sideindexx.util.XC_MethodHook
import com.eta.sideindexx.util.XposedBridge
import com.eta.sideindexx.util.XposedHelpers

/**
 * 感应带区域的触摸来源（1.31.0 起）+ 控制中心屏蔽（1.31.2 起）。
 *
 * 为什么不再用悬浮窗收触摸：感应带窗口一旦可触摸，它就是那块区域的**触摸目标** ——
 *   ①系统返回手势会把这次边缘滑动当成"非桌面窗口上的滑动"而冒返回箭头；
 *   ②窗口吞掉 DOWN 之后，桌面自己的翻页手势再也拿不到这根手指。
 * 试过的手势排除（`systemGestureExclusionRects`）在 ColorOS 上对 overlay 窗口**不生效**。
 *
 * 所以改成：感应带窗口**始终不可触摸**（触摸落给桌面/应用自己，桌面该翻页翻页、该返回返回），
 * 模块只**旁听** SystemUI 的返回手势监视器
 * （`com.android.systemui.shared.system.InputChannelCompat$InputEventReceiver$1`，监听器是 ColorOS 的
 * `DisplayBackGestureHandlerImpl$inputEventReceiver$1`，它能收到整块屏幕的触摸），
 * 把"落在感应带范围内"的那一根手势转给 OverlayController 做呼出判定。
 *
 * ⚠️ 旁听**绝不能吞事件**：在监视器/监听器这层吞掉（无论 finishInputEvent 传 true 还是 false）
 *    会让窗口彻底收不到这次触摸（1.30.27/30 实测：返回没了、索引也呼不出、桌面也不翻页）。
 *    回调还跑在 SystemUI 的 "GestureUI" 线程上，动视图必须回主线程（见 OverlayController 里的拷贝 + post）。
 *
 * 控制中心：桌面上"下滑打开控制中心"是桌面向 SystemUI 要面板，抢指针不一定拦得住，
 * 所以感应带内手势进行期间直接跳过 shade 的展开入口（用户要求：区内下滑不拉控制中心，区外照旧）。
 */
object BackGestureGate {

    /** 感应带在屏幕坐标系里的范围（左右各一条，由 OverlayController 更新） */
    @Volatile private var bandRight: Rect? = null
    @Volatile private var bandLeft: Rect? = null

    /** 底部导航条（小白条）感应区 */
    @Volatile private var bandBottom: Rect? = null

    /** 是否要把感应带内的手势转给模块（= 模块要不要接管这次呼出判定） */
    @Volatile private var active = false

    /** 触摸回调：(事件, 是否右侧) -> Unit。由 OverlayController 装 */
    @Volatile var onTouch: ((MotionEvent, Int) -> Unit)? = null

    private var installed = false
    private val seenReceivers = HashSet<String>()
    private val hookedListeners = HashSet<String>()

    /** 本次手势是否从感应带内开始（开始后整根跟着走，手指滑出带子也算） */
    private var trackingZone = 0

    /** 感应带内是否有手势正在进行（屏蔽控制中心的判据） */
    @Volatile private var bandGestureActive = false

    /** 上一次处理过的事件指纹：SystemUI 里有两个手势监视器，同一个事件会被各送一遍 */
    private var lastEventKey = ""

    /** 抢指针用的监视器（从返回手势处理器里反射拿） */
    @Volatile private var inputMonitor: Any? = null
    private var monitorSearched = false

    fun install(cl: ClassLoader) {
        if (installed) return
        installed = true
        hookReceivers(cl)
        hookShadeExpansion(cl)
    }

    fun update(right: Rect?, left: Rect?, bottom: Rect?, on: Boolean) {
        bandRight = right
        bandLeft = left
        bandBottom = bottom
        active = on
        if (!on) {
            trackingZone = 0
            bandGestureActive = false
        }
    }

    // ---------------- 抢指针 ----------------

    /** 呼出成立时抢走指针（桌面/应用的同期手势收到 cancel） */
    fun pilfer() {
        for (m in monitorCandidates) pilferOn(m, 0)
    }

    private val monitorCandidates = ArrayList<Any>()

    /**
     * ColorOS 里 `DisplayBackGestureHandlerImpl.inputMonitorCompat` 拿到的不是
     * `android.view.InputMonitor` 本体（1.31.1 实测：直接调 pilferPointers 抛 NoSuchMethodError），
     * 它是个只有 dispose/getInputReceiver/getSurface 的包装。所以把"类型名带 InputMonitor"的对象
     * 全收集起来（含包装里挖出来的），逐个试常见名字；全失败就把候选对象的方法名打进日志。
     */
    private fun pilferOn(m: Any, depth: Int) {
        for (n in listOf("pilferPointers", "pilfer")) {
            try {
                XposedHelpers.callMethod(m, n)
                XLog.d("呼出抢指针：${m.javaClass.simpleName}.$n() 成功")
                return
            } catch (t: Throwable) {
            }
        }
        if (depth < 2) {
            for (g in listOf("getInputMonitor", "getRealInputMonitor", "getMonitor")) {
                try {
                    val inner = XposedHelpers.callMethod(m, g) ?: continue
                    if (inner !== m) {
                        pilferOn(inner, depth + 1)
                        return
                    }
                } catch (t: Throwable) {
                }
            }
        }
        XLog.d(
            "呼出抢指针失败：${m.javaClass.name} 方法=" +
                m.javaClass.methods.joinToString(",") { it.name }
        )
    }

    // ---------------- 控制中心屏蔽 ----------------

    private fun hookShadeExpansion(cl: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.CommandQueue", cl)
        if (cls == null) {
            XLog.d("控制中心屏蔽：进程里没有 CommandQueue")
            return
        }
        var n = 0
        var expand = 0
        for (m in cls.declaredMethods) {
            val rt = m.returnType
            val canSkip = rt == java.lang.Void.TYPE || rt == java.lang.Boolean.TYPE
            val isExpand = m.name.contains("xpand")
            if (!isExpand && !canSkip) continue
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!bandGestureActive) return
                        // 诊断：感应带内手势期间，CommandQueue 上到底被调了哪些方法（找控制中心那条路）
                        XLog.d("控制中心屏蔽：手势期间 CommandQueue.${m.name} 被调用")
                        if (isExpand && canSkip) {
                            XLog.d("控制中心屏蔽：感应带内手势期间跳过 ${m.name}")
                            param.result = if (rt == java.lang.Boolean.TYPE) false else null
                        }
                    }
                })
                n++
                if (isExpand) expand++
            } catch (t: Throwable) {
                XLog.d("控制中心屏蔽：钩 ${m.name} 失败 ${t.javaClass.simpleName}")
            }
        }
        XLog.i("控制中心屏蔽：CommandQueue 钩住 $n 个入口（其中展开 $expand 个）")
    }

    // ---------------- 触摸旁听 ----------------

    private fun hookReceivers(cl: ClassLoader) {
        val base = XposedHelpers.findClassIfExists("android.view.InputEventReceiver", cl)
        if (base == null) {
            XLog.d("触摸旁听：进程里没有 InputEventReceiver")
            return
        }
        try {
            XposedBridge.hookAllConstructors(base, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        onReceiverCreated(param.thisObject ?: return)
                    } catch (t: Throwable) {
                        XLog.d("触摸旁听：处理接收器失败 ${t.javaClass.simpleName}")
                    }
                }
            })
            XLog.i("触摸旁听：已开始监听输入接收器")
        } catch (t: Throwable) {
            XLog.d("触摸旁听：钩 InputEventReceiver 构造失败 ${t.javaClass.simpleName}")
        }
    }

    private fun onReceiverCreated(obj: Any) {
        val cn = obj.javaClass.name
        if (seenReceivers.add(cn)) XLog.d("触摸旁听：输入接收器 $cn")
        if (!cn.contains("InputChannelCompat")) return
        findListener(obj)?.let { hookListener(it, cn) }
    }

    private fun findListener(obj: Any): Any? {
        val label = obj.javaClass.name
        try {
            var cls: Class<*>? = obj.javaClass
            var depth = 0
            while (cls != null && depth < 4) {
                for (f in cls.declaredFields) {
                    if (!f.name.contains("istener", ignoreCase = true)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(obj) ?: continue
                        val vc = v.javaClass.name
                        if (vc.startsWith("com.android") || vc.startsWith("com.oplus")) {
                            XLog.d("触摸旁听：$label 的监听器 = $vc")
                            return v
                        }
                    } catch (t: Throwable) {
                    }
                }
                cls = cls.superclass
                depth++
            }
        } catch (t: Throwable) {
            XLog.d("触摸旁听：找监听器失败 ${t.javaClass.simpleName}")
        }
        return null
    }

    private fun hookListener(listener: Any, owner: String) {
        val lc = listener.javaClass.name
        if (!hookedListeners.add(lc)) return
        var n = 0
        for (m in listener.javaClass.declaredMethods) {
            val first = m.parameterTypes.firstOrNull() ?: continue
            val paramOk = first == MotionEvent::class.java || first == InputEvent::class.java ||
                (first == java.lang.Object::class.java && m.name == "invoke")
            if (!paramOk) continue
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val e = param.args.firstOrNull() as? MotionEvent ?: return
                        // 同一个事件会被两个监视器各送一遍（实测一次手势打出两组"手势成立"）：
                        // 按 downTime+action+坐标 去重，只处理第一份
                        val key = e.downTime.toString() + ":" + e.actionMasked + ":" +
                            e.x.toInt() + ":" + e.y.toInt()
                        if (key == lastEventKey) return
                        lastEventKey = key
                        // 只旁听、不改写返回值 —— 吞事件会让窗口彻底收不到这根手指
                        if (!active) {
                            trackingZone = 0
                            bandGestureActive = false
                            return
                        }
                        when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                trackingZone = zoneOf(e.x, e.y)
                                bandGestureActive = trackingZone != 0
                                XLog.i(
                                    "触摸旁听：DOWN x=${e.x.toInt()} y=${e.y.toInt()} zone=$trackingZone"
                                )
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                val side = trackingZone
                                trackingZone = 0
                                bandGestureActive = false
                                if (side != 0) onTouch?.invoke(e, side)
                                return
                            }
                        }
                        if (trackingZone != 0) onTouch?.invoke(e, trackingZone)
                    }
                })
                n++
            } catch (t: Throwable) {
                XLog.d("触摸旁听：钩 ${m.name} 失败 ${t.javaClass.simpleName}")
            }
        }
        if (n > 0) XLog.i("触摸旁听：监听器 $lc（来自 $owner）钩住 $n 个入口")
        if (lc.contains("BackGesture")) findInputMonitor(listener)
    }

    /**
     * 从返回手势处理器里找它的 InputMonitor（抢指针要用）。
     * 监听器往往是 `Xxx$inputEventReceiver$1` 这种匿名类，monitor 在外层对象里，
     * 所以走广度优先：自己的字段 → 类型像 SystemUI/Oplus 的字段值（比如 this$0）继续挖。
     */
    private fun findInputMonitor(listener: Any) {
        if (monitorSearched) return
        monitorSearched = true
        try {
            val queue = ArrayDeque<Any>()
            queue.add(listener)
            var visited = 0
            while (queue.isNotEmpty() && visited < 12) {
                val obj = queue.removeFirst()
                visited++
                var cls: Class<*>? = obj.javaClass
                var depth = 0
                while (cls != null && depth < 6 && cls != Any::class.java) {
                    for (f in cls.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        val type = f.type.name
                        try {
                            f.isAccessible = true
                            if (type.contains("InputMonitor")) {
                                val v = f.get(obj) ?: continue
                                if (!monitorCandidates.contains(v)) monitorCandidates.add(v)
                                XLog.d(
                                    "触摸旁听：候选监视器 ${cls.simpleName}.${f.name} → ${v.javaClass.simpleName} " +
                                        "方法=" + v.javaClass.methods.joinToString(",") { it.name }
                                )
                                // 包装对象里可能藏着真身，继续往下挖
                                queue.add(v)
                                continue
                            }
                            if (type.startsWith("com.android.systemui") || type.startsWith("com.oplus")) {
                                f.get(obj)?.let { queue.add(it) }
                            }
                        } catch (t: Throwable) {
                        }
                    }
                    cls = cls.superclass
                    depth++
                }
            }
            XLog.d("触摸旁听：没找到 InputMonitor（扫了 $visited 个对象）")
        } catch (t: Throwable) {
            XLog.d("触摸旁听：找 InputMonitor 失败 ${t.javaClass.simpleName}")
        }
    }

    /** ZONE_*：底部条优先（它与左右带不重叠，但底部判定要更稳） */
    private fun zoneOf(x: Float, y: Float): Int {
        val xi = x.toInt()
        val yi = y.toInt()
        bandBottom?.let { if (it.contains(xi, yi)) return ZONE_BOTTOM }
        bandRight?.let { if (it.contains(xi, yi)) return ZONE_RIGHT }
        bandLeft?.let { if (it.contains(xi, yi)) return ZONE_LEFT }
        return ZONE_NONE
    }

    const val ZONE_NONE = 0
    const val ZONE_RIGHT = 1
    const val ZONE_LEFT = 2
    const val ZONE_BOTTOM = 3
}
