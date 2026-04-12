package moe.lyniko.hiderecent

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ParceledListSlice
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import moe.lyniko.hiderecent.utils.PreferenceUtils

class MainHook : IXposedHookLoadPackage {

    private lateinit var packages: Set<String>

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // 只在系统服务端生效，不需要管 SystemUI
        if (lpparam.packageName != "android") return

        // 1. 加载配置
        try {
            val xsp = de.robv.android.xposed.XSharedPreferences(BuildConfig.APPLICATION_ID, PreferenceUtils.functionalConfigName)
            xsp.makeWorldReadable()
            packages = PreferenceUtils.getPackageListFromPref(xsp)
            if (packages.isEmpty()) {
                XposedBridge.log("[HideRecent] Config is empty, skipping hook.")
                return
            }
        } catch (e: Throwable) {
            XposedBridge.log("[HideRecent] Failed to load config: ${e.message}")
            return
        }

        // 2. 根据版本分发
        if (Build.VERSION.SDK_INT >= 36) {
            hookAtmsForModernAndroid(lpparam)
        } else {
            hookLegacyVisibility(lpparam)
        }
    }

    /**
     * Android 16 专属方案：拦截 ATMS 返回的数据列表，进行物理删除
     */
    private fun hookAtmsForModernAndroid(lpparam: LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.ActivityTaskManagerService",
                lpparam.classLoader,
                "getRecentTasks",
                Int::class.javaPrimitiveType, // maxTasks
                Int::class.javaPrimitiveType, // flags
                Int::class.javaPrimitiveType, // userId
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rawResult = param.result ?: return

                        // ⚠️ 核心修复点：正确处理系统的返回值类型
                        val list: MutableList<*>? = when (rawResult) {
                            is ParceledListSlice<*> -> {
                                // Android 最常用的跨进程列表传输方式，调用 getList() 拿到底层可变集合
                                rawResult.getList()
                            }
                            is MutableList<*> -> {
                                // 如果碰巧是可变列表，直接用
                                rawResult
                            }
                            is List<*> -> {
                                // 如果是只读 List，无法直接删除，只能放弃（极少发生）
                                XposedBridge.log("[HideRecent] ⚠️ Result is read-only List, cannot filter.")
                                return
                            }
                            else -> return
                        }

                        if (list.isEmpty()) return

                        val iterator = list.iterator()
                        var removedCount = 0

                        while (iterator.hasNext()) {
                            val taskInfo = iterator.next() ?: continue
                            val pkgName = extractPackageNameFromTaskInfo(taskInfo)

                            if (pkgName != null && packages.contains(pkgName)) {
                                iterator.remove()
                                removedCount++
                                XposedBridge.log("[HideRecent] 🗑️ Removed: $pkgName")
                            }
                        }

                        if (removedCount > 0) {
                            XposedBridge.log("[HideRecent] Total removed $removedCount tasks from ATMS.")
                        }
                    }
                }
            )
            XposedBridge.log("[HideRecent] ✅ Successfully hooked ATMS.getRecentTasks (Data Filter Mode)")
        } catch (t: Throwable) {
            // 如果这里报错，说明你的 ROM 把参数改了，把日志发给我看
            XposedBridge.log("[HideRecent] ❌ ATMS hook failed: ${t.message}")
        }
    }

    /**
     * Android 15 及以下的旧方案（保留给你兼容旧机型用）
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
                            if (packages.contains(pkg)) {
                                param.result = false
                            }
                        } catch (_: Throwable) {}
                    }
                }
            )
            XposedBridge.log("[HideRecent] ✅ Hooked legacy isVisibleRecentTask")
        } catch (_: Throwable) {}
    }

    /**
     * 辅助方法：从 TaskInfo 对象中安全提取包名
     */
    private fun extractPackageNameFromTaskInfo(taskInfo: Any): String? {
        // 尝试 1：标准 API getBaseIntent()
        try {
            val intent = XposedHelpers.callMethod(taskInfo, "getBaseIntent") as? Intent
            val pkg = intent?.component?.packageName
            if (!pkg.isNullOrEmpty()) return pkg
        } catch (_: Throwable) {}

        // 尝试 2：直接读取 topActivity 字段 (它是 ComponentName 对象)
        try {
            val topActivity = XposedHelpers.getObjectField(taskInfo, "topActivity") as? ComponentName
            val pkg = topActivity?.packageName
            if (!pkg.isNullOrEmpty()) return pkg
        } catch (_: Throwable) {}

        // 尝试 3：以防万一，尝试读取 realActivity
        try {
            val realActivity = XposedHelpers.getObjectField(taskInfo, "realActivity") as? ComponentName
            val pkg = realActivity?.packageName
            if (!pkg.isNullOrEmpty()) return pkg
        } catch (_: Throwable) {}

        return null
    }
}
