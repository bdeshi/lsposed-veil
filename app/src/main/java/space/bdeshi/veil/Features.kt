package space.bdeshi.veil

object Features {
    const val PREFS_NAME = "hidea11y_prefs"
    const val KEY_PREFIX = "features_"

    const val ACCESSIBILITY = "accessibility"
    const val ADB = "adb"
    const val DEV_OPTIONS = "dev_options"
    const val MOCK_LOCATION = "mock_location"

    val ALL: Set<String> = setOf(ACCESSIBILITY, ADB, DEV_OPTIONS, MOCK_LOCATION)

    val LABELS: LinkedHashMap<String, String> = linkedMapOf(
        ACCESSIBILITY to "Accessibility services",
        ADB to "USB / wireless debugging",
        DEV_OPTIONS to "Developer options enabled",
        MOCK_LOCATION to "Mock location (legacy flag only)",
    )

    fun keyFor(packageName: String): String = KEY_PREFIX + packageName
}
