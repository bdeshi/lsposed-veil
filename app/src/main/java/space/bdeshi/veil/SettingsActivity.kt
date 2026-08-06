package space.bdeshi.veil

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
        "Any app not in the list below gets every feature applied by default — check here " +
        "first if something's being hidden that you didn't expect.",
)

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var appListContainer: LinearLayout
    private lateinit var hiddenPackagesChips: ChipGroup
    private lateinit var helpContainer: View
    private lateinit var helpToggle: MaterialButton
    private lateinit var hiddenPackageInput: TextInputEditText
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
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Hide device state from specific apps, per app."
            textSize = 14f
            alpha = 0.7f
            setPadding(0, dp(2), 0, dp(16))
        })

        helpToggle = MaterialButton(this).apply {
            text = "Help & troubleshooting ▾"
            setOnClickListener {
                helpVisible = !helpVisible
                helpContainer.visibility = if (helpVisible) View.VISIBLE else View.GONE
                text = if (helpVisible) "Help & troubleshooting ▴" else "Help & troubleshooting ▾"
            }
        }
        root.addView(helpToggle)

        val (helpCard, helpContent) = card()
        helpCard.visibility = View.GONE
        helpContainer = helpCard
        for ((title, body) in HELP_TIPS) {
            helpContent.addView(TextView(this).apply {
                text = title
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(8), 0, dp(2))
            })
            helpContent.addView(TextView(this).apply {
                text = body
                textSize = 13f
                alpha = 0.85f
            })
        }
        root.addView(helpCard, marginTop(dp(8)))

        root.addView(sectionTitle("Apps"))
        root.addView(TextView(this).apply {
            text = "Scope this module to a target app in LSPosed Manager, then open that app " +
                "once — it shows up here automatically. Apps not listed here get everything " +
                "hidden by default."
            textSize = 13f
            alpha = 0.8f
            setPadding(0, dp(4), 0, dp(8))
        })

        appListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(appListContainer)

        root.addView(sectionTitle("Packages hidden from PackageManager"), marginTop(dp(24)))
        root.addView(TextView(this).apply {
            text = "Used by the \"Hide root / manager apps\" checkbox above — apps here become " +
                "invisible to PackageManager lookups for any target app with that box checked."
            textSize = 13f
            alpha = 0.8f
            setPadding(0, dp(4), 0, dp(8))
        })

        hiddenPackageInput = textInput("e.g. me.weishu.kernelsu")
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

        hiddenPackagesChips = ChipGroup(this).apply {
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(hiddenPackagesChips)

        setContentView(ScrollView(this).apply { addView(root) })
        refreshAppList()
        refreshHiddenPackages()
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun marginTop(px: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = px
        }

    private fun textInput(hintText: String): TextInputEditText {
        val editText = TextInputEditText(this).apply {
            hint = hintText
            inputType = InputType.TYPE_CLASS_TEXT
        }
        return editText
    }

    private fun addRow(input: TextInputEditText, onAdd: () -> Unit): View {
        val layout = TextInputLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(input)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(layout)
            addView(MaterialButton(this@SettingsActivity).apply {
                text = "Add"
                setOnClickListener { onAdd() }
            })
        }
    }

    /** A MaterialCardView with an inner padded vertical container, added to root with bottom margin. */
    private fun card(): Pair<MaterialCardView, LinearLayout> {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val cardView = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            cardElevation = dp(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
            addView(content)
        }
        return cardView to content
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
                    "it once."
                textSize = 13f
                alpha = 0.8f
            })
            return
        }

        for (pkg in packages) {
            appListContainer.addView(buildPackageCard(pkg, isDiscoveredOnly = pkg !in configured))
        }
    }

    private fun buildPackageCard(pkg: String, isDiscoveredOnly: Boolean): View {
        val enabled = prefs.getStringSet(Features.keyFor(pkg), Features.ALL) ?: Features.ALL
        val (cardView, content) = card()

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = pkg
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (isDiscoveredOnly) {
            titleRow.addView(Chip(this).apply {
                text = "Auto-detected"
                isClickable = false
                isCheckable = false
            })
        }
        content.addView(titleRow)

        content.addView(TextView(this).apply {
            text = "Checking a box below hides that thing from this app specifically — it " +
                "stays on for every other app."
            textSize = 12f
            alpha = 0.7f
            setPadding(0, dp(2), 0, dp(8))
        })

        for ((id, info) in Features.INFO) {
            content.addView(MaterialCheckBox(this).apply {
                text = info.label
                isChecked = id in enabled
                setOnCheckedChangeListener { _, isChecked ->
                    val current = (prefs.getStringSet(Features.keyFor(pkg), Features.ALL) ?: Features.ALL).toMutableSet()
                    if (isChecked) current.add(id) else current.remove(id)
                    prefs.edit().putStringSet(Features.keyFor(pkg), current).apply()
                    fixPermissions()
                }
            })
            content.addView(TextView(this).apply {
                text = info.description
                textSize = 13f
                alpha = 0.7f
                setPadding(dp(32), 0, 0, dp(8))
            })
        }

        content.addView(MaterialButton(this).apply {
            text = "Apply defaults (hide everything)"
            setOnClickListener {
                prefs.edit().remove(Features.keyFor(pkg)).apply()
                fixPermissions()
                refreshAppList()
            }
        })

        return cardView
    }

    private fun refreshHiddenPackages() {
        hiddenPackagesChips.removeAllViews()
        for (pkg in hiddenPackages().sorted()) {
            hiddenPackagesChips.addView(Chip(this).apply {
                text = pkg
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    val current = hiddenPackages().toMutableSet()
                    current.remove(pkg)
                    prefs.edit().putStringSet(Features.HIDDEN_PACKAGES_KEY, current).apply()
                    fixPermissions()
                    refreshHiddenPackages()
                }
            })
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
