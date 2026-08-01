package com.ravi.askgalaxy

import android.Manifest
import android.app.Activity
import android.content.res.ColorStateList
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.drawable.GradientDrawable

class MainActivity : Activity() {
    private lateinit var preparationPanel: LinearLayout
    private lateinit var preparationStatus: TextView
    private lateinit var preparationProgress: ProgressBar
    private lateinit var searchPanel: LinearLayout
    private lateinit var conversationScroll: ScrollView
    private lateinit var facePromptPanel: LinearLayout
    private lateinit var facePromptStatus: TextView
    private lateinit var facePromptPreview: LinearLayout
    private lateinit var query: EditText
    private lateinit var gemmaWarmupStatus: TextView
    private lateinit var gemmaWarmupPanel: LinearLayout
    private lateinit var gemmaWarmupIndicator: ProgressBar
    private lateinit var resultPanel: LinearLayout
    private lateinit var answer: TextView
    private lateinit var conversationPanel: LinearLayout
    private lateinit var followUpPanel: LinearLayout
    private lateinit var followUpRow: LinearLayout
    private lateinit var sourcePanel: LinearLayout
    private lateinit var sourceRow: LinearLayout
    private lateinit var sourceDetails: LinearLayout
    private lateinit var modelProgress: ProgressBar
    private lateinit var modelStatus: TextView
    private lateinit var resultCount: TextView
    private lateinit var resultGrid: GridView
    private lateinit var timeStatsPanel: LinearLayout
    private lateinit var timeStatsButton: TextView
    private lateinit var timeStatsDetails: LinearLayout
    private lateinit var qpOutputLabel: TextView
    private lateinit var qpOutputText: TextView
    private lateinit var galleryIndexer: GalleryIndexer
    private var searchGeneration = 0L
    private var expandedSourceId: String? = null
    private var facePromptRequested = false
    private var searchReady = false
    /** Launch warmup is one-shot; later planner warmups follow answer completion. */
    private var launchPlannerWarmupRequested = false
    private var resultAdapter: SearchResultAdapter? = null
    private var lastSubmittedQuery = ""
    private var lastSubmittedAtMs = 0L
    /** The visible result set is reused by follow-up chips; no new QP/search. */
    private var activeSearchResponse: SearchResponse? = null
    private var followUpInFlight = false
    private var deferredFreshQuery: String? = null
    private var timeStatsSummary = "Timing…"
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            // Poll only while WorkManager is preparing the gallery/model.
            // Gemma readiness itself is delivered as a one-shot callback.
            if (refreshPreparation()) handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PreparationNotifier.createChannel(this)
        galleryIndexer = GalleryIndexer(this)
        setContentView(createContent())
        GemmaRuntime.setPlannerWarmupStateListener {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    refreshPreparation()
                    val deferredQuery = deferredFreshQuery
                    if (GemmaRuntime.isPlannerReady() && !deferredQuery.isNullOrBlank()) {
                        deferredFreshQuery = null
                        query.setText(deferredQuery)
                        query.setSelection(query.text.length)
                        query.post { search() }
                    }
                }
            }
        }
        if (!hasGalleryPermission()) {
            requestPermissions(galleryPermissions(), GALLERY_PERMISSION_REQUEST)
        } else if (!requestPhotoLocationPermissionIfNeeded()) {
            startBackgroundMaintenance()
        }
        refreshPreparation()
    }

    private fun startBackgroundMaintenance() {
        GemmaDownloadScheduler.enqueueIfNeeded(this)
        val preparation = PreparationStore(this).read()
        val visual = IndexProgressStore(this).read(IndexProgressStage.VISUAL)
        val visualWorkerStale =
            !visual.completed &&
                visual.current > 0L &&
                visual.updatedAtMs > 0L &&
                System.currentTimeMillis() - visual.updatedAtMs > STALE_WORKER_TIMEOUT_MS
        if (!preparation.isPrepared) {
            if (visualWorkerStale) {
                PreparationScheduler.restart(this)
            } else {
                PreparationScheduler.enqueue(this)
            }
        } else {
            // OCR has an independent versioned index and can migrate in the
            // background without rerunning the rest of gallery preparation.
            OcrReindexScheduler.enqueueIfNeeded(this)
        }
        LocationReindexScheduler.enqueueIfNeeded(this)
    }

    private fun requestPhotoLocationPermissionIfNeeded(): Boolean {
        if (!MediaLocationAccess.shouldRequest(this)) return false
        MediaLocationAccess.markRequested(this)
        requestPermissions(
            arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION),
            LOCATION_PERMISSION_REQUEST,
        )
        return true
    }

    override fun onResume() {
        super.onResume()
        facePromptRequested = false
        LocationReindexScheduler.enqueueIfNeeded(this)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        GemmaRuntime.setPlannerWarmupStateListener(null)
        if (::resultGrid.isInitialized) {
            resultGrid.adapter = null
            resultAdapter?.dispose()
            resultAdapter = null
        }
        galleryIndexer.close()
        GemmaRuntime.releaseResident()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == GALLERY_PERMISSION_REQUEST) {
            if (hasGalleryPermission() && requestPhotoLocationPermissionIfNeeded()) return
            startBackgroundMaintenance()
            refreshPreparation()
        } else if (requestCode == LOCATION_PERMISSION_REQUEST) {
            startBackgroundMaintenance()
            refreshPreparation()
        }
    }

    private fun createContent(): View {
        conversationScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(30), dp(24), dp(30))
            setBackgroundColor(Color.rgb(247, 247, 249))
        }
        conversationScroll.addView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Ask Galaxy"
            textSize = 30f
            setTextColor(Color.rgb(17, 19, 25))
            setTypeface(typeface, Typeface.BOLD)
        }
        titleGroup.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(56),
        ))
        titleGroup.addView(TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME}"
            textSize = 12f
            setTextColor(Color.rgb(91, 95, 110))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = roundedBackground(Color.rgb(240, 242, 248), dp(12).toFloat())
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = dp(9)
        })
        header.addView(titleGroup, LinearLayout.LayoutParams(0, dp(56), 1f))
        header.addView(TextView(this).apply {
            text = "⋯"
            textSize = 28f
            gravity = Gravity.CENTER
            setContentDescription("Settings")
            setTextColor(Color.rgb(28, 28, 30))
            background = roundedBackground(
                Color.WHITE,
                dp(18).toFloat(),
                Color.rgb(229, 229, 234),
            )
            elevation = dp(1).toFloat()
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        root.addView(header, matchWrap())

        preparationPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(28), 0, 0)
        }
        preparationStatus = TextView(this).apply {
            text = "Preparing your private gallery…"
            textSize = 17f
            setTextColor(Color.rgb(45, 47, 55))
        }
        preparationPanel.addView(preparationStatus, matchWrap())
        preparationProgress = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal,
        ).apply {
            max = 100
            setPadding(0, dp(18), 0, dp(12))
        }
        preparationPanel.addView(preparationProgress, matchWrap())
        preparationPanel.addView(TextView(this).apply {
            text = "You can leave Ask Galaxy. Model installation and indexing continue securely in the background."
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, dp(20))
        }, matchWrap())
        root.addView(preparationPanel, matchWrap())

        facePromptPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedBackground(Color.rgb(239, 242, 255), dp(22).toFloat())
            visibility = View.GONE
        }
        facePromptStatus = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(42, 48, 84))
            setTypeface(typeface, Typeface.BOLD)
        }
        facePromptPanel.addView(facePromptStatus, matchWrap())
        facePromptPanel.addView(TextView(this).apply {
            text = "Give each private face group a name before searching your gallery."
            textSize = 13f
            setTextColor(Color.rgb(73, 78, 111))
            setPadding(0, dp(6), 0, dp(12))
        }, matchWrap())
        facePromptPreview = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        facePromptPanel.addView(facePromptPreview, matchWrap())
        facePromptPanel.addView(Button(this).apply {
            text = "Tag face groups"
            setAllCaps(false)
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.rgb(63, 82, 181), dp(16).toFloat())
            setOnClickListener {
                startActivity(Intent(this@MainActivity, FaceClustersActivity::class.java))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(14)
        })
        val facePromptParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        facePromptParams.topMargin = dp(22)
        root.addView(facePromptPanel, facePromptParams)

        searchPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(30), 0, 0)
        }
        val searchCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(12), 0)
            background = roundedBackground(
                Color.WHITE,
                dp(22).toFloat(),
                Color.rgb(229, 229, 234),
            )
            elevation = dp(1).toFloat()
        }
        searchCard.addView(TextView(this).apply {
            text = "⌕"
            textSize = 28f
            setTextColor(Color.rgb(142, 142, 147))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(36), dp(60)))
        query = EditText(this).apply {
            hint = "Ask about photos, trips, or receipts"
            textSize = 16f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = null
            setTextColor(Color.rgb(28, 28, 30))
            setHintTextColor(Color.rgb(142, 142, 147))
            setPadding(dp(8), 0, 0, 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    search()
                    true
                } else {
                    false
                }
            }
        }
        searchCard.addView(query, LinearLayout.LayoutParams(0, dp(60), 1f))
        searchPanel.addView(searchCard, matchWrap())
        gemmaWarmupPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(12), 0)
            visibility = View.GONE
        }
        gemmaWarmupIndicator = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.rgb(65, 105, 225))
            visibility = View.GONE
        }
        gemmaWarmupPanel.addView(
            gemmaWarmupIndicator,
            LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                rightMargin = dp(8)
            },
        )
        gemmaWarmupStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(91, 95, 110))
        }
        gemmaWarmupPanel.addView(gemmaWarmupStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchPanel.addView(gemmaWarmupPanel, matchWrap())

        // Planning is a first-class, live search artifact. This panel sits
        // immediately below the query and is updated before retrieval starts,
        // so the expression shown here is exactly what the index executes.
        timeStatsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(11))
            background = roundedBackground(Color.rgb(246, 247, 252), dp(16).toFloat())
        }
        val queryTelemetryHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        qpOutputLabel = TextView(this).apply {
            text = "QP output • planning"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(71, 75, 94))
        }
        queryTelemetryHeader.addView(
            qpOutputLabel,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        timeStatsDetails = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(4), dp(9), dp(4), 0)
        }
        timeStatsButton = TextView(this).apply {
            text = "◷  Timing…"
            textSize = 12f
            setTextColor(Color.rgb(50, 57, 99))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = roundedBackground(Color.rgb(228, 232, 250), dp(16).toFloat())
            setOnClickListener {
                timeStatsDetails.visibility = if (timeStatsDetails.visibility == View.VISIBLE) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
                updateTimeStatsButton()
            }
        }
        queryTelemetryHeader.addView(
            timeStatsButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)),
        )
        timeStatsPanel.addView(queryTelemetryHeader, matchWrap())
        qpOutputText = TextView(this).apply {
            text = "Planning the executable query…"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(43, 48, 78))
            setTextIsSelectable(true)
            setPadding(0, dp(7), 0, 0)
        }
        timeStatsPanel.addView(qpOutputText, matchWrap())
        timeStatsPanel.addView(timeStatsDetails, matchWrap())
        searchPanel.addView(timeStatsPanel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(8)
        })

        resultPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(0), dp(24), dp(0), dp(12))
        }
        answer = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(42, 44, 52))
            setPadding(dp(6), 0, dp(6), dp(14))
        }
        resultPanel.addView(answer, matchWrap())
        conversationPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(6), 0, dp(6), dp(10))
        }
        resultPanel.addView(conversationPanel, matchWrap())
        followUpPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(6), 0, dp(6), dp(14))
        }
        followUpPanel.addView(TextView(this).apply {
            text = "Try next"
            textSize = 13f
            setTextColor(Color.rgb(91, 95, 110))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(7))
        }, matchWrap())
        val followUpScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        followUpRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        followUpScroll.addView(followUpRow, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        followUpPanel.addView(followUpScroll, matchWrap())
        resultPanel.addView(followUpPanel, matchWrap())
        sourcePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(6), 0, dp(6), dp(14))
        }
        sourcePanel.addView(TextView(this).apply {
            text = "Answer context • selected from the search results"
            textSize = 13f
            setTextColor(Color.rgb(91, 95, 110))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(7))
        }, matchWrap())
        val sourceScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        sourceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        sourceScroll.addView(sourceRow, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        sourcePanel.addView(sourceScroll, matchWrap())
        sourceDetails = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        sourcePanel.addView(sourceDetails, matchWrap())
        modelProgress = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal,
        ).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        resultPanel.addView(modelProgress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(4),
        ))
        modelStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(91, 95, 110))
            setPadding(dp(6), dp(8), dp(6), dp(8))
            visibility = View.GONE
        }
        resultPanel.addView(modelStatus, matchWrap())
        resultCount = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(63, 66, 78))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), dp(8), dp(6), dp(4))
            visibility = View.GONE
        }
        resultPanel.addView(resultCount, matchWrap())
        resultGrid = GridView(this).apply {
            numColumns = 2
            horizontalSpacing = dp(8)
            verticalSpacing = dp(8)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setPadding(0, dp(4), 0, dp(4))
            clipToPadding = false
        }
        resultPanel.addView(
            resultGrid,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(520)),
        )
        // Keep the full search browser ahead of the bounded answer context.
        // The 4 records selected for Gemma must never look like a replacement
        // for the user-visible result set.
        resultPanel.addView(sourcePanel, matchWrap())
        searchPanel.addView(resultPanel, matchWrap())
        root.addView(searchPanel, matchWrap())

        // The page is the primary scroll surface, so the query, QP output,
        // history, and follow-up turns all move naturally together. The
        // bounded grid keeps thumbnail virtualization rather than expanding
        // every one of the 30 result cards at once.
        return conversationScroll
    }

    /** @return true only while preparation progress needs periodic polling. */
    private fun refreshPreparation(): Boolean {
        val snapshot = PreparationStore(this).read()
        val indexPrepared = snapshot.isPrepared
        val selectedGemma = ModelCatalog.gemma(this)
        val gemmaReady = selectedGemma.isInstalled(this)
        val gemmaPart = selectedGemma.partFile(this)
        val gemmaBytes = gemmaPart.takeIf { it.isFile }?.length() ?: 0L
        val gemmaPercent = if (selectedGemma.expectedBytes > 0L) {
            ((gemmaBytes * 100L) / selectedGemma.expectedBytes).toInt().coerceIn(0, 100)
        } else {
            0
        }
        val waitingForFaceTags = snapshot.phase == PreparationPhase.WAITING_FOR_FACE_TAGS
        val ready = snapshot.isReady && gemmaReady
        val gemmaOnly = indexPrepared && !gemmaReady
        preparationProgress.isIndeterminate = if (gemmaOnly) false else snapshot.total <= 0L && !ready
        preparationProgress.progress = if (gemmaOnly) gemmaPercent else snapshot.percent
        preparationStatus.text = if (waitingForFaceTags) {
            if (gemmaOnly) {
                "Face groups are ready. Gemma 4 is still downloading (${gemmaPercent}%). You can review groups now."
            } else {
                "Face groups are ready. Review the complete set once, merge duplicates, then name the people you know."
            }
        } else if (gemmaOnly) {
            if (gemmaBytes > 0L) {
                "Gallery index is ready. Installing ${selectedGemma.name}: ${formatBytes(gemmaBytes)} / ${formatBytes(selectedGemma.expectedBytes)}"
            } else {
                "Gallery index is ready. Gemma 4 download is queued…"
            }
        } else {
            snapshot.message
        }
        preparationPanel.visibility = if (ready) View.GONE else View.VISIBLE
        facePromptPanel.visibility = if (waitingForFaceTags) View.VISIBLE else View.GONE
        searchPanel.visibility = if (ready) View.VISIBLE else View.GONE
        if (ready) {
            val plannerReady = GemmaRuntime.isPlannerReady()
            val hasReusableFollowUpContext = activeSearchResponse != null && !followUpInFlight
            // A submitted query must consume the prewarmed QP system KV.
            // An uncached Conversation re-prefills the 12K system context and
            // can take minutes, so wait for the one explicit QP warmup.
            query.isEnabled = plannerReady || hasReusableFollowUpContext
            query.alpha = if (query.isEnabled) 1f else 0.62f
            val showWarmup = !plannerReady && !hasReusableFollowUpContext
            gemmaWarmupPanel.visibility = if (showWarmup) View.VISIBLE else View.GONE
            gemmaWarmupIndicator.visibility = if (showWarmup) View.VISIBLE else View.GONE
            gemmaWarmupStatus.text = if (plannerReady) {
                "${GemmaModelSelection.selected(this).displayName} ready • local ${GemmaRuntime.backendPlacement()}"
            } else if (hasReusableFollowUpContext) {
                "Follow-up answers are ready from the current results"
            } else {
                "Warming ${GemmaModelSelection.selected(this).displayName} • preparing private search…"
            }
            // The spinner is enough; do not continuously fade the status text.
            gemmaWarmupStatus.clearAnimation()
        } else if (::gemmaWarmupStatus.isInitialized) {
            launchPlannerWarmupRequested = false
            query.isEnabled = false
            gemmaWarmupPanel.visibility = View.GONE
            gemmaWarmupIndicator.visibility = View.GONE
            gemmaWarmupStatus.clearAnimation()
        }
        if (ready && !searchReady) {
            searchReady = true
            SigLipTextEncoder.preloadAsync(this)
            NativeVectorIndex.preloadAsync(this)
        } else if (!ready) {
            searchReady = false
        }
        if (ready && !launchPlannerWarmupRequested) {
            // Gemma occupies several GiB of mapped/native state on the target
            // device. Do not make it compete with the one-time OCR migration.
            // This is the only launch-time QP system prefill; subsequent
            // planner warmups are scheduled after the answer session closes.
            val pendingOcr = runCatching { galleryIndexer.pendingOcrCount() }
                .getOrDefault(Int.MAX_VALUE)
            if (pendingOcr == 0) {
                launchPlannerWarmupRequested = true
                GemmaRuntime.preloadPlannerAsync(
                    this,
                    QueryPlannerRuntime.plannerSystemInstruction(),
                )
            }
        }
        if (waitingForFaceTags && !facePromptRequested) refreshFacePrompt()
        return !ready
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1_024L * 1_024L) return "${bytes / 1_024L} KB"
        return String.format(
            java.util.Locale.US,
            "%.1f GB",
            bytes / (1_024.0 * 1_024.0 * 1_024.0),
        )
    }

    private fun search() {
        if (!::query.isInitialized) return
        val requestedText = query.text?.toString().orEmpty().trim()
        if (!GemmaRuntime.isPlannerReady()) {
            if (requestedText.isNotBlank()) {
                // A typed fresh search intentionally trades the kept answer
                // KV for a planner KV; chip follow-ups never take this path.
                // Answer KV is intentionally resident after a turn. A plain
                // planner preload refuses to run while it is reserved, which
                // used to leave this fresh-search path stuck forever.
                deferredFreshQuery = requestedText
                query.isEnabled = false
                query.alpha = 0.62f
                GemmaRuntime.preloadPlannerAfterAnswerAsync(
                    this,
                    QueryPlannerRuntime.plannerSystemInstruction(),
                )
            }
            if (::gemmaWarmupStatus.isInitialized) {
                gemmaWarmupPanel.visibility = View.VISIBLE
                gemmaWarmupIndicator.visibility = View.VISIBLE
                gemmaWarmupStatus.text = "Warming ${GemmaModelSelection.selected(this).displayName} • almost ready…"
            }
            return
        }
        if (!query.isEnabled) return
        val text = requestedText
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        if (text.equals(lastSubmittedQuery, ignoreCase = true) && now - lastSubmittedAtMs < 1_500L) {
            return
        }
        lastSubmittedQuery = text
        lastSubmittedAtMs = now
        val generation = ++searchGeneration
        activeSearchResponse = null
        followUpInFlight = false
        // The consumed QP KV is now unavailable by design. Keep the field
        // locked through QP, answer-prefix warmup, answer generation, then
        // the next explicit planner warmup.
        query.isEnabled = false
        query.alpha = 0.62f
        // Keep Gemma 4 resident across planning and answer generation. The
        // prewarmed planner conversation is intentionally claimed by this
        // query so its system-preface KV cache is reused.
        SigLipTextEncoder.preloadAsync(this)
        NativeVectorIndex.preloadAsync(this)
        resultPanel.visibility = View.VISIBLE
        answer.visibility = View.VISIBLE
        answer.text = "Searching your private gallery…"
        conversationPanel.removeAllViews()
        conversationPanel.visibility = View.GONE
        resultGrid.adapter = null
        resultAdapter?.dispose()
        resultAdapter = null
        resultCount.visibility = View.GONE
        sourcePanel.visibility = View.GONE
        sourceRow.removeAllViews()
        sourceDetails.removeAllViews()
        followUpPanel.visibility = View.GONE
        followUpRow.removeAllViews()
        showQueryPlanningTelemetry()
        expandedSourceId = null
        setModelLoading(true, "Blending image, OCR, and metadata matches…")
        galleryIndexer.searchAsync(
            query = text,
            onMatches = { matches, totalMatches ->
                runOnUiThread {
                    if (generation != searchGeneration || matches.isEmpty()) return@runOnUiThread
                    renderResults(matches, generation, totalMatches)
                    answer.text = "I found $totalMatches matching item${if (totalMatches == 1) "" else "s"}."
                }
            },
            onProgress = { progress ->
                runOnUiThread {
                    if (generation != searchGeneration) return@runOnUiThread
                    setModelLoading(
                        true,
                        when (progress.stage) {
                            SearchStage.QUERY_PLANNING ->
                                "${GemmaModelSelection.selected(this).displayName} is planning this query locally…"
                            SearchStage.QUERY_PLANNED -> {
                                renderQpOutput(progress.effectivePlanJson)
                                renderTimeStats(progress.timings, "Planning")
                                "QP ready. Searching image, OCR, and metadata indexes…"
                            }
                            SearchStage.HYBRID_RETRIEVAL -> {
                                renderTimeStats(progress.timings, "Search")
                                if (progress.contextCount > 0) {
                                    "Blending image, OCR, metadata, and ${progress.contextCount} personal context matches…"
                                } else {
                                    "Blending image, OCR, and metadata matches…"
                                }
                            }
                            SearchStage.DIVERSE_EVIDENCE -> {
                                renderTimeStats(progress.timings, "Search")
                                "Preparing answer context across ${progress.candidateCount} matches…"
                            }
                        },
                    )
                }
            },
        ) { result ->
            runOnUiThread {
                if (generation != searchGeneration) return@runOnUiThread
                val response = result.getOrElse { error ->
                    activeSearchResponse = null
                    setModelLoading(false, "")
                    renderQpFailure()
                    warmPlannerForNextSearch()
                    answer.text = if (
                        error.message.orEmpty().contains("Gemma 4", ignoreCase = true) ||
                        error.cause?.message.orEmpty().contains("Gemma 4", ignoreCase = true)
                    ) {
                        "${GemmaModelSelection.selected(this).displayName} couldn't prepare this search query yet."
                    } else {
                        "Search paused while the on-device index is unavailable."
                    }
                    return@runOnUiThread
                }
                renderQpOutput(response.effectivePlanJson)
                renderTimeStats(response.timings, "Search")
                if (response.gallery.isEmpty() && response.personalContext.isEmpty()) {
                    activeSearchResponse = null
                    response.plannerSession?.close()
                    setModelLoading(false, "")
                    warmPlannerForNextSearch()
                    answer.text = "I couldn't find matching photos or personal context yet."
                    return@runOnUiThread
                }
                if (response.gallery.isNotEmpty()) {
                    renderResults(response.gallery, generation, response.totalGalleryMatches)
                    answer.text = "I found ${response.totalGalleryMatches} matching item${if (response.totalGalleryMatches == 1) "" else "s"}."
                } else {
                    answer.text = "I found relevant personal context."
                }
                if (!response.needsAnswer) {
                    activeSearchResponse = null
                    response.plannerSession?.close()
                    setModelLoading(false, "")
                    followUpPanel.visibility = View.GONE
                    followUpRow.removeAllViews()
                    sourcePanel.visibility = View.GONE
                    warmPlannerForNextSearch()
                    return@runOnUiThread
                }
                activeSearchResponse = response
                val contextCount = response.answerContext?.items?.size
                    ?: response.answerGallery.size.coerceAtMost(4)
                val hasVisualContext = response.answerContext?.includeVisuals == true &&
                    contextCount > 0
                val visualCount = if (hasVisualContext) {
                    contextCount.coerceAtMost(
                        QueryCategoryContextPolicy.SCENARY_ANSWER_IMAGE_LIMIT,
                    )
                } else {
                    0
                }
                setModelLoading(
                    true,
                    if (hasVisualContext && response.personalContext.isNotEmpty()) {
                        "${GemmaModelSelection.selected(this).displayName} is joining $visualCount downscaled scenery images with $contextCount text records and personal context…"
                    } else if (hasVisualContext) {
                        "${GemmaModelSelection.selected(this).displayName} is joining $visualCount downscaled scenery images with $contextCount text records…"
                    } else {
                        "${GemmaModelSelection.selected(this).displayName} is reading $contextCount scoped OCR/metadata record${if (contextCount == 1) "" else "s"}…"
                    },
                )
                galleryIndexer.answerAsync(
                    query = text,
                    response = response,
                    onFinished = { answerResult ->
                        runOnUiThread {
                            if (generation != searchGeneration) return@runOnUiThread
                            setModelLoading(false, "")
                            answerResult.fold(
                                onSuccess = {
                                    answer.visibility = View.GONE
                                    appendConversationTurn(
                                        question = text,
                                        answerText = it.text.ifBlank {
                                            "I couldn't find enough photos or details to answer that yet."
                                        },
                                    )
                                    // Replace the broad search browser with the
                                    // exact four gallery records used for this
                                    // answer. This makes the answer auditable at
                                    // a glance without exposing an unrelated
                                    // tail of search results.
                                    renderAnswerEvidence(it.sources, generation)
                                    sourcePanel.visibility = View.GONE
                                    sourceRow.removeAllViews()
                                    sourceDetails.removeAllViews()
                                    renderFollowUps(emptyList(), emptyList(), generation)
                                    renderQpOutput(it.effectivePlanJson)
                                    renderTimeStats(it.timings, "Total")
                                    refreshPreparation()
                                },
                                onFailure = {
                                    followUpPanel.visibility = View.GONE
                                    followUpRow.removeAllViews()
                                    sourcePanel.visibility = View.GONE
                                    renderTimeStats(response.timings, "Search")
                                    answer.visibility = View.VISIBLE
                                    answer.text = "I found matching photos, but couldn't generate an answer on this device yet."
                                },
                            )
                        }
                    },
                    onFollowUps = { suggestions ->
                        runOnUiThread {
                            if (generation == searchGeneration && suggestions.isNotEmpty()) {
                                renderFollowUpsAfterAnswer(suggestions, generation)
                            }
                        }
                    },
                    warmPlannerAfterAnswer = false,
                    warmAnswerAfterAnswer = true,
                )
            }
        }
    }

    private fun setModelLoading(visible: Boolean, message: String) {
        if (!::modelProgress.isInitialized || !::modelStatus.isInitialized) return
        modelProgress.visibility = if (visible) View.VISIBLE else View.GONE
        modelStatus.visibility = if (visible) View.VISIBLE else View.GONE
        modelStatus.text = message
        // Keep the status text stable; the progress indicator alone conveys
        // active work without repeatedly fading the rest of the screen.
        modelStatus.clearAnimation()
    }

    /** Replaces a QP session consumed by a browse, empty, or failed search. */
    private fun warmPlannerForNextSearch() {
        GemmaRuntime.preloadPlannerAfterAnswerAsync(
            this,
            QueryPlannerRuntime.plannerSystemInstruction(),
        )
    }

    private fun showQueryPlanningTelemetry() {
        timeStatsPanel.visibility = View.VISIBLE
        qpOutputLabel.text = "QP output • planning"
        qpOutputText.text = "Planning the executable query…"
        timeStatsDetails.visibility = View.GONE
        timeStatsDetails.removeAllViews()
        timeStatsSummary = "Timing…"
        updateTimeStatsButton()
    }

    private fun renderQpOutput(effectivePlanJson: String) {
        if (effectivePlanJson.isBlank()) return
        timeStatsPanel.visibility = View.VISIBLE
        qpOutputLabel.text = "QP output • ready"
        qpOutputText.text = effectivePlanJson
    }

    private fun renderQpFailure() {
        timeStatsPanel.visibility = View.VISIBLE
        if (qpOutputLabel.text.toString().contains("planning", ignoreCase = true)) {
            qpOutputLabel.text = "QP output • unavailable"
            qpOutputText.text = "The executable query could not be prepared."
        }
        timeStatsSummary = "Stopped"
        updateTimeStatsButton()
    }

    private fun updateTimeStatsButton() {
        val expanded = timeStatsDetails.visibility == View.VISIBLE
        timeStatsButton.text = "${if (expanded) "⌃" else "◷"}  $timeStatsSummary"
    }

    private fun renderTimeStats(
        timings: PhaseTimings,
        phaseLabel: String,
    ) {
        val wasExpanded = timeStatsDetails.visibility == View.VISIBLE
        timeStatsDetails.removeAllViews()
        val phaseRows = listOf(
            "Query planning" to timings.queryPlanningMs,
            "Search / hybrid retrieval" to timings.searchMs,
            "Top 4 context selection" to timings.diverseRerankingMs,
            "Evidence curation" to timings.evidenceCurationMs,
            "Answer generation" to timings.answerGenerationMs,
            "Follow-up query/actions" to timings.followUpMs,
        ).filter { (_, durationMs) -> durationMs > 0L }
        val rows = buildList {
            phaseRows.forEach { (label, durationMs) ->
                add(label to durationMs)
                if (label == "Query planning") {
                    timings.plannerProfile?.let { profile ->
                        if (profile.startupPrefillMs > 0L) add("  ↳ QP system warm-up (overlapped)" to profile.startupPrefillMs)
                        if (profile.prefillMs > 0L) add("  ↳ QP prefill (${profile.prefillTokens} tok)" to profile.prefillMs)
                        if (profile.decodeMs > 0L) add("  ↳ QP token generation (${profile.decodeTokens} tok)" to profile.decodeMs)
                        if (profile.timeToFirstTokenMs > 0L) add("  ↳ QP time to first token" to profile.timeToFirstTokenMs)
                    }
                }
                if (label == "Answer generation") {
                    timings.answerProfile?.let { profile ->
                        if (profile.startupPrefillMs > 0L) add("  ↳ AP system warm-up (overlapped)" to profile.startupPrefillMs)
                        if (profile.prefillMs > 0L) add("  ↳ AP answer-prompt prefill" + profile.prefillTokens.takeIf { it > 0 }?.let { " ($it tok)" }.orEmpty() to profile.prefillMs)
                        if (profile.decodeMs > 0L) add("  ↳ AP token generation" + profile.decodeTokens.takeIf { it > 0 }?.let { " ($it tok)" }.orEmpty() to profile.decodeMs)
                        if (profile.timeToFirstTokenMs > 0L) add("  ↳ AP time to first token" to profile.timeToFirstTokenMs)
                    }
                }
            }
            add(
            (if (phaseLabel == "Total") "Total" else "Total so far") to timings.totalMs,
            )
        }
        rows.forEachIndexed { index, (label, durationMs) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (index == rows.lastIndex) {
                    setPadding(0, dp(8), 0, 0)
                } else {
                    setPadding(0, dp(3), 0, dp(3))
                }
            }
            row.addView(TextView(this).apply {
                text = label
                val indented = label.startsWith("  ↳")
                textSize = if (index == rows.lastIndex) 13f else if (indented) 11f else 12f
                setTextColor(if (index == rows.lastIndex) Color.rgb(35, 38, 49) else if (indented) Color.rgb(105, 109, 125) else Color.rgb(91, 95, 110))
                if (indented) setPadding(dp(12), dp(2), 0, dp(2))
                if (index == rows.lastIndex) setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = formatPhaseDuration(durationMs)
                textSize = if (index == rows.lastIndex) 13f else 12f
                setTextColor(Color.rgb(50, 57, 99))
                if (index == rows.lastIndex) setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            timeStatsDetails.addView(row, matchWrap())
        }
        listOfNotNull(
            timings.plannerProfile?.let { "QP placement: ${it.backendPlacement}" },
            timings.answerProfile?.let { "Answer placement: ${it.backendPlacement}" },
        ).forEach { placement ->
            timeStatsDetails.addView(TextView(this).apply {
                text = placement
                textSize = 11f
                setTextColor(Color.rgb(91, 95, 110))
                setPadding(0, dp(3), 0, dp(3))
            }, matchWrap())
        }
        timeStatsPanel.visibility = View.VISIBLE
        timeStatsDetails.visibility = if (wasExpanded) View.VISIBLE else View.GONE
        timeStatsSummary = if (timings.totalMs > 0L) {
            "$phaseLabel ${formatPhaseDuration(timings.totalMs)}"
        } else {
            "$phaseLabel…"
        }
        updateTimeStatsButton()
    }

    private fun formatPhaseDuration(durationMs: Long): String = when {
        durationMs < 1_000L -> "${durationMs} ms"
        else -> String.format(java.util.Locale.US, "%.2f s", durationMs / 1_000.0)
    }

    private fun renderResults(
        matches: List<GalleryMedia>,
        generation: Long,
        totalMatches: Int = matches.size,
    ) {
        resultGrid.layoutParams = resultGrid.layoutParams.apply { height = dp(520) }
        val current = resultAdapter
        if (current == null || current.generation != generation || !current.hasSameItems(matches)) {
            resultGrid.adapter = null
            current?.dispose()
            resultAdapter = SearchResultAdapter(matches, generation).also {
                resultGrid.adapter = it
            }
        }
        resultCount.text = if (totalMatches > matches.size) {
            "Showing newest ${matches.size} of $totalMatches matches • scroll to browse"
        } else {
            "All ${matches.size} match${if (matches.size == 1) "" else "es"} • newest first • scroll to browse"
        }
        resultCount.visibility = if (matches.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Shows the answer's exact gallery inputs instead of the broad result set. */
    private fun renderAnswerEvidence(sources: List<AnswerSource>, generation: Long) {
        val evidence = sources.mapNotNull { source ->
            source.media?.takeIf { source.type == AnswerSourceType.GALLERY_IMAGE }
        }.distinctBy { it.mediaStoreId }.take(4)
        if (evidence.isEmpty()) return
        renderResults(evidence, generation)
        resultGrid.layoutParams = resultGrid.layoutParams.apply {
            height = dp(220 * ((evidence.size + 1) / 2))
        }
        resultGrid.requestLayout()
        resultCount.text = "Evidence used for this answer • ${evidence.size} selected photo${if (evidence.size == 1) "" else "s"}"
        resultCount.visibility = View.VISIBLE
    }

    private inner class SearchResultAdapter(
        private val matches: List<GalleryMedia>,
        val generation: Long,
    ) : BaseAdapter() {
        private val requested = HashSet<Long>()
        private val thumbnails = object : LruCache<Long, Bitmap>(RESULT_THUMBNAIL_CACHE_KB) {
            override fun sizeOf(key: Long, value: Bitmap): Int =
                (value.allocationByteCount / 1024).coerceAtLeast(1)

            override fun entryRemoved(
                evicted: Boolean,
                key: Long,
                oldValue: Bitmap,
                newValue: Bitmap?,
            ) {
                if (oldValue !== newValue && !oldValue.isRecycled) oldValue.recycle()
            }
        }
        private var disposed = false

        override fun getCount(): Int = matches.size
        override fun getItem(position: Int): GalleryMedia = matches[position]
        override fun getItemId(position: Int): Long = matches[position].mediaStoreId

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder: ResultViewHolder
            val card: LinearLayout
            if (convertView is LinearLayout && convertView.tag is ResultViewHolder) {
                card = convertView
                holder = convertView.tag as ResultViewHolder
            } else {
                val image = ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.rgb(224, 226, 233))
                }
                val caption = TextView(this@MainActivity).apply {
                    textSize = 12f
                    setTextColor(Color.rgb(63, 65, 74))
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(7), dp(7), dp(7), 0)
                }
                holder = ResultViewHolder(image, caption)
                card = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(5), dp(5), dp(5), dp(8))
                    background = roundedBackground(Color.rgb(247, 248, 251), dp(18).toFloat())
                    addView(
                        image,
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(156)),
                    )
                    addView(
                        caption,
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)),
                    )
                    tag = holder
                }
            }
            val media = getItem(position)
            holder.image.tag = media.mediaStoreId
            holder.image.contentDescription = resultCaption(media)
            holder.caption.text = resultCaption(media)
            card.contentDescription = "Open ${resultCaption(media)}"
            card.setOnClickListener {
                startActivity(MediaDetailActivity.intent(this@MainActivity, media))
            }
            val cached = thumbnails.get(media.mediaStoreId)
            if (cached != null && !cached.isRecycled) {
                holder.image.setImageBitmap(cached)
            } else {
                holder.image.setImageDrawable(null)
                requestThumbnail(media, holder.image)
            }
            return card
        }

        fun hasSameItems(other: List<GalleryMedia>): Boolean =
            matches.size == other.size &&
                matches.indices.all { matches[it].mediaStoreId == other[it].mediaStoreId }

        fun dispose() {
            disposed = true
            requested.clear()
            thumbnails.evictAll()
        }

        private fun requestThumbnail(media: GalleryMedia, target: ImageView) {
            if (disposed || !requested.add(media.mediaStoreId)) return
            galleryIndexer.loadThumbnailAsync(media) { bitmapResult ->
                val bitmap = bitmapResult.getOrNull()
                runOnUiThread {
                    requested -= media.mediaStoreId
                    if (
                        bitmap == null ||
                        disposed ||
                        generation != searchGeneration ||
                        isFinishing ||
                        isDestroyed
                    ) {
                        if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
                        return@runOnUiThread
                    }
                    thumbnails.put(media.mediaStoreId, bitmap)
                    if (target.tag == media.mediaStoreId && !bitmap.isRecycled) {
                        target.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    private fun resultCaption(media: GalleryMedia): String = buildString {
        append(if (media.mimeType.startsWith("video/", ignoreCase = true)) "Video" else "Photo")
        val timestamp = media.dateTakenMs ?: media.dateModifiedSeconds.takeIf { it > 0L }?.times(1000L)
        timestamp?.let {
            append(" • ")
            append(
                java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy")
                    .format(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())),
            )
        }
        media.locationName?.takeIf(String::isNotBlank)?.let {
            append("\n")
            append(it)
        }
    }

    private fun renderAnswerSources(sources: List<AnswerSource>, generation: Long) {
        sourceRow.removeAllViews()
        sourceDetails.removeAllViews()
        expandedSourceId = null
        if (sources.isEmpty()) {
            sourcePanel.visibility = View.GONE
            return
        }
        sourcePanel.visibility = View.VISIBLE
        sources.forEach { source ->
            val icon = when (source.type) {
                AnswerSourceType.GALLERY_IMAGE -> "▣"
                AnswerSourceType.PERSONAL_CONTEXT -> source.context?.kind?.icon ?: "•"
            }
            val chip = TextView(this).apply {
                text = "$icon ${source.label}"
                textSize = 13f
                setTextColor(Color.rgb(50, 57, 99))
                gravity = Gravity.CENTER
                setPadding(dp(13), dp(8), dp(13), dp(8))
                background = roundedBackground(Color.rgb(235, 238, 255), dp(18).toFloat())
                contentDescription = "Expand source ${source.label}"
                setOnClickListener {
                    if (expandedSourceId == source.id) {
                        expandedSourceId = null
                        sourceDetails.removeAllViews()
                    } else {
                        expandedSourceId = source.id
                        showExpandedSource(source, generation)
                    }
                }
            }
            sourceRow.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38),
            ).apply { marginEnd = dp(7) })
        }
    }

    private fun renderFollowUps(
        suggestions: List<FollowUpSuggestion>,
        sources: List<AnswerSource>,
        generation: Long,
    ) {
        followUpRow.removeAllViews()
        if (suggestions.isEmpty()) {
            followUpPanel.visibility = View.GONE
            return
        }
        followUpPanel.visibility = View.VISIBLE
        suggestions.forEach { suggestion ->
            val sourceId = Regex("\\b[GC]\\d+\\b")
                .find(suggestion.text)
                ?.value
            val chip = TextView(this).apply {
                text = if (suggestion.isQuery) {
                    "⌕ ${suggestion.text}"
                } else {
                    "↗ ${suggestion.text}"
                }
                textSize = 13f
                setTextColor(Color.rgb(50, 57, 99))
                setPadding(dp(13), dp(8), dp(13), dp(8))
                maxLines = 2
                background = roundedBackground(Color.rgb(235, 238, 255), dp(18).toFloat())
                isClickable = suggestion.isQuery || sourceId != null
                if (isClickable) {
                    setOnClickListener {
                        if (suggestion.isQuery) {
                            answerFollowUp(suggestion.text, generation)
                        } else {
                            val source = sources.firstOrNull { it.id == sourceId }
                            if (source != null) {
                                expandedSourceId = source.id
                                showExpandedSource(source, generation)
                            }
                        }
                    }
                }
            }
            followUpRow.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(7) })
        }
    }

    /** Runs a chip on the existing result set; it never enters query planning. */
    private fun answerFollowUp(followUpQuery: String, generation: Long) {
        if (generation != searchGeneration || followUpInFlight) return
        val currentResponse = activeSearchResponse ?: return
        followUpInFlight = true
        query.isEnabled = false
        query.alpha = 0.62f
        followUpPanel.visibility = View.GONE
        followUpRow.removeAllViews()
        answer.visibility = View.VISIBLE
        answer.text = "Answering from the current search results…"
        appendConversationQuestion(followUpQuery)
        setModelLoading(true, "${GemmaModelSelection.selected(this).displayName} is selecting fresh answer context from the current results…")
        galleryIndexer.answerFollowUpAsync(
            query = followUpQuery,
            currentResponse = currentResponse,
            onFinished = { result ->
                runOnUiThread {
                    if (generation != searchGeneration) return@runOnUiThread
                    followUpInFlight = false
                    setModelLoading(false, "")
                    result.fold(
                        onSuccess = {
                            answer.visibility = View.GONE
                            appendConversationAnswer(
                                it.text.ifBlank {
                                    "I couldn't find enough details in the current results to answer that yet."
                                },
                            )
                            renderAnswerEvidence(it.sources, generation)
                            sourcePanel.visibility = View.GONE
                            sourceRow.removeAllViews()
                            sourceDetails.removeAllViews()
                            renderFollowUps(emptyList(), emptyList(), generation)
                            renderTimeStats(it.timings, "Follow-up")
                        },
                        onFailure = {
                            answer.text = "I couldn't answer that from the current search results yet."
                            answer.visibility = View.VISIBLE
                            renderFollowUps(emptyList(), emptyList(), generation)
                        },
                    )
                    refreshPreparation()
                }
            },
            onFollowUps = { suggestions ->
                runOnUiThread {
                    if (generation == searchGeneration && !followUpInFlight && suggestions.isNotEmpty()) {
                        renderFollowUpsAfterAnswer(suggestions, generation)
                    }
                }
            },
        )
    }

    /** Posts chip rendering after the answer card has been laid out on screen. */
    private fun renderFollowUpsAfterAnswer(
        suggestions: List<FollowUpSuggestion>,
        generation: Long,
    ) {
        conversationPanel.post {
            if (generation == searchGeneration && !followUpInFlight) {
                renderFollowUps(suggestions, emptyList(), generation)
            }
        }
    }

    private fun appendConversationTurn(question: String, answerText: String) {
        appendConversationQuestion(question)
        appendConversationAnswer(answerText)
    }

    private fun appendConversationQuestion(text: String) = appendConversationBubble(
        label = "You",
        text = text,
        backgroundColor = Color.rgb(10, 132, 255),
        textColor = Color.WHITE,
        labelColor = Color.rgb(222, 239, 255),
        gravity = Gravity.END,
    )

    private fun appendConversationAnswer(text: String) = appendConversationBubble(
        label = "Ask Galaxy",
        text = text,
        backgroundColor = Color.WHITE,
        textColor = Color.rgb(28, 28, 30),
        labelColor = Color.rgb(99, 99, 102),
        gravity = Gravity.START,
    )

    private fun appendConversationBubble(
        label: String,
        text: String,
        backgroundColor: Int,
        textColor: Int,
        labelColor: Int,
        gravity: Int,
    ) {
        conversationPanel.visibility = View.VISIBLE
        val row = LinearLayout(this).apply {
            this.gravity = gravity
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = roundedBackground(
                backgroundColor,
                dp(19).toFloat(),
                if (gravity == Gravity.START) Color.rgb(229, 229, 234) else null,
            )
            if (gravity == Gravity.START) elevation = dp(1).toFloat()
        }
        card.addView(TextView(this).apply {
            this.text = label
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(labelColor)
            setPadding(0, 0, 0, dp(3))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        card.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(textColor)
            maxWidth = resources.displayMetrics.widthPixels - dp(108)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        row.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        conversationPanel.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(7) })
    }

    private fun showExpandedSource(source: AnswerSource, generation: Long) {
        sourceDetails.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = roundedBackground(Color.rgb(247, 248, 252), dp(16).toFloat())
        }
        card.addView(TextView(this).apply {
            text = source.label
            textSize = 14f
            setTextColor(Color.rgb(35, 38, 49))
            setTypeface(typeface, Typeface.BOLD)
        }, matchWrap())
        if (source.type == AnswerSourceType.GALLERY_IMAGE && source.media != null) {
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.rgb(225, 227, 235))
                contentDescription = "Source image ${source.label}"
            }
            card.addView(image, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(130),
            ).apply { topMargin = dp(9) })
            galleryIndexer.loadThumbnailAsync(source.media) { result ->
                val bitmap = result.getOrNull()
                runOnUiThread {
                    if (generation == searchGeneration && expandedSourceId == source.id && bitmap != null && !isFinishing) {
                        image.setImageBitmap(bitmap)
                    }
                }
            }
        }
        card.addView(TextView(this).apply {
            text = source.detail.take(MAX_SOURCE_DETAIL_CHARS)
            textSize = 13f
            setTextColor(Color.rgb(76, 79, 91))
            setPadding(0, dp(9), 0, 0)
        }, matchWrap())
        sourceDetails.addView(card, matchWrap())
    }

    private fun refreshFacePrompt() {
        facePromptRequested = true
        galleryIndexer.faceClustersAsync { result ->
            runOnUiThread {
                val clusters = result.getOrDefault(emptyList())
                val unnamed = clusters.count { it.label.isBlank() }
                facePromptStatus.text = if (unnamed == 0) {
                    "The complete face-group inventory is ready."
                } else {
                    "Review all ${clusters.size} face groups once, merge duplicates, then name the people you know."
                }
                facePromptPreview.removeAllViews()
                clusters.filter { it.label.isBlank() }.take(5).forEach { cluster ->
                    val image = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(Color.rgb(220, 224, 241))
                        contentDescription = "Unlabeled face group"
                    }
                    facePromptPreview.addView(image, LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                        marginEnd = dp(8)
                    })
                    galleryIndexer.loadFaceThumbnailAsync(cluster) { thumbnailResult ->
                        val bitmap = thumbnailResult.getOrNull()
                        runOnUiThread {
                            if (bitmap != null && !isFinishing) image.setImageBitmap(bitmap)
                        }
                    }
                }
            }
        }
    }

    private fun hasGalleryPermission(): Boolean = galleryPermissions().all {
        Build.VERSION.SDK_INT < 23 || checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun galleryPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun roundedBackground(
        color: Int,
        radius: Float,
        strokeColor: Int? = null,
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            strokeColor?.let { setStroke(dp(1), it) }
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun matchWrap(): ViewGroup.LayoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    companion object {
        private const val GALLERY_PERMISSION_REQUEST = 1001
        private const val LOCATION_PERMISSION_REQUEST = 1002
        private const val MAX_SOURCE_DETAIL_CHARS = 1_400
        private const val RESULT_THUMBNAIL_CACHE_KB = 24 * 1024
        private const val STALE_WORKER_TIMEOUT_MS = 2 * 60 * 1_000L
    }

    private data class ResultViewHolder(
        val image: ImageView,
        val caption: TextView,
    )
}
