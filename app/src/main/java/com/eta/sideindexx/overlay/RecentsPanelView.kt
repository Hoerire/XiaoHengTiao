package com.eta.sideindexx.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.eta.sideindexx.util.Display
import com.eta.sideindexx.util.Haptics
import com.eta.sideindexx.util.XLog
import kotlin.math.abs

/**
 * 底部浮层：真实 View 树（GridView + Adapter 复用 + ImageView 画 Drawable），
 * 与系统桌面抽屉同结构 —— 滚动由原生长列表完成，不做自绘图标。
 *
 * 分组：最近 / 常用 / 全部；「全部」带右侧 A-Z 字母索引（按住/拖动跳组）。
 */
class RecentsPanelView(context: Context) : FrameLayout(context) {

    companion object {
        const val TAB_RECENT = 0
        const val TAB_PINNED = 1

        const val TAB_HEIGHT_DP = 46
        const val HINT_HEIGHT_DP = 28
        const val ROW_HEIGHT_DP = 96
        const val MAX_ROWS = 6
        /** 按住满 1 秒才触发收藏/取消收藏 */
        const val LONG_PRESS_MS = 1000L
        /** 面板跟手阻尼：手指位移 × 该系数 = 面板位移 */
        const val PULL_DAMPING = 0.38f
        /** 下拉超过面板高度的该比例即收起 */
        const val PULL_DISMISS_FRACTION = 0.16f
        /** 松手回弹时长 */
        const val SPRING_MS = 320L
    }

    private fun isNight(c: android.content.res.Configuration) =
        (c.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private var dark = isNight(context.resources.configuration)

    private var recentApps: List<LaunchableApp> = emptyList()
    private var pinnedApps: List<LaunchableApp> = emptyList()
    private var tab = TAB_RECENT

    private var columns = 5
    private var rowHeight = Display.dp(context, ROW_HEIGHT_DP)
    private var cellWidth = 0
    private var viewHeight = 0

    var onPick: ((LaunchableApp) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null
    var onPinToggle: ((LaunchableApp) -> Unit)? = null

    private val tabRow = LinearLayout(context)
    private val grid = GridView(context)
    private val pinnedGrid = GridView(context)
    private val hint = TextView(context)
    private val tabSnack = TextView(context).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, 0, Display.dp(context, 14), 0)
        visibility = INVISIBLE
    }
    private var snackHide: Runnable? = null

    private val tabViews = ArrayList<TextView>()
    private var readyAt = 0L
    private var scrolling = false
    // 「常用」的添加模式：临时列出全部应用供勾选固定
    // 左右滑动切换分组
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    /** 正在纵向拉动面板（列表已到顶，手指继续下拉） */
    private var pulling = false
    private var pullAnim: android.animation.ValueAnimator? = null
    private var swipingGroup = false
    private var downAt = 0L
    private var lpRunnable: Runnable? = null
    private var lpFired = false

    /** 按住满 [LONG_PRESS_MS] 才触发收藏/取消收藏，避免误触 */
    private fun startLongPressTimer(ev: MotionEvent) {
        cancelLongPressTimer()
        val g = if (tab == TAB_PINNED) pinnedGrid else grid
        val loc = IntArray(2)
        g.getLocationOnScreen(loc)
        val pos = g.pointToPosition((ev.rawX - loc[0]).toInt(), (ev.rawY - loc[1]).toInt())
        if (pos < 0) return
        val ad = if (tab == TAB_PINNED) pinnedAdapter else adapter
        val runnable = Runnable {
            lpRunnable = null
            ad.getItem(pos)?.let { a ->
                lpFired = true
                // 震动反馈（长按 3 秒达成）
                try { Haptics.tick(context, 0, this@RecentsPanelView) } catch (t: Throwable) { }
                onPinToggle?.invoke(a)
                showSnack(if (tab == TAB_PINNED) "已取消收藏：${a.label}" else "已收藏：${a.label}")
            }
        }
        lpRunnable = runnable
        postDelayed(runnable, LONG_PRESS_MS)
    }

    private fun cancelLongPressTimer() {
        lpRunnable?.let { removeCallbacks(it) }
        lpRunnable = null
    }
    private var groupSwitched = false
    private val groupSlop = Display.dp(context, 16).toFloat()

    private val onSurfaceColor get() = if (dark) 0xFFEDEDED.toInt() else 0xFF1D1B20.toInt()
    private val primaryColor get() = if (dark) 0xFFD0BCFF.toInt() else 0xFF6750A4.toInt()

    private val adapter = AppAdapter()
    private val pinnedAdapter = AppAdapter()

