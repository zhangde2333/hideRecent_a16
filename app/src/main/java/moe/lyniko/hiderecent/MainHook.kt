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

    // 用于伪装的假包名
    private val FAKE_PACKAGE = "com.android.hidden.dummy"

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName == "android") {
            loadConfig()
            if (packages.isEmpty()) return

            // 统一在 ATMS 层处理所有版本
            hookAtmsDataSpoofing(lpparam)
        }
    }

    /**
     * 核心大杀器：数据换皮方案
     * 不改变列表结构，只篡改目标 App 的身份信息
     */
    private fun hookAtmsDataSpoofing(lpparam: LoadPackageParam) {
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

                        // 安全获取底层列表（处理 ParceledListSlice）
                        val list: MutableList<*>? = try {
                            val getListMethod = rawResult.javaClass.getMethod("getList")
                            getListMethod.invoke(rawResult) as? MutableList<*>
                        } catch (_: Throwable) {
                            if (rawResult is MutableList<*>) rawResult else null
                        }

                        if (list.isNullOrEmpty()) return

                        for (taskInfo in list) {
                            if (taskInfo == null) continue
                            
                            val realPkg = extractPackageNameFromTaskInfo(taskInfo) ?: continue

                            if (packages.contains(realPkg)) {
                                XposedBridge.log("[HideRecent] 🥷 Spoofing package: $realPkg -> $FAKE_PACKAGE")
                                
                                try {
                                    // 1. 篡改 baseIntent 字段（直接改字段，绕过 getter）
                                    val baseIntent = XposedHelpers.getObjectField(taskInfo, "baseIntent") as? Intent
                                    baseIntent?.component = ComponentName(FAKE_PACKAGE, "")

                                    // 2. 篡改 topActivity 字段
                                    XposedHelpers.setObjectField(taskInfo, "topActivity", ComponentName(FAKE_PACKAGE, ""))
                                    
                                    // 3. 篡改 realActivity 字段
                                    XposedHelpers.setObjectField(taskInfo, "realActivity", ComponentName(FAKE_PACKAGE, ""))

                                    // 4. 篡改 origActivity 字段 (部分 ROM 有这个字段)
                                    try {
                                        XposedHelpers.setObjectField(taskInfo, "origActivity", ComponentName(FAKE_PACKAGE, ""))
                                    } catch (_: Throwable) {}
                                    
                                } catch (e: Throwable) {
                                    XposedBridge.log("[HideRecent] Failed to spoof fields: ${e.message}")
                                }
                            }
                        }
                    }
                }
            )
            XposedBridge.log("[HideRecent] ✅ ATMS Data Spoofing hook installed.")
        } catch (t: Throwable) {
            XposedBridge.log("[HideRecent] ❌ ATMS hook failed: ${t.message}")
        }
    }

    // --- 辅助方法 ---

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
        // 优先从 baseIntent 字段直接读取（不调方法）
        try {
            val intent = XposedHelpers.getObjectField(taskInfo, "baseIntent") as? Intent
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
