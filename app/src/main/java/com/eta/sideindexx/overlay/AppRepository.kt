package com.eta.sideindexx.overlay

import android.app.ActivityOptions
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.LruCache
import com.eta.sideindexx.Const
import com.eta.sideindexx.index.Pinyin
import com.eta.sideindexx.util.XLog
import com.eta.sideindexx.util.XposedHelpers
import android.graphics.Bitmap
import android.graphics.Canvas
import com.eta.sideindexx.config.ModuleConfig
import com.eta.sideindexx.util.Display
import java.text.Collator

data class LaunchableApp(
    val packageName: String,
    val activityName: String,
    val label: String,
    val initial: String,
    val component: ComponentName
)

/**
 * 可启动应用清单 + 图标缓存。
 * 图标缓存是**进程级静态**的：浮层每次手势重建，缓存必须跨手势复用，
 * 否则每次都要重新加载 100+ 个图标，就是"不跟手"的主因。
 */
object AppRepository {

    private const val CACHE_TTL_MS = 120_000L

    /**
     * 重启后 SystemUI 常常比 PackageManager 先就绪（direct boot / 用户存储还没解锁），
     * 这时查询 CATEGORY_LAUNCHER 只能拿到寥寥几个应用 —— 曾出现"字母条只剩 Y 和 S"。
     * 清单数量明显偏少时改用短 TTL 反复重查，等系统扫完自动补全，不用手动重启系统界面。
     */
    private const val EARLY_TTL_MS = 2_500L
    private const val MIN_EXPECTED_APPS = 15

    @Volatile private var cached: List<LaunchableApp> = emptyList()
    /** 不含 ★ 的原始清单：重排收藏栏时拿它当底座 */
    @Volatile private var baseList: List<LaunchableApp> = emptyList()
    @Volatile private var loadedAt = 0L

    private val iconCache = object : LruCache<String, Drawable?>(512) {}

