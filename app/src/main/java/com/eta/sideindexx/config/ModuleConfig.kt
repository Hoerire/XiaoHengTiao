package com.eta.sideindexx.config

import android.content.SharedPreferences

/** 索引条出现在哪一侧 */
enum class Side { RIGHT, LEFT }

object PrefKeys {
    const val ENABLED = "enabled"
    /** 左右两侧都能呼出（关掉只保留右侧） */
    const val BOTH_SIDES = "both_sides"

    /** 呼出侧：right / left / both（老配置没有这个键时按 both_sides 推导） */
    const val TRIGGER_SIDE = "trigger_side"
    /** 仅在桌面（Launcher）前台时才响应手势 */
    const val ONLY_LAUNCHER = "only_launcher"
    /** 触发区内允许系统返回手势（默认开 = 不排除系统边缘手势） */
    const val ALLOW_SYSTEM_BACK = "allow_system_back"
    /** 已废弃：横屏改为"仅游戏屏蔽"写死，不再有名单（保留常量供 AppPickerActivity 兼容，实际不再编码） */
    const val LANDSCAPE_WHITELIST = "landscape_whitelist"
    const val BLACKLIST = "blacklist"
    const val LOG_VERBOSE = "log_verbose"
    const val TRIGGER_WIDTH_DP = "trigger_width_dp"
    /** 触发区中心的屏高百分比：0=贴顶、50=居中、100=贴底 */
    const val TRIGGER_POSITION_PERCENT = "trigger_position_percent"
    const val SENSITIVITY = "sensitivity"
    const val BAR_RADIUS_DP = "bar_radius_dp"
    const val BAR_BG_ALPHA = "bar_bg_alpha"
    const val BAR_BG_COLOR = "bar_bg_color"
    const val BAR_TEXT_COLOR = "bar_text_color"
    const val BAR_HIGHLIGHT_COLOR = "bar_highlight_color"
    const val BAR_FADE_MS = "bar_fade_ms"
    const val BAR_HEIGHT_PERCENT = "bar_height_percent"
    const val VIBRATE_ENABLED = "vibrate_enabled"
    const val VIBRATE_STRENGTH = "vibrate_strength"
    const val VIBRATE_PER_GROUP = "vibrate_per_group"
    const val FREEFORM_LAUNCH = "freeform_launch"
    const val ICON_SIZE_DP = "icon_size_dp"
    /** 每行图标数（4 或 5），超出换行 */
    const val GRID_COLUMNS = "grid_columns"
    /** 常用应用（逗号分隔包名）：固定显示在「常用」栏，顺序即这里写的顺序 */
    const val FAVORITES = "favorites"
    /** 沉浸模式：开 = 呼出背景是模糊桌面壁纸；关 = 不提取壁纸，系统直接模糊浮层背后内容 */
    const val IMMERSIVE = "immersive"
    /** 游戏中屏蔽手势（ColorOS 游戏模式信号 debug_gamemode_value），仅全局索引开启时生效 */
    const val GAME_BLOCK = "game_block"
    /** 应用黑名单（逗号分隔包名）：这些应用里不响应呼出 */
    const val RELOAD_INTERVAL_MS = "reload_interval_ms"
}

interface PrefSource {
    fun getBoolean(key: String, def: Boolean): Boolean
    fun getInt(key: String, def: Int): Int
    fun getString(key: String, def: String?): String?
}

class SharedPrefSource(private val prefs: SharedPreferences) : PrefSource {
    override fun getBoolean(key: String, def: Boolean) = try { prefs.getBoolean(key, def) } catch (_: Throwable) { def }
    override fun getInt(key: String, def: Int) = try { prefs.getInt(key, def) } catch (_: Throwable) { def }
    override fun getString(key: String, def: String?) = try { prefs.getString(key, def) } catch (_: Throwable) { def }
}

class MapPrefSource(private val map: Map<String, Any?>) : PrefSource {
    override fun getBoolean(key: String, def: Boolean) = when (val v = map[key]) {
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull() ?: def
        else -> def
    }

    override fun getInt(key: String, def: Int) = when (val v = map[key]) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: def
        else -> def
    }

    override fun getString(key: String, def: String?) = when (val v = map[key]) {
        null -> def
        is String -> v
        else -> v.toString()
    }
}

