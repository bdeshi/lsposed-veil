package space.bdeshi.veil

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.VersionedPackage
import android.content.ContentResolver
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
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
        hookSettingsProviderAccess(lpparam)
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

    // The Settings classes above end up in the settings provider; an app can skip them and reach
    // it directly, either through ContentResolver.call(content://settings/<table>, "GET_<table>",
    // key), whose Bundle carries the answer under "value", or through ContentResolver.query,
    // whose cursor carries name/value rows. Mask both. The Settings classes' own cache goes
    // through IContentProvider, not ContentResolver, so nothing is masked twice.
    private fun hookSettingsProviderAccess(lpparam: LoadPackageParam) {
        val resolverClass = XposedHelpers.findClassIfExists("android.content.ContentResolver", lpparam.classLoader)
            ?: return

        val maskValue = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val authority = when (val target = param.args.getOrNull(0)) {
                    is Uri -> target.authority
                    is String -> target
                    else -> return
                }
                if (authority != "settings") return
                val method = param.args.getOrNull(1) as? String ?: return
                val key = param.args.getOrNull(2) as? String ?: return
                val result = param.result as? Bundle ?: return
                when (method) {
                    "GET_secure" -> when (key) {
                        in SECURE_NULL_STRING_KEYS -> result.putString("value", null)
                        in SECURE_ZERO_VALUE_KEYS -> result.putString("value", "0")
                    }
                    "GET_global" -> if (key in GLOBAL_ZERO_VALUE_KEYS) result.putString("value", "0")
                }
            }
        }

        // Every query() overload funnels into this one.
        val maskCursor = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val uri = param.args.getOrNull(0) as? Uri ?: return
                if (uri.authority != "settings") return
                val table = uri.pathSegments?.firstOrNull() ?: return
                if (table != "secure" && table != "global") return
                val cursor = param.result as? Cursor ?: return
                val nameIndex = cursor.getColumnIndex("name")
                val valueIndex = cursor.getColumnIndex("value")
                if (valueIndex < 0) return
                // A caller can project just "value". The key is then only in the URI path
                // (settings/global/<key>) or in the selection args (name=?), so take it from there.
                val queriedName = if (nameIndex >= 0) null else {
                    uri.pathSegments.getOrNull(1)
                        ?: (param.args.getOrNull(2) as? Bundle)
                            ?.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)?.singleOrNull()
                }
                if (nameIndex < 0 && queriedName == null) return

                fun masked(name: String?): Pair<Boolean, String?> = when (table) {
                    "secure" -> when (name) {
                        in SECURE_NULL_STRING_KEYS -> true to null
                        in SECURE_ZERO_VALUE_KEYS -> true to "0"
                        else -> false to null
                    }
                    "global" -> if (name in GLOBAL_ZERO_VALUE_KEYS) true to "0" else false to null
                    else -> false to null
                }

                val rows = mutableListOf<Array<Any?>>()
                var changed = false
                if (cursor.moveToFirst()) {
                    do {
                        val row = arrayOfNulls<Any?>(cursor.columnCount)
                        for (i in row.indices) {
                            row[i] = when (cursor.getType(i)) {
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                                Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                                Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i)
                                else -> null
                            }
                        }
                        val (hide, value) = masked(if (nameIndex >= 0) row[nameIndex] as? String else queriedName)
                        if (hide) {
                            row[valueIndex] = value
                            changed = true
                        }
                        rows.add(row)
                    } while (cursor.moveToNext())
                }
                if (!changed) {
                    cursor.moveToPosition(-1)
                    return
                }
                val replacement = MatrixCursor(cursor.columnNames, rows.size)
                rows.forEach { replacement.addRow(it) }
                cursor.close()
                param.result = replacement
            }
        }

        val stringType = String::class.java
        try {
            XposedHelpers.findAndHookMethod(
                resolverClass, "query", Uri::class.java, Array<String>::class.java,
                Bundle::class.java, CancellationSignal::class.java, maskCursor,
            )
        } catch (t: Throwable) {
            // Not present on this API level.
        }
        for (signature in listOf(
            arrayOf<Class<*>>(Uri::class.java, stringType, stringType, Bundle::class.java),
            arrayOf<Class<*>>(stringType, stringType, stringType, Bundle::class.java),
        )) {
            try {
                XposedHelpers.findAndHookMethod(resolverClass, "call", *signature, maskValue)
            } catch (t: Throwable) {
                // Not present with this signature on this API level.
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
