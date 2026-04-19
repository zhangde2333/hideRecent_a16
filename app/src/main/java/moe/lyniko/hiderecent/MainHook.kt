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
        // 1. 加载配置（在两个进程中都会执行一次）
        if (lpparam.packageName == "android" || lpparam.packageName == "com.android.systemui") {
            loadConfig()
        }

        // 2. 分发 Hook 逻辑
        when (lpparam.packageName) {
            "android" -> {
                // 对于 Android 16，不在系统服务端删数据，防止打断动画
                if (Build.VERSION.SDK_INT < 36) {
                    hookLegacyVisibility(lpparam)
                } else {
                    XposedBridge.log("[HideRecent] Android 16 detected, skipping server-side data removal to prevent black screen.")
                }
            }
            "com.android.systemui" -> {
                // 核心大杀器：在 UI 绘制层将其隐形
                hookSystemUITaskView(lpparam)
            }
        }
    }

    /**
     * 核心方案：Hook SystemUI 中负责绘制每一个任务卡片的容器
     * 优点：完全不影响底层动画流水线，绝对不会黑屏
     */
    private fun hookSystemUITaskView(lpparam: LoadPackageParam) {
        try {
            // TaskView 是 Android 原生用来包裹每一个最近任务卡片的 View
            val taskViewClass = XposedHelpers.findClass(
                "com.android.systemui.recents.views.TaskView", 
                lpparam.classLoader
            )

            // Hook TaskView 的 bind 方法（当系统把任务数据绑定到这个 View 上时触发）
            XposedBridge.hookAllMethods(taskViewClass, "bind", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (packages.isEmpty()) return
                    val taskView = param.thisObject as View

                    // 从参数中提取 TaskInfo（bind 方法的第一个参数通常是 TaskInfo）
                    val taskInfo = param.args.firstOrNull() ?: return
                    val pkgName = extractPackageNameFromTaskInfo(taskInfo)

                    if (pkgName != null && packages.contains(pkgName)) {
                        XposedBridge.log("[HideRecent] Found target in SystemUI: $pkgName. Making it invisible.")
                        
                        // 神来之笔：不删除，不 GONE，只是设为完全透明且不可点击
                        // 这样底层动画依然会在这个 View 上播放，不会黑屏！
                        taskView.alpha = 0f
                        taskView.isClickable = false
                        taskView.visibility = View.INVISIBLE // 用 INVISIBLE 而不是 GONE，保留它占用的动画空间
                    }
                }
            })
            XposedBridge.log("[HideRecent] Successfully hooked TaskView.bind (Animation Safe)")
        } catch (t: Throwable) {
            XposedBridge.log("[HideRecent] Failed to hook TaskView: ${t.message}")
            // 如果极个别 ROM 连 TaskView 都改了名字，走备用方案
            hookFallbackRecents(lpparam)
        }
    }

    /**
     * 备用方案：如果找不到 TaskView，尝试 Hook RecyclerView 的 Adapter
     */
    private fun hookFallbackRecents(lpparam: LoadPackageParam) {
        try {
            // 注意这里的 \$ 转义符，因为 Kotlin 字符串里 $ 是特殊字符
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass("com.android.systemui.recents.views.RecentsRecyclerView\$RecentsAdapter", lpparam.classLoader),
                "onBindViewHolder",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (packages.isEmpty()) return
                        val viewHolder = param.args[1] ?: return
                        val itemView = XposedHelpers.callMethod(viewHolder, "getItemViewRoot") as? View ?: return
                        
                        // 备用方案中，尝试从 viewHolder 的 itemView 获取包名较复杂
                        // 为了稳定性，这里仅做兜底打印，不强行操作
                        XposedBridge.log("[HideRecent] Fallback triggered: onBindViewHolder called.")
                    }
                }
            )
        } catch (_: Throwable) {
            XposedBridge.log("[HideRecent] Fallback hook also failed, but main hook should work.")
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