data class ModuleConfig(
    /** 总开关：侧边索引 */
    val enabled: Boolean = true,
    /** 左右两侧都能呼出（老键，保留兼容；新配置看 triggerSide） */
    val bothSides: Boolean = false,
    /** 呼出侧：right（默认）/ left / both */
    val triggerSide: String = "right",
    /** 游戏内关闭索引（ColorOS 游戏模式信号命中时不响应） */
    val onlyLauncher: Boolean = true,
    /** 触发区内允许系统返回手势：true = 不排除系统边缘手势（横滑返回归系统，代价是竖滑呼出略慢） */
    val allowSystemBack: Boolean = true,
    /** 触发带宽度（触摸可触发的横向范围，独立于字母条视觉宽度） */
    val triggerWidthDp: Int = 18,
    /** 触发区中心在屏幕上的高度百分比（绝对位置，往上/往下挪） */
    val triggerPositionPercent: Int = 55,
    val sensitivity: Int = 50,
    val barRadiusDp: Int = FIXED_BAR_RADIUS_DP,
    val barBgAlpha: Int = FIXED_BAR_BG_ALPHA,
    val barBgColor: Int = 0xFF1F2126.toInt(),
    val barTextColor: Int = 0xFFE8EAED.toInt(),
    val barHighlightColor: Int = 0xFF3D7EFF.toInt(),
    val barFadeMs: Int = 180,
    /** 可见字母条占屏幕高度百分比（越小越省力）：默认 58%，字母间隙更大更好点 */
    val barHeightPercent: Int = 58,
    val vibrateEnabled: Boolean = false,
    /** 震动强度 0-100：0 = 完全不震动（替代原来的"启用震动"开关） */
    val vibrateStrength: Int = 0,
    val vibratePerGroup: Boolean = false,
    /** 用小窗（ColorOS 弹性窗口）打开应用 */
    val freeformLaunch: Boolean = true,
    val iconSizeDp: Int = FIXED_ICON_SIZE_DP,
    /** 每行图标数（4 或 5），超出换行 */
    val gridColumns: Int = 5,
    /** 常用应用：逗号分隔包名，由设置页的「常用应用」挑选；固定显示，不随使用变化 */
    val favorites: String = "",
    /** 沉浸模式：开 = 背景是模糊桌面壁纸；关（默认）= 系统直接模糊背后内容（不提取壁纸） */
    val immersive: Boolean = false,
    /** 游戏中屏蔽手势（仅全局索引开启时才有意义；桌面呼出不受影响） */
    val gameBlock: Boolean = true,
    /** 应用黑名单：这些应用里不响应呼出（逗号分隔包名） */
    val blacklist: String = "",
    val logVerbose: Boolean = false,
    val reloadIntervalMs: Int = 1200,
    val source: String = "default"
) {

    /** 强度大于 0 才震动 */
    fun vibrateOn(): Boolean = vibrateStrength > 0

    /** 右侧要不要挂感应带（triggerSide = right / both） */
    fun onRight(): Boolean = triggerSide != "left"

    /** 左侧要不要挂感应带（triggerSide = left / both） */
    fun onLeft(): Boolean = triggerSide != "right"

    fun summary(): String =
        "enabled=$enabled sides=$triggerSide onlyLauncher=$onlyLauncher allowBack=$allowSystemBack " +
            "trigger=${triggerWidthDp}dp@${triggerPositionPercent}% " +
            "sens=$sensitivity bar=${FIXED_BAR_WIDTH_DP}dp/${barHeightPercent}% " +
            "vib=$vibrateStrength freeform=$freeformLaunch cols=$gridColumns"

    companion object {
        /** 固定观感（不开放给用户调）：圆角、背景透明度、图标大小 */
        const val FIXED_BAR_RADIUS_DP = 12
        const val FIXED_BAR_BG_ALPHA = 20
        const val FIXED_ICON_SIZE_DP = 47

        /** 字母条宽度固定 23dp：再宽会挤占屏幕、再窄不好点 */
        const val FIXED_BAR_WIDTH_DP = 23

        /** 字母中心到呼出侧屏幕边缘的距离（dp）：8dp，比原来的"半个条宽"收窄约一半 */
        const val FIXED_BAR_EDGE_INSET_DP = 8

        /** 字母字号固定 16sp：条高变化时 IndexBarView 会按格子高度自适应缩小 */
        const val FIXED_BAR_TEXT_SIZE_SP = 13

        /** 横屏独立参数（写死，无设置入口）：字母条高 = 横屏屏高的 80%，垂直位置 50% 屏高，触发宽度继承竖屏设置 */
        const val FIXED_LANDSCAPE_BAR_PERCENT = 80

        /** 横屏"离开索引条"分界：手指离边缘超过这个 dp 就锁字母、进入挑图标（竖屏是 条宽+8dp≈31dp） */
        const val FIXED_LANDSCAPE_LOCK_DP = 21

        /** 横屏触发带宽度（写死）：比竖屏更宽，横屏下手指更容易按到 */
        const val FIXED_LANDSCAPE_TRIGGER_DP = 22

        /**
         * 字号上限基准：按「字母条高 = 38% 屏高」时的字号封顶。
         * 触发高度再往上调，字母不再跟着变大（只把字母之间的间隙拉开），
         * 免得条越高、字越大，又挤又难认。
         */
        const val FIXED_TEXT_CAP_BAR_PERCENT = 38

        /** 图标之间的横向间距（写死 5.5dp） */
        const val FIXED_GRID_GAP_DP = 5.5f

        fun read(src: PrefSource, sourceName: String): ModuleConfig {
            return ModuleConfig(
                enabled = src.getBoolean(PrefKeys.ENABLED, true),
                bothSides = src.getBoolean(PrefKeys.BOTH_SIDES, false),
                triggerSide = src.getString(PrefKeys.TRIGGER_SIDE, null)
                    ?: if (src.getBoolean(PrefKeys.BOTH_SIDES, false)) "both" else "right",
                onlyLauncher = src.getBoolean(PrefKeys.ONLY_LAUNCHER, true),
                allowSystemBack = src.getBoolean(PrefKeys.ALLOW_SYSTEM_BACK, true),
                triggerWidthDp = src.getInt(PrefKeys.TRIGGER_WIDTH_DP, 18).coerceIn(0, 35),
                triggerPositionPercent = src.getInt(PrefKeys.TRIGGER_POSITION_PERCENT, 55).coerceIn(0, 100),
                sensitivity = src.getInt(PrefKeys.SENSITIVITY, 50).coerceIn(0, 100),
                barRadiusDp = FIXED_BAR_RADIUS_DP,
                barBgAlpha = FIXED_BAR_BG_ALPHA,
                barBgColor = src.getInt(PrefKeys.BAR_BG_COLOR, 0xFF1F2126.toInt()),
                barTextColor = src.getInt(PrefKeys.BAR_TEXT_COLOR, 0xFFE8EAED.toInt()),
                barHighlightColor = src.getInt(PrefKeys.BAR_HIGHLIGHT_COLOR, 0xFF3D7EFF.toInt()),
                barFadeMs = src.getInt(PrefKeys.BAR_FADE_MS, 180).coerceIn(0, 800),
                barHeightPercent = src.getInt(PrefKeys.BAR_HEIGHT_PERCENT, 58).coerceIn(20, 100),
                vibrateEnabled = src.getBoolean(PrefKeys.VIBRATE_ENABLED, false),
                vibrateStrength = src.getInt(PrefKeys.VIBRATE_STRENGTH, 0).coerceIn(0, 100),
                vibratePerGroup = src.getBoolean(PrefKeys.VIBRATE_PER_GROUP, false),
                freeformLaunch = src.getBoolean(PrefKeys.FREEFORM_LAUNCH, true),
                iconSizeDp = FIXED_ICON_SIZE_DP,
                gridColumns = src.getInt(PrefKeys.GRID_COLUMNS, 5).coerceIn(4, 5),
                favorites = src.getString(PrefKeys.FAVORITES, "")?.trim() ?: "",
                immersive = src.getBoolean(PrefKeys.IMMERSIVE, false),
                gameBlock = src.getBoolean(PrefKeys.GAME_BLOCK, true),
                blacklist = src.getString(PrefKeys.BLACKLIST, "")?.trim() ?: "",
                logVerbose = src.getBoolean(PrefKeys.LOG_VERBOSE, false),
                reloadIntervalMs = src.getInt(PrefKeys.RELOAD_INTERVAL_MS, 1200).coerceIn(300, 10_000),
                source = sourceName
            )
        }
    }
}
