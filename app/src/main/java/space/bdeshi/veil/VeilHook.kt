package space.bdeshi.veil

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.VersionedPackage
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

// Keyed by the Settings class each key actually lives on, so we only ever install hooks on
// the one class that could plausibly be asked about a given key — never both.
private val SECURE_NULL_STRING_KEYS = setOf("enabled_accessibility_services")

private val SECURE_ZERO_VALUE_KEYS = setOf(
    "accessibility_enabled",
    "touch_exploration_enabled",
    "mock_location",
    "allow_mock_location",
)

private val GLOBAL_ZERO_VALUE_KEYS = setOf(
    "adb_enabled",
    "adb_wifi_enabled",
    "development_settings_enabled",
)

/**
 * Applies the same fixed baseline to every app in Veil's LSPosed scope. There is deliberately
 * no per-app configuration: a hooked app can't read anything Veil's own app saves (its data
 * dir is private to it, and the framework's shared-prefs redirect for legacy modules depends
 * on the framework hooking Veil's own process, which Vector doesn't reliably do), so settings
 * would silently be ignored. What's hidden lives in this file and Features.HIDDEN_PACKAGES;
 * change it there and rebuild.
 */
class VeilHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName == Features.SELF_PACKAGE) return

        hookAccessibilityManager(lpparam)
        hookSettingsClass("android.provider.Settings\$Secure", lpparam, SECURE_NULL_STRING_KEYS, SECURE_ZERO_VALUE_KEYS)
        hookSettingsClass("android.provider.Settings\$Global", lpparam, emptySet(), GLOBAL_ZERO_VALUE_KEYS)
        hookPackageManager(lpparam, Features.HIDDEN_PACKAGES)
        spoofBuildTags()
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

        // beforeHookedMethod + param.result, not XC_MethodReplacement: some overloads (e.g. the
        // 2-arg Settings.Global.getInt) throw a checked SettingNotFoundException for keys they
        // don't recognize. Manually calling XposedBridge.invokeOriginalMethod for the pass-through
        // case routes through Method.invoke(), which wraps ANY exception the real method throws
        // in InvocationTargetException — breaking callers that catch the real exception type.
        // Leaving param untouched for the pass-through case lets the original run through its
        // normal (non-reflective) path instead, so its real exceptions propagate correctly.
        val stringHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val name = param.args.getOrNull(1) as? String ?: return
                when (name) {
                    in nullStringKeys -> param.result = null
                    in zeroValueKeys -> param.result = "0"
                }
            }
        }

        val intHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val name = param.args.getOrNull(1) as? String ?: return
                if (name in zeroValueKeys) param.result = 0
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
                    XposedHelpers.findAndHookMethod(clazz, methodName, *signature, stringHook)
                } catch (t: Throwable) {
                    // Not present with this signature on this API level.
                }
            }
        }

        for (methodName in listOf("getInt", "getIntForUser")) {
            for (signature in signatures) {
                try {
                    XposedHelpers.findAndHookMethod(clazz, methodName, *signature, intHook)
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

        // See hookSettingsClass for why this is beforeHookedMethod + param.throwable rather than
        // XC_MethodReplacement: getPackageInfo/getApplicationInfo throw NameNotFoundException for
        // ANY not-installed package, not just ones we hide. Calling invokeOriginalMethod ourselves
        // for the pass-through case would wrap that entirely normal exception in an
        // InvocationTargetException via Method.invoke(), which callers don't expect and crash on.
        val throwIfHidden = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val name = param.args.getOrNull(0) as? String
                if (name != null && name in hiddenPackages) {
                    param.throwable = PackageManager.NameNotFoundException(name)
                }
            }
        }

        // Same lookup keyed by a VersionedPackage rather than a name.
        val throwIfVersionedHidden = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val name = (param.args.getOrNull(0) as? VersionedPackage)?.packageName
                if (name != null && name in hiddenPackages) {
                    param.throwable = PackageManager.NameNotFoundException(name)
                }
            }
        }

        // For an unknown package the real getApplicationEnabledSetting throws an unchecked
        // IllegalArgumentException, not NameNotFoundException.
        val throwUnknownIfHidden = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val name = param.args.getOrNull(0) as? String
                if (name != null && name in hiddenPackages) {
                    param.throwable = IllegalArgumentException("Unknown package: $name")
                }
            }
        }

        val nullIfHidden = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val name = param.args.getOrNull(0) as? String
                if (name != null && name in hiddenPackages) {
                    param.result = null
                }
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
            try {
                XposedHelpers.findAndHookMethod(clazz, "getPackageUid", stringType, flagsType, throwIfHidden)
            } catch (t: Throwable) {
                // Not present with this signature on this API level.
            }
            try {
                XposedHelpers.findAndHookMethod(clazz, "getPackageInfo", VersionedPackage::class.java, flagsType, throwIfVersionedHidden)
            } catch (t: Throwable) {
                // Not present with this signature on this API level.
            }
        }

        try {
            XposedHelpers.findAndHookMethod(clazz, "getLaunchIntentForPackage", stringType, nullIfHidden)
        } catch (t: Throwable) {
            // Not present on this API level.
        }
        try {
            XposedHelpers.findAndHookMethod(clazz, "getApplicationEnabledSetting", stringType, throwUnknownIfHidden)
        } catch (t: Throwable) {
            // Not present on this API level.
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
