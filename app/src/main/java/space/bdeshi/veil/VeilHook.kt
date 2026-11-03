package space.bdeshi.veil

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

// Keyed by the Settings class each key actually lives on, so we only ever install hooks on
// the one class that could plausibly be asked about a given feature — never both.
private val SECURE_NULL_STRING_KEYS_BY_FEATURE = mapOf(
    Features.ACCESSIBILITY to setOf("enabled_accessibility_services"),
)

private val SECURE_ZERO_VALUE_KEYS_BY_FEATURE = mapOf(
    Features.ACCESSIBILITY to setOf("accessibility_enabled", "touch_exploration_enabled"),
    Features.MOCK_LOCATION to setOf("mock_location", "allow_mock_location"),
)

private val GLOBAL_ZERO_VALUE_KEYS_BY_FEATURE = mapOf(
    Features.ADB to setOf("adb_enabled", "adb_wifi_enabled"),
    Features.DEV_OPTIONS to setOf("development_settings_enabled"),
)

private fun keysForEnabledFeatures(
    enabled: Set<String>,
    byFeature: Map<String, Set<String>>,
): Set<String> {
    if (enabled.isEmpty() || byFeature.isEmpty()) return emptySet()
    val result = mutableSetOf<String>()
    for (feature in enabled) {
        byFeature[feature]?.let { result.addAll(it) }
    }
    return result
}

class VeilHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName == Features.SELF_PACKAGE) return

        reportScoped(lpparam)

        val prefs = openPrefs()
        val enabled = readEnabledFeatures(prefs, lpparam.packageName)
        if (enabled.isEmpty()) return

        if (Features.ACCESSIBILITY in enabled) {
            hookAccessibilityManager(lpparam)
        }

        val secureNullKeys = keysForEnabledFeatures(enabled, SECURE_NULL_STRING_KEYS_BY_FEATURE)
        val secureZeroKeys = keysForEnabledFeatures(enabled, SECURE_ZERO_VALUE_KEYS_BY_FEATURE)
        if (secureNullKeys.isNotEmpty() || secureZeroKeys.isNotEmpty()) {
            hookSettingsClass("android.provider.Settings\$Secure", lpparam, secureNullKeys, secureZeroKeys)
        }

        val globalZeroKeys = keysForEnabledFeatures(enabled, GLOBAL_ZERO_VALUE_KEYS_BY_FEATURE)
        if (globalZeroKeys.isNotEmpty()) {
            hookSettingsClass("android.provider.Settings\$Global", lpparam, emptySet(), globalZeroKeys)
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

    // Lets Veil's own settings screen list this app automatically, without the user re-typing
    // a package name it can already see was actually injected. Application#attach is the
    // earliest reliable point at which a real Context exists in the hooked process.
    private fun reportScoped(lpparam: LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val context = param.args[0] as Context
                            // Most apps can't see Veil (Android 11+ package visibility), and
                            // then can't reach its provider either. Checking first skips the
                            // doomed call, and with it the "Failed to find provider info"
                            // line naming Veil that the framework would write to the app's
                            // own, app-readable logcat. Such apps are added via the picker.
                            if (context.packageManager.resolveContentProvider(Features.PROVIDER_AUTHORITY, 0) == null) {
                                return
                            }
                            context.contentResolver.call(
                                Uri.parse("content://${Features.PROVIDER_AUTHORITY}"),
                                "register",
                                lpparam.packageName,
                                null,
                            )
                        } catch (t: Throwable) {
                            // Veil app unreachable — doesn't affect the hiding hooks below.
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            // Application#attach signature not present — safe to skip discovery only.
        }
    }

    private fun openPrefs(): XSharedPreferences? {
        return try {
            XSharedPreferences(Features.SELF_PACKAGE, Features.PREFS_NAME).apply { reload() }
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