    init {
        applyTheme()
        isClickable = true

        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 分组胶囊
        tabRow.orientation = LinearLayout.HORIZONTAL
        tabRow.gravity = Gravity.CENTER
        val tabH = Display.dp(context, TAB_HEIGHT_DP)
        root.addView(tabRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, tabH))
        // 左侧占位：让标签整体保持居中
        tabRow.addView(android.view.View(context), LinearLayout.LayoutParams(0, 1, 1f))
        for ((i, name) in arrayOf("最近", "收藏").withIndex()) {
            val tv = TextView(context).apply {
                text = name
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(Display.dp(context, 18), 0, Display.dp(context, 18), 0)
                setOnClickListener { switchTab(i) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Display.dp(context, 34)
            )
            lp.marginStart = Display.dp(context, 4)
            lp.marginEnd = Display.dp(context, 4)
            lp.gravity = Gravity.CENTER_VERTICAL
            tabRow.addView(tv, lp)
            tabViews.add(tv)
        }


        // tab 行右侧提示位（与标题同一行对齐）
        tabRow.addView(tabSnack, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
            gravity = Gravity.END
        })

        // 两个 GridView 叠放：各自建好、切组只切可见性 —— 切换不重建视图，不卡也不掉图
        val gridHost = FrameLayout(context)
        root.addView(gridHost, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        gridHost.addView(grid, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        gridHost.addView(pinnedGrid, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        configureGrid(grid, adapter)
        configureGrid(pinnedGrid, pinnedAdapter)
        pinnedGrid.visibility = GONE

        // 底部提示
        hint.gravity = Gravity.CENTER
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        hint.setTextColor(if (dark) 0x99FFFFFF.toInt() else 0x99000000.toInt())
        root.addView(hint, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Display.dp(context, HINT_HEIGHT_DP)))

        // 右侧字母列：自绘（只画文字），位置固定不依赖子 View 布局
    }

    // ---------------- 对外接口 ----------------

    fun prepare(
        recent: List<LaunchableApp>,
        pinned: List<LaunchableApp>,
        widthPx: Int,
        heightPx: Int,
        columns: Int,
        keepTab: Boolean = false
    ) {
        recentApps = recent
        pinnedApps = pinned
        if (!keepTab) tab = TAB_RECENT
        this.columns = columns.coerceIn(3, 6)
        viewHeight = heightPx
        this.cellWidth = (widthPx / this.columns)
        grid.numColumns = this.columns
        applyTabStyles()
        refresh()
        // 常驻预热：本次列表全部图标一次性装进缓存，滑动不再触发加载
        AppRepository.preloadIcons(context, recent + pinned)
    }

    fun updatePinned(pinned: List<LaunchableApp>) {
        pinnedApps = pinned
        if (tab == TAB_PINNED) refresh()
    }

    /** 收起后释放：清空 adapter，避免大量 item/Drawable 常驻 SystemUI 内存 */
    fun release() {
        adapter.submit(emptyList())
    }

    fun count(): Int = currentApps().size

    fun hoveredApp(): LaunchableApp? = null

    fun armInputDelay(ms: Long) {
        readyAt = SystemClock.uptimeMillis() + ms
    }

    private fun currentApps(): List<LaunchableApp> =
        if (tab == TAB_PINNED) pinnedApps else recentApps

    private fun switchTab(index: Int) {
        if (index == tab) return
        val dir = if (index > tab) 1 else -1
        tab = index
        applyTabStyles()
        refresh()
        bounceGrid(if (tab == TAB_PINNED) pinnedGrid else grid, dir)
        XLog.i("浮层分组切换：$index（${currentApps().size} 个）")
    }

    /** 切组回弹：横向过冲后归位，观感与列表上下 overscroll 一致 */
    private fun bounceGrid(g: GridView, dir: Int) {
        val shift = Display.dp(context, 26).toFloat()
        g.animate().cancel()
        g.translationX = dir * shift
        g.alpha = 0.55f
        g.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(260)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.35f))
            .start()
    }

    private fun applyTabStyles() {
        for ((i, tv) in tabViews.withIndex()) {
            val active = i == tab
            tv.setTextColor(if (active) primaryColor else onSurfaceColor and 0x00FFFFFF or 0x99000000.toInt())
            tv.background = GradientDrawable().apply {
                cornerRadius = Display.dp(context, 17).toFloat()
                setColor(if (active) (primaryColor and 0x00FFFFFF) or 0x33000000 else Color.TRANSPARENT)
            }
        }
    }

    /** 跟随系统日夜切换：重设底色/文字/标签样式 */
    private fun applyTheme() {
        setBackgroundColor(if (dark) 0xFF1F1D22.toInt() else 0xFFFFFFFF.toInt())
        if (tabViews.isNotEmpty()) applyTabStyles()
        adapter.notifyDataSetChanged()
        pinnedAdapter.notifyDataSetChanged()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val nowDark = isNight(newConfig)
        if (nowDark == dark) return
        dark = nowDark
        applyTheme()
        hint.setTextColor(if (dark) 0x99FFFFFF.toInt() else 0x99000000.toInt())
        XLog.i("浮层主题跟随系统：${if (nowDark) "暗色" else "亮色"}")
    }

    private fun configureGrid(g: GridView, ad: AppAdapter) {
        g.numColumns = columns
        g.isVerticalScrollBarEnabled = false
        g.setScrollingCacheEnabled(true)
        g.isVerticalFadingEdgeEnabled = false
        g.overScrollMode = View.OVER_SCROLL_ALWAYS
        g.isSmoothScrollbarEnabled = true
        g.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        g.horizontalSpacing = 0
        g.verticalSpacing = 0
        g.adapter = ad
        g.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView?, state: Int) {
                scrolling = state != android.widget.AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                if (!scrolling) ad.notifyDataSetChanged()
            }
            override fun onScroll(view: android.widget.AbsListView?, first: Int, visible: Int, total: Int) {}
        })
        g.isLongClickable = false
        g.setOnItemClickListener { _, _, position, _ ->
            if (lpFired) { lpFired = false; return@setOnItemClickListener }
            val app = ad.getItem(position) ?: return@setOnItemClickListener
            onPick?.invoke(app)
        }
    }

    private fun refresh() {
        adapter.submit(recentApps)
        pinnedAdapter.submit(pinnedApps)
        val showPinned = tab == TAB_PINNED
        grid.visibility = if (showPinned) GONE else VISIBLE
        pinnedGrid.visibility = if (showPinned) VISIBLE else GONE
        val list = currentApps()
        hint.text = if (list.isEmpty()) {
            if (tab == TAB_PINNED) "长按图标可加入收藏" else "暂无应用"
        } else {
            "${list.size} 个应用 · 点图标打开" + if (tab == TAB_PINNED) " · 长按取消收藏" else " · 长按收藏"
        }
    }


    /** 提示：显示在 tab 行右侧（与标题同排对齐），1.4s 后淡出 */
    fun showSnack(text: String) {
        try {
            tabSnack.setTextColor(if (dark) 0xCCFFFFFF.toInt() else 0xCC000000.toInt())
            tabSnack.text = text
            tabSnack.alpha = 1f
            tabSnack.visibility = VISIBLE
            snackHide?.let { removeCallbacks(it) }
            val hide = Runnable {
                tabSnack.animate().alpha(0f).setDuration(200).withEndAction {
                    tabSnack.visibility = INVISIBLE
                    tabSnack.text = ""
                }.start()
            }
            snackHide = hide
            postDelayed(hide, 1400)
        } catch (t: Throwable) {
            XLog.w("提示失败：${t.javaClass.simpleName}")
        }
    }

    private fun placeholderOf(holder: Cell): android.graphics.drawable.Drawable =
        holder.placeholder

    private class Cell(val icon: ImageView, val label: TextView, val placeholder: GradientDrawable) {
        var bound: android.content.ComponentName? = null
        var app: LaunchableApp? = null
    }

    private inner class AppAdapter : BaseAdapter() {
        private var items: List<LaunchableApp> = emptyList()

        fun submit(list: List<LaunchableApp>) {
            if (items === list) return
            items = list
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): LaunchableApp? = items.getOrNull(position)
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell: LinearLayout
            val holder: Cell
            if (convertView is LinearLayout && convertView.tag is Cell) {
                cell = convertView
                holder = convertView.tag as Cell
            } else {
                cell = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = android.widget.AbsListView.LayoutParams(
                        cellWidth.coerceAtLeast(1), rowHeight
                    )
                }
                val placeholder = GradientDrawable().apply {
                    cornerRadius = Display.dp(context, 12).toFloat()
                    setColor(if (dark) 0x1FFFFFFF else 0x14000000)
                }
                val icon = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        Display.dp(context, 46), Display.dp(context, 46)
                    )
                    setImageDrawable(placeholder)
                }
                val label = TextView(context).apply {
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = Display.dp(context, 8)
                lp.marginStart = Display.dp(context, 6)
                lp.marginEnd = Display.dp(context, 6)
                cell.addView(icon)
                cell.addView(label, lp)
                holder = Cell(icon, label, placeholder)
                cell.tag = holder
            }

            val app = items.getOrNull(position) ?: return cell
            if (holder.label.text != app.label) {
                holder.label.setTextColor(if (dark) 0xD9FFFFFF.toInt() else 0xD9000000.toInt())
                holder.label.text = app.label
            }
            // 图标：绑定不同应用、或当前视图根本没有图（切组/回收后）都要重设
            if (holder.bound != app.component || holder.icon.drawable == null) {
                val cached = AppRepository.iconCached(context, app)
                    ?: if (!scrolling) AppRepository.iconSync(context, app) else null
                if (cached != null) {
                    holder.bound = app.component
                    holder.icon.setImageDrawable(cached)
                } else {
                    // 滚动中不加载；bound 置空，停下后必然重试
                    holder.bound = null
                    holder.icon.setImageDrawable(placeholderOf(holder))
                }
            }
            holder.app = app
            return cell
        }
    }

    private fun curGrid(): GridView = if (tab == TAB_PINNED) pinnedGrid else grid

    /** 进入拉动：取消长按、打断列表自身的触摸（避免它同时滚/发光） */
    private fun beginPull(ev: MotionEvent) {
        pulling = true
        cancelLongPressTimer()
        pullAnim?.cancel()
        try {
            val cancel = MotionEvent.obtain(ev)
            cancel.action = MotionEvent.ACTION_CANCEL
            curGrid().dispatchTouchEvent(cancel)
            cancel.recycle()
        } catch (t: Throwable) {
        }
    }

    /** 松手回弹：从当前位移以 ease-out 弹回原位（与系统 overscroll 手感一致） */
    private fun springBackToRest() {
        pullAnim?.cancel()
        val from = translationY
        if (abs(from) < 1f) {
            translationY = 0f
            return
        }
        pullAnim = android.animation.ValueAnimator.ofFloat(from, 0f).apply {
            duration = SPRING_MS
            interpolator = android.view.animation.PathInterpolator(0.16f, 0.9f, 0.22f, 1f)
            addUpdateListener { translationY = it.animatedValue as Float }
            start()
        }
    }

    // ---------------- 触摸（点空白关闭） ----------------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            if (SystemClock.uptimeMillis() >= readyAt) {
                XLog.i("浮层外触摸，关闭")
                onDismiss?.invoke()
            }
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.x
                swipeDownY = ev.y
                swipingGroup = false
                downAt = SystemClock.uptimeMillis()
                startLongPressTimer(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swipingGroup) {
                    val dx = ev.x - swipeDownX
                    val dy = ev.y - swipeDownY
                    if (abs(dx) > groupSlop || abs(dy) > groupSlop) cancelLongPressTimer()
                    if (abs(dx) > groupSlop && abs(dx) > abs(dy) * 1.2f) {
                        swipingGroup = true
                        groupSwitched = false
                        // 立刻把当前手势 cancel 给 GridView，否则它会把这次横滑当点击
                        try {
                            val cancel = MotionEvent.obtain(ev)
                            cancel.action = MotionEvent.ACTION_CANCEL
                            grid.dispatchTouchEvent(cancel)
                            cancel.recycle()
                        } catch (t: Throwable) {
                        }
                    }
                }
                if (swipingGroup) {
                    val dx = ev.x - swipeDownX
                    // 超过阈值就在 MOVE 阶段完成切换（避免 UP 再触发子视图点击）
                    if (!groupSwitched && abs(dx) > groupSlop * 3) {
                        groupSwitched = true
                        val target = if (dx < 0) tab + 1 else tab - 1
                        if (target in 0..1) switchTab(target)
                    }
                    return true
                }
                // 纵向拉动：列表已在顶部，手指继续下拉 → 面板跟手（阻尼），松手回弹
                if (!pulling && ev.y - swipeDownY > groupSlop && !curGrid().canScrollVertically(-1)) {
                    beginPull(ev)
                }
                if (pulling) {
                    translationY = (ev.y - swipeDownY).coerceAtLeast(0f) * PULL_DAMPING
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                cancelLongPressTimer()
                if (pulling) {
                    pulling = false
                    if (translationY > height * PULL_DISMISS_FRACTION) {
                        XLog.i("浮层下拉超过阈值，收起")
                        onDismiss?.invoke()
                    } else {
                        springBackToRest()
                    }
                    return true
                }
                if (swipingGroup) {
                    swipingGroup = false
                    // 未达阈值：无需处理（没有位移）
                    groupSwitched = false
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelLongPressTimer()
                if (pulling) {
                    pulling = false
                    springBackToRest()
                    return true
                }
                swipingGroup = false
                grid.animate().translationX(0f).setDuration(160L).start()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = event.x
                swipeDownY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (SystemClock.uptimeMillis() < readyAt) return true
                val dx = event.x - swipeDownX
                if (abs(dx) > groupSlop * 2) {
                    // 左滑 → 下一个分组；右滑 → 上一个
                    val target = if (dx < 0) tab + 1 else tab - 1
                    if (target in 0..1) switchTab(target)
                } else {
                    onDismiss?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }
}