    /** 图标后台加载：主线程只贴缓存，未命中丢单线程队列，避免滚动时同步 PackageManager */
    private val iconExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "EtaIconLoader").apply { isDaemon = true }
    }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pendingIcons = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 与 [icon] 内部 key 完全一致，否则缓存永远 miss */
    private fun iconKey(@Suppress("UNUSED_PARAMETER") ctx: Context, app: LaunchableApp): String =
        app.component.flattenToString()

    /** 仅查缓存，绝不触发加载 */
    fun iconCached(ctx: Context, app: LaunchableApp): Drawable? =
        try { iconCache.get(iconKey(ctx, app)) } catch (t: Throwable) { null }

    /** 预热全部图标到常驻缓存（后台单线程，幂等） */
    @Volatile private var preloadSign = ""

    fun preloadIcons(ctx: Context, apps: List<LaunchableApp>) {
        val sign = "${apps.size}:${apps.firstOrNull()?.packageName}:${apps.lastOrNull()?.packageName}"
        if (sign == preloadSign) return
        preloadSign = sign
        iconExecutor.execute {
            for (a in apps) {
                try {
                    if (iconCache.get(iconKey(ctx, a)) == null) icon(ctx, a)
                } catch (t: Throwable) {
                }
            }
        }
    }

    /** 同步取图标（列表项少时用；命中缓存则零成本） */
    fun iconSync(ctx: Context, app: LaunchableApp): Drawable? =
        try { icon(ctx, app) } catch (t: Throwable) { null }

    /** 异步取图标；回调一定在主线程 */
    fun iconAsync(ctx: Context, app: LaunchableApp, cb: (Drawable?) -> Unit) {
        val key = iconKey(ctx, app)
        iconCache.get(key)?.let { cb(it); return }
        if (!pendingIcons.add(key)) return
        iconExecutor.execute {
            var d: Drawable? = null
            try { d = icon(ctx, app) } catch (t: Throwable) { }
            val result = d
            pendingIcons.remove(key)
            mainHandler.post { cb(result) }
        }
    }

    @Volatile private var refreshing = false

    fun apps(ctx: Context, force: Boolean = false): List<LaunchableApp> {
        val now = System.currentTimeMillis()
        val ttl = if (isSettled()) CACHE_TTL_MS else EARLY_TTL_MS
        if (!force && cached.isNotEmpty() && now - loadedAt < ttl) return cached
        if (!force && cached.isNotEmpty()) {
            // 缓存过期：手势路径上千万别同步查 PackageManager（100+ 个应用的 label 要几百毫秒，
            // 就是"呼出不跟手"的元凶）——先返回旧清单，后台刷新，下一次手势用新的
            refreshAsync(ctx)
            return cached
        }
        val list = load(ctx)
        if (list.isNotEmpty()) {
            val wasSettled = isSettled()
            installList(ctx, list)
            loadedAt = now
            if (!wasSettled) {
                val note = if (list.size < MIN_EXPECTED_APPS) "（系统可能还没扫完，稍后自动重查）" else ""
                XLog.d("应用清单就绪：${list.size} 个应用$note")
            }
        }
        return cached
    }

    /** 后台刷新应用清单（结果只覆盖缓存，不阻塞调用方） */
    private fun refreshAsync(ctx: Context) {
        if (refreshing) return
        refreshing = true
        Thread {
            try {
                val list = load(ctx)
                if (list.isNotEmpty()) {
                    val oldCount = cached.size
                    installList(ctx, list)
                    loadedAt = System.currentTimeMillis()
                    if (list.size != oldCount) {
                        XLog.d("应用清单后台刷新：${list.size} 个应用")
                    }
                }
            } catch (t: Throwable) {
                XLog.w("后台刷新应用清单失败", t)
            } finally {
                refreshing = false
            }
        }.start()
    }

    /** 清单是否已经"像样"：数量太少说明 PackageManager 还没扫完，先别长缓存 */
    fun isSettled(): Boolean = cached.size >= MIN_EXPECTED_APPS

    /** 「常用」：来自 favorites 的手动固定（顺序即显示顺序） */
    private fun favoritesFrom(list: List<LaunchableApp>): List<LaunchableApp> {
        if (favoritePackages.isEmpty()) return emptyList()
        val byPkg = HashMap<String, LaunchableApp>()
        for (a in list) if (!byPkg.containsKey(a.packageName)) byPkg[a.packageName] = a
        return favoritePackages.mapNotNull { byPkg[it] }
    }

    /** 安装清单：只剩"最近/常用"两条路径，不再构造字母分组（侧边栏已移除） */
    private fun installList(ctx: Context, list: List<LaunchableApp>) {
        baseList = list
        cached = list
    }


    /** 「常用」固定的包名（顺序即显示顺序），由宿主从配置同步过来 */
    @Volatile var favoritePackages: List<String> = emptyList()



    /**
     * 「常用」栏：设置页固定下来的应用，[favoritePackages] 的顺序即显示顺序。
     * 已卸载、或不是可启动应用的包名直接跳过。
     */

    /**
     * ★ 栏：**最近打开的应用**。取数与排序对齐 ColorOS 智能侧边栏的「最近和推荐应用」
     * （`com.oplus.smartsidebar…recentapp.RecentAppSource.loadRecentFromUsageStats`）：
     * `queryAndAggregateUsageStats(now-24h, now)` → 按 `getLastTimeVisible()` 降序。
     *
     * 旧实现读 `UsageStats.getAppLaunchCount()`（SystemApi，只能反射）按**累计打开次数**排，
     * 在 Android 16 上反射多半直接失败、退回"累计前台时长"当常用度 —— 两者都是长期累计量，
     * 排序几乎不动，表现就是"★ 栏列表不更新"。24 小时窗口的最后可见时间会跟着实际使用走。
     */

    /** 日志用：把"最后可见时间"显示成"多久以前" */
    private fun formatAgo(t: Long): String {
        if (t <= 0L) return "?"
        val d = System.currentTimeMillis() - t
        return when {
            d < 60_000L -> "${d / 1000}s前"
            d < 3600_000L -> "${d / 60_000L}min前"
            d < 86_400_000L -> "${d / 3600_000L}h前"
            else -> "${d / 86_400_000L}d前"
        }
    }

    @Volatile private var recentCache: Map<String, Long> = emptyMap()
    private var recentAt = 0L

    /**
     * 本进程内"刚通过索引栏打开"的时间戳，用来补任务栈更新的时间差；SystemUI 重启即清空。
     * 用 ConcurrentHashMap：写发生在手势线程（recordLaunch），
     * 读可能发生在后台刷新线程（refreshAsync → installList → starsFrom），普通 HashMap 会踩并发。
     */
    private val localLaunchAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** UsageStats 底座层取数窗口：24 小时（对齐 RecentAppSource.RECENT_APP_SINCE_TIME） */
    private const val RECENT_WINDOW_MS = 24L * 3600_000L

    /** 未来时间偏移容差（对齐 RecentAppSource.LAST_VISIBLE_FUTURE_SKEW_MS） */
    private const val FUTURE_SKEW_MS = 5_000L

    /** UsageStats 底座层缓存：新鲜度由任务栈那层负责，这层放长一点省 IPC */
    private const val RECENT_TTL_MS = 10_000L

    /** 向 ActivityTaskManager 要多少个最近任务（够 ★ 栏挑就行） */
    private const val RECENT_TASK_LIMIT = 24

    /**
     * ★ 栏的取数顺序（包名 → 排序时间戳，值越大越"新"）。两层信号叠加：
     *
     * ① **ActivityTaskManager 的最近任务列表（实时）** —— 就是 [ForegroundApp] 判前台用的同一个
     *    系统服务。SystemUI 是 system uid，`getTasks(n)` 拿得到，返回顺序即系统认定的
     *    "最近打开顺序"，没有额外滞后。★ 能不能跟着手感走，全靠这一层。
     *
     * ② **UsageStats 近 24 小时的最后可见时间（照搬 ColorOS 智能侧边栏）** ——
     *    `queryAndAggregateUsageStats` + `getLastTimeVisible()`，用来覆盖"已被划掉后台、
     *    不再在任务栈里"的应用。它有自己的刷写节奏、会滞后（本仓库 ForegroundApp 里也记过
     *    "事件流有延迟、也有盲区"），所以只当底座，不承担新鲜度。
     *
     * ①的时间戳按栈序取 `now - i`，恒大于②里任何历史时间 —— 还在任务栈里的应用一律排在
     * "只有 UsageStats 记录"的应用之前，顺序即栈序。
     */
    private fun recentVisible(ctx: Context): Map<String, Long> {
        val now = System.currentTimeMillis()
        val usage = if (now - recentAt < RECENT_TTL_MS) recentCache else refreshUsageStats(ctx, now)
        val out = HashMap<String, Long>(usage)
        // 实时层：第 0 个就是刚刚用过的那个
        val tasks = atmRecentPackages(ctx, RECENT_TASK_LIMIT)
        for ((i, pkg) in tasks.withIndex()) out[pkg] = now - i
        if (tasks.isNotEmpty()) {
            XLog.d("★ 任务栈（新→旧）：" + tasks.joinToString(", ") { it.substringAfterLast('.') })
        }
        // 本进程刚通过索引栏打开过的：任务栈还没更新时补一手
        for ((pkg, t) in localLaunchAt) if (t > (out[pkg] ?: 0L)) out[pkg] = t
        return out
    }

    /**
     * ActivityTaskManager 的最近任务（实时）。
     * 隐藏 API 必须走 XposedHelpers —— 普通反射会被隐藏 API 限制拦掉（同 [ForegroundApp]）。
     * 拿不到就返回空表，★ 栏自动退回纯 UsageStats 排序，不影响可用性。
     */
    private fun atmRecentPackages(ctx: Context, limit: Int): List<String> {
        return try {
            val cl = XposedHelpers.findClassIfExists("android.app.ActivityTaskManager", ctx.classLoader)
                ?: return emptyList()
            val service = XposedHelpers.callStaticMethod(cl, "getService") ?: return emptyList()
            val tasks = XposedHelpers.callMethod(service, "getTasks", limit) as? List<*> ?: return emptyList()
            val launcher = ForegroundApp.launcherPackage(ctx)
            val out = ArrayList<String>(tasks.size)
            for (t in tasks) {
                val pkg = taskTopPackage(t) ?: continue
                if (pkg.isEmpty() || pkg == Const.MODULE_PACKAGE) continue
                // 桌面/系统界面不该占 ★ 的位置（同智能侧边栏的 isLauncherDisable 过滤）
                if (launcher.isNotEmpty() && (pkg == launcher || pkg.startsWith("$launcher:"))) continue
                if (!out.contains(pkg)) out.add(pkg)
            }
            out
        } catch (t: Throwable) {
            XLog.d("读取最近任务失败：${t.javaClass.simpleName}")
            emptyList()
        }
    }

    /** 从一个 TaskInfo 取顶层包名：优先 topActivity；任务刚建时它为空，退回 baseIntent */
    private fun taskTopPackage(taskInfo: Any?): String? {
        if (taskInfo == null) return null
        val top = try {
            XposedHelpers.getObjectField(taskInfo, "topActivity")
        } catch (t: Throwable) {
            null
        }
        if (top is ComponentName) return top.packageName
        return try {
            val base = XposedHelpers.getObjectField(taskInfo, "baseIntent") as? Intent
            base?.component?.packageName
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 底座层：近 24 小时各应用**最后一次出现在前台的时间**（包名 → 毫秒时间戳）。
     * 照搬 ColorOS 智能侧边栏 `RecentAppSource`：窗口 24 小时、
     * `queryAndAggregateUsageStats` 聚合同一应用的多条记录、
     * 排序键取 `getLastTimeVisible()`（**不是** getLastTimeUsed），
     * 丢弃时间戳为 0 或超前当前时间 5 秒以上（时钟/系统写入抖动）的记录。
     */
    private fun refreshUsageStats(ctx: Context, now: Long): Map<String, Long> {
        val out = HashMap<String, Long>()
        try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm != null) {
                // 主路径：聚合查询 —— 与系统侧边栏同一个 API、同一套语义
                val agg = usm.queryAndAggregateUsageStats(now - RECENT_WINDOW_MS, now)
                if (agg != null) {
                    for ((pkg, s) in agg) {
                        val t = s.lastTimeVisible
                        if (t > 0L && t <= now + FUTURE_SKEW_MS) out[pkg] = maxOf(out[pkg] ?: 0L, t)
                    }
                }
                // 兜底：个别机型聚合结果为空，就按天桶自己取最大可见时间
                if (out.isEmpty()) {
                    val stats = usm.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY, now - RECENT_WINDOW_MS, now
                    )
                    if (stats != null) {
                        for (s in stats) {
                            val t = s.lastTimeVisible
                            if (t > 0L && t <= now + FUTURE_SKEW_MS) {
                                out[s.packageName] = maxOf(out[s.packageName] ?: 0L, t)
                            }
                        }
                    }
                    if (out.isNotEmpty()) XLog.d("最近应用：按天桶回退到 ${out.size} 个包")
                }
            }
        } catch (t: Throwable) {
            XLog.d("读取最近应用失败：${t.javaClass.simpleName}")
        }
        recentCache = out
        recentAt = now
        return out
    }

    private fun starCounts(ctx: Context): Map<String, Int> {
        val raw = try {
            android.provider.Settings.Secure.getString(ctx.contentResolver, STAR_KEY)
        } catch (t: Throwable) {
            null
        }.orEmpty()
        if (raw.isEmpty()) return emptyMap()
        val map = HashMap<String, Int>()
        raw.split(',').forEach { part ->
            val i = part.indexOf('=')
            if (i <= 0) return@forEach
            val pkg = part.substring(0, i).trim()
            val c = part.substring(i + 1).trim().toIntOrNull() ?: return@forEach
            if (pkg.contains('.')) map[pkg] = c
        }
        return map
    }

    /**
     * 通过索引栏打开一次：记下"刚刚用过"的时间戳并立刻重排 ★ 栏。
     *
     * 系统侧的 UsageStats 是异步聚合的，刚点开的应用不会马上出现在统计里，
     * 所以这里在本进程内补一个即时时间戳（[localLaunchAt]），排序时取两者较新者。
     *
     * Secure 里的 `pkg=count` 计数继续维护：它不是排序依据了，但设置页"恢复默认"会清它，
     * 且留着才能让改造前后行为可对照（不影响架构）。
     */
    fun recordLaunch(ctx: Context, packageName: String) {
        try {
            localLaunchAt[packageName] = System.currentTimeMillis()
            // 让 recentVisible 的 5 秒缓存立即失效，否则刚打开的应用要等下一轮才排到最前
            recentAt = 0L
            val counts = starCounts(ctx).toMutableMap()
            counts[packageName] = (counts[packageName] ?: 0) + 1
            val payload = counts.entries
                .sortedByDescending { it.value }
                .take(20)
                .joinToString(",") { "${it.key}=${it.value}" }
            android.provider.Settings.Secure.putString(ctx.contentResolver, STAR_KEY, payload)
            XLog.d("★ 记录打开：$packageName（累计 ${counts[packageName]}）")
            // 立刻重排 ★ 栏：否则要等下一次重扫应用清单（最长 2 分钟）才看得到
            refreshStarsNow(ctx)
        } catch (t: Throwable) {
            XLog.d("★ 记录打开失败：${t.javaClass.simpleName}")
        }
    }

    /** 按当前 Secure 里的计数立刻重排 ★ 栏：只重算分组，不重新扫 PackageManager */
    fun refreshStarsNow(ctx: Context) {
        val base = baseList
        if (base.isEmpty()) return
        try {
            installList(ctx, base)
        } catch (t: Throwable) {
            XLog.d("★ 立即刷新失败：${t.javaClass.simpleName}")
        }
    }

    private const val STAR_KEY = "eta_sideindex_star"

    /**
     * 最近应用（新→旧）：ATMS 任务栈顺序优先，UsageStats 兜底。
     * 排除桌面自身与模块包名；拿不到清单时返回空表，调用方自行降级。
     */
    fun recents(ctx: Context, limit: Int = 8): List<LaunchableApp> {
        val list = apps(ctx)
        if (list.isEmpty()) return emptyList()
        val times = recentVisible(ctx)
        if (times.isEmpty()) return emptyList()
        val byPkg = HashMap<String, LaunchableApp>()
        for (a in list) if (!byPkg.containsKey(a.packageName)) byPkg[a.packageName] = a
        val launcher = ForegroundApp.launcherPackage(ctx)
        return times.entries
            .filter { (pkg, t) ->
                t > 0L && pkg != Const.MODULE_PACKAGE && pkg != launcher && byPkg.containsKey(pkg)
            }
            .sortedByDescending { it.value }
            .mapNotNull { byPkg[it.key] }
            .take(limit)
    }

    /** 按给定包名顺序映射成应用（用于"常用"分组，顺序即固定顺序） */
    fun byPackages(ctx: Context, packages: List<String>): List<LaunchableApp> {
        if (packages.isEmpty()) return emptyList()
        val list = apps(ctx)
        if (list.isEmpty()) return emptyList()
        val map = HashMap<String, LaunchableApp>()
        for (a in list) if (!map.containsKey(a.packageName)) map[a.packageName] = a
        val out = ArrayList<LaunchableApp>(packages.size)
        for (pkg in packages) map[pkg]?.let { out.add(it) }
        return out
    }


    /** 启动时/配置变化时预热，避免第一次手势卡顿；顺带后台预加载全部图标（否则首次滑到某组会现场加载图标） */
    fun prewarm(ctx: Context) {
        try { apps(ctx, force = true) } catch (t: Throwable) { XLog.w("预热应用清单失败", t) }
        Thread {
            try {
                val snapshot = cached
                val iconPx = Display.dp(ctx, ModuleConfig.FIXED_ICON_SIZE_DP)
                for (app in snapshot) { icon(ctx, app); iconBitmap(ctx, app, iconPx) }
                if (snapshot.isNotEmpty()) XLog.d("图标预热完成：${snapshot.size} 个")
            } catch (t: Throwable) {
                XLog.w("预热图标失败", t)
            }
        }.start()
    }

    fun invalidate() { loadedAt = 0L }

    /** 有应用装上/卸下时立刻重建清单并预热新图标（全在后台线程，不阻塞手势） */
    fun reloadAsync(ctx: Context) {
        if (refreshing) return
        refreshing = true
        Thread {
            try {
                val list = load(ctx)
                if (list.isNotEmpty()) {
                    installList(ctx, list)
                    loadedAt = System.currentTimeMillis()
                    XLog.d("应用变化后重建清单：${list.size} 个应用")
                    val iconPx = Display.dp(ctx, ModuleConfig.FIXED_ICON_SIZE_DP)
                    for (app in list) { icon(ctx, app); iconBitmap(ctx, app, iconPx) }
                }
            } catch (t: Throwable) {
                XLog.w("应用变化后重建清单失败", t)
            } finally {
                refreshing = false
            }
        }.start()
    }

    private fun launcherApps(ctx: Context): android.content.pm.LauncherApps? =
        try { ctx.getSystemService(android.content.pm.LauncherApps::class.java) } catch (t: Throwable) { null }

    private fun currentUser(): android.os.UserHandle =
        android.os.Process.myUserHandle()

    /** component(flatten) → LauncherActivityInfo：图标走 getBadgedIcon，系统侧有缓存 */
    @Volatile
    private var launcherInfos: Map<String, android.content.pm.LauncherActivityInfo> = emptyMap()
    private val launcherByPkg = HashMap<String, android.content.pm.LauncherActivityInfo>()

    private fun load(ctx: Context): List<LaunchableApp> {
        val pm = ctx.packageManager
        val result = ArrayList<LaunchableApp>()
        // 优先 LauncherApps（桌面/系统侧边栏同路径，系统服务侧有图标缓存，明显快于 PackageManager）
        try {
            val la = launcherApps(ctx)
            if (la != null) {
                val activities = la.getActivityList(null, currentUser())
                val index = HashMap<String, android.content.pm.LauncherActivityInfo>(activities.size)
                for (a in activities) a.componentName?.let { index[it.flattenToString()] = a }
                launcherInfos = index
                synchronized(launcherByPkg) {
                    launcherByPkg.clear()
                    for (a in activities) a.componentName?.let { launcherByPkg.putIfAbsent(it.packageName, a) }
                }
                for (info in activities) {
                    val cn = info.componentName ?: continue
                    val label = try { info.label?.toString() } catch (t: Throwable) { null }
                        ?: cn.packageName
                    result.add(
                        LaunchableApp(
                            packageName = cn.packageName,
                            activityName = cn.className,
                            label = label,
                            initial = Pinyin.initial(label),
                            component = cn
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            XLog.d("LauncherApps 取应用失败，回退 PackageManager：${t.javaClass.simpleName}")
        }
        if (result.isNotEmpty()) {
            sortAndReturn(result)
            return result
        }
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(intent, 0)
            val seen = HashSet<String>()
            for (info in resolved) {
                val ai = info.activityInfo ?: continue
                val key = "${ai.packageName}/${ai.name}"
                if (!seen.add(key)) continue
                val label = try { info.loadLabel(pm).toString() } catch (t: Throwable) { ai.packageName }
                result.add(
                    LaunchableApp(
                        packageName = ai.packageName,
                        activityName = ai.name,
                        label = label,
                        initial = Pinyin.initial(label),
                        component = ComponentName(ai.packageName, ai.name)
                    )
                )
            }
            sortAndReturn(result)
        } catch (t: Throwable) {
            XLog.w("读取可启动应用失败", t)
        }
        return result
    }

    private fun sortAndReturn(result: ArrayList<LaunchableApp>) {
        try {
            val collator = Collator.getInstance()
            // "#"（数字/符号开头）排在**字母之后**：用户要求它紧邻底部的「常用」「最近」，
            // 而不是像原来那样压在最顶上（要够到得先滑过整个字母表）
            result.sortWith(
                compareBy<LaunchableApp> { it.initial == "#" }
                    .thenBy { it.initial }
                    .thenBy(collator) { it.label }
            )
        } catch (t: Throwable) {
        }
    }

    private val iconBitmapCache = object : LruCache<String, Bitmap>(48) {}

    /**
     * 预渲染成小位图再交给 ImageView。
     * 图标大多是 AdaptiveIconDrawable：每次绘制都要按当前尺寸重新分层合成，
     * 换字母时十几个图标一起重绘就明显吃性能；贴位图只是拷贝像素。
     */
    fun iconBitmap(ctx: Context, app: LaunchableApp, sizePx: Int): Bitmap? {
        val key = "${app.component.flattenToString()}@$sizePx"
        iconBitmapCache.get(key)?.let { return it }
        val drawable = icon(ctx, app) ?: return null
        return try {
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(Canvas(bmp))
            iconBitmapCache.put(key, bmp)
            bmp
        } catch (t: Throwable) {
            null
        }
    }

    fun icon(ctx: Context, app: LaunchableApp): Drawable? {
        val key = iconKey(ctx, app)
        iconCache.get(key)?.let { return it }
        val drawable = try {
            val info = launcherInfos[key] ?: synchronized(launcherByPkg) { launcherByPkg[app.packageName] }
            if (info != null) {
                info.getBadgedIcon(ctx.resources.displayMetrics.densityDpi)
            } else {
                ctx.packageManager.getActivityIcon(app.component)
            }
        } catch (t: Throwable) {
            try { ctx.packageManager.getActivityIcon(app.component) } catch (t2: Throwable) {
                try { ctx.packageManager.getApplicationIcon(app.packageName) } catch (t3: Throwable) { null }
            }
        }
        if (drawable != null) iconCache.put(key, drawable)
        return drawable
    }

    /** 启动：小窗优先走 ColorOS 弹性窗口协议，失败退 AOSP freeform，再退 root 的 am */
    fun launch(ctx: Context, app: LaunchableApp, freeform: Boolean): Boolean {
        if (freeform) {
            if (launchWithOptions(ctx, app, COLOROS_FLEXIBLE_MODE, zoomFlag = true)) {
                XLog.d("小窗启动（ColorOS 弹性窗口）：${app.packageName}")
                return true
            }
            if (launchWithOptions(ctx, app, AOSP_FREEFORM_MODE, zoomFlag = false)) {
                XLog.d("小窗启动（AOSP freeform）：${app.packageName}")
                return true
            }
            try {
                val cmd = "am start --windowingMode $COLOROS_FLEXIBLE_MODE -n ${app.packageName}/${app.activityName}"
                val code = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
                if (code == 0) { XLog.d("小窗启动（su am）：${app.packageName}"); return true }
            } catch (t: Throwable) {
                XLog.w("小窗启动失败（需要 root）：$t")
            }
        }
        return try {
            ctx.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            )
            true
        } catch (t: Throwable) {
            XLog.e("启动 ${app.packageName} 失败", t)
            false
        }
    }

    private fun launchWithOptions(
        ctx: Context,
        app: LaunchableApp,
        windowingMode: Int,
        zoomFlag: Boolean
    ): Boolean = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val bundle = Bundle()
        bundle.putInt(BUNDLE_WINDOWING_MODE, windowingMode)
        if (zoomFlag) bundle.putInt(BUNDLE_ZOOM_FLAGS, ZOOM_LAUNCH_FLAG)
        try {
            val options = ActivityOptions.makeBasic()
            ActivityOptions::class.java
                .getDeclaredMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(options, windowingMode)
            bundle.putAll(options.toBundle())
        } catch (_: Throwable) {
        }
        ctx.startActivity(intent, bundle)
        true
    } catch (t: Throwable) {
        XLog.d("小窗启动方式失败（mode=$windowingMode）：${t.javaClass.simpleName}")
        false
    }

    /** ColorOS「弹性窗口」私有模式号；AOSP 的 freeform 是 3 */
    private const val COLOROS_FLEXIBLE_MODE = 100
    private const val AOSP_FREEFORM_MODE = 3
    private const val ZOOM_LAUNCH_FLAG = 4
    private const val BUNDLE_WINDOWING_MODE = "android.activity.windowingMode"
    private const val BUNDLE_ZOOM_FLAGS = "android:activity.mZoomLaunchFlags"
}
