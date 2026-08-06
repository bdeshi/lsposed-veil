package space.bdeshi.veil

object Features {
    const val PREFS_NAME = "veil_prefs"
    const val KEY_PREFIX = "features_"
    const val HIDDEN_PACKAGES_KEY = "hidden_packages"

    const val ACCESSIBILITY = "accessibility"
    const val ADB = "adb"
    const val DEV_OPTIONS = "dev_options"
    const val MOCK_LOCATION = "mock_location"
    const val ROOT_APPS = "root_apps"
    const val BUILD_TAGS = "build_tags"

    val ALL: Set<String> = setOf(
        ACCESSIBILITY,
        ADB,
        DEV_OPTIONS,
        MOCK_LOCATION,
        ROOT_APPS,
        BUILD_TAGS,
    )

    val LABELS: LinkedHashMap<String, String> = linkedMapOf(
        ACCESSIBILITY to "Accessibility services",
        ADB to "USB / wireless debugging",
        DEV_OPTIONS to "Developer options enabled",
        MOCK_LOCATION to "Mock location (legacy flag only)",
        ROOT_APPS to "Root/manager apps (PackageManager)",
        BUILD_TAGS to "Build tags/type (test-keys, userdebug)",
    )

    val DEFAULT_HIDDEN_PACKAGES: Set<String> = setOf(
        "me.weishu.kernelsu",
        "com.topjohnwu.magisk",
    )

    fun keyFor(packageName: String): String = KEY_PREFIX + packageName
}
