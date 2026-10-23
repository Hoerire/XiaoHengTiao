package com.eta.sideindexx.overlay

import android.content.Context
import android.view.WindowManager
import com.eta.sideindexx.config.ConfigReader
import com.eta.sideindexx.util.XLog

/**
 * SystemUI 承载宿主：本模块唯一的工作方式。
 *
 * LSPosed 把模块注入「系统界面」进程后由这里创建控制器：
 *   - SystemUI 常驻，划掉模块应用后台也不影响呼出
 *   - 系统应用身份，不需要悬浮窗权限、不需要前台服务
 *   - 不受"后台启动 Activity"限制
 *
 * 控制器一旦创建就常驻（它的心跳负责按配置挂载/摘除感应带），
 * 所以用户在设置里开关功能不需要重启系统界面。
 */
object SystemUiHost {

    private var controller: OverlayController? = null

    fun sync(context: Context) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm == null) {
            XLog.e("SystemUI 承载：拿不到 WindowManager")
            return
        }
        if (controller == null) {
            BackGestureGate.install(context.classLoader)
            controller = OverlayController(
                context = context,
                windowManager = wm,
                configProvider = { ConfigReader.current() }
            ).also { it.start() }
        }
    }

    fun stop() {
        controller?.stop()
        controller = null
    }
}
