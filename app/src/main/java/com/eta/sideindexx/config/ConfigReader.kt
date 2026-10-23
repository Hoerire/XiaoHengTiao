package com.eta.sideindexx.config

import android.content.Context
import android.content.SharedPreferences
import com.eta.sideindexx.Const
import com.eta.sideindexx.util.XLog
import com.eta.sideindexx.util.Xp
import java.io.File

/**
 * Hook 进程侧的配置读取，通道顺序：
 *   0) Settings.Secure（主通道，任何进程免权限，不依赖模块应用存活）
 *   1) XSharedPreferences（经典方式，部分 ROM 可用）
 *   2) 模块的 ContentProvider
 *   3) 直读 SharedPreferences XML
 */
object ConfigReader {

    private val IDLE = ModuleConfig(enabled = false, source = "unavailable")
    private const val FAILURE_WARN_INTERVAL_MS = 60_000L

    @Volatile private var candidates: List<Pair<String, SharedPreferences>> = emptyList()
    @Volatile private var direct: DirectPrefs? = null
    @Volatile private var cached: ModuleConfig = ModuleConfig()
    @Volatile private var lastReloadAt = 0L
    @Volatile private var lastSource = "none"
    @Volatile private var lastFailureWarnAt = 0L
    @Volatile private var configAvailable = false
    @Volatile private var appContext: Context? = null
    private var initialized = false

    fun attach(context: Context?) {
        if (appContext != null) return
        val ctx = context?.applicationContext ?: context ?: return
        appContext = ctx
    }

    fun current(): ModuleConfig {
        ensureInit()
        val interval = cached.reloadIntervalMs.toLong().coerceAtLeast(300L)
        val now = System.currentTimeMillis()
        if (now - lastReloadAt < interval) return if (configAvailable) cached else IDLE
        synchronized(this) {
            if (System.currentTimeMillis() - lastReloadAt < interval) {
                return if (configAvailable) cached else IDLE
            }
            lastReloadAt = System.currentTimeMillis()
            load()?.let {
                cached = it
                XLog.setVerbose(it.logVerbose)
            } ?: run {
                if (!configAvailable && now - lastFailureWarnAt > FAILURE_WARN_INTERVAL_MS) {
                    lastFailureWarnAt = now
                    XLog.w("暂时读不到模块配置，本次不启用索引（打开一次模块设置页即可生成）")
                }
            }
        }
        return if (configAvailable) cached else IDLE
    }

    fun invalidate() { lastReloadAt = 0L }

    fun sourceName(): String { ensureInit(); return lastSource }

    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            candidates = buildCandidates()
            direct = DirectPrefs(PrefFiles.modulePrefsFile())
            load()?.let {
                cached = it
                XLog.setVerbose(it.logVerbose)
            }
            XLog.i("配置初始化：source=$lastSource 可读=$configAvailable")
        }
    }

    private fun load(): ModuleConfig? {
        appContext?.let { ctx ->
            SecureConfigChannel.read(ctx)?.let {
                if (lastSource != "secure") XLog.i("配置来源：Settings.Secure")
                lastSource = "secure"
                configAvailable = true
                return it
            }
        }

        for ((label, prefs) in candidates) {
            try {
                // API 102 的远程偏好本身是实时视图，没有老 XSharedPreferences.reload() 这一步
                val map = prefs.all
                if (map != null && map.isNotEmpty()) {
                    if (lastSource != label) XLog.i("配置来源：$label（${map.size} 项）")
                    lastSource = label
                    configAvailable = true
                    return ModuleConfig.read(SharedPrefSource(prefs), label)
                }
            } catch (t: Throwable) {
                XLog.d("读取失败 $label：${t.javaClass.simpleName}")
            }
        }


        val d = direct
        if (d != null && d.exists()) {
            val map = d.read()
            if (map != null) {
                lastSource = "direct-xml"
                configAvailable = true
                return ModuleConfig.read(MapPrefSource(map), lastSource)
            }
        }
        if (!configAvailable) lastSource = "none"
        return null
    }


    /**
     * 远程偏好通道：LibXposed API 102 用 `getRemotePreferences(name)` 直接取模块自己
     * SharedPreferences 的跨进程视图（老 API 的 XSharedPreferences 已随老 API 一起移除）。
     * 文件名按历史命名差异依次试一轮；拿不到就返回空表，让后面的 provider / 直读 XML 兜底。
     */
    private fun buildCandidates(): List<Pair<String, SharedPreferences>> {
        val names = listOf(Const.PREFS_NAME, Const.MODULE_PACKAGE)
        val result = ArrayList<Pair<String, SharedPreferences>>(names.size)
        for (name in names) {
            try {
                val prefs = Xp.remotePreferences(name) ?: continue
                result.add("remote:$name" to prefs)
            } catch (t: Throwable) {
                XLog.d("远程偏好打开失败（$name）：${t.javaClass.simpleName}")
            }
        }
        return result
    }
}
