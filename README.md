# 小横条增强

LSPosed 模块，运行于 `com.android.systemui` 与 `system`（system_server）两个进程，实现两件互不依赖的事：

1. **底栏浮窗**：接管底部导航小横条的左右滑，不再切换上一个应用，改为呼出应用浮层（最近 / 固定）
2. **小窗点外关闭**：ColorOS 小窗在默认大小下点击窗外即关闭，并阻止这次点击落到下层应用

本文只讲技术要点与实现逻辑，不含版本与提交历史。

---

## 1. 进程与代码结构

```
com.android.systemui                        system（system_server）
ModuleEntry(SystemUI 分支)                  ModuleEntry(isSystemServer 分支)
└─ SystemUiHost                             └─ WmProbe.bootstrap()
    └─ OverlayController                        ├─ classLoader 定位
        ├─ BackGestureGate（手势旁听 + pilfer）  ├─ hook handleTapOutsideTask
        ├─ RecentsPanelView（浮层 UI）           ├─ hook ScaleManager 手势方法
        └─ AppRepository（图标 / 收藏）          └─ 判定 → exitFlexibleTask + cancelCurrentTouch
```

```
app/src/main/java/com/eta/sideindexx/
├── ModuleEntry.kt          LibXposed 入口，两条分支
├── config/                 配置读取与模型（Secure → XSharedPreferences → Provider → XML）
├── overlay/                功能一（SystemUI）
├── wm/WmProbe.kt           功能二（system_server）
└── util/                   XLog / Xp（LibXposed→老 API 适配）/ Display / Haptics
app/src/main/xposed/        module.prop、java_init.list、scope.list、xposed_module
```

入口（`ModuleEntry`）：

- `onModuleLoaded`：挂上框架实例；若 `param.isSystemServer` 为真，立刻启动小窗探测
  （热挂载场景下 `onPackageLoaded("android")` 在 system_server **不会**再回调，必须走这条）
- `onPackageLoaded`：`pkg == "android"` 时用 `/proc/self/cmdline == "system"` 门控再用 `defaultClassLoader` 兜底启动；
  `pkg == "com.android.systemui"` 时 hook `Application.onCreate` 之后初始化浮层
- `onPackageReady`：SystemUI 已被拉起时的补挂路径；`activate()` 有幂等锁，避免建出两套浮层

---

## 2. 功能一：底栏浮窗

### 2.1 为什么是「旁听 + pilfer」

自建一条可触摸的底部感应带会带来两个硬伤：

1. 它成为该区域的**触摸目标** → 系统返回手势把这次边缘滑动当成"普通窗口上的滑动"，手势被吃掉
2. 窗口吞掉 DOWN 后，桌面自己的翻页手势拿不到手指

且 ColorOS 上 `systemGestureExclusionRects` 对 overlay 窗口**不生效**。

所以：

- 感应带窗口**始终不可触摸**，只用于换算屏幕坐标与告知输入监听器范围
- 模块在 SystemUI 的手势监视器上**旁听**整屏触摸：
  `com.android.systemui.shared.system.InputChannelCompat$InputEventReceiver$1`、
  ColorOS 的 `DisplayBackGestureHandlerImpl$inputEventReceiver$1`
- 判定成立 → `pilferPointers` 抢走指针，系统 quick-switch 会话收到 cancel 便不再切应用 → 呼出浮层

要点：

- 旁听**绝不能吞事件**（`finishInputEvent` 传 true/false 都会导致窗口彻底收不到这次触摸：返回失效、桌面不翻页）
- 回调运行在 SystemUI 的 "GestureUI" 线程，所有 UI 操作必须 `post` 回主线程
- `pilfer()` 不能直接对 `android.view.InputMonitor` 调 `pilferPointers`（会抛 `NoSuchMethodError`），
  需要遍历候选对象逐层下钻，找可调用的 `pilferPointers` / `pilfer`

### 2.2 手势判定（`OverlayController`）

状态机 `IDLE → PENDING → ACTIVE`：

| 常量 | 值 | 作用 |
|---|---|---|
| `BAND_WIDTH_PERCENT` / `BAND_HEIGHT_DP` | 60% / 30 dp | 底部感应带几何 |
| `HORIZONTAL_SLOP_DP` | 22 dp | 横向位移超过 → `pilfer()` + `showPanel()` |
| `VERTICAL_GIVEUP_DP` | 26 dp | 纵向位移超过且横向不足 → 让给系统（回桌面 / 最近任务） |
| `OPEN_MS` / `CLOSE_MS` | 220 / 150 ms | 入场 / 出场 |
| `OPEN_SCALE_FROM` / `OPEN_SPRING_MS` / `OPEN_OVERSHOOT` | 0.90 / 340 ms / 2.2 | 入场回弹 |
| `TOUCHABLE_DELAY_MS` | 260 ms | 呼出后延迟可触摸，防误触 |
| `PANEL_MAX_HEIGHT_DP` | 386 dp | 浮层最大高度 |

