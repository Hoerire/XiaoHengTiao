package com.eta.sideindexx

import android.app.Application
import com.eta.sideindexx.config.ConfigReader
import com.eta.sideindexx.overlay.SystemUiHost
import com.eta.sideindexx.util.XC_MethodHook
import com.eta.sideindexx.util.XLog
import com.eta.sideindexx.util.XposedHelpers
import com.eta.sideindexx.util.Xp
import com.eta.sideindexx.wm.WmProbe
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * 模块入口（LibXposed API 102）。
 *
 * 老 API 的 `IXposedHookZygoteInit` / `IXposedHookLoadPackage` 已由新 API 取代：
 * 入口类继承 [XposedModule]，框架在模块加载 / 包加载 / 包就绪时回调对应方法，
 * 入口类名由 `META-INF/xposed/java_init.list` 指定（不再是 `assets/xposed_init`）。
 *
 * 只注入「系统界面」：索引浮层由 SystemUI 进程承载，
 * 因此不需要作用域里勾任何目标应用，也不需要前台服务保活。
 */
class ModuleEntry : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        // 先挂上框架实例：之后所有 hook / log / 远程偏好都要从这里取
        Xp.attach(this)
        XLog.i("模块加载完成 process=${param.processName} systemServer=${param.isSystemServer}")
        // system_server 热挂载时 "android" 包早已加载完，onPackageLoaded 不会再回调 → 主动起探测
        if (param.isSystemServer) {
            try {
                WmProbe.bootstrap(null)
            } catch (t: Throwable) {
                XLog.w("小窗探测启动失败", t)
            }
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        Xp.attach(this)
        val pkg = param.packageName
        // 自身设置界面直接跳过
        if (pkg == Const.MODULE_PACKAGE) return

        // system_server（系统框架）：小窗（弹性窗口）相关 hook
        if (pkg == "android") {
            // "android" 在**每个**进程都会回调；只有 system_server 才需要小窗 hook
            if (isSysServerProcess()) {
                try { WmProbe.bootstrap(param.defaultClassLoader) } catch (t: Throwable) {
                    XLog.w("小窗探测安装失败", t)
                }
            }
            return
        }
        if (pkg != Const.SYSTEMUI_PACKAGE) return

        // 在 Application.onCreate 之前挂钩：那时才是"系统界面刚起来"的时机
        XLog.i("注入系统界面进程（Application.onCreate 前）")
        try {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val app = param.thisObject as? Application ?: return
                        activate(app, "onCreate")
                    }
                }
            )
        } catch (t: Throwable) {
            XLog.e("Hook SystemUI 失败", t)
        }
    }

    /**
     * 兜底：`onCreate` 已经错过时（模块热重载、模块后装、SystemUI 比模块先起来）
     * 直接拿当前进程的 Application 补一次。已初始化过就走 [activate] 里的幂等开关。
     */
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        Xp.attach(this)
        if (param.packageName != Const.SYSTEMUI_PACKAGE) return
        val app = currentApplication() ?: return
        activate(app, "onPackageReady")
    }

    /** 当前进程是不是 system_server（cmdline=system）。别用 uid，uid 1000 的进程不止它 */
    private fun isSysServerProcess(): Boolean = try {
        java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000').trim() == "system"
    } catch (t: Throwable) {
        false
    }

    private fun currentApplication(): Application? {
        return try {
            val at = XposedHelpers.findClassIfExists("android.app.ActivityThread", null) ?: return null
            XposedHelpers.callStaticMethod(at, "currentApplication") as? Application
        } catch (t: Throwable) {
            null
        }
    }

    /** 幂等：两个回调都可能到，只能真正初始化一次（否则会建出两套浮层窗口） */
    private fun activate(app: Application, from: String) {
        if (activated) return
        synchronized(lock) {
            if (activated) return
            activated = true
        }
        XLog.i("系统界面承载启动（$from）")
        try {
            ConfigReader.attach(app)
        } catch (t: Throwable) {
            XLog.e("配置读取初始化失败", t)
        }
        try {
            SystemUiHost.sync(app)
        } catch (t: Throwable) {
            XLog.e("SystemUI 承载启动失败", t)
        }
    }

    private companion object {
        val lock = Any()

        /** 幂等标志：入口实例按"模块代次"创建，放伴生对象里跨回调共享 */
        @Volatile
        var activated = false
    }
}
