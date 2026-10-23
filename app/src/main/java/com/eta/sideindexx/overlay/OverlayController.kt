package com.eta.sideindexx.overlay

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import com.eta.sideindexx.Const
import com.eta.sideindexx.config.ConfigReader
import com.eta.sideindexx.config.ModuleConfig
import com.eta.sideindexx.config.SecureConfigChannel
import com.eta.sideindexx.util.Display
import com.eta.sideindexx.util.Haptics
import com.eta.sideindexx.util.XLog

/**
 * 底部导航条触发 + 最近应用浮层（唯一入口）。
 *
 * 侧边字母索引已移除；这里只做三件事：
 *   1. 底部挂一条常驻不可触摸的感应带（只用于算屏幕坐标 + 通知输入监听器范围）
 *   2. 监听 SystemUI 的手势输入（BackGestureGate），在感应带内横滑 → 抢指针 + 呼出浮层
 *   3. 浮层生命周期与 LiquidDialog 同规格的入场/出场动画
 */
class OverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val configProvider: () -> ModuleConfig
) {

    private enum class State { IDLE, PENDING, ACTIVE }

    private val handler = Handler(Looper.getMainLooper())

    private var config = ModuleConfig()
    private var running = false

    private var strip: View? = null
    private var stripRect: Rect? = null
    private var panel: RecentsPanelView? = null
    private var panelVisible = false

    private var state = State.IDLE
    private var downX = 0f
    private var downY = 0f
    private var maxDy = 0f
    private var lastEventAt = 0L

    // ---------------- 生命周期 ----------------

    fun start() {
        if (running) return
        running = true
        config = configProvider()
        XLog.i("底栏浮层启动：${config.summary()}")
        BackGestureGate.onTouch = { e, zone ->
            if (zone == BackGestureGate.ZONE_BOTTOM) {
                try {
                    val copy = MotionEvent.obtain(e)
                    handler.post {
                        try {
                            handleBottomTouch(copy)
                        } catch (t: Throwable) {
                            XLog.e("底栏手势异常", t)
                            resetBottom("手势异常")
                        } finally {
                            copy.recycle()
                        }
                    }
                } catch (t: Throwable) {
                    XLog.e("底栏事件拷贝失败", t)
                }
            }
        }
        try {
            context.registerComponentCallbacks(componentCallbacks)
            context.registerReceiver(
                packageReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addDataScheme("package")
                }
            )
        } catch (t: Throwable) {
            XLog.d("注册监听失败：${t.javaClass.simpleName}")
        }
        syncStrip()
        handler.postDelayed(tick, TICK_MS)
    }

    fun stop() {
        running = false
        BackGestureGate.onTouch = null
        BackGestureGate.update(null, null, null, false)
        hidePanel(animated = false, reason = "停止")
        panel?.let { removePanelWindow(it) }
        removeStrip()
        handler.removeCallbacksAndMessages(null)
        try { context.unregisterComponentCallbacks(componentCallbacks) } catch (t: Throwable) {}
        try { context.unregisterReceiver(packageReceiver) } catch (t: Throwable) {}
        XLog.i("底栏浮层已停止")
    }

    private val componentCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            try { syncStrip() } catch (t: Throwable) { XLog.e("旋转处理失败", t) }
        }

        override fun onLowMemory() {}
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val pkg = intent?.data?.schemeSpecificPart ?: return
            if (pkg == context.packageName) return
            try {
                AppRepository.reloadAsync(context)
            } catch (t: Throwable) {
                XLog.d("重建应用清单失败：${t.javaClass.simpleName}")
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val latest = configProvider()
                if (latest != config) {
                    XLog.d("配置热更新：${latest.summary()}")
                    config = latest
                    AppRepository.invalidate()
                    AppRepository.reloadAsync(context)
                }
                ForegroundApp.refresh(context)
                syncStrip()
                if (state == State.PENDING &&
                    System.currentTimeMillis() - lastEventAt > GESTURE_STALL_MS
                ) {
                    XLog.w("底栏手势事件流停滞，强制复位")
                    resetBottom("事件流停滞")
                }
            } catch (t: Throwable) {
                XLog.e("心跳异常", t)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    // ---------------- 底部感应带 ----------------

    private fun landscapeNow(): Boolean = try {
        Display.isLandscape(context, windowManager)
    } catch (t: Throwable) {
        false
    }

    private fun stripLayout(): WindowManager.LayoutParams {
        val bounds = Display.screenBounds(windowManager, context)
        val width = (bounds.width() * BAND_WIDTH_PERCENT / 100)
            .coerceAtLeast(Display.dp(context, 180))
            .coerceAtMost(bounds.width())
        val height = Display.dp(context, BAND_HEIGHT_DP)
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 0
            try {
                if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            } catch (t: Throwable) {
            }
        }
    }

    private fun syncStrip() {
        if (!config.enabled || landscapeNow()) {
            if (strip != null) {
                XLog.i("摘下底部感应带（enabled=${config.enabled} landscape=${landscapeNow()}）")
                removeStrip()
            }
            return
        }
        val lp = stripLayout()
        val view = strip
        if (view == null || view.parent == null) {
            val v = View(context).apply { setBackgroundColor(0x01000000) }
            try {
                windowManager.addView(v, lp)
                strip = v
                XLog.i("底部感应带已挂载：${lp.width}x${lp.height}")
            } catch (t: Throwable) {
                XLog.e("挂载底部感应带失败", t)
                return
            }
        } else {
            val cur = view.layoutParams as? WindowManager.LayoutParams
            if (cur != null && cur.width == lp.width && cur.height == lp.height &&
                cur.gravity == lp.gravity && cur.y == lp.y
            ) {
                refreshStripRect()
                syncGate()
                return
            }
            try {
                windowManager.updateViewLayout(view, lp)
                XLog.d("底部感应带已更新：${lp.width}x${lp.height}")
            } catch (t: Throwable) {
                XLog.d("更新底部感应带失败：${t.javaClass.simpleName}")
            }
        }
        refreshStripRect()
        syncGate()
    }

    private fun removeStrip() {
        strip?.let {
            try { windowManager.removeView(it) } catch (t: Throwable) {}
        }
        strip = null
        stripRect = null
        syncGate()
    }

    private fun refreshStripRect() {
        val v = strip ?: run { stripRect = null; return }
        stripRect = try {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        } catch (t: Throwable) {
            null
        }
    }

    private fun syncGate() {
        val rect = stripRect
        BackGestureGate.update(null, null, rect, rect != null && config.enabled && !landscapeNow())
    }

    private fun bandTop(): Int = stripRect?.top ?: (Display.screenBounds(windowManager, context).height() - Display.dp(context, BAND_HEIGHT_DP))

    // ---------------- 手势 ----------------

    private fun handleBottomTouch(event: MotionEvent) {
        if (landscapeNow()) return
        val x = event.x
        val y = event.y
        lastEventAt = System.currentTimeMillis()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                maxDy = 0f
                state = if (allowedNow()) State.PENDING else State.IDLE
                XLog.i("底栏 DOWN x=${x.toInt()} y=${y.toInt()} state=$state")
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = x - downX
                val dy = y - downY
                if (kotlin.math.abs(dy) > maxDy) maxDy = kotlin.math.abs(dy)
                val slop = Display.dp(context, HORIZONTAL_SLOP_DP).toFloat()
                // 系统上滑手势（回桌面 / 进最近任务）：先收起浮层，再把手势让给系统
                if (maxDy > Display.dp(context, VERTICAL_GIVEUP_DP) && kotlin.math.abs(dx) < slop) {
                    if (panelVisible) {
                        XLog.i("系统上滑：收起浮层并放行")
                        hidePanel(animated = false, reason = "系统上滑")
                    }
                    state = State.IDLE
                    return
                }
                // 只有还在 PENDING 时才做激活（ACTIVE 之后由浮层自己处理）
                if (state != State.PENDING) return
                if (kotlin.math.abs(dx) < slop) return
                // 抢指针：系统 quick-switch / 手机会话收到 cancel，不再自己切应用
                BackGestureGate.pilfer()
                // 底栏左右滑 = 呼出系统智能侧边栏（原生 UI/数据/图标缓存）
                state = State.IDLE
                showPanel()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (state != State.ACTIVE) state = State.IDLE
            }

        }
    }

    private fun allowedNow(): Boolean = try {
        ForegroundApp.refreshNow(context)
        val fg = ForegroundApp.current(context)
        val gameBlocked = config.gameBlock && SettingsGameMode()
        val blacklisted = fg.isNotEmpty() && config.blacklist.isNotBlank() &&
            config.blacklist.split(',').map { it.trim() }.contains(fg)
        !gameBlocked && !blacklisted
    } catch (t: Throwable) {
        true
    }

    private fun SettingsGameMode(): Boolean = try {
        android.provider.Settings.Global.getInt(context.contentResolver, "debug_gamemode_value") == 1
    } catch (t: Throwable) {
        false
    }

    // ---------------- 浮层 ----------------

    private fun ensurePanelWindow(): RecentsPanelView? {
        panel?.let { if (it.parent != null) return it }
        val view = RecentsPanelView(context)
        view.isClickable = true
        view.onPick = { app -> launch(app) }
        view.onDismiss = { hidePanel(animated = true, reason = "上滑/点击关闭") }
        view.onPinToggle = { app -> togglePin(app) }
        val (w0, h0) = panelSize()
        val lp = WindowManager.LayoutParams(
            w0,
            h0,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 0
            try {
                if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            } catch (t: Throwable) {
            }
        }
        return try {
            windowManager.addView(view, lp)
            view.visibility = View.INVISIBLE
            view.alpha = 0f
            panel = view
            XLog.i("浮层窗口已常驻")
            view
        } catch (t: Throwable) {
            XLog.e("创建浮层窗口失败", t)
            null
        }
    }

    private fun removePanelWindow(view: RecentsPanelView) {
        try { windowManager.removeView(view) } catch (t: Throwable) {}
        if (panel === view) panel = null
    }

    private fun setPanelTouchable(touchable: Boolean) {
        val v = panel ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        if (lp.flags == flags) return
        lp.flags = flags
        try {
            // 避免在事件派发中同步改窗口属性（会打断输入派发）
            handler.post { try { windowManager.updateViewLayout(v, lp) } catch (t: Throwable) {} }
        } catch (t: Throwable) {
            XLog.d("切换浮层触摸模式失败：${t.javaClass.simpleName}")
        }
    }

    private fun pinnedPackages(): List<String> =
        config.favorites.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** 弹窗尺寸 = 卡片尺寸（列宽 × 行数），不做全屏 */
    private fun panelSize(): Pair<Int, Int> {
        val bounds = Display.screenBounds(windowManager, context)
        val columns = config.gridColumns.coerceIn(3, 6)
        val padX = Display.dp(context, 14)
        val pitch = minOf((bounds.width() - 2 * padX) / columns, Display.dp(context, 104))
            .coerceAtLeast(Display.dp(context, 48))
        // 满屏宽 + 内容高度（不再全屏铺满），底边贴到屏幕最底、不留空
        val tabH = Display.dp(context, RecentsPanelView.TAB_HEIGHT_DP)
        val hintH = Display.dp(context, RecentsPanelView.HINT_HEIGHT_DP)
        val rowH = Display.dp(context, RecentsPanelView.ROW_HEIGHT_DP)
        val rows = ((Display.dp(context, PANEL_MAX_HEIGHT_DP) - tabH - hintH) / rowH)
            .coerceIn(1, RecentsPanelView.MAX_ROWS)
        return bounds.width() to (tabH + rows * rowH + hintH)
    }

    private fun applyPanelSize(view: RecentsPanelView): Pair<Int, Int> {
        val (w, h) = panelSize()
        val lp = view.layoutParams as? WindowManager.LayoutParams
        if (lp != null && (lp.width != w || lp.height != h)) {
            lp.width = w
            lp.height = h
            try { windowManager.updateViewLayout(view, lp) } catch (t: Throwable) {}
        }
        return w to h
    }

    private fun showPanel() {
        val recent = try {
            AppRepository.recents(context, Int.MAX_VALUE)
        } catch (t: Throwable) {
            emptyList()
        }
        val pinned = try {
            AppRepository.byPackages(context, pinnedPackages())
        } catch (t: Throwable) {
            emptyList()
        }
        if (recent.isEmpty() && pinned.isEmpty()) {
            XLog.i("应用清单为空，浮层不呼出")
            state = State.IDLE
            return
        }
        val view = ensurePanelWindow() ?: run {
            state = State.IDLE
            return
        }
        val (winW, winH) = applyPanelSize(view)
        view.prepare(
            recent, pinned, winW, winH, config.gridColumns
        )
        // LiquidDialog 规格：缩放中心压在底部感应带上，视觉上"从底栏长出来"
        // 打开：像侧边栏从边缘滑出那样，从底部滑上来 + 淡入（decellerate）
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.pivotX = winW / 2f
        view.pivotY = winH.toFloat()
        // 入场：位移用 decelerate 滑上来；缩放用 overshoot 做回弹
        // （translationY 过冲是向上的，会被窗口高度裁掉；以底部为轴心的缩放胀出再回落才看得见）
        view.alpha = 0f
        view.translationY = winH * OPEN_SLIDE
        view.scaleX = OPEN_SCALE_FROM
        view.scaleY = OPEN_SCALE_FROM
        view.animate().alpha(1f).setDuration(OPEN_MS).start()
        android.animation.ObjectAnimator.ofFloat(view, "translationY", winH * OPEN_SLIDE, 0f).apply {
            duration = OPEN_MS
            interpolator = PathInterpolator(0.05f, 0f, 0f, 1f)
            start()
        }
        for (prop in arrayOf("scaleX", "scaleY")) {
            android.animation.ObjectAnimator.ofFloat(view, prop, OPEN_SCALE_FROM, 1f).apply {
                duration = OPEN_SPRING_MS
                interpolator = android.view.animation.OvershootInterpolator(OPEN_OVERSHOOT)
                start()
            }
        }
        panelVisible = true
        state = State.ACTIVE
        view.armInputDelay(TOUCHABLE_DELAY_MS)
        handler.removeCallbacks(touchableTask)
        handler.postDelayed(touchableTask, TOUCHABLE_DELAY_MS)
        if (config.vibrateOn()) Haptics.tick(context, config.vibrateStrength, null)
        XLog.i("浮层已呼出：最近 ${recent.size} / 常用 ${pinned.size}")
    }

    /** 长按切换"常用"固定：写回 Secure 配置，面板即时刷新（不影响当前分组） */
    private fun togglePin(app: LaunchableApp) {
        val cur = pinnedPackages().toMutableList()
        val on = !cur.contains(app.packageName)
        if (on) cur.add(0, app.packageName) else cur.remove(app.packageName)
        val next = config.copy(favorites = cur.joinToString(","))
        val ok = try {
            SecureConfigChannel.write(context, next)
        } catch (t: Throwable) {
            false
        }
        if (!ok) {
            XLog.w("固定写入失败：${app.label}")
            return
        }
        config = next
        ConfigReader.invalidate()
        panel?.updatePinned(AppRepository.byPackages(context, cur))
        if (config.vibrateOn()) Haptics.tick(context, config.vibrateStrength, null)
        XLog.i("常用固定${if (on) "成功" else "取消"}：${app.label}（共 ${cur.size} 个）")
    }

    private val touchableTask = Runnable {
        if (panelVisible) setPanelTouchable(true)
    }

    private fun hidePanel(animated: Boolean, reason: String) {
        panel?.let { pv -> handler.postDelayed({ if (!panelVisible) try { pv.release() } catch (t: Throwable) {} }, 400L) }
        val v = panel ?: return
        state = State.IDLE
        handler.removeCallbacks(touchableTask)
        setPanelTouchable(false)
        if (!panelVisible) return
        panelVisible = false
        XLog.i("浮层收起：$reason")
        v.animate().cancel()
        if (animated) {
            v.animate().cancel()
            v.animate()
                .alpha(0f)
                .translationY(v.height * CLOSE_SLIDE)
                .setDuration(CLOSE_MS)
                .setInterpolator(PathInterpolator(0.3f, 0f, 1f, 1f))
                .withEndAction {
                    if (!panelVisible) {
                        v.visibility = View.INVISIBLE
                        v.translationY = 0f
                    }
                }
                .start()
        } else {
            v.alpha = 0f
            v.translationY = 0f
            v.visibility = View.INVISIBLE
        }
    }

    private fun launch(app: LaunchableApp) {
        hidePanel(animated = false, reason = "选中应用")
        XLog.i("启动应用：${app.label}")
        try {
            AppRepository.launch(context, app, config.freeformLaunch)
            AppRepository.recordLaunch(context, app.packageName)
            AppRepository.invalidate()
        } catch (t: Throwable) {
            XLog.e("启动应用失败", t)
        }
    }

    private fun resetBottom(reason: String) {
        state = State.IDLE
        hidePanel(animated = false, reason = reason)
    }

    private companion object {
        const val TICK_MS = 2500L
        const val GESTURE_STALL_MS = 5000L
        const val BAND_WIDTH_PERCENT = 60
        const val BAND_HEIGHT_DP = 30
        const val HORIZONTAL_SLOP_DP = 22
        const val VERTICAL_GIVEUP_DP = 26
        const val PANEL_MAX_HEIGHT_DP = 386
        /** 实测侧边栏：约 150~200ms 内完成，位移为主、几乎无过渡拖尾 */
        const val OPEN_MS = 220L
        /** 入场起始缩放（略小 → 胀回 1.0 形成回弹） */
        const val OPEN_SCALE_FROM = 0.90f
        /** 缩放回弹时长 */
        const val OPEN_SPRING_MS = 340L
        /** 回弹强度：越大胀出越明显（>1 即过冲） */
        const val OPEN_OVERSHOOT = 2.2f
        const val CLOSE_MS = 150L
        const val OPEN_SLIDE = 0.10f
        const val CLOSE_SLIDE = 0.08f
        const val TOUCHABLE_DELAY_MS = 260L
        const val FG_WATCH_MS = 250L
        const val FG_SETTLE_MS = 1200L
    }
}