同一 `MotionEvent` 会被两个手势监视器各送一次，按 `downTime:action:x:y` 去重，只处理第一份。

**入场回弹的实现坑**：`translationY` 的过冲方向朝上，而浮层窗口高度等于面板高度，超出部分被窗口裁掉，
看不出来。因此位移用 decelerate 滑上来，**缩放**用 `ObjectAnimator` + `OvershootInterpolator` 从 0.90 胀回 1.0
（以底边为轴心），视觉上"胀一下再回落"。

### 2.3 浮层 UI（`RecentsPanelView`）

- 真实 View 树：两个 `GridView` + `BaseAdapter` 复用 + `ImageView` 画 Drawable，不逐帧自绘
- 页签：`TAB_RECENT` / `TAB_PINNED`；tab 高 46 dp、提示行 28 dp
- 长按 `LONG_PRESS_MS = 1000` 才收藏/取消（防误触），震动跟随系统强度，提示显示在 tab 行右侧
- 横向滑动切页：`groupSlop = 16 dp`，超过 3 倍 slop 立即完成切换（避免 UP 再触发子视图点击）
- 纵向回弹：列表已在顶部（`canScrollVertically(-1) == false`）时继续下拉 →
  `translationY = 位移 × 0.38` 跟手；松手 > 面板高 16% 收起，否则 320 ms `PathInterpolator(0.16, 0.9, 0.22, 1)` 回弹
- 图标：`LauncherApps.getBadgedIcon`，LRU 512；收藏持久化在 `eta_sideindex_star`

浮层窗口是 overlay（非 Activity），系统 stretch overscroll 不生效，所以列表边缘回弹是自实现的。

### 2.4 配置

唯一通道 `Settings.Secure["eta_sideindex_config"]`（JSON）。读取优先级：
`Settings.Secure` → `XSharedPreferences` → 模块 `ContentProvider` → 直读 SharedPreferences XML。

主要键：`enabled`、`log_verbose`、`trigger_side`(right/left/both)、`both_sides`、`allow_system_back`、`only_launcher`、
`trigger_width_dp`、`sensitivity`、`trigger_position_percent`、`bar_*`(外观)、`vibrate_*`、
`icon_size_dp`、`grid_columns`(4/5)、`favorites`、`immersive`、`freeform_launch`。
完整定义见 `config/ModuleConfig.kt`。

---

## 3. 功能二：小窗点外关闭 + 防穿透

### 3.1 为什么必须在 system_server

ColorOS 小窗是 `com.android.server.wm` 里的弹性窗口（task），实现位于 **/system/framework/oplus-services.jar**：
`FlexibleTaskController`、`FlexibleTaskScaleManager`、`FlexibleCaptionView`、`OplusZoomWindowManagerService`。
SystemUI 侧拿不到小窗状态与关闭接口。

### 3.2 classLoader 定位（第一个坑）

现象：探针在 system_server 里所有目标类报「缺少 XxxClass」。

真因不是 hook 时机早，而是 **loader 取错**：

- `/proc/<system_server>/environ` 的 `SYSTEMSERVERCLASSPATH` 从进程启动就含 `oplus-services.jar`，
  system_server 自己的 `PathClassLoader` 一开始就能看到这些类
- `ClassLoader.getSystemClassLoader()` 的父是 boot loader，只覆盖 `BOOTCLASSPATH`（framework.jar 等），
  **看不到** `services.jar` / `oplus-services.jar`
- 模块热挂载时 `onPackageLoaded("android")` 在 system_server 不再回调

实现：

```kotlin
// 取本地服务实例的 classLoader（在 system_server 内 getService 返回本地对象，不是代理）
for (svc in arrayOf("activity", "window", "package", "input")) {
    val sm = XposedHelpers.findClassIfExists("android.os.ServiceManager", null)
    val cl = XposedHelpers.callStaticMethod(sm, "getService", svc)?.javaClass?.classLoader
}
// 候选 loader（含各线程 TCCL 兜底）依次 findClass；后台线程 40 × 1.5s 轮询，冷启动也能等到
```
命中结果是 `dalvik.system.PathClassLoader[DexPathList[[… services.jar, oplus-services.jar …]]]`。

