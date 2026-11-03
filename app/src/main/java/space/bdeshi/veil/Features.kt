package space.bdeshi.veil

data class FeatureInfo(val label: String, val description: String)

object Features {
    const val SELF_PACKAGE = "space.bdeshi.veil"

    // Shown in the settings screen. Every one of these is always applied — see VeilHook.
    val INFO: List<FeatureInfo> = listOf(
        FeatureInfo(
            "Accessibility services",
            "Apps see zero accessibility services running, even if you have some enabled.",
        ),
        FeatureInfo(
            "USB / wireless debugging",
            "Apps see debugging as off, regardless of your actual setting.",
        ),
        FeatureInfo(
            "Developer options",
            "Apps see Developer Options as disabled.",
        ),
        FeatureInfo(
            "Mock location (legacy flag)",
            "Only covers the old pre-Android-6 system flag — modern per-app mock location isn't covered.",
        ),
        FeatureInfo(
            "Root / manager apps",
            "The packages below are invisible to apps' PackageManager lookups.",
        ),
        FeatureInfo(
            "Test-keys / userdebug build",
            "Apps see build tags as release-keys/user instead of your real build.",
        ),
    )

    // Fixed at build time; edit and rebuild to change. Veil itself is here too: an installed
    // hooking module is as much a tell as a root manager.
    val HIDDEN_PACKAGES: Set<String> = setOf(
        "me.weishu.kernelsu",
        "com.topjohnwu.magisk",
        SELF_PACKAGE,
    )
}
