package com.ravi.askgalaxy

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.util.Linkify
import android.text.method.LinkMovementMethod
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SettingsActivity : Activity() {
    private lateinit var summary: TextView
    private lateinit var progress: ProgressBar
    private lateinit var models: TextView
    private lateinit var personalContextStatus: TextView
    private lateinit var personalContextToggle: Switch
    private val handler = Handler(Looper.getMainLooper())
    private val contextExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var updatingContextToggle = false
    private var personalContextBaseStatus = ""
    private val refresh = object : Runnable {
        override fun run() {
            refreshState()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContent())
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        contextExecutor.shutdown()
        super.onDestroy()
    }

    private fun createContent(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 36)
            setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(this).apply {
            text = "Settings"
            textSize = 30f
            setTextColor(Color.rgb(20, 22, 28))
            setTypeface(typeface, Typeface.BOLD)
        }, wrap())
        root.addView(TextView(this).apply {
            text = "Ask Galaxy prepares privately in the background."
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(0, 8, 0, 28)
        }, wrap())

        summary = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.rgb(30, 32, 40))
        }
        root.addView(summary, wrap())
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            setPadding(0, 16, 0, 26)
        }
        root.addView(progress, wrap())

        root.addView(TextView(this).apply {
            text = "Model packages"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 12, 0, 12)
        }, wrap())
        models = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(50, 52, 60))
            setLineSpacing(5f, 1f)
        }
        root.addView(models, wrap())

        root.addView(TextView(this).apply {
            text = "Photo location enrichment"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 28, 0, 8)
        }, wrap())
        root.addView(TextView(this).apply {
            text = "GPS-tagged photos are resolved to readable place names during indexing. The Android system geocoder is tried first. If it has no result, Ask Galaxy can send coordinates rounded to about 110 m to the rate-limited OpenStreetMap lookup; successful names are cached on this phone."
            textSize = 14f
            setTextColor(Color.rgb(72, 75, 85))
            setLineSpacing(3f, 1f)
        }, wrap())
        root.addView(Switch(this).apply {
            text = "Use OpenStreetMap fallback"
            textSize = 15f
            setTextColor(Color.rgb(32, 34, 42))
            setPadding(0, 12, 0, 4)
            isChecked = LocationEnrichmentPreferences
                .isOpenStreetMapFallbackEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, enabled ->
                LocationEnrichmentPreferences.setOpenStreetMapFallbackEnabled(
                    this@SettingsActivity,
                    enabled,
                )
                if (enabled) PreparationScheduler.enqueue(this@SettingsActivity)
            }
        }, wrap())
        root.addView(TextView(this).apply {
            text = "Location data © OpenStreetMap contributors — https://www.openstreetmap.org/copyright"
            textSize = 12f
            setTextColor(Color.rgb(80, 83, 94))
            setPadding(0, 4, 0, 4)
            Linkify.addLinks(this, Linkify.WEB_URLS)
            movementMethod = LinkMovementMethod.getInstance()
        }, wrap())

        root.addView(TextView(this).apply {
            text = "Face groups"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 28, 0, 8)
        }, wrap())
        root.addView(TextView(this).apply {
            text = "Review the complete face-group set, merge every group that belongs to the same person, or add and change names later."
            textSize = 14f
            setTextColor(Color.rgb(72, 75, 85))
            setLineSpacing(3f, 1f)
        }, wrap())
        root.addView(Button(this).apply {
            text = "Review face groups and names"
            setAllCaps(false)
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, FaceClustersActivity::class.java))
            }
        }, wrap())

        root.addView(TextView(this).apply {
            text = "Personal context (optional)"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 28, 0, 8)
        }, wrap())
        root.addView(TextView(this).apply {
            text = "Ask Galaxy can use relevant future notifications such as flight bookings, hotel reservations, receipts, and deliveries. Everything stays on this phone and sensitive OTP, PIN, and bank-security alerts are skipped."
            textSize = 14f
            setTextColor(Color.rgb(72, 75, 85))
            setLineSpacing(3f, 1f)
        }, wrap())
        personalContextToggle = Switch(this).apply {
            text = "Use personal context in answers"
            textSize = 15f
            setTextColor(Color.rgb(32, 34, 42))
            setPadding(0, 12, 0, 4)
            setOnCheckedChangeListener { _, checked ->
                if (updatingContextToggle) return@setOnCheckedChangeListener
                if (checked && !PersonalContextAccess.isNotificationListenerEnabled(this@SettingsActivity)) {
                    updatingContextToggle = true
                    isChecked = false
                    updatingContextToggle = false
                    Toast.makeText(
                        this@SettingsActivity,
                        "Allow Ask Galaxy notification access first.",
                        Toast.LENGTH_LONG,
                    ).show()
                    PersonalContextAccess.openNotificationAccessSettings(this@SettingsActivity)
                } else {
                    PersonalContextSettings.setEnabled(this@SettingsActivity, checked)
                    refreshState()
                }
            }
        }
        root.addView(personalContextToggle, wrap())
        personalContextStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(90, 93, 103))
            setPadding(0, 0, 0, 8)
        }
        root.addView(personalContextStatus, wrap())
        root.addView(Button(this).apply {
            text = "Choose notification access"
            setAllCaps(false)
            setOnClickListener {
                PersonalContextAccess.openNotificationAccessSettings(this@SettingsActivity)
            }
        }, wrap())
        root.addView(Button(this).apply {
            text = "Clear saved personal context"
            setAllCaps(false)
            setOnClickListener { confirmClearPersonalContext() }
        }, wrap())

        root.addView(TextView(this).apply {
            text = "Gallery preparation resumes automatically after interruptions, screen-off, or process recreation."
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 30, 0, 0)
        }, wrap())
        return ScrollView(this).apply { addView(root) }
    }

    private fun refreshState() {
        val snapshot = PreparationStore(this).read()
        summary.text = snapshot.message
        progress.progress = snapshot.percent
        models.text = ModelCatalog.all.joinToString("\n") { artifact ->
            val state = when {
                artifact.isInstalled(this) -> "Installed"
                !artifact.required -> "Optional / not installed"
                artifact.partFile(this).isFile -> "Resuming download"
                artifact.hasDownloadSource() -> "Queued for background download"
                else -> "Waiting for model package"
            }
            "${artifact.name}  ·  $state"
        }
        val access = PersonalContextAccess.isNotificationListenerEnabled(this)
        updatingContextToggle = true
        personalContextToggle.isChecked = PersonalContextSettings.isEnabled(this)
        personalContextToggle.isEnabled = true
        updatingContextToggle = false
        personalContextBaseStatus = when {
            !access -> "Notification access is off. Ask Galaxy will not read notifications."
            PersonalContextSettings.isEnabled(this) -> "Notification access is on. Reading only new, relevant alerts; existing notifications are not imported."
            else -> "Access is available but personal context is turned off for answers."
        }
        personalContextStatus.text = personalContextBaseStatus
        contextExecutor.execute {
            val count = runCatching { PersonalContextDatabase(this).use { it.count() } }.getOrDefault(0)
            runOnUiThread {
                if (!isFinishing) {
                    personalContextStatus.text = "$personalContextBaseStatus Stored local records: $count."
                }
            }
        }
    }

    private fun confirmClearPersonalContext() {
        AlertDialog.Builder(this)
            .setTitle("Clear personal context?")
            .setMessage("This removes the locally encrypted notification facts. New facts will only be saved again if you leave personal context enabled.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                contextExecutor.execute {
                    runCatching { PersonalContextDatabase(this).use { it.clear() } }
                    runOnUiThread {
                        Toast.makeText(this, "Personal context cleared.", Toast.LENGTH_SHORT).show()
                        refreshState()
                    }
                }
            }
            .show()
    }

    private fun wrap(): ViewGroup.LayoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}