### 3.3 触发链与 hook 点

```
InputDispatcher → InputMonitor 旁听通道
  → FlexibleTaskController$TouchListener#onPointerEvent          ← 事件此时已派发给下层窗口
    → FlexibleTaskController#-$$Nest$mhandleTapOutsideTask
      → FlexibleTaskController#handleTapOutsideTask(dc, x, y)     ← hook 点（private，参数带坐标）
```

原生实现开头有 `shouldHandleHideTouch()` 门控（游戏 QuickFold 专用），普通场景直接 return，所以原生不做点外关闭。

### 3.4 五步判定链（顺序不可调换）

**① mini（折叠）态 → 交系统**

```kotlin
isTaskInMiniFlexible(task) == true → return
// 语义：isTaskInFlexibleScenario(task) && task.getWrapper().getExtImpl().getFlexibleZoomState() == 2
```
⚠ `isTaskOnFlexibleMinimized(Task)` 不是 mini 语义，其字节码是
`FlexibleWindowManagerService.getInstance(null).getFirstTaskId() == task.mTaskId`（是否全屏首个 task），恒为 false。

**② 用户刚手动缩放过 → 交系统**

两条证据，任一成立即视为"用户还要继续看"：

- **事件驱动**：hook `FlexibleTaskScaleManager` 中**参数含 `MotionEvent`** 的方法
  （`endMultiRatioScaleHandleInAnimHandler(MotionEvent, Task, int, float[], Point)`、
  `updateMultiRatioScaleFinalPosInAnimHandler(MotionEvent, Task, float, Point, int)` 等），
  命中后用 `WeakHashMap<task, timestamp>` 记录，30 秒内有效
- **面积兜底**：`WeakHashMap<task, maxArea>`，当前可见面积 < 该 task 见过的最大面积 97%

⚠ 三个已踩过的坑：

- 不能按「参数含 `Task`」筛方法：`resetFlexibleTaskScaleState`（关窗重置）、
  `startSameRatioScaleSpringAnimation`（打开/布局动画）都带 Task → 开关窗被误判为用户缩放 → 第二次打开就关不掉
- 不能用 `getFlexibleTaskCurrRatio(task)`：本机固定返回 `1.7775158`，默认态与缩放态完全相同，不是窗口比例
- 不能用 `taskId` 记面积：`taskId` 会被系统复用、不同 app 默认尺寸不同（实测 1848240 vs 2167152）→ 基准必然失准

**③ 点在窗内 → 不干预**

```kotlin
inside = hasFlexibleTaskInTouchableRegion(x, y) || vis.contains(x, y)
```
`vis` 取系统 `isInTouchableRegion` 的口径：`TaskExtImpl.getFlexibleTaskPosAfterIme()` 非空则用它，
否则 `FlexibleTaskController.getFlexibleTaskVisibleBounds(task)`。

⚠ **绝不能用 `task.getBounds()`**：它返回接近全屏的 task bounds（实测 `Rect(126,329-1398,2590)`，
真实小窗是 `Rect(126,329-1146,2141)`），`contains()` 恒真 → 表现为"点小窗内部也关闭"或"完全关不掉"。

**④ 键盘区 → 不干预**

```kotlin
val ime = fieldOf(dc, "mInputMethodWindow")       // 独立 WindowState，坐标在小窗之外
ime.isVisible() && ime.getFrame().contains(x, y)  // → 键盘上，不关
```

**⑤ 关闭 + 防穿透**

```kotlin
cancelCurrentTouch(ctl)                              // 先撤当前手势
invoke(ctl, "exitFlexibleTask", task, false, 0, 0)   // 再关窗
param.returnEarly = true                             // 不跑原生 QuickFold 逻辑
```

### 3.5 关闭 API

```
FlexibleTaskController.exitFlexibleTask(Task task, boolean exitFreeFormStack,
                                        int flexibleCustomTransition, int caller)
```
内部（日志串 + 指令流实证）：

- `getFlexibleInfoManager(task).setTaskState(4)` / `setToBack(...)`
- `taskExtImpl.addFlexibleCustomTransitionType(flexibleCustomTransition)`
- `getFlexibleInfoManager(task).setAnimTransitionTriggerSource(caller)`
- 末尾 `task.moveTaskToBack(task)` ← 上滑关闭的收敛点

