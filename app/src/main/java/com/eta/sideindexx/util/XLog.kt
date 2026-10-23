package com.eta.sideindexx.util

import android.util.Log
import com.eta.sideindexx.Const

/** 同时写 logcat 与 LSPosed 日志；在模块自身进程里 XposedBridge 不存在，必须容错 */
object XLog {

    @Volatile private var verbose = false

    fun setVerbose(value: Boolean) { verbose = value }
    fun isVerbose(): Boolean = verbose

    fun d(msg: String) { if (verbose) write('D', msg, null) }
    fun i(msg: String) = write('I', msg, null)
    fun w(msg: String, t: Throwable? = null) = write('W', msg, t)
    fun e(msg: String, t: Throwable? = null) = write('E', msg, t)

    private fun write(level: Char, msg: String, t: Throwable?) {
        val line = "[$level] $msg"
        try {
            when (level) {
                'D' -> Log.d(Const.LOG_TAG, line, t)
                'I' -> Log.i(Const.LOG_TAG, line, t)
                'W' -> Log.w(Const.LOG_TAG, line, t)
                else -> Log.e(Const.LOG_TAG, line, t)
            }
        } catch (_: Throwable) {
        }
        try {
            Xp.log("${Const.LOG_TAG} $line")
            if (t != null) Xp.log(t)
        } catch (_: Throwable) {
        }
    }
}
