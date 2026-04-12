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
        // 在 system_server 进程中加载配置（确保只加载一次）
        if (lpparam.packageName == "android" && !::packages.isInitialized) {
            val xsp = XSharedPreferences(BuildConfig.APPLICATION_ID, PreferenceUtils.functionalConfigName)
            xsp.makeWorldReadable()
            packages = PreferenceUtils.getPackageListFromPref(xsp)
            XposedBridge.log("[HideRecent] Loaded ${packages.size} packages to hide: $packages")
        }

        // 根据进程分发 Hook
        when (lpparam.processName) {
            "android" -> {
                XposedBridge.log("[HideRecent] Hooking in system_server process...")
                hookInSystemServer(lpparam)
            }
            "com.android.systemui" -> {
                XposedBridge.log("[HideRecent] Hooking in SystemUI process...")
                hookInSystemUI(lpparam)
            }
        }
    }

    // ==================== system_server 进程 Hook ====================
    private fun hookInSystemServer(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        var hooked = false

        // 方案1：Hook Task.isVisible (Android 10+ 常用)
        try {
            val taskClass = XposedHelpers.findClass("com.android.server.wm.Task", classLoader)
            XposedBridge.hookAllMethods(taskClass, "isVisible", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val task = param.thisObject
                    val packageName = extractPackageNameFromTask(task)
                    if (BuildConfig.DEBUG) {
                        XposedBridge.log("[HideRecent] Task.isVisible called for $packageName, original result=${param.result}")
                    }
                    if (packageName != null && packages.contains(packageName)) {
                        param.result = false
                        if (BuildConfig.DEBUG) {
                            XposedBridge.log("[HideRecent] Force hidden: $packageName")
                        }
                    }
                }
            })
            XposedBridge.log("[HideRecent] ✅ system_server: Hooked Task.isVisible")
            hooked = true
        } catch (e: Throwable) {
            XposedBridge.log("[HideRecent] ❌ system_server: Task.isVisible hook failed: ${e.message}")
        }

        // 方案2：Hook RecentTasks.isVisibleRecentTask (备用)
        if (!hooked) {
            try {
                val taskClass = XposedHelpers.findClass("com.android.server.wm.Task", classLoader)
                XposedHelpers.findAndHookMethod(
                    "com.android.server.wm.RecentTasks",
                    classLoader,
                    "isVisibleRecentTask",
                    taskClass,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val task = param.args[0]
                            val packageName = extractPackageNameFromTask(task)
                            if (packageName != null && packages.contains(packageName)) {
                                param.result = false
                                if (BuildConfig.DEBUG) {
                                    XposedBridge.log("[HideRecent] Force hidden via RecentTasks: $packageName")
                                }
                            }
                        }
                    }
                )
                XposedBridge.log("[HideRecent] ✅ system_server: Hooked RecentTasks.isVisibleRecentTask")
                hooked = true
            } catch (e: Throwable) {
                XposedBridge.log("[HideRecent] ❌ system_server: RecentTasks hook failed: ${e.message}")
            }
        }

        // 方案3：Hook ATMS.getRecentTasks 返回值过滤 (作为兜底)
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
                        val result = param.result as? MutableList<*>
                        if (result != null) {
                            filterTaskInfoList(result)
                        }
                    }
                }
            )
            XposedBridge.log("[HideRecent] ✅ system_server: Hooked ATMS.getRecentTasks (result filter)")
        } catch (e: Throwable) {
            XposedBridge.log("[HideRecent] ❌ system_server: ATMS.getRecentTasks hook failed: ${e.message}")
        }

        // 兼容旧版 Android (9 及以下)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            hookLegacyAms(lpparam)
        }

        if (!hooked) {
            XposedBridge.log("[HideRecent] ⚠️ system_server: No primary visibility hook succeeded!")
        }
    }

    private fun hookLegacyAms(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val taskRecordClass = XposedHelpers.findClass("com.android.server.am.TaskRecord", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                "com.android.server.am.RecentTasks",
                lpparam.classLoader,
                "isVisibleRecentTask",
                taskRecordClass,
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
            XposedBridge.log("[HideRecent] ✅ system_server: Hooked legacy RecentTasks")
        } catch (e: Throwable) {
            XposedBridge.log("[HideRecent] ❌ system_server: Legacy hook failed: ${e.message}")
        }
    }

    // ==================== SystemUI 进程 Hook ====================
    private fun hookInSystemUI(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        // 尝试 Hook SystemUI 中可能加载最近任务列表的类和方法
        val candidateClasses = listOf(
            "com.android.systemui.recents.RecentTasks",
            "com.android.systemui.recents.model.RecentsModel",
            "com.android.systemui.recents.Recents",
            "com.android.systemui.recent.Recents"
        )

        var hooked = false
        for (className in candidateClasses) {
            try {
                val targetClass = XposedHelpers.findClass(className, classLoader)
                // Hook 所有名为 getRecentTasks 的方法
                XposedBridge.hookAllMethods(targetClass, "getRecentTasks", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val result = param.result as? MutableList<*>
                        if (result != null) {
                            filterTaskInfoList(result)
                            if (BuildConfig.DEBUG) {
                                XposedBridge.log("[HideRecent] SystemUI: Filtered ${className}.getRecentTasks result")
                            }
                        }
                    }
                })
                XposedBridge.log("[HideRecent] ✅ SystemUI: Hooked $className.getRecentTasks")
                hooked = true
                break
            } catch (e: Throwable) {
                // 类不存在，尝试下一个
            }
        }

        // 额外尝试：Hook RecentsModel 的 loadTasks 方法
        if (!hooked) {
            try {
                val recentsModelClass = XposedHelpers.findClass(
                    "com.android.systemui.recents.model.RecentsModel",
                    classLoader
                )
                XposedBridge.hookAllMethods(recentsModelClass, "loadTasks", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // loadTasks 可能将结果存入某个字段，这里简单处理：强制后续刷新
                        XposedBridge.log("[HideRecent] SystemUI: loadTasks called, may need to refresh list")
                        // 无法直接修改返回值，但可以尝试延迟过滤（见下方备用思路）
                    }
                })
                XposedBridge.log("[HideRecent] ✅ SystemUI: Hooked RecentsModel.loadTasks (monitoring)")
            } catch (e: Throwable) {
                XposedBridge.log("[HideRecent] ❌ SystemUI: RecentsModel.loadTasks hook failed: ${e.message}")
            }
        }

        if (!hooked) {
            XposedBridge.log("[HideRecent] ⚠️ SystemUI: No suitable class found. Try enabling verbose logs.")
        }
    }

    // 过滤 MutableList<*> 类型的任务信息列表
    private fun filterTaskInfoList(taskList: MutableList<*>) {
        val iterator = taskList.iterator()
        var removedCount = 0
        while (iterator.hasNext()) {
            val taskInfo = iterator.next()
            val packageName = extractPackageNameFromTaskInfo(taskInfo)
            if (packageName != null && packages.contains(packageName)) {
                iterator.remove()
                removedCount++
                if (BuildConfig.DEBUG) {
                    XposedBridge.log("[HideRecent] Removed task: $packageName")
                }
            }
        }
        if (removedCount > 0) {
            XposedBridge.log("[HideRecent] Total removed $removedCount tasks from list")
        }
    }

    // ==================== 包名提取辅助函数 ====================
    private fun extractPackageNameFromTask(task: Any): String? {
        try {
            val intent = XposedHelpers.callMethod(task, "getBaseIntent") as? Intent
            val pkg = intent?.component?.packageName
            if (pkg != null) {
                if (BuildConfig.DEBUG) XposedBridge.log("[HideRecent] Extracted via getBaseIntent: $pkg")
                return pkg
            }
        } catch (e: Throwable) { /* ignore */ }
        try {
            val realActivity = XposedHelpers.getObjectField(task, "realActivity")
            val pkg = realActivity?.let { XposedHelpers.callMethod(it, "getPackageName") as? String }
            if (pkg != null) {
                if (BuildConfig.DEBUG) XposedBridge.log("[HideRecent] Extracted via realActivity: $pkg")
                return pkg
            }
        } catch (e: Throwable) { /* ignore */ }
        try {
            val topActivity = XposedHelpers.getObjectField(task, "topActivity")
            val topIntent = topActivity?.let { XposedHelpers.callMethod(it, "getIntent") as? Intent }
            val pkg = topIntent?.component?.packageName
            if (pkg != null) {
                if (BuildConfig.DEBUG) XposedBridge.log("[HideRecent] Extracted via topActivity: $pkg")
                return pkg
            }
        } catch (e: Throwable) { /* ignore */ }
        try {
            val taskInfo = XposedHelpers.callMethod(task, "getTaskInfo")
            val topActivityComp = XposedHelpers.getObjectField(taskInfo, "topActivity")
            val compStr = topActivityComp?.toString()
            val pkg = compStr?.substringBefore("/")
            if (!pkg.isNullOrEmpty()) {
                if (BuildConfig.DEBUG) XposedBridge.log("[HideRecent] Extracted via taskInfo.topActivity: $pkg")
                return pkg
            }
        } catch (e: Throwable) { /* ignore */ }
        if (BuildConfig.DEBUG) XposedBridge.log("[HideRecent] ⚠️ Failed to extract package from task: $task")
        return null
    }

    private fun extractPackageNameFromTaskInfo(taskInfo: Any?): String? {
        if (taskInfo == null) return null
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
        return try {
            val intent = XposedHelpers.getObjectField(task, "intent") as? Intent
            intent?.component?.packageName
        } catch (e: Throwable) {
            null
        }
    }
}