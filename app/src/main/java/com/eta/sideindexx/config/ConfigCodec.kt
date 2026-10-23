package com.eta.sideindexx.config

import com.eta.sideindexx.Const

/**
 * 配置的紧凑编解码（分隔符用分号，方便经 `settings put secure` 传递）。
 * 重要：ModuleConfig.read() 里读取的每个键都必须在这里编码，
 * 否则 SystemUI 侧会拿到默认值（曾因此漏掉 host_in_system_ui / global_drawer 导致整条腿失效）。
 */
object ConfigCodec {

    private const val SEPARATOR = ';'
    private const val KV = '='

    fun encode(config: ModuleConfig): String {
        val sb = StringBuilder(512)
        fun put(key: String, value: Any?) {
            if (value == null) return
            val text = value.toString()
            if (text.indexOf(SEPARATOR) >= 0 || text.indexOf(KV) >= 0) return
            sb.append(key).append(KV).append(text).append(SEPARATOR)
        }

        put(PrefKeys.ENABLED, config.enabled)
        put(PrefKeys.BOTH_SIDES, config.bothSides)
        put(PrefKeys.TRIGGER_SIDE, config.triggerSide)
        put(PrefKeys.ONLY_LAUNCHER, config.onlyLauncher)
        put(PrefKeys.ALLOW_SYSTEM_BACK, config.allowSystemBack)
        put(PrefKeys.TRIGGER_WIDTH_DP, config.triggerWidthDp)
        put(PrefKeys.TRIGGER_POSITION_PERCENT, config.triggerPositionPercent)
        put(PrefKeys.SENSITIVITY, config.sensitivity)
        put(PrefKeys.BAR_RADIUS_DP, config.barRadiusDp)
        put(PrefKeys.BAR_BG_ALPHA, config.barBgAlpha)
        put(PrefKeys.BAR_BG_COLOR, config.barBgColor)
        put(PrefKeys.BAR_TEXT_COLOR, config.barTextColor)
        put(PrefKeys.BAR_HIGHLIGHT_COLOR, config.barHighlightColor)
        put(PrefKeys.BAR_FADE_MS, config.barFadeMs)
        put(PrefKeys.BAR_HEIGHT_PERCENT, config.barHeightPercent)
        put(PrefKeys.VIBRATE_ENABLED, config.vibrateEnabled)
        put(PrefKeys.VIBRATE_STRENGTH, config.vibrateStrength)
        put(PrefKeys.VIBRATE_PER_GROUP, config.vibratePerGroup)
        put(PrefKeys.FREEFORM_LAUNCH, config.freeformLaunch)
        put(PrefKeys.ICON_SIZE_DP, config.iconSizeDp)
        put(PrefKeys.GRID_COLUMNS, config.gridColumns)
        put(PrefKeys.FAVORITES, config.favorites)
        put(PrefKeys.IMMERSIVE, config.immersive)
        put(PrefKeys.GAME_BLOCK, config.gameBlock)
        put(PrefKeys.BLACKLIST, config.blacklist)
        put(PrefKeys.LOG_VERBOSE, config.logVerbose)
        put(PrefKeys.RELOAD_INTERVAL_MS, config.reloadIntervalMs)
        return sb.toString()
    }

    fun decode(payload: String?): ModuleConfig? {
        val text = payload?.trim().orEmpty()
        if (text.isEmpty()) return null
        val map = HashMap<String, Any?>()
        for (line in text.split(SEPARATOR)) {
            if (line.isEmpty()) continue
            val index = line.indexOf(KV)
            if (index <= 0) continue
            map[line.substring(0, index)] = line.substring(index + 1)
        }
        if (map.isEmpty()) return null
        return ModuleConfig.read(MapPrefSource(map), "channel")
    }

    /** 供设置页做"编码键完整性"自检 */
    fun encodedKeys(): List<String> = listOf(
        PrefKeys.ENABLED, PrefKeys.BOTH_SIDES, PrefKeys.TRIGGER_SIDE,
        PrefKeys.ONLY_LAUNCHER, PrefKeys.ALLOW_SYSTEM_BACK,
        PrefKeys.TRIGGER_WIDTH_DP,
        PrefKeys.TRIGGER_POSITION_PERCENT,
        PrefKeys.SENSITIVITY,
        PrefKeys.BAR_RADIUS_DP, PrefKeys.BAR_BG_ALPHA, PrefKeys.BAR_BG_COLOR,
        PrefKeys.BAR_TEXT_COLOR, PrefKeys.BAR_HIGHLIGHT_COLOR,
        PrefKeys.BAR_FADE_MS, PrefKeys.BAR_HEIGHT_PERCENT, PrefKeys.VIBRATE_ENABLED,
        PrefKeys.VIBRATE_STRENGTH, PrefKeys.VIBRATE_PER_GROUP, PrefKeys.FREEFORM_LAUNCH,
        PrefKeys.ICON_SIZE_DP, PrefKeys.LOG_VERBOSE, PrefKeys.RELOAD_INTERVAL_MS,
        PrefKeys.GRID_COLUMNS, PrefKeys.FAVORITES, PrefKeys.IMMERSIVE,
        PrefKeys.GAME_BLOCK, PrefKeys.BLACKLIST
    ).sorted()
}
