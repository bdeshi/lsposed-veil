package space.bdeshi.veil

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

private const val SELF_PACKAGE = "space.bdeshi.veil"

private val NULL_STRING_KEYS_BY_FEATURE = mapOf(
    Features.ACCESSIBILITY to setOf("enabled_accessibility_services"),
)

private val ZERO_VALUE_KEYS_BY_FEATURE = mapOf(
    Features.ACCESSIBILITY to setOf("accessibility_enabled", "touch_exploration_enabled"),
    Features.ADB to setOf("adb_enabled", "adb_wifi_enabled"),
    Features.DEV_OPTIONS to setOf("development_settings_enabled"),
    Features.MOCK_LOCATION to setOf("mock_location", "allow_mock_location"),
)

class VeilHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName == SELF_PACKAGE) return

        val prefs = openPrefs()
        val enabled = readEnabledFeatures(prefs, lpparam.packageName)
        if (enabled.isEmpty()) return

        if (Features.ACCESSIBILITY in enabled) {
            hookAccessibilityManager(lpparam)
        }

        val nullStringKeys = enabled.flatMap { NULL_STRING_KEYS_BY_FEATURE[it].orEmpty() }.toSet()
        val zeroValueKeys = enabled.flatMap { ZERO_VALUE_KEYS_BY_FEATURE[it].orEmpty() }.toSet()
        if (nullStringKeys.isNotEmpty() || zeroValueKeys.isNotEmpty()) {
            hookSettingsClass("android.provider.Settings\$Secure", lpparam, nullStringKeys, zeroValueKeys)
            hookSettingsClass("android.provider.Settings\$Global", lpparam, nullStringKeys, zeroValueKeys)
        }

        if (Features.ROOT_APPS in enabled) {
            val hiddenPackages = readHiddenPackages(prefs)
            if (hiddenPackages.isNotEmpty()) {
                hookPackageManager(lpparam, hiddenPackages)
            }
        }

        if (Features.BUILD_TAGS in enabled) {
            spoofBuildTags()
        }
    }

    private fun openPrefs(): XSharedPreferences? {
        return try {
            XSharedPreferences(SELF_PACKAGE, Features.PREFS_NAME).apply { reload() }
        } catch (t: Throwable) {
            null
        }
    }

    private fun readEnabledFeatures(prefs: XSharedPreferences?, packageName: String): Set<String> {
        // Config unreadable (never configured, or permissions not yet fixed) — fail open to "hide everything".
        return prefs?.getStringSet(Features.keyFor(packageName), Features.ALL) ?: Features.ALL
    }

    private fun readHiddenPackages(prefs: XSharedPreferences?): Set<String> {
        return prefs?.getStringSet(Features.HIDDEN_PACKAGES_KEY, Features.DEFAULT_HIDDEN_PACKAGES)
            ?: Features.DEFAULT_HIDDEN_PACKAGES
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

    private fun hookSettingsClass(
        className: String,
        lpparam: LoadPackageParam,
        nullStringKeys: Set<String>,
        zeroValueKeys: Set<String>,
    ) {
        val clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader) ?: return

        fun callOriginal(param: XC_MethodHook.MethodHookParam): Any? =
            XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)

        val stringReplacement = object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any? {
                val name = param.args.getOrNull(1) as? String ?: return callOriginal(param)
                return when (name) {
                    in nullStringKeys -> null
                    in zeroValueKeys -> "0"
                    else -> callOriginal(param)
                }
            }
        }

        val intReplacement = object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any? {
                val name = param.args.getOrNull(1) as? String ?: return callOriginal(param)
                return if (name in zeroValueKeys) 0 else callOriginal(param)
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

    private fun hookPackageManager(lpparam: LoadPackageParam, hiddenPackages: Set<String>) {
        val clazz = XposedHelpers.findClassIfExists(
            "android.app.ApplicationPackageManager",
            lpparam.classLoader,
        ) ?: return

        val throwIfHidden = object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any? {
                val name = param.args.getOrNull(0) as? String
                if (name != null && name in hiddenPackages) {
                    throw PackageManager.NameNotFoundException(name)
                }
                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
            }
        }

        val filterList = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                @Suppress("UNCHECKED_CAST")
                val list = param.result as? MutableList<Any?> ?: return
                list.removeAll { entry ->
                    val name = (entry as? PackageInfo)?.packageName ?: (entry as? ApplicationInfo)?.packageName
                    name != null && name in hiddenPackages
                }
            }
        }

        val flagsClasses = listOfNotNull(
            Int::class.javaPrimitiveType,
            XposedHelpers.findClassIfExists("android.content.pm.PackageManager\$PackageInfoFlags", lpparam.classLoader),
        )
        val appFlagsClasses = listOfNotNull(
            Int::class.javaPrimitiveType,
            XposedHelpers.findClassIfExists("android.content.pm.PackageManager\$ApplicationInfoFlags", lpparam.classLoader),
        )
        val stringType = String::class.java

        for (flagsType in flagsClasses) {
            try {
                XposedHelpers.findAndHookMethod(clazz, "getPackageInfo", stringType, flagsType, throwIfHidden)
            } catch (t: Throwable) {
                // Not present with this signature on this API level.
            }
            try {
                XposedHelpers.findAndHookMethod(clazz, "getInstalledPackages", flagsType, filterList)
            } catch (t: Throwable) {
                // Not present with this signature on this API level.
            }
        }
        for (flagsType in appFlagsClasses) {
            try {
                XposedHelpers.findAndHookMethod(clazz, "getApplicationInfo", stringType, flagsType, throwIfHidden)
            } catch (t: Throwable) {
                // Not present with this signature on this API level.
            }
            try {
                XposedHelpers.findAndHookMethod(clazz, "getInstalledApplications", flagsType, filterList)
            } catch (t: Throwable) {
                // Not present with this signature on this API level.
            }
        }
    }

    private fun spoofBuildTags() {
        try {
            XposedHelpers.setStaticObjectField(Build::class.java, "TAGS", "release-keys")
            XposedHelpers.setStaticObjectField(Build::class.java, "TYPE", "user")
        } catch (t: Throwable) {
            // Field not present/settable on this API level — safe to skip.
        }
    }
}
