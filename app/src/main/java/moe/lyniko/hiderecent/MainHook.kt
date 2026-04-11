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
                hookRecentTasksVisibleList(lpparam)
            }
            else -> {
                hookLegacyAms(lpparam)
            }
        }
    }

    /**
     * 核心方案：Hook RecentTasks 类的 mVisibleTasks 列表
     * 适用于 Android 10 及以上版本
     */
    private fun hookRecentTasksVisibleList(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        try {
            // 获取 RecentTasks 类
            val recentTasksClass = XposedHelpers.findClass(
                "com.android.server.wm.RecentTasks",
                classLoader
            )

            // 获取 ATMS 实例（用于获取 RecentTasks 对象）
            val atmsClass = XposedHelpers.findClass(
                "com.android.server.wm.ActivityTaskManagerService",
                classLoader
            )

            // 尝试多种获取 RecentTasks 实例的方式
            val recentTasksInstance = try {
                // 方式1：通过 ATMS 的 getRecentTasks() 方法（返回 RecentTasks 对象）
                val atmsInstance = XposedHelpers.callStaticMethod(atmsClass, "getInstance")
                    ?: XposedHelpers.getStaticObjectField(atmsClass, "mService")
                XposedHelpers.callMethod(atmsInstance, "getRecentTasks")
            } catch (e: Throwable) {
                // 方式2：直接读取 ATMS 的 mRecentTasks 字段
                try {
                    val atmsInstance = XposedHelpers.callStaticMethod(atmsClass, "getInstance")
                        ?: XposedHelpers.getStaticObjectField(atmsClass, "mService")
                    XposedHelpers.getObjectField(atmsInstance, "mRecentTasks")
                } catch (e2: Throwable) {
                    // 方式3：从 RecentTasks 类获取单例
                    try {
                        XposedHelpers.callStaticMethod(recentTasksClass, "getInstance")
                    } catch (e3: Throwable) {
                        XposedBridge.log("[HideRecent] Cannot get RecentTasks instance: ${e3.message}")
                        null
                    }
                }
            }

            // 如果获取到了实例，先执行一次初始过滤
            if (recentTasksInstance != null) {
                filterVisibleTasksFromInstance(recentTasksInstance)
            } else {
                XposedBridge.log("[HideRecent] RecentTasks instance is null, will try static field")
            }

            // Hook 所有可能修改 mVisibleTasks 的方法
            val methodsToHook = arrayOf(
                "addTask",               // 添加新任务
                "removeTask",            // 移除任务
                "updateVisibleTasks",    // 更新可见任务列表
                "onTaskMovedToFront",    // 任务移到前台
                "notifyTaskMovedToFront", // 通知任务移至前台
                "processTaskStackChange"  // 处理任务栈变化
            )

            methodsToHook.forEach { methodName ->
                try {
                    XposedBridge.hookAllMethods(recentTasksClass, methodName, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            // 从 this 或 static context 获取 RecentTasks 实例
                            val instance = if (param.thisObject != null) param.thisObject else recentTasksInstance
                            if (instance != null) {
                                filterVisibleTasksFromInstance(instance)
                            } else {
                                // 尝试从静态字段获取
                                filterVisibleTasksFromStatic(recentTasksClass)
                            }
                        }
                    })
                } catch (e: Throwable) {
                    // 某些方法可能不存在，忽略即可
                }
            }

            // 额外 Hook：监听系统 UI 进程查询最近任务时的调用
            try {
                XposedHelpers.findAndHookMethod(
                    "com.android.server.wm.ActivityTaskManagerService",
                    classLoader,
                    "getRecentTasks",
                    Int::class.java,
                    Int::class.java,
                    Int::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            // 在返回结果前确保 mVisibleTasks 已被过滤
                            if (recentTasksInstance != null) {
                                filterVisibleTasksFromInstance(recentTasksInstance)
                            } else {
                                filterVisibleTasksFromStatic(recentTasksClass)
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                XposedBridge.log("[HideRecent] Failed to hook getRecentTasks: ${e.message}")
            }

            XposedBridge.log("[HideRecent] Successfully hooked RecentTasks.mVisibleTasks")
        } catch (e: Throwable) {
            XposedBridge.log("[HideRecent] Failed to setup hook: ${e.message}")
            // 降级到旧方案
            hookLegacyAms(lpparam)
        }
    }

    /**
     * 从 RecentTasks 实例中过滤 mVisibleTasks 列表
     */
    private fun filterVisibleTasksFromInstance(instance: Any) {
        try {
            // 获取 mVisibleTasks 字段
            val visibleTasksField = instance.javaClass.getDeclaredField("mVisibleTasks")
            visibleTasksField.isAccessible = true
            val visibleTasks = visibleTasksField.get(instance) as? MutableList<*> ?: return

            filterTaskList(visibleTasks)
        } catch (e: Throwable) {
            // 字段名可能变化，尝试其他名称
            tryAlternativeFieldNames(instance)
        }
    }

    /**
     * 从 RecentTasks 类的静态上下文中过滤（如果 mVisibleTasks 是静态字段）
     */
    private fun filterVisibleTasksFromStatic(clazz: Class<*>) {
        try {
            val visibleTasksField = clazz.getDeclaredField("mVisibleTasks")
            visibleTasksField.isAccessible = true
            val visibleTasks = visibleTasksField.get(null) as? MutableList<*> ?: return

            filterTaskList(visibleTasks)
        } catch (e: Throwable) {
            // 忽略
        }
    }

    /**
     * 尝试其他可能的字段名
     */
    private fun tryAlternativeFieldNames(instance: Any) {
        val possibleFieldNames = arrayOf("mTasks", "mVisibleTaskList", "mRecentTasks", "visibleTasks")
        for (fieldName in possibleFieldNames) {
            try {
                val field = instance.javaClass.getDeclaredField(fieldName)
                field.isAccessible = true
                val list = field.get(instance) as? MutableList<*> ?: continue
                filterTaskList(list)
                if (BuildConfig.DEBUG) {
                    XposedBridge.log("[HideRecent] Found alternative field: $fieldName")
                }
                break
            } catch (e: Throwable) {
                // continue
            }
        }
    }

    /**
     * 过滤任务列表，移除隐藏包名的任务
     */
    private fun filterTaskList(taskList: MutableList<*>) {
        val iterator = taskList.iterator()
        var removedCount = 0
        while (iterator.hasNext()) {
            val task = iterator.next()
            val packageName = extractPackageNameFromTask(task)
            if (packageName != null && packages.contains(packageName)) {
                iterator.remove()
                removedCount++
                if (BuildConfig.DEBUG) {
                    XposedBridge.log("[HideRecent] Removed task: $packageName")
                }
            }
        }
        if (BuildConfig.DEBUG && removedCount > 0) {
            XposedBridge.log("[HideRecent] Total removed: $removedCount tasks")
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
                        // 方式4：通过 mUserId 和包名映射（降级）
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
     * 兼容 Android 9 及以下版本的 AMS Hook
     */
    private fun hookLegacyAms(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        try {
            val recentTasksClass = XposedHelpers.findClass(
                "com.android.server.am.RecentTasks",
                classLoader
            )
            XposedBridge.hookAllMethods(recentTasksClass, "updateVisibleTasks", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    filterVisibleTasksFromStatic(recentTasksClass)
                }
            })
            XposedBridge.log("[HideRecent] LEGACY: Hooked RecentTasks")
        } catch (e: Throwable) {
            XposedBridge.log("[HideRecent] LEGACY: Failed to hook: ${e.message}")
        }
    }
}
