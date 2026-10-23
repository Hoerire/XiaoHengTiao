# 模块入口与整体代码保留（大量反射与类名匹配）
-keep class com.eta.sideindexx.ModuleEntry { *; }
-keep class com.eta.sideindexx.** { *; }
-keepclassmembers class com.eta.sideindexx.** { *; }

# Xposed / LSPosed API
-keep class de.robv.android.xposed.** { *; }
-keepclassmembers class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**

# 反射用到的系统类
-keep class android.app.ActivityOptions { *; }
-keep class android.app.ActivityTaskManager { *; }
-keep class android.app.usage.UsageStatsManager { *; }
-dontwarn android.app.**

# 自绘 View
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
}

-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, SourceFile, LineNumberTable
-dontwarn kotlin.**
