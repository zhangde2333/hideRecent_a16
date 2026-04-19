package moe.lyniko.hiderecent

import android.content.ComponentName
import android.content.Intent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import moe.lyniko.hiderecent.utils.PreferenceUtils

class MainHook : IXposedHookLoadPackage {

    private lateinit var packages: Set<String>

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // 只在系统服务端拦截，不管 SystemUI
        if (lpparam.packageName != "android") return

        loadConfig()
        if (packages.isEmpty()) return

        hookAtmsAndRebuildSlice(lpparam)
    }

    /**
     * 核心大杀器：过滤列表 + 重建 ParceledListSlice 完美替换
     */
    private fun hookAtmsAndRebuildSlice(lpparam: LoadPackageParam) {
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

                        try {
                            // 1. 安全地把外层包裹拆开，拿到原始 List
                            val getListMethod = rawResult.javaClass.getMethod("getList")
                            val originalList = getListMethod.invoke(rawResult) as? List<*> ?: return

                            // 2. 核心：不修改原列表，而是用 Kotlin 的高阶函数生成一个全新的、干净的新列表
                            val filteredList = originalList.filterNot { taskInfo ->
                                if (taskInfo == null) return@filterNot false
                                val pkg = extractPkgFromTaskInfo(taskInfo)
                                packages.contains(pkg)
                            }

                            // 如果过滤后数量没变，说明没有需要隐藏的，直接放行，减少性能损耗
                            if (filteredList.size == originalList.size) return

                            XposedBridge.log("[HideRecent] Target found! Original: ${originalList.size}, Filtered: ${filteredList.size}")

                            // 3. 神来之笔：反射获取 ParceledListSlice 的构造函数
                            // 它有一个公开的构造方法：public ParceledListSlice(List<T> list)
                            val sliceClass = rawResult.javaClass
                            val constructor = sliceClass.getConstructor(List::class.java)

                            // 4. 用干净的新列表，实例化出一个全新的、内部计数器完全正确的包裹
                            val newSlice = constructor.newInstance(filteredList)

                            // 5. 偷天换日：把系统原来的返回值替换成我们新建的包裹
                            param.result = newSlice
                            XposedBridge.log("[HideRecent] ✅ Successfully replaced ParceledListSlice! No black screen guaranteed.")

                        } catch (t: Throwable) {
                            XposedBridge.log("[HideRecent] ❌ Rebuild failed: ${t.message}")
                        }
                    }
                }
            )
            XposedBridge.log("[HideRecent] ✅ ATMS Rebuild Hook installed.")
        } catch (t: Throwable) {
            XposedBridge.log("[HideRecent] ❌ ATMS Hook failed: ${t.message}")
        }
    }

    // --- 辅助方法：直接读字段提取包名（绕过所有 Getter 方法） ---

    private fun extractPkgFromTaskInfo(taskInfo: Any): String? {
        // 绝对不调 getBaseIntent() 方法，直接读 baseIntent 字段
        try {
            val intent = XposedHelpers.getObjectField(taskInfo, "baseIntent") as? Intent
            val pkg = intent?.component?.packageName
            if (!pkg.isNullOrEmpty()) return pkg
        } catch (_: Throwable) {}

        // 直接读 topActivity 字段
        try {
            val comp = XposedHelpers.getObjectField(taskInfo, "topActivity") as? ComponentName
            val pkg = comp?.packageName
            if (!pkg.isNullOrEmpty()) return pkg
        } catch (_: Throwable) {}
        
        return null
    }

    private fun loadConfig() {
        try {
            val xsp = de.robv.android.xposed.XSharedPreferences(BuildConfig.APPLICATION_ID, PreferenceUtils.functionalConfigName)
            xsp.makeWorldReadable()
            packages = PreferenceUtils.getPackageListFromPref(xsp)
        } catch (e: Throwable) {
            packages = emptySet()
        }
    }
}
