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
    //
    // Well-known root managers, module managers, hook frameworks and older su apps, by package
    // name. Packages that aren't installed match nothing, so a name that's wrong or absent is
    // harmless. Not covered: Xposed modules in general (they have no common package name;
    // detectors find them by manifest metadata).
    val HIDDEN_PACKAGES: Set<String> = setOf(
        SELF_PACKAGE,

        // Root managers: Magisk and forks, KernelSU and forks, APatch
        "com.topjohnwu.magisk",
        "io.github.huskydg.magisk",
        "io.github.vvb2060.magisk",
        "me.weishu.kernelsu",
        "com.rifsxd.ksunext",
        "com.sukisu.ultra",
        "me.bmax.apatch",

        // Module managers and helpers
        "com.dergoogler.mmrl",
        "com.dergoogler.mmrl.wx",
        "com.fox2code.mmm",
        "io.github.a13e300.ksuwebui",

        // Hook frameworks and their managers
        "org.matrix.vector.manager",
        "org.lsposed.manager",
        "org.lsposed.lspatch",
        "de.robv.android.xposed.installer",
        "org.meowcat.edxposed.manager",
        "com.solohsu.android.edxp.manager",
        "me.weishu.exp",
        "io.va.exposed",
        "com.saurik.substrate",

        // Older su apps and one-click root
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "com.noshufou.android.su",
        "com.noshufou.android.su.elite",
        "com.thirdparty.superuser",
        "com.yellowes.su",
        "me.phh.superuser",
        "com.alephzain.framaroot",
    )
}
