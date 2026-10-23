package com.eta.sideindexx.util

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager

object Display {

    fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun sp(ctx: Context, value: Int): Float =
        value * ctx.resources.displayMetrics.scaledDensity

    /**
     * 横屏判定（不依赖"自动旋转"开关）：
     *   Configuration.orientation 或窗口真实宽高任一为横屏就算横屏。
     * 自动旋转关着时 Configuration 可能一直是竖屏，但视频/游戏把显示横过来时
     * WindowManager 的 bounds 依然是宽>高，所以这里两个都看。
     */
    fun isLandscape(ctx: Context, windowManager: WindowManager?): Boolean {
        val byConfig = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val byBounds = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windowManager != null) {
                val b = windowManager.currentWindowMetrics.bounds
                b.width() > b.height()
            } else {
                false
            }
        } catch (t: Throwable) {
            false
        }
        return byConfig || byBounds
    }

    fun screenBounds(windowManager: WindowManager?, fallbackCtx: Context): Rect {
        val rect = Rect()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windowManager != null) {
                rect.set(windowManager.currentWindowMetrics.bounds)
                if (rect.width() > 0 && rect.height() > 0) return rect
            }
        } catch (t: Throwable) {
            XLog.d("windowMetrics 不可用：${t.javaClass.simpleName}")
        }
        val dm = fallbackCtx.resources.displayMetrics
        rect.set(0, 0, dm.widthPixels, dm.heightPixels)
        return rect
    }
}
