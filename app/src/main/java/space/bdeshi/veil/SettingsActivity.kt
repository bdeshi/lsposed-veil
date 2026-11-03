package space.bdeshi.veil

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

private val HELP_TIPS: List<Pair<String, String>> = listOf(
    "Target app still won't launch?" to
        "Check KernelSU Manager → the target app's profile → make sure \"Umount modules\" " +
        "is OFF for it. When that's on, Zygisk (and therefore LSPosed) skips loading into that " +
        "app's process entirely — Veil never gets injected in the first place.",
    "Using a separate Zygisk implementation?" to
        "Standalone Zygisk modules (e.g. Zygisk Next / ReZygisk) can carry their own independent " +
        "DenyList on top of KernelSU's. If an app is still being skipped after fixing the above, " +
        "check there too.",
    "Want something left visible to one app?" to
        "Take that app out of Veil's scope in LSPosed Manager. There's no per-app configuration: " +
        "hooked apps can't read anything this screen could save, so it would be silently ignored.",
    "Need a different package list?" to
        "It's fixed in this build: edit Features.HIDDEN_PACKAGES and rebuild. For per-app package " +
        "lists, Hide My Applist is built for exactly that.",
    "Scope changed but nothing happened?" to
        "Force-stop and reopen the target app. Veil is applied when that app's process starts, " +
        "so scope changes only take effect on its next cold start.",
    "How to confirm it's actually working" to
        "Turn on verbose logging in LSPosed Manager, then check its Logs tab after relaunching " +
        "the target app. No log lines from Veil there usually means it never got injected " +
        "(see the DenyList tip above). Turn verbose logging off again afterwards: it writes a " +
        "line naming Veil into each hooked app's own logcat, which that app can read.",
)

class SettingsActivity : AppCompatActivity() {

    private lateinit var helpContainer: View
    private var helpVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            text = "Hides the same things from every app in Veil's LSPosed scope."
            textSize = 14f
            alpha = 0.7f
            setPadding(0, dp(2), 0, dp(16))
        })

        root.addView(MaterialButton(this).apply {
            text = "Help & troubleshooting ▾"
            setOnClickListener {
                helpVisible = !helpVisible
                helpContainer.visibility = if (helpVisible) View.VISIBLE else View.GONE
                text = if (helpVisible) "Help & troubleshooting ▴" else "Help & troubleshooting ▾"
            }
        })

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

        root.addView(sectionTitle("What's hidden"), marginTop(dp(16)))
        val (featureCard, featureContent) = card()
        for (info in Features.INFO) {
            featureContent.addView(TextView(this).apply {
                text = info.label
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(6), 0, dp(2))
            })
            featureContent.addView(TextView(this).apply {
                text = info.description
                textSize = 13f
                alpha = 0.7f
            })
        }
        root.addView(featureCard, marginTop(dp(8)))

        root.addView(sectionTitle("Hidden packages (${Features.HIDDEN_PACKAGES.size})"), marginTop(dp(16)))
        root.addView(TextView(this).apply {
            text = "Fixed in this build — edit Features.HIDDEN_PACKAGES and rebuild to change. " +
                "Packages that aren't installed match nothing."
            textSize = 13f
            alpha = 0.8f
            setPadding(0, dp(4), 0, dp(8))
        })
        val packageChips = ChipGroup(this).apply {
            visibility = View.GONE
            for (pkg in Features.HIDDEN_PACKAGES.sorted()) {
                addView(Chip(this@SettingsActivity).apply {
                    text = pkg
                    isClickable = false
                    isCheckable = false
                })
            }
        }
        root.addView(MaterialButton(this).apply {
            text = "Show list ▾"
            setOnClickListener {
                val show = packageChips.visibility != View.VISIBLE
                packageChips.visibility = if (show) View.VISIBLE else View.GONE
                text = if (show) "Hide list ▴" else "Show list ▾"
            }
        })
        root.addView(packageChips)

        setContentView(ScrollView(this).apply { addView(root); fitsSystemWindows = true })
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

    /** A MaterialCardView with an inner padded vertical container. */
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
            )
            addView(content)
        }
        return cardView to content
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
