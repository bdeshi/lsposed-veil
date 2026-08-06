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

class SettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var listContainer: LinearLayout
    private lateinit var packageInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Features.PREFS_NAME, Context.MODE_PRIVATE)

        val padding = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        root.addView(TextView(this).apply {
            text = "Hide A11y"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Add the package name of an app you've scoped this module to in LSPosed, " +
                "then choose which checks to hide from it. Apps you haven't added here get " +
                "everything hidden by default (matches this module's original behavior)."
            setPadding(0, dp(8), 0, dp(16))
        })

        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        packageInput = EditText(this).apply {
            hint = "e.g. com.example.targetapp"
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val addButton = Button(this).apply {
            text = "Add"
            setOnClickListener {
                val pkg = packageInput.text.toString().trim()
                if (pkg.isNotEmpty() && pkg.contains(".")) {
                    prefs.edit().putStringSet(Features.keyFor(pkg), Features.ALL.toMutableSet()).apply()
                    fixPermissions()
                    packageInput.setText("")
                    refreshList()
                }
            }
        }
        addRow.addView(packageInput)
        addRow.addView(addButton)
        root.addView(addRow)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(listContainer)

        setContentView(ScrollView(this).apply { addView(root) })
        refreshList()
    }

    private fun refreshList() {
        listContainer.removeAllViews()
        val packages = prefs.all.keys
            .filter { it.startsWith(Features.KEY_PREFIX) }
            .map { it.removePrefix(Features.KEY_PREFIX) }
            .sorted()

        if (packages.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "No apps configured yet."
            })
            return
        }

        for (pkg in packages) {
            listContainer.addView(buildPackageCard(pkg))
        }
    }

    private fun buildPackageCard(pkg: String): View {
        val enabled = prefs.getStringSet(Features.keyFor(pkg), Features.ALL) ?: Features.ALL

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        card.addView(TextView(this).apply {
            text = pkg
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })

        for ((id, label) in Features.LABELS) {
            card.addView(CheckBox(this).apply {
                text = label
                isChecked = id in enabled
                setOnCheckedChangeListener { _, isChecked ->
                    val current = (prefs.getStringSet(Features.keyFor(pkg), Features.ALL) ?: Features.ALL).toMutableSet()
                    if (isChecked) current.add(id) else current.remove(id)
                    prefs.edit().putStringSet(Features.keyFor(pkg), current).apply()
                    fixPermissions()
                }
            })
        }

        card.addView(Button(this).apply {
            text = "Remove (revert to default: hide everything)"
            setOnClickListener {
                prefs.edit().remove(Features.keyFor(pkg)).apply()
                fixPermissions()
                refreshList()
            }
        })

        return card
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
