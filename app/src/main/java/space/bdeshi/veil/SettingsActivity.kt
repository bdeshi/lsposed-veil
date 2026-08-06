package space.bdeshi.veil

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

private val HELP_TIPS: List<Pair<String, String>> = listOf(
    "Target app still won't launch?" to
        "Check KernelSU Manager → the target app's profile → make sure \"Umount modules\" " +
        "is OFF for it. When that's on, Zygisk (and therefore LSPosed) skips loading into that " +
        "app's process entirely — no Veil setting can work around that, since the module " +
        "never gets injected in the first place.",
    "Using a separate Zygisk implementation?" to
        "Standalone Zygisk modules (e.g. Zygisk Next / ReZygisk) can carry their own independent " +
        "DenyList on top of KernelSU's. If an app is still being skipped after fixing the above, " +
        "check there too.",
    "Changed a setting but nothing happened?" to
        "Force-stop and reopen the target app. Veil reads its config once when that app's " +
        "process starts, so changes here (or to LSPosed scope) only take effect on the next " +
        "cold start — a full device reboot usually isn't necessary.",
    "How to confirm it's actually working" to
        "Turn on verbose logging in LSPosed Manager, then check its Logs tab after relaunching " +
        "the target app. No log lines from Veil there usually means it never got injected " +
        "(see the DenyList tip above), not a bug in this module.",
    "Forgot what an unconfigured app does?" to
        "Any app not in the list above gets every feature applied by default — check here " +
        "first if something's being hidden that you didn't expect.",
)

class SettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var appListContainer: LinearLayout
    private lateinit var hiddenPackagesContainer: LinearLayout
    private lateinit var helpContainer: LinearLayout
    private lateinit var helpToggle: Button
    private lateinit var packageInput: EditText
    private lateinit var hiddenPackageInput: EditText
    private var helpVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Features.PREFS_NAME, Context.MODE_PRIVATE)

        val padding = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        root.addView(TextView(this).apply {
            text = "Veil"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })

        helpToggle = Button(this).apply {
            text = "Help & troubleshooting ▾"
            setOnClickListener {
                helpVisible = !helpVisible
                helpContainer.visibility = if (helpVisible) View.VISIBLE else View.GONE
                text = if (helpVisible) "Help & troubleshooting ▴" else "Help & troubleshooting ▾"
            }
        }
        root.addView(helpToggle)

        helpContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            visibility = View.GONE
            for ((title, body) in HELP_TIPS) {
                addView(TextView(this@SettingsActivity).apply {
                    text = title
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(8), 0, dp(2))
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = body
                    textSize = 13f
                })
            }
        }
        root.addView(helpContainer)

        root.addView(TextView(this).apply {
            text = "Add the package name of an app you've scoped this module to in LSPosed, " +
                "then choose which checks to hide from it. Apps you haven't added here get " +
                "everything hidden by default (matches this module's original behavior)."
            setPadding(0, dp(8), 0, dp(16))
        })

        packageInput = EditText(this).apply {
            hint = "e.g. com.example.targetapp"
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        root.addView(addRow(packageInput) {
            val pkg = packageInput.text.toString().trim()
            if (pkg.isNotEmpty() && pkg.contains(".")) {
                prefs.edit().putStringSet(Features.keyFor(pkg), Features.ALL.toMutableSet()).apply()
                fixPermissions()
                packageInput.setText("")
                refreshAppList()
            }
        })

        appListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(appListContainer)

        root.addView(TextView(this).apply {
            text = "Packages hidden from PackageManager"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(24), 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "Used by the \"Hide root / manager apps\" checkbox above — apps in this list " +
                "become invisible to PackageManager lookups for any target app with that box " +
                "checked."
            setPadding(0, 0, 0, dp(8))
        })

        hiddenPackageInput = EditText(this).apply {
            hint = "e.g. me.weishu.kernelsu"
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        root.addView(addRow(hiddenPackageInput) {
            val pkg = hiddenPackageInput.text.toString().trim()
            if (pkg.isNotEmpty() && pkg.contains(".")) {
                val current = hiddenPackages().toMutableSet()
                current.add(pkg)
                prefs.edit().putStringSet(Features.HIDDEN_PACKAGES_KEY, current).apply()
                fixPermissions()
                hiddenPackageInput.setText("")
                refreshHiddenPackages()
            }
        })

        hiddenPackagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(hiddenPackagesContainer)

        setContentView(ScrollView(this).apply { addView(root) })
        refreshAppList()
        refreshHiddenPackages()
    }

    private fun addRow(input: EditText, onAdd: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input)
            addView(Button(this@SettingsActivity).apply {
                text = "Add"
                setOnClickListener { onAdd() }
            })
        }
    }

    private fun hiddenPackages(): Set<String> =
        prefs.getStringSet(Features.HIDDEN_PACKAGES_KEY, Features.DEFAULT_HIDDEN_PACKAGES) ?: Features.DEFAULT_HIDDEN_PACKAGES

    private fun configuredPackages(): Set<String> =
        prefs.all.keys.filter { it.startsWith(Features.KEY_PREFIX) }.map { it.removePrefix(Features.KEY_PREFIX) }.toSet()

    private fun discoveredPackages(): Set<String> =
        prefs.getStringSet(Features.DISCOVERED_PACKAGES_KEY, emptySet()) ?: emptySet()

    private fun refreshAppList() {
        appListContainer.removeAllViews()
        val configured = configuredPackages()
        val packages = (configured + discoveredPackages()).sorted()

        if (packages.isEmpty()) {
            appListContainer.addView(TextView(this).apply {
                text = "No apps yet — scope Veil to a target app in LSPosed Manager and open " +
                    "it once, or add a package name above."
            })
            return
        }

        for (pkg in packages) {
            appListContainer.addView(buildPackageCard(pkg, isDiscoveredOnly = pkg !in configured))
        }
    }

    private fun buildPackageCard(pkg: String, isDiscoveredOnly: Boolean): View {
        val enabled = prefs.getStringSet(Features.keyFor(pkg), Features.ALL) ?: Features.ALL

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = pkg
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        if (isDiscoveredOnly) {
            titleRow.addView(TextView(this).apply {
                text = " (auto-detected)"
                textSize = 13f
            })
        }
        card.addView(titleRow)
        card.addView(TextView(this).apply {
            text = "Checking a box below hides that thing from this app specifically — it stays " +
                "on for every other app."
            setPadding(0, 0, 0, dp(8))
        })

        for ((id, info) in Features.INFO) {
            card.addView(CheckBox(this).apply {
                text = info.label
                isChecked = id in enabled
                setOnCheckedChangeListener { _, isChecked ->
                    val current = (prefs.getStringSet(Features.keyFor(pkg), Features.ALL) ?: Features.ALL).toMutableSet()
                    if (isChecked) current.add(id) else current.remove(id)
                    prefs.edit().putStringSet(Features.keyFor(pkg), current).apply()
                    fixPermissions()
                }
            })
            card.addView(TextView(this).apply {
                text = info.description
                textSize = 13f
                setPadding(dp(32), 0, 0, dp(8))
            })
        }

        card.addView(Button(this).apply {
            text = "Remove (revert to default: hide everything)"
            setOnClickListener {
                prefs.edit().remove(Features.keyFor(pkg)).apply()
                fixPermissions()
                refreshAppList()
            }
        })

        return card
    }

    private fun refreshHiddenPackages() {
        hiddenPackagesContainer.removeAllViews()
        val packages = hiddenPackages().sorted()

        if (packages.isEmpty()) {
            hiddenPackagesContainer.addView(TextView(this).apply {
                text = "None configured."
            })
            return
        }

        for (pkg in packages) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = pkg
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "Remove"
                setOnClickListener {
                    val current = hiddenPackages().toMutableSet()
                    current.remove(pkg)
                    prefs.edit().putStringSet(Features.HIDDEN_PACKAGES_KEY, current).apply()
                    fixPermissions()
                    refreshHiddenPackages()
                }
            })
            hiddenPackagesContainer.addView(row)
        }
    }

    // LSPosed's own SELinux policy is what actually lets hooked processes read this file;
    // this just ensures the DAC bits don't block it too.
    private fun fixPermissions() {
        try {
            val prefsDir = File(applicationInfo.dataDir, "shared_prefs")
            prefsDir.setReadable(true, false)
            prefsDir.setExecutable(true, false)
            prefsDir.listFiles()?.forEach { it.setReadable(true, false) }
        } catch (t: Throwable) {
            // Best-effort.
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