前置：`FlexibleWindowUtils.isFlexibleTaskAndHasCaption(task) == false` 时直接 return。
现有调用者是 `AbsFlexibleTaskExitStrategy` 子类（ToBack / ToFloat transition=16 / ToSplit transition=128）；
模块传 `(task, false, 0, 0)` 实测可用。

### 3.6 防穿透

穿透成因：点外时系统按窗口层级把事件派给下层应用；`TouchListener` 只是旁听，此时 DOWN 已到应用。

不需要阻止"派发"，只需让这次点击无效 —— 取消整条手势：

```kotlin
// controller.mAtms.mWindowManager.mInputManager.cancelCurrentTouch()
// InputManagerService.cancelCurrentTouch() -> V
```
触摸焦点窗口收到 `ACTION_CANCEL`，没有 `UP` 就不会触发点击。实测边缘返回、状态栏下拉不受影响。

已验证不可行的方案：

| 方案 | 为什么不行 |
|---|---|
| 扩 `FlexibleCaptionView.updateTouchableRegion(Region)` 到全屏挖洞 | 只调 `FlexibleTaskExtraViewManager.setTouchRegion(region)`；`initCaptionViewAndAttachToTask` 里 `setView(task.getSurfaceControl(), captionView, lp, region, bundle)` 说明 extra surface 是小窗 task surface 的**子 surface**，region 超出部分被裁剪 |
| `interceptMotionBeforeQueueingNonInteractive(displayId, source, action, when, policyFlags)` 清 `PASS_TO_USER` | 签名**不含坐标**，无法判断点内外 |
| WMS `InputMonitor.pilferPointers()` | 该类 46 个方法里没有 pilfer；pilfer 只在 `InputManagerService.pilferPointers(IBinder token)` / `InputMonitorHost`，需要 monitor channel token |

### 3.7 hook 面

只挂三类，避免在 system_server 留大量拦截点：

1. `FlexibleTaskController#handleTapOutsideTask`（功能本体）
2. `FlexibleTaskScaleManager` 中参数含 `MotionEvent` 的方法（缩放标记）
3. 无其它日志型 hook；常规分支日志为 `XLog.d`，受 `log_verbose` 门控，调用栈只在 verbose 时构造

---

## 4. 构建

```
aapt2 compile/link → javac(生成的 R) → kotlinc → R8 → 打包(zip + resources.arsc 对齐) → apksigner
```

```sh
ENV_ROOT=/path/to/env sh tools/build.sh                 # 编译 + R8
BUILD_DIR=$PWD/build XPOSED_DIR=$PWD/app/src/main/xposed python3 tools/package.py
ENV_ROOT=/path/to/env sh tools/sign.sh                  # 产物 out/XiaoHengTiao-<version>.apk
```

要点：

- 版本号**唯一来源**是 `app/src/main/xposed/module.prop`（`version` / `versionCode`），脚本自动读入 manifest 与产物名
- `AAPT2` 默认取 PATH 里的 `aapt2`（chroot 里 SDK 自带的是 ELF，可能与宿主 libc 不匹配而无法执行）
- `apksigner` 跑不起来时用 `java -jar $ENV_ROOT/android-sdk/build-tools/36.0.0/lib/apksigner.jar`
- 必须用包内 `release.keystore`（alias `sideindex`，store/key 密码均 `sideindex`），换钥匙会导致覆盖安装失败
- `resources.arsc` 必须 4 字节对齐，`tools/package.py` 会按需重打包补 padding

## 5. 排障

日志 tag `EtaSideIndex`；`XLog.d` 受配置 `log_verbose` 门控。

| 关键字 | 含义 |
|---|---|
| `命中 loader=dalvik.system.PathClassLoader[...]` | system_server 侧 loader 定位成功（没有这行说明模块未注入或没走 SystemServer 分支） |
| `已挂点外单击→关闭` | 点外 hook 已挂上 |
| `FlexibleTaskController 挂 N 个方法` / `FlexibleTaskScaleManager 挂 N 个方法` | hook 统计，应为个位数 |
| `缩放标记生效来源 FlexibleTaskScaleManager#<带 MotionEvent 的方法>` | 用户缩放手势已被捕获 |
| `点(x,y) 窗外 vis=Rect(...) → 关闭` | 判定为点外并关闭 |
| `已取消当前触摸手势（防穿透）` | `cancelCurrentTouch` 生效 |
| `拿不到 mInputManager，防穿透跳过` | IMS 反射路径变化，需要按新字段名调整 |
| `浮层已呼出：最近 N / 常用 M` | 浮层数据加载成功 |
