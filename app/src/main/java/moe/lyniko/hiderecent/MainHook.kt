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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hookAtms(lpparam)
        } else {
            hookLegacyAms(lpparam)
        }
    }

    private fun hookAtms(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        val isVisibleHook = createVisibilityHook()

        // 修正：Int::class.java 替换 Int::class.javaPrimitiveType
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

        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.ActivityTaskManagerService",
                classLoader,
                "getRecentTasks",
                Int::class.java,      // ✅ 修正
                Int::class.java,      // ✅ 修正
                Int::class.java,      // ✅ 修正
                createGetRecentTasksHook()
            )
            XposedBridge.log("[HideRecent] ATMS: Hooked ActivityTaskManagerService.getRecentTasks")
        } catch (e: Throwable) {
            XposedBridge.log("[HideRecent] ATMS: getRecentTasks hook failed: ${e.message}")
        }

        // 后续其他 hook 同理修正
    }

    private fun hookLegacyAms(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 类似修正 Int::class.java
    }

    private fun createVisibilityHook(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // 实现略
            }
        }
    }

    private fun createGetRecentTasksHook(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                // 实现略
            }
        }
    }

    // 其他辅助方法...
}
