package com.eta.sideindexx.config

import android.content.Context
import android.os.Environment
import android.provider.Settings
import android.system.Os
import com.eta.sideindexx.Const
import com.eta.sideindexx.util.XLog
import java.io.File

object PrefFiles {
    fun modulePrefsFile(): File = File(
        Environment.getDataDirectory(),
        "data/${Const.MODULE_PACKAGE}/shared_prefs/${Const.PREFS_NAME}.xml"
    )

    fun describe(): String {
        val file = modulePrefsFile()
        val dir = file.parentFile
        return "路径：${file.absolutePath}\n存在：${file.isFile}\n" +
            "目录权限：${modeOf(dir)}  文件权限：${modeOf(file)}"
    }

    private fun modeOf(file: File?): String = try {
        if (file == null) "-" else String.format("%o", Os.stat(file.absolutePath).st_mode and 0x1FF)
    } catch (t: Throwable) {
        "未知"
    }
}

/**
 * 系统设置库通道（Settings.Secure）：整套配置的**主通道**。
 * 读取任何进程免权限、不依赖模块应用存活、不受 SELinux 文件隔离影响；
 * 写入需要 WRITE_SECURE_SETTINGS，没有该权限时用 root 的 settings 命令兜底。
 */
object SecureConfigChannel {

    const val KEY = "eta_sideindex_config"

    fun read(context: Context): ModuleConfig? = try {
        ConfigCodec.decode(Settings.Secure.getString(context.contentResolver, KEY))
    } catch (t: Throwable) {
        XLog.d("Secure 通道读取失败：${t.javaClass.simpleName}")
        null
    }

    /** 删除配置键（清数据 / 卸载时把残留清干净）；和 write 一样带 root 兜底 */
    fun clear(context: Context): Boolean {
        try {
            Settings.Secure.putString(context.contentResolver, KEY, null)
            return true
        } catch (t: Throwable) {
            XLog.d("直接清 Secure 失败：${t.javaClass.simpleName}")
        }
        return try {
            val code = Runtime.getRuntime()
                .exec(arrayOf("su", "-c", "settings delete secure $KEY"))
                .waitFor()
            code == 0
        } catch (t: Throwable) {
            XLog.w("清 Secure 失败（需要 root 授权）：$t")
            false
        }
    }

    fun write(context: Context, config: ModuleConfig): Boolean {
        val payload = ConfigCodec.encode(config)
        if (payload.isEmpty()) return false
        try {
            Settings.Secure.putString(context.contentResolver, KEY, payload)
            return true
        } catch (t: Throwable) {
            XLog.d("直接写 Secure 失败：${t.javaClass.simpleName}")
        }
        return try {
            val escaped = payload.replace("'", "'\\''")
            val code = Runtime.getRuntime()
                .exec(arrayOf("su", "-c", "settings put secure $KEY '$escaped'"))
                .waitFor()
            if (code == 0) XLog.i("已通过 root 写入 Secure 配置通道（${payload.length} 字节）") else XLog.w("root 写 Secure 失败 code=$code")
            code == 0
        } catch (t: Throwable) {
            XLog.w("Secure 通道写入失败（需要 root 授权）：$t")
            false
        }
    }
}
