package com.ravi.askgalaxy

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.util.Linkify
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
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
    private lateinit var ocrStatus: TextView
    private lateinit var locationStatus: TextView
    private lateinit var fileAccessStatus: TextView
    private lateinit var incrementalIndexStatus: TextView
    private lateinit var incrementalIndexButton: Button
    private lateinit var locationPermissionButton: Button
    private lateinit var answerabilityStatus: TextView
    private lateinit var answerabilityProgress: ProgressBar
    private lateinit var answerabilityButton: Button
    private lateinit var gemmaModelChoice: RadioGroup
    private lateinit var searchScopeSummary: TextView
    private val stageProgressViews = LinkedHashMap<IndexProgressStage, StageProgressView>()
    private val handler = Handler(Looper.getMainLooper())
    private val contextExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var updatingGemmaModelChoice = false
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
        // Returning from Android's all-files access screen should immediately
        // start the same complete personal-source pass.
        // KEEP is intentional here: returning to Settings must not restart a
        // live pass or create a retry loop after a recorded failure. The
        // personal-index action below is the explicit retry boundary.
        DocumentIndexScheduler.enqueue(this)
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            LocationReindexScheduler.enqueueIfNeeded(this)
            refreshState()
        }
        if (requestCode == DOCUMENT_PERMISSION_REQUEST) {
            DocumentIndexScheduler.enqueue(this)
            refreshState()
        }
        if (requestCode == FILE_ACCESS_PERMISSION_REQUEST) {
            DocumentIndexScheduler.enqueue(this)
            refreshState()
        }
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
        root.addView(TextView(this).apply {
            text = "Gemma model"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, 4)
        }, wrap())
        gemmaModelChoice = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(gemmaOption(GemmaModelVariant.E2B, "E2B • compact, GPU-first download"))
            setOnCheckedChangeListener { _, checkedId ->
                if (updatingGemmaModelChoice) return@setOnCheckedChangeListener
                val selected = GemmaModelVariant.E2B
                if (GemmaModelSelection.select(this@SettingsActivity, selected)) {
                    Toast.makeText(
                        this@SettingsActivity,
                        "${selected.displayName} selected. It will download in the background if needed.",
                        Toast.LENGTH_LONG,
                    ).show()
                    refreshState()
                }
            }
        }
        root.addView(gemmaModelChoice, wrap())
        root.addView(TextView(this).apply {
            text = "The Gemma 4 E2B model runs locally on this device with text and vision on the GPU."
            textSize = 13f
            setTextColor(Color.rgb(72, 75, 85))
            setPadding(0, 0, 0, 12)
        }, wrap())
        root.addView(TextView(this).apply {
            text = "Query planner"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 6, 0, 4)
        }, wrap())
        root.addView(Switch(this).apply {
            text = "Use experimental typed JSON planner"
            textSize = 15f
            setTextColor(Color.rgb(32, 34, 42))
            isChecked = QueryPlannerProtocolPreferences.selected(this@SettingsActivity) ==
                QueryPlannerProtocol.V2
            setOnCheckedChangeListener { _, enabled ->
                QueryPlannerProtocolPreferences.setV2Enabled(this@SettingsActivity, enabled)
                GemmaRuntime.invalidatePlannerPrefill()
                QueryPlannerRuntime.preloadAsync(this@SettingsActivity)
                Toast.makeText(
                    this@SettingsActivity,
                    if (enabled) "Typed JSON planner enabled" else "AST planner restored",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }, wrap())
        root.addView(TextView(this).apply {
            text = "AST is the default E2B query-plan format. The typed JSON trial remains available only as an explicit rollback experiment; changing this does not touch indexes or models."
            textSize = 13f
            setTextColor(Color.rgb(72, 75, 85))
            setPadding(0, 0, 0, 12)
        }, wrap())
        root.addView(TextView(this).apply {
            text = "Answers"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 6, 0, 4)
        }, wrap())
        root.addView(Switch(this).apply {
            text = "Generate answers with E2B"
            textSize = 15f
            setTextColor(Color.rgb(32, 34, 42))
            isChecked = AnswerFeaturePreferences.isEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, enabled ->
                AnswerFeaturePreferences.setEnabled(this@SettingsActivity, enabled)
                if (!enabled) {
                    // Drop any answer-only KV and immediately restore the resident planner prefix.
                    GemmaRuntime.preloadPlannerAfterAnswerAsync(
                        this@SettingsActivity,
                        QueryPlannerRuntime.plannerSystemInstruction(this@SettingsActivity),
                    )
                }
                Toast.makeText(
                    this@SettingsActivity,
                    if (enabled) "E2B answers enabled" else "Answers off • planner kept warm",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }, wrap())
        root.addView(TextView(this).apply {
            text = "When off, E2B still plans every query. Search curates 8 intent-aware standouts from the fused Top 100, then shows all ranked results below."
            textSize = 13f
            setTextColor(Color.rgb(72, 75, 85))
            setPadding(0, 0, 0, 12)
        }, wrap())
        root.addView(TextView(this).apply {
            text = "Search indexes"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 8, 0, 4)
        }, wrap())
        root.addView(TextView(this).apply {
            text = "Search only the selected indexes. This changes retrieval immediately and never deletes or rebuilds existing index data."
            textSize = 13f
            setTextColor(Color.rgb(72, 75, 85))
            setPadding(0, 0, 0, 4)
        }, wrap())
        val selectedSearchScope = IndexSearchScopePreferences.selected(this)
        SearchIndexSource.entries.forEach { source ->
            root.addView(Switch(this).apply {
                text = source.displayName
                textSize = 15f
                setTextColor(Color.rgb(32, 34, 42))
                isChecked = selectedSearchScope.contains(source)
                setOnCheckedChangeListener { _, enabled ->
                    IndexSearchScopePreferences.setEnabled(this@SettingsActivity, source, enabled)
                    refreshSearchScopeSummary()
                }
            }, wrap())
        }
        searchScopeSummary = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(72, 75, 85))
            setPadding(0, 2, 0, 12)
        }
        root.addView(searchScopeSummary, wrap())
        refreshSearchScopeSummary()
        models = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(50, 52, 60))
            setLineSpacing(5f, 1f)
        }
        root.addView(models, wrap())
        root.addView(TextView(this).apply {
            text = "EmbeddingGemma is the semantic document-embedding model for personal-source search. It is gated by Google’s Gemma license; authorize it here and Ask Galaxy will download its two required files."
            textSize = 13f
            setTextColor(Color.rgb(72, 75, 85))
            setLineSpacing(3f, 1f)
            setPadding(0, 10, 0, 6)
        }, wrap())
        root.addView(Button(this).apply {
            text = "Authorize and download EmbeddingGemma"
            setAllCaps(false)
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, EmbeddingGemmaAuthorizationActivity::class.java))
            }
        }, wrap())
        root.addView(TextView(this).apply {
            text = "Personal sources"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 28, 0, 8)
        }, wrap())
        root.addView(TextView(this).apply {
            text = "Index Messages, Calendar, Contacts, call logs, and all accessible My Files documents. PDFs use only their first five pages."
            textSize = 14f
            setTextColor(Color.rgb(72, 75, 85))
            setLineSpacing(3f, 1f)
        }, wrap())
        root.addView(Button(this).apply {
            text = "Allow Messages, Calendar, Contacts & call logs"
            setAllCaps(false)
            setOnClickListener {
                if (missingPersonalRuntimePermissions()) {
                    requestPermissions(
                        arrayOf(
                            Manifest.permission.READ_SMS,
                            Manifest.permission.READ_CALENDAR,
                            Manifest.permission.READ_CALL_LOG,
                            Manifest.permission.READ_CONTACTS,
                        ),
                        DOCUMENT_PERMISSION_REQUEST,
                    )
                } else {
                    DocumentIndexScheduler.restart(this@SettingsActivity)
                }
            }
        }, wrap())
        fileAccessStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(72, 75, 85))
            setPadding(0, 4, 0, 0)
        }
        root.addView(fileAccessStatus, wrap())
        root.addView(Button(this).apply {
            text = "Allow internal storage / SD card access"
            setAllCaps(false)
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    openAllFilesAccess()
                } else {
                    requestPermissions(
                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                        FILE_ACCESS_PERMISSION_REQUEST,
                    )
                }
            }
        }, wrap())
        root.addView(TextView(this).apply {
            text = "Incremental index update"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 20, 0, 4)
        }, wrap())
        root.addView(TextView(this).apply {
            text = "Check the phone for photos, videos, PDFs, and supported documents added since the last pass. Only unindexed records are appended. Existing gallery rows, OCR, vectors, faces, labels, document chunks, and files are never cleared or removed."
            textSize = 13f
            setTextColor(Color.rgb(72, 75, 85))
            setLineSpacing(3f, 1f)
        }, wrap())
        incrementalIndexStatus = TextView(this).apply {
            text = "Ready to check for new records."
            textSize = 13f
            setTextColor(Color.rgb(90, 93, 103))
            setPadding(0, 8, 0, 4)
        }
        root.addView(incrementalIndexStatus, wrap())
        incrementalIndexButton = Button(this).apply {
            text = "Update index"
            setAllCaps(false)
            setOnClickListener {
                if (!isEnabled) return@setOnClickListener
                isEnabled = false
                incrementalIndexStatus.text = "Checking for new photos, videos, and files…"
                IncrementalIndexScheduler.enqueue(this@SettingsActivity)
                refreshState()
            }
        }
        root.addView(incrementalIndexButton, wrap())

        root.addView(TextView(this).apply {
            text = "Preparation progress"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 28, 0, 8)
        }, wrap())
        root.addView(TextView(this).apply {
            text = "Each pipeline stage reports its own progress and estimated remaining time."
            textSize = 14f
            setTextColor(Color.rgb(72, 75, 85))
            setLineSpacing(3f, 1f)
        }, wrap())
        IndexProgressStage.entries.forEach { stage ->
            val label = TextView(this).apply {
                textSize = 13f
                setTextColor(Color.rgb(60, 63, 72))
                setPadding(0, 10, 0, 2)
            }
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                setPadding(0, 0, 0, 2)
            }
            stageProgressViews[stage] = StageProgressView(label, bar)
            root.addView(label, wrap())
            root.addView(bar, wrap())
        }

        root.addView(TextView(this).apply {
            text = "Answerability index"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 28, 0, 8)
        }, wrap())
        root.addView(TextView(this).apply {
            text = "Build labelled fact cards for every existing gallery OCR row and personal-source record. This uses no LLM and changes only the derived answerability index; OCR, vectors, faces, metadata, and documents are preserved."
            textSize = 14f
            setTextColor(Color.rgb(72, 75, 85))
            setLineSpacing(3f, 1f)
        }, wrap())
        answerabilityStatus = TextView(this).apply {
            text = "Ready to build on demand."
            textSize = 13f
            setTextColor(Color.rgb(90, 93, 103))
            setPadding(0, 8, 0, 4)
        }
        root.addView(answerabilityStatus, wrap())
        answerabilityProgress = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal,
        ).apply {
            max = 100
            visibility = View.GONE
            setPadding(0, 2, 0, 4)
        }
        root.addView(answerabilityProgress, wrap())
        answerabilityButton = Button(this).apply {
            text = "Build answerability index for all records"
            setAllCaps(false)
            setOnClickListener {
                if (!isEnabled) return@setOnClickListener
                isEnabled = false
                answerabilityProgress.visibility = View.VISIBLE
                answerabilityStatus.text = "Preparing all indexed records…"
                contextExecutor.execute {
                    val result = runCatching {
                        AnswerabilityIndexBuilder(this@SettingsActivity).rebuildBlocking { update ->
                            runOnUiThread {
                                if (isFinishing) return@runOnUiThread
                                val percent = if (update.total <= 0) 100 else {
                                    (update.completed * 100L / update.total).toInt().coerceIn(0, 100)
                                }
                                answerabilityProgress.progress = percent
                                answerabilityStatus.text =
                                    "${update.phase}: ${update.completed}/${update.total} records"
                            }
                        }
                    }
                    runOnUiThread {
                        isEnabled = true
                        answerabilityProgress.visibility = View.GONE
                        answerabilityStatus.text = if (result.isSuccess) {
                            "Answerability index is ready for all existing records."
                        } else {
                            "Answerability build paused safely; existing indexes were preserved. Tap again to resume."
                        }
                        Toast.makeText(
                            this@SettingsActivity,
                            if (result.isSuccess) "Answerability index is ready." else "Answerability build could not complete.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
        root.addView(answerabilityButton, wrap())

        root.addView(TextView(this).apply {
            text = "Index maintenance"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 28, 0, 8)
        }, wrap())
        root.addView(TextView(this).apply {
            text = "Each action affects only the selected derived index. Gallery metadata, permissions, and unrelated indexes remain intact. Rebuilds run in the foreground and can resume after interruption."
            textSize = 14f
            setTextColor(Color.rgb(72, 75, 85))
            setLineSpacing(3f, 1f)
        }, wrap())
        val visualRebuildButton = Button(this).apply {
            text = "Clear and rebuild visual search"
            setAllCaps(false)
        }
        visualRebuildButton.setOnClickListener {
            confirmIsolatedRebuild(
                button = visualRebuildButton,
                index = IsolatedIndex.VISUAL,
                title = "Rebuild visual search?",
                message = "This clears only the SigLIP visual vector index and recomputes it. OCR, faces, locations, episodes, and gallery metadata are preserved.",
            )
        }
        root.addView(visualRebuildButton, wrap())
        val faceRebuildButton = Button(this).apply {
            text = "Clear and rebuild face index"
            setAllCaps(false)
        }
        faceRebuildButton.setOnClickListener {
            confirmIsolatedRebuild(
                button = faceRebuildButton,
                index = IsolatedIndex.FACE,
                title = "Rebuild face index?",
                message = "This reruns face detection and embeddings, then refreshes face groups and dependent photo episodes. Existing names are matched where possible.",
            )
        }
        root.addView(faceRebuildButton, wrap())
        val episodeRebuildButton = Button(this).apply {
            text = "Rebuild photo episodes only"
            setAllCaps(false)
        }
        episodeRebuildButton.setOnClickListener {
            confirmIsolatedRebuild(
                button = episodeRebuildButton,
                index = IsolatedIndex.EPISODE,
                title = "Rebuild photo episodes?",
                message = "This replaces only the derived episode headers and memberships. Media metadata, OCR, visual vectors, and faces are preserved.",
            )
        }
        root.addView(episodeRebuildButton, wrap())

        root.addView(TextView(this).apply {
            text = "Text recognition"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 28, 0, 8)
        }, wrap())
        root.addView(TextView(this).apply {
            text = "Bundled ML Kit reads text offline from aspect-preserving photo and screenshot tiles. Rebuilding here changes only OCR text and the doc/scenary label; visual search, places, faces, and episodes are preserved."
            textSize = 14f
            setTextColor(Color.rgb(72, 75, 85))
            setLineSpacing(3f, 1f)
        }, wrap())
        ocrStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(90, 93, 103))
            setPadding(0, 8, 0, 6)
        }
        root.addView(ocrStatus, wrap())
        root.addView(Button(this).apply {
            text = "Rebuild text index only"
            setAllCaps(false)
            setOnClickListener {
                isEnabled = false
                contextExecutor.execute {
                    val count = runCatching {
                        OcrReindexScheduler.rebuild(this@SettingsActivity)
                    }.getOrDefault(0)
                    runOnUiThread {
                        isEnabled = true
                        Toast.makeText(
                            this@SettingsActivity,
                            "Refreshing text for $count photos in the background.",
                            Toast.LENGTH_LONG,
                        ).show()
                        refreshState()
                    }
                }
            }
        }, wrap())

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
        locationStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(90, 93, 103))
            setPadding(0, 8, 0, 6)
        }
        root.addView(locationStatus, wrap())
        locationPermissionButton = Button(this).apply {
            text = "Allow precise photo locations"
            setAllCaps(false)
            setOnClickListener {
                if (MediaLocationAccess.hasPermission(this@SettingsActivity)) {
                    return@setOnClickListener
                }
                MediaLocationAccess.markRequested(this@SettingsActivity)
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION),
                    LOCATION_PERMISSION_REQUEST,
                )
            }
        }
        root.addView(locationPermissionButton, wrap())
        root.addView(Button(this).apply {
            text = "Rebuild photo locations only"
            setAllCaps(false)
            setOnClickListener {
                if (!MediaLocationAccess.hasPermission(this@SettingsActivity)) {
                    Toast.makeText(
                        this@SettingsActivity,
                        "Allow precise photo locations first.",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@setOnClickListener
                }
                isEnabled = false
                contextExecutor.execute {
                    val count = runCatching {
                        LocationReindexScheduler.rebuild(this@SettingsActivity)
                    }.getOrDefault(0)
                    runOnUiThread {
                        isEnabled = true
                        Toast.makeText(
                            this@SettingsActivity,
                            "Refreshing locations for $count photos in the background.",
                            Toast.LENGTH_LONG,
                        ).show()
                        refreshState()
                    }
                }
            }
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
                if (enabled) LocationReindexScheduler.enqueueIfNeeded(this@SettingsActivity)
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
            text = "Gallery preparation resumes automatically after interruptions, screen-off, or process recreation."
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 30, 0, 0)
        }, wrap())
        return ScrollView(this).apply { addView(root) }
    }

    private fun refreshSearchScopeSummary() {
        val scope = IndexSearchScopePreferences.selected(this)
        val enabled = SearchIndexSource.entries.filter(scope::contains)
        searchScopeSummary.text = if (enabled.isEmpty()) {
            "No indexes selected. Searches will return no records."
        } else {
            "Enabled: ${enabled.joinToString { it.displayName }}"
        }
    }

    private fun refreshState() {
        val snapshot = PreparationStore(this).read()
        summary.text = snapshot.message
        progress.progress = snapshot.percent
        val filesGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        fileAccessStatus.text = if (filesGranted) {
            "My Files access: Granted (internal storage and accessible SD-card files)."
        } else {
            "My Files access: Needed. Android will open a separate All files access screen."
        }
        val selectedGemma = ModelCatalog.gemma(this)
        updatingGemmaModelChoice = true
        gemmaModelChoice.check(GemmaModelSelection.selected(this).ordinal)
        updatingGemmaModelChoice = false
        models.text = ModelCatalog.all(this).joinToString("\n") { artifact ->
            val state = when {
                artifact.isInstalled(this) -> "Installed"
                !artifact.required -> "Optional / not installed"
                artifact.partFile(this).isFile -> "Resuming download"
                artifact.hasDownloadSource() -> "Queued for background download"
                else -> "Waiting for model package"
            }
            "${artifact.name}  ·  $state"
        }
        contextExecutor.execute {
            val pendingOcr = runCatching {
                GalleryDatabase(this).use { it.pendingOcrCount() }
            }.getOrDefault(-1)
            val locationCounts = runCatching {
                GalleryDatabase(this).use {
                    it.pendingLocationCount() to it.resolvedLocationCount()
                }
            }.getOrDefault(-1 to -1)
            val progressStore = IndexProgressStore(this)
            if (selectedGemma.isInstalled(this)) {
                progressStore.update(
                    IndexProgressStage.MODELS,
                    selectedGemma.expectedBytes,
                    selectedGemma.expectedBytes,
                    completed = true,
                )
            }
            val embeddingGemma = ModelCatalog.embeddingGemma
            if (embeddingGemma.isInstalled(this)) {
                progressStore.update(
                    IndexProgressStage.EMBEDDING_GEMMA,
                    embeddingGemma.expectedBytes,
                    embeddingGemma.expectedBytes,
                    completed = true,
                    phase = "Installed",
                )
            }
            val stageProgress = progressStore.readAll()
            runOnUiThread {
                if (!isFinishing) {
                    updateStageProgress(stageProgress)
                    val incremental = stageProgress[IndexProgressStage.INCREMENTAL_UPDATE]
                        ?: StageProgress()
                    incrementalIndexButton.isEnabled =
                        incremental.updatedAtMs == 0L || incremental.completed || incremental.error.isNotBlank()
                    incrementalIndexStatus.text = when {
                        incremental.error.isNotBlank() ->
                            "Update paused safely: ${incremental.error}. Existing indexes were preserved; tap to retry."
                        incremental.completed -> incremental.phase.ifBlank {
                            "Index update complete. Existing index data was preserved."
                        }
                        incremental.updatedAtMs > 0L -> incremental.phase.ifBlank {
                            "Checking for unindexed records…"
                        }
                        else -> "Ready to check for new records."
                    }
                    ocrStatus.text = when (pendingOcr) {
                        0 -> "Text index is current."
                        -1 -> "Text-index status is temporarily unavailable."
                        else -> "Refreshing $pendingOcr photos in the background."
                    }
                    val hasLocationPermission =
                        MediaLocationAccess.hasPermission(this@SettingsActivity)
                    locationPermissionButton.isEnabled = !hasLocationPermission
                    locationPermissionButton.text = if (hasLocationPermission) {
                        "Precise photo location access granted"
                    } else {
                        "Allow precise photo locations"
                    }
                    locationStatus.text = when {
                        !hasLocationPermission ->
                            "Photo GPS is protected by Android. Allow access to index unredacted locations."
                        locationCounts.first < 0 ->
                            "Photo-location status is temporarily unavailable."
                        locationCounts.first > 0 ->
                            "Refreshing ${locationCounts.first} items. " +
                                "${locationCounts.second} locations are currently resolved."
                        else ->
                            "Photo locations are current. " +
                                "${locationCounts.second} GPS-tagged items have readable place names."
                    }
                }
            }
        }
    }

    private fun gemmaOption(variant: GemmaModelVariant, label: String): RadioButton = RadioButton(this).apply {
        id = variant.ordinal
        text = label
        textSize = 14f
        setTextColor(Color.rgb(50, 52, 60))
        setPadding(0, 0, 0, 2)
    }

    private fun updateStageProgress(progress: Map<IndexProgressStage, StageProgress>) {
        val now = System.currentTimeMillis()
        stageProgressViews.forEach { (stage, views) ->
            val state = progress[stage] ?: StageProgress()
            bindProgressView(views.label, views.bar, stage, state, now)
        }
    }

    private fun bindProgressView(
        label: TextView,
        bar: ProgressBar,
        stage: IndexProgressStage,
        state: StageProgress,
        now: Long,
    ) {
        val isQueued = state.phase.startsWith("queued", ignoreCase = true)
        // Queued stages are status-only. Do not animate or update a progress
        // bar until that source actually starts indexing.
        bar.visibility = if (isQueued || state.updatedAtMs == 0L) View.GONE else View.VISIBLE
        if (bar.visibility == View.VISIBLE) {
            bar.isIndeterminate = state.updatedAtMs > 0L && !state.completed && state.total <= 0L
            bar.progress = state.percent
        } else {
            bar.isIndeterminate = false
            bar.progress = 0
        }
        label.text = "${stage.label}  ·  ${formatStageProgress(stage, state, now)}"
    }

    private fun formatStageProgress(
        stage: IndexProgressStage,
        progress: StageProgress,
        nowMs: Long,
    ): String {
        if (progress.completed) return "Complete"
        if (progress.paused) return "Paused at ${progress.current} / ${progress.total}"
        if (progress.updatedAtMs == 0L) return "Waiting"
        val counts = when {
            stage == IndexProgressStage.MODELS || stage == IndexProgressStage.EMBEDDING_GEMMA ->
                "${formatBytes(progress.current)} / ${formatBytes(progress.total)}"
            else -> "${progress.current} / ${progress.total}"
        }
        val eta = progress.etaMs(nowMs)?.let { " • ETA ${formatDuration(it)}" }.orEmpty()
        val phase = progress.phase.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
        return "$counts (${progress.percent}%)$phase$eta"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1_024L * 1_024L) return "${bytes / 1_024L} KB"
        if (bytes < 1_024L * 1_024L * 1_024L) {
            return String.format(
                java.util.Locale.US,
                "%.1f MB",
                bytes / (1_024.0 * 1_024.0),
            )
        }
        return String.format(
            java.util.Locale.US,
            "%.1f GB",
            bytes / (1_024.0 * 1_024.0 * 1_024.0),
        )
    }

    private fun formatDuration(durationMs: Long): String {
        val totalMinutes = durationMs / 60_000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0L) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private fun confirmIsolatedRebuild(
        button: Button,
        index: IsolatedIndex,
        title: String,
        message: String,
    ) {
        if (!PreparationStore(this).read().isPrepared) {
            Toast.makeText(this, "Finish initial gallery preparation first.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear and rebuild") { _, _ ->
                button.isEnabled = false
                contextExecutor.execute {
                    val count = runCatching {
                        IndexRebuildScheduler.rebuild(this@SettingsActivity, index)
                    }.getOrDefault(-1)
                    runOnUiThread {
                        button.isEnabled = true
                        val messageText = if (count >= 0) {
                            "${index.displayName.replaceFirstChar { it.uppercase() }} rebuild started for $count rows."
                        } else {
                            "Could not start ${index.displayName} rebuild."
                        }
                        Toast.makeText(this, messageText, Toast.LENGTH_LONG).show()
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

    private fun missingPersonalRuntimePermissions(): Boolean = listOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
    ).any { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }

    private fun openAllFilesAccess() {
        startActivity(Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        ))
    }

    private data class StageProgressView(
        val label: TextView,
        val bar: ProgressBar,
    )

    private companion object {
        const val LOCATION_PERMISSION_REQUEST = 2001
        const val DOCUMENT_PERMISSION_REQUEST = 4102
        const val FILE_ACCESS_PERMISSION_REQUEST = 4103
    }
}
