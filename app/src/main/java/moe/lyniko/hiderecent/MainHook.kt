package moe.lyniko.hiderecent

import android.content.ComponentName
import android.content.Intent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import moe.lyniko.hiderecent.utils.PreferenceUtils
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

class MainHook : IXposedHookLoadPackage {

    private lateinit var packages: Set<String>

    // ==========================================
    // 全局反射缓存区（只反射一次，永久复用，彻底消除反射耗时）
    // ==========================================
    private var getListMethod: Method? = null
    private var sliceConstructor: Constructor<*>? = null
    private var baseIntentField: Field? = null
    private var topActivityField: Field? = null

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != "android") return

        loadConfig()
        if (packages.isEmpty()) return

        try {
            // 1. 启动时一次性预热并缓存所有反射对象
            preCacheReflection(lpparam.classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("[HideRecent] ❌ Pre-cache failed: ${t.message}")
            return
        }

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
                        // 极速执行，不分配任何新对象
                        processTasksFast(param)
                    }
                }
            )
            XposedBridge.log("[HideRecent] ✅ Ultra-fast engine installed.")
        } catch (t: Throwable) {
            XposedBridge.log("[HideRecent] ❌ Hook failed: ${t.message}")
        }
    }

    /**
     * 预热：在模块加载时，把需要用的 Method/Field/Constructor 全部找出来存起来
     */
    private fun preCacheReflection(classLoader: ClassLoader) {
        val taskInfoClass = Class.forName("android.app.TaskInfo", false, classLoader)
        
        // 缓存字段读取器
        baseIntentField = taskInfoClass.getDeclaredField("baseIntent").apply { isAccessible = true }
        topActivityField = taskInfoClass.getDeclaredField("topActivity").apply { isAccessible = true }

        // 找到 ParceledListSlice 的类（不管它是叫 Slice 还是叫别的，拿返回值的类就行）
        // 这里我们先用一个假的实例去触发，或者直接用标准类名
        val sliceClass = Class.forName("android.content.pm.ParceledListSlice", false, classLoader)
        
        // 缓存构造器：接收 List 的那个构造函数
        sliceConstructor = sliceClass.getConstructor(List::class.java)
        
        // 缓存 getList 方法
        getListMethod = sliceClass.getMethod("getList")
        
        XposedBridge.log("[HideRecent] ✅ Reflection cache warmed up.")
    }

    /**
     * 核心极速处理引擎
     * 原则：不使用 filterNot (会产生新List)，不使用 XposedHelpers，严控对象创建
     */
    private fun processTasksFast(param: XC_MethodHook.MethodHookParam) {
        val rawResult = param.result ?: return

        try {
            // 步骤 1：使用缓存的 Method 拆包（无反射查找开销）
            val originalList = getListMethod!!.invoke(rawResult) as? List<*> ?: return

            // 步骤 2：快速扫描，判断是否真的有需要隐藏的任务
            var hasTarget = false
            for (taskInfo in originalList) {
                if (taskInfo != null && packages.contains(extractPkgUltraFast(taskInfo))) {
                    hasTarget = true
                    break // 找到一个就立刻停止扫描，节省 CPU
                }
            }

            // 如果没有目标，直接 return，连一个对象都不 new，零性能损耗
            if (!hasTarget) return

            // 步骤 3：只有在确认有目标时，才进行重建（极低频操作）
            val filteredList = ArrayList<Any>(originalList.size - 1) // 预分配准确容量，避免扩容损耗

            for (taskInfo in originalList) {
                if (taskInfo != null && !packages.contains(extractPkgUltraFast(taskInfo))) {
                    filteredList.add(taskInfo)
                }
            }

            // 步骤 4：使用缓存的 Constructor 实例化新包裹
            param.result = sliceConstructor!!.newInstance(filteredList)
            
        } catch (_: Throwable) {
            // 发生任何意外（比如极为罕见的类型转换错误），直接放弃本次拦截
            // 绝不能让异常抛到 system_server 导致系统崩溃
        }
    }

    /**
     * 极速包名提取器
     * 直接操作缓存的 Field 对象，绕过一切 Xposed 框架的方法调用和异常捕获
     */
    private fun extractPkgUltraFast(taskInfo: Any): String? {
        try {
            val intent = baseIntentField!!.get(taskInfo) as? Intent
            val comp = intent?.component
            if (comp != null) return comp.packageName
        } catch (_: Throwable) {}

        try {
            val comp = topActivityField!!.get(taskInfo) as? ComponentName
            if (comp != null) return comp.packageName
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
