package moe.lyniko.hiderecent

import android.content.ComponentName
import android.content.Intent
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
        if (lpparam.packageName != "android") return

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

        if (Build.VERSION.SDK_INT >= 36) {
            hookAtmsForModernAndroid(lpparam)
        } else {
            hookLegacyVisibility(lpparam)
        }
    }

    private fun hookAtmsForModernAndroid(lpparam: LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.ActivityTaskManagerService",
                lpparam.classLoader,
                "getRecentTasks",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rawResult = param.result ?: return

                        val list: MutableList<*>? = try {
                            val getListMethod = rawResult.javaClass.getMethod("getList")
                            getListMethod.invoke(rawResult) as? MutableList<*>
                        } catch (_: Throwable) {
                            if (rawResult is MutableList<*>) rawResult else null
                        }

                        if (list.isNullOrEmpty()) return

                        val iterator = list.iterator()
                        var removedCount = 0

                        while (iterator.hasNext()) {
                            val taskInfo = iterator.next() ?: continue
                            val pkgName = extractPackageNameFromTaskInfo(taskInfo)

                            if (pkgName != null && packages.contains(pkgName)) {
                                iterator.remove()
                                removedCount++
                                
                            }
                        }

                        if (removedCount > 0) {
                            
                        }
                    }
                }
            )
            XposedBridge.log("[HideRecent] Successfully hooked ATMS.getRecentTasks")
        } catch (t: Throwable) {
            XposedBridge.log("[HideRecent] ATMS hook failed: ${t.message}")
        }
    }

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
            XposedBridge.log("[HideRecent] Hooked legacy isVisibleRecentTask")
        } catch (_: Throwable) {}
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

        try {
            val realActivity = XposedHelpers.getObjectField(taskInfo, "realActivity") as? ComponentName
            val pkg = realActivity?.packageName
            if (!pkg.isNullOrEmpty()) return pkg
        } catch (_: Throwable) {}

        return null
    }
}
