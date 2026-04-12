package moe.lyniko.hiderecent

import android.content.Intent
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import moe.lyniko.hiderecent.utils.PreferenceUtils

class MainHook : IXposedHookLoadPackage {

    private lateinit var packages: MutableSet<String>

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return

        val xsp = XSharedPreferences(BuildConfig.APPLICATION_ID, PreferenceUtils.functionalConfigName)
        xsp.makeWorldReadable()
        packages = PreferenceUtils.getPackageListFromPref(xsp)
        XposedBridge.log("[HideRecent] Loaded ${packages.size} packages to hide: $packages")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hookTaskVisibility(lpparam)
        } else {
            hookLegacy(lpparam)
        }
    }

    private fun hookTaskVisibility(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        var hooked = false

        // 方案1：Hook Task.isVisible (最常见)
        try {
            val taskClass = XposedHelpers.findClass("com.android.server.wm.Task", classLoader)
            XposedBridge.hookAllMethods(taskClass, "isVisible", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val task = param.thisObject
                    val packageName = extractPackageNameFromTask(task)
                    XposedBridge.log("[HideRecent] Task.isVisible called for $packageName, original result=${param.result}")
                    if (packageName != null && packages.contains(packageName)) {
                        param.result = false
                        XposedBridge.log("[HideRecent] Force hidden: $packageName")
                    }
                }
            })
            XposedBridge.log("[HideRecent] ✅ Hooked Task.isVisible")
            hooked = true
        } catch (e: Throwable) {
            XposedBridge.log("[HideRecent] ❌ Task.isVisible hook failed: ${e.message}")
        }

        // 方案2：Hook RecentTasks.isVisibleRecentTask (备用)
        if (!hooked) {
            try {
                XposedHelpers.findAndHookMethod(
                    "com.android.server.wm.RecentTasks",
                    classLoader,
                    "isVisibleRecentTask",
                    XposedHelpers.findClass("com.android.server.wm.Task", classLoader),
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val task = param.args[0]
                            val packageName = extractPackageNameFromTask(task)
                            XposedBridge.log("[HideRecent] RecentTasks.isVisibleRecentTask called for $packageName")
                            if (packageName != null && packages.contains(packageName)) {
                                param.result = false
                                XposedBridge.log("[HideRecent] Force hidden via RecentTasks: $packageName")
                            }
                        }
                    }
                )
                XposedBridge.log("[HideRecent] ✅ Hooked RecentTasks.isVisibleRecentTask")
                hooked = true
            } catch (e: Throwable) {
                XposedBridge.log("[HideRecent] ❌ RecentTasks hook failed: ${e.message}")
            }
        }

        // 方案3：Hook ATMS.getRecentTasks 返回值过滤
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.ActivityTaskManagerService",
                classLoader,
                "getRecentTasks",
                Int::class.java,
                Int::class.java,
                Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val result = param.result as? MutableList<*> ?: return
                        var removed = 0
                        val iterator = result.iterator()
                        while (iterator.hasNext()) {
                            val taskInfo = iterator.next()
                            val packageName = extractPackageNameFromTaskInfo(taskInfo)
                            if (packageName != null && packages.contains(packageName)) {
                                iterator.remove()
                                removed++
                            }
                        }
                        if (removed > 0) {
                            XposedBridge.log("[HideRecent] Removed $removed tasks from getRecentTasks result")
                        }
                    }
                }
            )
            XposedBridge.log("[HideRecent] ✅ Hooked ATMS.getRecentTasks (result filter)")
        } catch (e: Throwable) {
            XposedBridge.log("[HideRecent] ❌ ATMS.getRecentTasks hook failed: ${e.message}")
        }

        if (!hooked) {
            XposedBridge.log("[HideRecent] ⚠️ No primary visibility hook succeeded! Module may not work.")
        }
    }

    private fun hookLegacy(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.am.RecentTasks",
                lpparam.classLoader,
                "isVisibleRecentTask",
                XposedHelpers.findClass("com.android.server.am.TaskRecord", lpparam.classLoader),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val task = param.args[0]
                        val packageName = extractPackageNameFromTaskLegacy(task)
                        if (packageName != null && packages.contains(packageName)) {
                            param.result = false
                        }
                    }
                }
            )
            XposedBridge.log("[HideRecent] ✅ Hooked legacy RecentTasks")
        } catch (e: Throwable) {
            XposedBridge.log("[HideRecent] ❌ Legacy hook failed: ${e.message}")
        }
    }

    // ---------- 包名提取函数（增强日志）----------
    private fun extractPackageNameFromTask(task: Any): String? {
        try {
            // 尝试1: getBaseIntent()
            val intent = XposedHelpers.callMethod(task, "getBaseIntent") as? Intent
            val pkg = intent?.component?.packageName
            if (pkg != null) {
                XposedBridge.log("[HideRecent] Extracted via getBaseIntent: $pkg")
                return pkg
            }
        } catch (e: Throwable) {}
        try {
            // 尝试2: realActivity
            val realActivity = XposedHelpers.getObjectField(task, "realActivity")
            val pkg = XposedHelpers.callMethod(realActivity, "getPackageName") as? String
            if (pkg != null) {
                XposedBridge.log("[HideRecent] Extracted via realActivity: $pkg")
                return pkg
            }
        } catch (e: Throwable) {}
        try {
            // 尝试3: topActivity
            val topActivity = XposedHelpers.getObjectField(task, "topActivity")
            val topIntent = XposedHelpers.callMethod(topActivity, "getIntent") as? Intent
            val pkg = topIntent?.component?.packageName
            if (pkg != null) {
                XposedBridge.log("[HideRecent] Extracted via topActivity: $pkg")
                return pkg
            }
        } catch (e: Throwable) {}
        try {
            // 尝试4: getTaskInfo().topActivity
            val taskInfo = XposedHelpers.callMethod(task, "getTaskInfo")
            val topActivityComp = XposedHelpers.getObjectField(taskInfo, "topActivity")
            val compStr = topActivityComp?.toString()
            val pkg = compStr?.substringBefore("/")
            if (pkg != null && pkg.isNotEmpty()) {
                XposedBridge.log("[HideRecent] Extracted via taskInfo.topActivity: $pkg")
                return pkg
            }
        } catch (e: Throwable) {}
        XposedBridge.log("[HideRecent] ⚠️ Failed to extract package from task: $task")
        return null
    }

    private fun extractPackageNameFromTaskInfo(taskInfo: Any): String? {
        try {
            val intent = XposedHelpers.getObjectField(taskInfo, "baseIntent") as? Intent
            return intent?.component?.packageName
        } catch (e: Throwable) {
            try {
                val topActivity = XposedHelpers.getObjectField(taskInfo, "topActivity")
                return topActivity?.toString()?.substringBefore("/")
            } catch (e2: Throwable) {
                return null
            }
        }
    }

    private fun extractPackageNameFromTaskLegacy(task: Any): String? {
        try {
            val intent = XposedHelpers.getObjectField(task, "intent") as? Intent
            return intent?.component?.packageName
        } catch (e: Throwable) {
            return null
        }
    }
}