package com.eta.sideindexx.util

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View

object Haptics {

    private const val DURATION_MS = 16L

    /** 短促脉冲（逐格咔哒）。目标进程没有 VIBRATE 权限时回退到 View 触感反馈 */
    fun tick(ctx: Context, strengthPercent: Int, fallbackView: View?) {
        // strengthPercent <= 0 表示跟随系统震动强度（DEFAULT_AMPLITUDE）
        val amplitude = if (strengthPercent <= 0) VibrationEffect.DEFAULT_AMPLITUDE
        else (strengthPercent.coerceIn(1, 100) * 255 / 100).coerceIn(1, 255)
        var ok = false
        try {
            val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(DURATION_MS, amplitude))
                ok = true
            }
        } catch (t: Throwable) {
            XLog.d("Vibrator 不可用：${t.javaClass.simpleName}")
        }
        if (!ok) {
            try { fallbackView?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) } catch (_: Throwable) {}
        }
    }
}
