package moe.lyniko.hiderecent

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.view.View
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import moe.lyniko.hiderecent.utils.PreferenceUtils

class MainHook : IXposedHookLoadPackage {

    private lateinit var packages: Set<String>

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName == "android" || lpparam.packageName == "com.android.systemui") {
            loadConfig()
        }

        when (lpparam.packageName) {
            "android" -> {
                // Android 16 不在服务端删数据，防黑屏
                if (Build.VERSION.SDK_INT < 36) {
                    hookLegacyVisibility(lpparam)
                }
            }
            "com.android.systemui" -> {
                hookSystemUISafe(lpparam)
            }
        }
    }

    /**
     * 核心：带双重保险的 SystemUI Hook
     */
    private fun hookSystemUISafe(lpparam: LoadPackageParam) {
        var taskViewHooked = false

        // ==========================================
        // 方案 A：尝试精准隐形 TaskView (首选)
        // ==========================================
        try {
            // 防弹级字符串拼接：防止任何复制粘贴/编码把 "." 变成 "$"
            val className = String(charArrayOf(
                'c','o','m','.','a','n','d','r','o','i','d','.',
                's','y','s','t','e','m','u','i','.',
                'r','e','c','e','n','t','s','.',
                'v','i','e','w','s','.',
                'T','a','s','k','V','i','e','w'
            ))
            
            val taskViewClass = XposedHelpers.findClass(className, lpparam.classLoader)

            XposedBridge.hookAllMethods(taskViewClass, "bind", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (packages.isEmpty()) return
                    val taskView = param.thisObject as View
                    val taskInfo = param.args.firstOrNull() ?: return
                    val pkgName = extractPackageNameFromTaskInfo(taskInfo)

                    if (pkgName != null && packages.contains(pkgName)) {
                        XposedBridge.log("[HideRecent] TaskView bind intercepted: $pkgName. Hiding...")
                        taskView.alpha = 0f
                        taskView.isClickable = false
                        taskView.visibility = View.INVISIBLE
                    }
                }
            })
            taskViewHooked = true
            XposedBridge.log("[HideRecent] ✅ TaskView hooked successfully!")
        } catch (t: Throwable) {
            XposedBridge.log("[HideRecent] ⚠️ TaskView not found: ${t.message}")
        }

        // ==========================================
        // 方案 B：兜底策略 - 阉割 TaskInfo 数据
        // 如果上面的 UI 类找不到，就在数据源头把包名清空
        // 效果：卡片变成空白占位符（不黑屏，但能隐藏信息）
        // ==========================================
        if (!taskViewHooked) {
            XposedBridge.log("[HideRecent] Falling back to TaskInfo data stripping...")
            try {
                val taskInfoClass = XposedHelpers.findClass("android.app.TaskInfo", lpparam.classLoader)

                // 拦截获取 Intent 的请求
                XposedBridge.hookAllMethods(taskInfoClass, "getBaseIntent", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val intent = param.result as? Intent ?: return
                        val pkg = intent.component?.packageName ?: return
                        if (packages.contains(pkg)) {
                            // 把包名清空，SystemUI 就无法加载图标和标题
                            intent.component = ComponentName("", "")
                            XposedBridge.log("[HideRecent] Stripped baseIntent for: $pkg")
                        }
                    }
                })

                // 拦截获取顶部 Activity 的请求
                XposedBridge.hookAllMethods(taskInfoClass, "getTopActivity", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val comp = param.result as? ComponentName ?: return
                        if (packages.contains(comp.packageName)) {
                            param.result = ComponentName("", "")
                            XposedBridge.log("[HideRecent] Stripped topActivity for: ${comp.packageName}")
                        }
                    }
                })
                XposedBridge.log("[HideRecent] ✅ Fallback TaskInfo hook installed!")
            } catch (t: Throwable) {
                XposedBridge.log("[HideRecent] ❌ Fallback failed: ${t.message}")
            }
        }
    }

    /**
     * 旧版 Android 兼容方案
     */
    private fun hookLegacyVisibility(lpparam: LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.RecentTasks",
                lpparam.classLoader,
                "isVisibleRecentTask",
                XposedHelpers.findClass("com.android.server.wm.Task", lpparam.classLoader),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val intent = XposedHelpers.callMethod(param.args[0], "getBaseIntent") as? Intent
                            val pkg = intent?.component?.packageName ?: return
                            if (packages.contains(pkg)) param.result = false
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    // --- 以下为辅助方法 ---

    private fun loadConfig() {
        try {
            val xsp = de.robv.android.xposed.XSharedPreferences(BuildConfig.APPLICATION_ID, PreferenceUtils.functionalConfigName)
            xsp.makeWorldReadable()
            packages = PreferenceUtils.getPackageListFromPref(xsp)
        } catch (e: Throwable) {
            packages = emptySet()
        }
    }

    private fun extractPackageNameFromTaskInfo(taskInfo: Any): String? {
        try {
            val intent = XposedHelpers.callMethod(taskInfo, "getBaseIntent") as? Intent
            val pkg = intent?.component?.packageName
            if (!pkg.isNullOrEmpty()) return pkg
        } catch (_: Throwable) {}
        try {
            val topActivity = XposedHelpers.getObjectField(taskInfo, "topActivity") as? ComponentName
            val pkg = topActivity?.packageName
            if (!pkg.isNullOrEmpty()) return pkg
        } catch (_: Throwable) {}
        return null
    }
}
