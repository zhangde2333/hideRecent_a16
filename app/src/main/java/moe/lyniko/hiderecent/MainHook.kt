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
                hookTaskIsVisible(lpparam)
            }
            else -> {
                hookLegacyAms(lpparam)
            }
        }
    }

    /**
     * 主方案：直接 Hook Task 类的 isVisible 方法
     * 适用于 Android 10 及以上版本，包括 Android 16
     */
    private fun hookTaskIsVisible(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        // 尝试多种可能的可见性判断方法
        val methodNames = arrayOf(
            "isVisible",
            "isVisibleForUser",
            "shouldBeVisible",
            "isTaskVisible"
        )

        var hooked = false
        for (methodName in methodNames) {
            try {
                val taskClass = XposedHelpers.findClass("com.android.server.wm.Task", classLoader)
                XposedBridge.hookAllMethods(taskClass, methodName, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val task = param.thisObject ?: return  // 如果 thisObject 为 null，直接返回
                        val packageName = extractPackageNameFromTask(task)

                        if (packageName != null && packages.contains(packageName)) {
                            param.result = false
                            if (BuildConfig.DEBUG) {
                                XposedBridge.log("[HideRecent] Task.$methodName -> false for: $packageName")
                            }
                        }
                    }
                })
                XposedBridge.log("[HideRecent] Successfully hooked Task.$methodName")
                hooked = true
                break
            } catch (e: Throwable) {
                // 继续尝试下一个方法名
            }
        }

        if (!hooked) {
            XposedBridge.log("[HideRecent] Failed to hook any Task visibility method, fallback to ATMS")
            hookAtmsFallback(lpparam)
        }
    }

    /**
     * 备选方案：Hook ActivityTaskManagerService.getRecentTasks
     * 当 Task 可见性 Hook 失败时使用
     */
    private fun hookAtmsFallback(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
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
                        val iterator = result.iterator()
                        while (iterator.hasNext()) {
                            val taskInfo = iterator.next() ?: continue
                            val packageName = extractPackageNameFromTaskInfo(taskInfo)
                            if (packageName != null && packages.contains(packageName)) {
                                iterator.remove()
                                if (BuildConfig.DEBUG) {
                                    XposedBridge.log("[HideRecent] Filtered from getRecentTasks: $packageName")
                                }
                            }
                        }
                    }
                }
            )
            XposedBridge.log("[HideRecent] ATMS fallback hooked successfully")
        } catch (e: Throwable) {
            XposedBridge.log("[HideRecent] ATMS fallback failed: ${e.message}")
        }
    }

    /**
     * 兼容 Android 9 及以下版本的 AMS Hook
     */
    private fun hookLegacyAms(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        try {
            val taskClass = XposedHelpers.findClass("com.android.server.am.TaskRecord", classLoader)
            XposedBridge.hookAllMethods(taskClass, "isVisible", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val task = param.thisObject ?: return
                    val packageName = extractPackageNameFromTaskLegacy(task)
                    if (packageName != null && packages.contains(packageName)) {
                        param.result = false
                        if (BuildConfig.DEBUG) {
                            XposedBridge.log("[HideRecent] LEGACY Task.isVisible -> false for: $packageName")
                        }
                    }
                }
            })
            XposedBridge.log("[HideRecent] LEGACY: Hooked TaskRecord.isVisible")
        } catch (e: Throwable) {
            XposedBridge.log("[HideRecent] LEGACY: Failed to hook: ${e.message}")
        }
    }

    /**
     * 从 Task 对象中提取包名（多种降级方案）
     */
    private fun extractPackageNameFromTask(task: Any): String? {
        return try {
            // 方式1：通过 getBaseIntent() 获取
            val intent = XposedHelpers.callMethod(task, "getBaseIntent") as? Intent
            intent?.component?.packageName
        } catch (e: Throwable) {
            try {
                // 方式2：通过 realActivity 字段
                val realActivity = XposedHelpers.getObjectField(task, "realActivity")
                XposedHelpers.callMethod(realActivity, "getPackageName") as? String
            } catch (e2: Throwable) {
                try {
                    // 方式3：通过 topActivity 字段
                    val topActivity = XposedHelpers.getObjectField(task, "topActivity")
                    val topIntent = XposedHelpers.callMethod(topActivity, "getIntent") as? Intent
                    topIntent?.component?.packageName
                } catch (e3: Throwable) {
                    try {
                        // 方式4：通过 getTaskInfo().topActivity
                        val taskInfo = XposedHelpers.callMethod(task, "getTaskInfo")
                        val topActivityComp = XposedHelpers.getObjectField(taskInfo, "topActivity")
                        topActivityComp?.toString()?.substringBefore("/")
                    } catch (e4: Throwable) {
                        null
                    }
                }
            }
        }
    }

    /**
     * 从 TaskInfo 对象中提取包名（用于 getRecentTasks 返回值）
     */
    private fun extractPackageNameFromTaskInfo(taskInfo: Any): String? {
        return try {
            val intent = XposedHelpers.getObjectField(taskInfo, "baseIntent") as? Intent
            intent?.component?.packageName
        } catch (e: Throwable) {
            try {
                val topActivity = XposedHelpers.getObjectField(taskInfo, "topActivity")
                topActivity?.toString()?.substringBefore("/")
            } catch (e2: Throwable) {
                null
            }
        }
    }

    /**
     * 从旧版 TaskRecord 中提取包名
     */
    private fun extractPackageNameFromTaskLegacy(task: Any): String? {
        return try {
            val intent = XposedHelpers.getObjectField(task, "intent") as? Intent
            intent?.component?.packageName
        } catch (e: Throwable) {
            try {
                val realActivity = XposedHelpers.getObjectField(task, "realActivity")
                realActivity?.toString()?.substringBefore("/")
            } catch (e2: Throwable) {
                null
            }
        }
    }
}
