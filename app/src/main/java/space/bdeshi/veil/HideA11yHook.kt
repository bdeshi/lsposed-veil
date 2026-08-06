package space.bdeshi.veil

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

private const val SELF_PACKAGE = "space.bdeshi.veil"

// Settings keys whose *string* reads should look absent to the hooked app.
private val NULL_STRING_KEYS = setOf(
    "enabled_accessibility_services",
)

// Settings keys whose reads (string "0" / int 0) should look disabled to the hooked app.
private val ZERO_VALUE_KEYS = setOf(
    "accessibility_enabled",
    "touch_exploration_enabled",
    "adb_enabled",
    "adb_wifi_enabled",
)

class HideA11yHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName == SELF_PACKAGE) return

        hookAccessibilityManager(lpparam)
        hookSettingsClass("android.provider.Settings\$Secure", lpparam)
        hookSettingsClass("android.provider.Settings\$Global", lpparam)
    }

    private fun hookAccessibilityManager(lpparam: LoadPackageParam) {
        val clazz = XposedHelpers.findClassIfExists(
            "android.view.accessibility.AccessibilityManager",
            lpparam.classLoader,
        ) ?: return

        fun hook(name: String, vararg args: Any, result: Any?) {
            try {
                XposedHelpers.findAndHookMethod(
                    clazz, name, *args,
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any? {
                            return result
                        }
                    },
                )
            } catch (t: Throwable) {
                // Method signature not present on this API level — safe to skip.
            }
        }

        hook("getEnabledAccessibilityServiceList", Int::class.javaPrimitiveType!!, result = emptyList<Any>())
        hook("getInstalledAccessibilityServiceList", result = emptyList<Any>())
        hook("getAccessibilityServiceList", result = emptyList<Any>())
        hook("isEnabled", result = false)
        hook("isTouchExplorationEnabled", result = false)
    }

    private fun hookSettingsClass(className: String, lpparam: LoadPackageParam) {
        val clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader) ?: return

        fun callOriginal(param: XC_MethodHook.MethodHookParam): Any? =
            XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)

        val stringReplacement = object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any? {
                val name = param.args.getOrNull(1) as? String ?: return callOriginal(param)
                return when (name) {
                    in NULL_STRING_KEYS -> null
                    in ZERO_VALUE_KEYS -> "0"
                    else -> callOriginal(param)
                }
            }
        }

        val intReplacement = object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any? {
                val name = param.args.getOrNull(1) as? String ?: return callOriginal(param)
                return if (name in ZERO_VALUE_KEYS) 0 else callOriginal(param)
            }
        }

        val resolverType = android.content.ContentResolver::class.java
        val stringType = String::class.java
        val intType = Int::class.javaPrimitiveType!!

        val signatures = listOf(
            arrayOf(resolverType, stringType),
            arrayOf(resolverType, stringType, intType),
        )

        for (methodName in listOf("getString", "getStringForUser")) {
            for (signature in signatures) {
                try {
                    XposedHelpers.findAndHookMethod(clazz, methodName, *signature, stringReplacement)
                } catch (t: Throwable) {
                    // Not present with this signature on this API level.
                }
            }
        }

        for (methodName in listOf("getInt", "getIntForUser")) {
            for (signature in signatures) {
                try {
                    XposedHelpers.findAndHookMethod(clazz, methodName, *signature, intReplacement)
                } catch (t: Throwable) {
                    // Not present with this signature on this API level.
                }
            }
        }
    }
}
