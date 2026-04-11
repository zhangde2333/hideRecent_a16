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
        // 仅在 system_server 进程中进行 hook
        if (lpparam.packageName != "android") return

        // 加载配置
        val xsp = XSharedPreferences(BuildConfig.APPLICATION_ID, PreferenceUtils.functionalConfigName)
        xsp.makeWorldReadable()
        packages = PreferenceUtils.getPackageListFromPref(xsp)

        // 根据 Android 版本执行不同的 hook 策略
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // Android 10+ 使用 ATMS
                hookAtms(lpparam)
            }
            else -> {
                // Android 9 及以下使用旧版 AMS
                hookLegacyAms(lpparam)
            }
        }
    }

    /**
     * 兼容 Android 10 及以上版本的 ATMS Hook
     */
    private fun hookAtms(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        val isVisibleHook = createVisibilityHook()

        // 方案一：Hook RecentTasks.isVisibleRecentTask (Android 10-13 可用)
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.RecentTasks",
                classLoader,
                "isVisibleRecentTask",
                XposedHelpers.findClass("com.android.server.wm.Task", classLoader),
                isVisibleHook
            )
            XposedBridge.log("[HideRecent] ATMS: Hooked RecentTasks.isVisibleRecentTask")
        } catch (e: Throwable) {
            XposedBridge.log("[HideRecent] ATMS: RecentTasks hook failed: ${e.message}")
        }

        // 方案二：Hook ATMS.getRecentTasks (Android 14+ 备用)
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.ActivityTaskManagerService",
                classLoader,
                "getRecentTasks",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                createGetRecentTasksHook()
            )
            XposedBridge.log("[HideRecent] ATMS: Hooked ActivityTaskManagerService.getRecentTasks")
        } catch (e: Throwable) {
            XposedBridge.log("[HideRecent] ATMS: getRecentTasks hook failed: ${e.message}")
        }

        // 方案三：Hook ATMS.getRecentTasksImpl (某些定制系统)
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.ActivityTaskManagerService",
                classLoader,
                "getRecentTasksImpl",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                createGetRecentTasksHook()
            )
            XposedBridge.log("[HideRecent] ATMS: Hooked ActivityTaskManagerService.getRecentTasksImpl")
        } catch (e: Throwable) {
            XposedBridge.log("[HideRecent] ATMS: getRecentTasksImpl hook failed: ${e.message}")
        }
    }

    /**
     * 兼容 Android 9 及以下版本的 AMS Hook
     */
    private fun hookLegacyAms(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        val isVisibleHook = createVisibilityHook()

        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.am.RecentTasks",
                classLoader,
                "isVisibleRecentTask",
                XposedHelpers.findClass("com.android.server.am.TaskRecord", classLoader),
                isVisibleHook
            )
            XposedBridge.log("[HideRecent] LEGACY: Hooked RecentTasks.isVisibleRecentTask")
        } catch (e: Throwable) {
            XposedBridge.log("[HideRecent] LEGACY: RecentTasks hook failed: ${e.message}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.am.ActivityManagerService",
                classLoader,
                "getRecentTasks",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                createGetRecentTasksHook()
            )
            XposedBridge.log("[HideRecent] LEGACY: Hooked ActivityManagerService.getRecentTasks")
        } catch (e: Throwable) {
            XposedBridge.log("[HideRecent] LEGACY: getRecentTasks hook failed: ${e.message}")
        }
    }

    /**
     * 创建用于过滤可见任务的 Hook 回调
     */
    private fun createVisibilityHook(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args.isEmpty()) return

                val packageName = try {
                    // 从 Task/TaskRecord 参数中提取包名
                    extractPackageNameFromTask(param.args[0])
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) {
                        XposedBridge.log("[HideRecent] Failed to extract package: ${e.message}")
                    }
                    return
                }

                if (packageName != null && packages.contains(packageName)) {
                    param.result = false
                    if (BuildConfig.DEBUG) {
                        XposedBridge.log("[HideRecent] Hidden: $packageName")
                    }
                }
            }
        }
    }

    /**
     * 创建用于过滤 getRecentTasks 返回列表的 Hook 回调
     */
    private fun createGetRecentTasksHook(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val result = param.result as? List<*> ?: return

                val filtered = result.filter { taskInfo ->
                    try {
                        val packageName = extractPackageNameFromTaskInfo(taskInfo)
                        packageName == null || !packages.contains(packageName)
                    } catch (e: Exception) {
                        true // 无法提取包名时保留任务
                    }
                }

                param.result = filtered
                if (BuildConfig.DEBUG) {
                    XposedBridge.log("[HideRecent] Filtered ${result.size - filtered.size} tasks")
                }
            }
        }
    }

    /**
     * 从 Task 对象中提取包名
     */
    private fun extractPackageNameFromTask(task: Any): String? {
        return try {
            // 方式一：通过 getBaseIntent 获取
            val intent = XposedHelpers.callMethod(task, "getBaseIntent") as? Intent
            intent?.component?.packageName
        } catch (e: Exception) {
            // 方式二：通过 realActivity 获取
            try {
                val realActivity = XposedHelpers.getObjectField(task, "realActivity")
                XposedHelpers.callMethod(realActivity, "getPackageName") as? String
            } catch (e2: Exception) {
                // 方式三：通过 topActivity 获取
                try {
                    val topActivity = XposedHelpers.getObjectField(task, "topActivity")
                    val intent = XposedHelpers.callMethod(topActivity, "getIntent") as? Intent
                    intent?.component?.packageName
                } catch (e3: Exception) {
                    null
                }
            }
        }
    }

    /**
     * 从 TaskInfo 对象中提取包名
     */
    private fun extractPackageNameFromTaskInfo(taskInfo: Any): String? {
        return try {
            // 尝试获取 baseIntent
            val intent = XposedHelpers.getObjectField(taskInfo, "baseIntent") as? Intent
            intent?.component?.packageName
        } catch (e: Exception) {
            try {
                // 尝试获取 topActivity
                val topActivity = XposedHelpers.getObjectField(taskInfo, "topActivity")
                topActivity?.toString()?.let {
                    val start = it.indexOf("{") + 1
                    val end = it.indexOf(" ", start)
                    if (start > 0 && end > start) it.substring(start, end).takeIf { it.contains("/") }?.substringBefore("/")
                    else null
                }
            } catch (e2: Exception) {
                null
            }
        }
    }
}
