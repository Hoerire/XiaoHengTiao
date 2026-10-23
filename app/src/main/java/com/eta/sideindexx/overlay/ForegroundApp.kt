package com.eta.sideindexx.overlay

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.eta.sideindexx.util.XLog
import com.eta.sideindexx.util.XposedHelpers

/**
 * 前台应用判定（只用来判断"是不是在桌面"）。
 *
 * 主判据是 **ActivityTaskManager 的当前焦点任务** —— 系统里最直接、最实时的"谁在前面"，
 * 用 XposedHelpers 调用可以绕过隐藏 API 限制。
 * 拿不到时才退回 UsageStats 的 MOVE_TO_FOREGROUND 事件流（它有延迟、也有盲区）。
 *
 * 走过的弯路（别回头）：
 *   - `ActivityManager.getRunningAppProcesses()` 的 importance 在 ColorOS 上完全不可信：
 *     它会把几十个常驻系统进程（com.oplus.* / com.heytap.* / com.qti.* / system …）都标成
 *     "前台"，桌面和后台应用根本分不出来。
 *   - 普通反射读 android.app.ActivityTaskManager 会被隐藏 API 限制拦掉（日志里 atm 一直是空）。
 */
object ForegroundApp {

    private const val REFRESH_INTERVAL_MS = 3000L

    @Volatile private var cached = ""
    @Volatile private var lastRefreshAt = 0L
    @Volatile private var refreshing = false

    /** UsageStats 事件里最近一次切到前台的包名（sticky） */
    @Volatile private var usageCurrent = ""
    private var lastEventAt = 0L

    @Volatile private var launcherPkg: String? = null

    /** 上一次打过的判定结论：心跳每 2.5 秒问一次，结论没变就不重复打日志 */
    @Volatile private var lastVerdict = ""

    /** 取数失败原因只报一次，避免每次手势都刷屏 */
    @Volatile private var failureLogged = false

    private fun logVerdict(verdict: String) {
        if (verdict == lastVerdict) return
        lastVerdict = verdict
        XLog.d("前台判定：$verdict")
    }

    fun current(ctx: Context): String = cached

