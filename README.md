# 小横条增强

ColorOS 手势小横条与浮窗的增强模块（LSPosed）。

## 功能

**底栏浮窗** —— 屏蔽系统小横条「左右滑动切换最近应用」，改为在底部呼出应用浮层

- 两组页签：最近使用 / 固定应用
- 长按图标 1 秒收藏或取消收藏
- 可调网格列数、图标大小、振动、沉浸模式等

**小窗点外关闭** —— 小窗在默认大小下，点击窗外即关闭

- 关闭时不会误触下层应用（防穿透）
- 缩小小窗后不关闭；键盘弹出时不误关；边缘返回手势不受影响

## 环境

- ColorOS / Android 12 及以上
- LSPosed（LibXposed API 102）
- 作用域：系统界面 `com.android.systemui`、系统 `system`

## 使用

1. 安装后在 LSPosed 中启用模块，勾选上述两个作用域
2. 改系统界面侧：重启 SystemUI；改系统侧：重启框架

## 配置

配置写在 `Settings.Secure["eta_sideindex_config"]`（JSON），主要键：
`enabled`、`trigger_side`、`trigger_width_dp`、`sensitivity`、`bar_*`（外观）、
`vibrate_*`、`icon_size_dp`、`grid_columns`、`favorites`、`immersive`、`log_verbose`。

## 构建

```
sh tools/build.sh            # aapt2 + javac + kotlinc + R8
python3 tools/package.py     # 打包（需 BUILD_DIR / XPOSED_DIR）
sh tools/sign.sh             # 签名
```

版本号在 `app/src/main/xposed/module.prop` 维护。
