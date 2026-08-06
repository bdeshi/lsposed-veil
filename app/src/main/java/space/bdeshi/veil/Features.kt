package space.bdeshi.veil

data class FeatureInfo(val label: String, val description: String)

object Features {
    const val PREFS_NAME = "veil_prefs"
    const val KEY_PREFIX = "features_"
    const val HIDDEN_PACKAGES_KEY = "hidden_packages"
    const val DISCOVERED_PACKAGES_KEY = "discovered_packages"
    const val PROVIDER_AUTHORITY = "space.bdeshi.veil.provider"

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

    val INFO: LinkedHashMap<String, FeatureInfo> = linkedMapOf(
        ACCESSIBILITY to FeatureInfo(
            "Hide accessibility services",
            "This app will see zero accessibility services running, even if you have some enabled.",
        ),
        ADB to FeatureInfo(
            "Hide USB / wireless debugging",
            "This app will see debugging as off, regardless of your actual setting.",
        ),
        DEV_OPTIONS to FeatureInfo(
            "Hide developer options",
            "This app will see Developer Options as disabled.",
        ),
        MOCK_LOCATION to FeatureInfo(
            "Hide mock location (legacy flag)",
            "Only covers the old pre-Android-6 system flag — modern per-app mock location isn't covered.",
        ),
        ROOT_APPS to FeatureInfo(
            "Hide root / manager apps",
            "Apps in the \"hidden packages\" list below become invisible to this app's PackageManager lookups.",
        ),
        BUILD_TAGS to FeatureInfo(
            "Hide test-keys / userdebug build",
            "This app will see build tags as release-keys/user instead of your real build.",
        ),
    )

    val DEFAULT_HIDDEN_PACKAGES: Set<String> = setOf(
        "me.weishu.kernelsu",
        "com.topjohnwu.magisk",
    )

    fun keyFor(packageName: String): String = KEY_PREFIX + packageName
}