    /** 心跳调用：后台刷新（失败保留旧值） */
    fun refresh(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastRefreshAt < REFRESH_INTERVAL_MS) return
        lastRefreshAt = now
        if (refreshing) return
        refreshing = true
        Thread {
            try {
                val detected = detect(ctx)
                if (detected.isNotEmpty()) cached = detected
            } catch (t: Throwable) {
                XLog.d("刷新前台应用失败：${t.javaClass.simpleName}")
            } finally {
                refreshing = false
            }
        }.start()
    }

    /** 手势路径上同步刷一次（一次 ATMS 调用 + 一次事件查询，几毫秒） */
    fun refreshNow(ctx: Context) {
        try {
            val top = atmTop(ctx)
            if (top.isNotEmpty()) {
                cached = top
                return
            }
            detect(ctx)
        } catch (t: Throwable) {
            XLog.d("同步刷新前台失败：${t.javaClass.simpleName}")
        }
    }

    /** 桌面包名（默认 Launcher） */
    fun launcherPackage(ctx: Context): String {
        launcherPkg?.let { return it }
        val resolved = try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        } catch (t: Throwable) {
            null
        }.orEmpty()
        if (resolved.isNotEmpty()) launcherPkg = resolved
        return resolved
    }

    /**
     * 前台是不是桌面。
     * 以 ActivityTaskManager 的顶层包名为准；只有拿不到时才退回事件流。
     */
    fun isLauncher(ctx: Context): Boolean {
        val launcher = launcherPackage(ctx)
        if (launcher.isEmpty()) return true
        val top = atmTop(ctx)
        if (top.isNotEmpty()) {
            val here = top == launcher || top.startsWith("$launcher:")
            logVerdict(if (here) "顶层=$top → 桌面" else "顶层=$top → 不在桌面")
            // ⚠️ 这里**刻意不做**"顶层不是桌面、但桌面还在下面（小窗/分屏）"的兜底。
            // 旧实现靠 `desktopStillThere()`：只要 launcher 的 root task 存在且 `visible == true`
            // 就按桌面处理。但 ColorOS 16 上桌面那个 task 的 `visible` **长期是 true**
            // （即使被全屏应用完全盖住），于是**任何场景都被判成"桌面在场"** ——
            // 表现就是"没开全局索引、别的应用里也能呼出"。
            // 宁可牺牲"桌面+小窗"时要先点一下桌面的便利，也不能让判定恒真。
            return here
        }
        refreshNow(ctx)
        val byUsage = usageCurrent
        if (byUsage.isEmpty()) return true // 两个信号都拿不到：宽松放行，否则功能直接不可用
        logVerdict("兜底事件流：使用中=$byUsage")
        return byUsage == launcher
    }

    /**
     * 廉价版前台判定：只问「顶层任务是不是桌面」——一次 ATMS 调用，不打日志。
     *
     * 给「穿透看护」（OverlayController 里 5Hz 轮询的那种）用：
     * [isLauncher] 会顺手拼诊断字符串、走完整判定链，那套开销不能按 5Hz 来跑。
     */
    fun topIsLauncher(ctx: Context): Boolean {
        val launcher = launcherPackage(ctx)
        if (launcher.isEmpty()) return true
        val top = atmTop(ctx)
        if (top.isEmpty()) return false
        return top == launcher || top.startsWith("$launcher:")
    }

    /** 当前用于判断的值（日志用） */
    fun debugValue(ctx: Context): String {
        val u = usageCurrent.ifEmpty { "-" }
        val a = atmTop(ctx).ifEmpty { "-" }
        return "usage=$u atm=$a"
    }

    // ---------------- 实现 ----------------

    /** UsageStats 增量事件：找出这段时间内最后一次切到前台的包名（只作兜底） */
    private fun detect(ctx: Context): String {
        try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm != null) {
                val now = System.currentTimeMillis()
                val from = if (lastEventAt > 0) lastEventAt - 1000 else now - 60_000
                val events = usm.queryEvents(from, now)
                if (events != null) {
                    val ev = UsageEvents.Event()
                    var pkg: String? = null
                    var ts = 0L
                    while (events.hasNextEvent()) {
                        events.getNextEvent(ev)
                        if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                            val p = ev.packageName
                            if (!p.isNullOrEmpty() && ev.timeStamp >= ts) {
                                ts = ev.timeStamp
                                pkg = p
                            }
                        }
                    }
                    lastEventAt = now
                    if (pkg != null) usageCurrent = pkg
                }
            }
        } catch (t: Throwable) {
            XLog.d("UsageStats 前台事件失败：${t.javaClass.simpleName}")
        }
        return usageCurrent
    }

    /**
     * 顶层应用包名：ActivityTaskManager 的当前焦点任务。
     * 隐藏 API 必须走 XposedHelpers，普通反射会被隐藏 API 限制拦掉。
     */
    /**
     * 顶层应用包名。取数顺序（前面拿不到就一直往后退）：
     *   ① [atmsTop]：`ActivityTaskManager`（框架隐藏类，最准）；
     *   ② [systemUiTop]：SystemUI 自己的 `ActivityManagerWrapper`；
     *   ③ [amTop]：`ActivityManager.getRunningTasks`（公开 API，虽已废弃）；
     *   ④ 事件流（见 [detect]）。
     *
     * 为什么要这么多条：① 在 LibXposed 下可能被 hidden API 拦掉，而它一空，
     * 上层 `current()` 就是空，`allowedHere()` 会误判成"判不出来"从而到处放行 ——
     * 表现就是"没开全局索引、别的应用里也能呼出"。②③ 分别是 SystemUI 自己的类和公开 API，
     * 都不经过 hidden API 检查。任何一条通了，判定就是准的。
     */
    private fun atmTop(ctx: Context): String {
        val viaAtms = atmsTop(ctx)
        if (viaAtms.isNotEmpty()) return viaAtms
        val viaSysUi = systemUiTop(ctx)
        if (viaSysUi.isNotEmpty()) return viaSysUi
        return amTop(ctx)
    }

    /**
     * 公开 API 兜底：`ActivityManager.getRunningTasks(1)`。
     * 它已废弃，但 SystemUI 持有 REACTIVITY 权限、能拿到全部任务；而且它是公开接口，
     * 完全不涉及 hidden API 检查 —— 前两条都被拦时靠它。
     */
    private fun amTop(ctx: Context): String {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                ?: run {
                    logFailureOnce("取不到 ActivityManager")
                    return ""
                }
            @Suppress("DEPRECATION")
            val tasks = am.getRunningTasks(1)
            if (tasks.isNullOrEmpty()) {
                logFailureOnce("getRunningTasks 返回空（可能缺 REACTIVITY 权限）")
                return ""
            }
            tasks[0]?.topActivity?.packageName ?: run {
                logFailureOnce("getRunningTasks[0].topActivity 为空")
                ""
            }
        } catch (t: Throwable) {
            logFailureOnce("getRunningTasks 失败：${t.javaClass.simpleName}")
            ""
        }
    }

    private fun atmsTop(ctx: Context): String {
        return try {
            val cl = XposedHelpers.findClassIfExists("android.app.ActivityTaskManager", ctx.classLoader)
                ?: run {
                    logFailureOnce("找不到 ActivityTaskManager（可能被 hidden API 拦）")
                    return ""
                }
            val service = XposedHelpers.callStaticMethod(cl, "getService")
                ?: run {
                    logFailureOnce("ActivityTaskManager.getService 返回空")
                    return ""
                }
            val focused = try {
                XposedHelpers.callMethod(service, "getFocusedRootTaskInfo")
            } catch (t: Throwable) {
                null
            }
            readTopActivityPackage(focused)?.let { if (it.isNotEmpty()) return it }
            val tasks = try {
                XposedHelpers.callMethod(service, "getTasks", 1) as? List<*>
            } catch (t: Throwable) {
                null
            }
            readTopActivityPackage(tasks?.firstOrNull()) ?: ""
        } catch (t: Throwable) {
            logFailureOnce("ActivityTaskManager 取顶层失败：${t.javaClass.simpleName} ${t.message}")
            ""
        }
    }

    /**
     * SystemUI 自带的运行任务查询：`ActivityManagerWrapper.getRunningTask()`。
     * 它和模块跑在同一个进程，又是 SystemUI 自己的类（不是 framework 的 @hide 成员），
     * 反射它不触发 hidden API 检查 —— 专门兜住 [atmsTop] 被拦的情况。
     * 类名/方法名随系统版本会变，拿不到就返回空，继续退到事件流。
     */
    private fun systemUiTop(ctx: Context): String {
        return try {
            val cl = XposedHelpers.findClassIfExists(
                "com.android.systemui.shared.system.ActivityManagerWrapper", ctx.classLoader
            ) ?: run {
                logFailureOnce("找不到 ActivityManagerWrapper，退回事件流")
                return ""
            }
            val instance = XposedHelpers.callStaticMethod(cl, "getInstance")
                ?: run {
                    logFailureOnce("ActivityManagerWrapper.getInstance 返回空")
                    return ""
                }
            val task = XposedHelpers.callMethod(instance, "getRunningTask")
                ?: run {
                    logFailureOnce("ActivityManagerWrapper.getRunningTask 返回空")
                    return ""
                }
            readTopActivityPackage(task) ?: ""
        } catch (t: Throwable) {
            logFailureOnce("SystemUI 前台查询失败：${t.javaClass.simpleName}")
            ""
        }
    }

    /** 这条链路每次手势都会走一遍，失败原因只报一次，别把 logcat 刷爆 */
    private fun logFailureOnce(msg: String) {
        if (failureLogged) return
        failureLogged = true
        XLog.i("前台判定取数失败（后续静默）：$msg")
    }

    private fun readTopActivityPackage(taskInfo: Any?): String? {
        if (taskInfo == null) return null
        val top = try {
            XposedHelpers.getObjectField(taskInfo, "topActivity")
        } catch (t: Throwable) {
            null
        } ?: try {
            XposedHelpers.getObjectField(taskInfo, "baseActivity")
        } catch (t: Throwable) {
            null
        }
        return when (top) {
            null -> null
            is ComponentName -> top.packageName
            else -> try {
                XposedHelpers.getObjectField(top, "packageName") as? String
            } catch (t: Throwable) {
                null
            }
        }
    }
}
