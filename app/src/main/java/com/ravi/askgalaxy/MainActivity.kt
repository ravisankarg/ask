package com.ravi.askgalaxy

import android.Manifest
import android.app.Activity
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
import android.view.animation.AlphaAnimation
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
import android.widget.TextView
import android.graphics.drawable.GradientDrawable

class MainActivity : Activity() {
    private lateinit var preparationPanel: LinearLayout
    private lateinit var preparationStatus: TextView
    private lateinit var preparationProgress: ProgressBar
    private lateinit var searchPanel: LinearLayout
    private lateinit var facePromptPanel: LinearLayout
    private lateinit var facePromptStatus: TextView
    private lateinit var facePromptPreview: LinearLayout
    private lateinit var query: EditText
    private lateinit var resultPanel: LinearLayout
    private lateinit var answer: TextView
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
    private var plannerPreloadStarted = false
    private var resultAdapter: SearchResultAdapter? = null
    private var lastSubmittedQuery = ""
    private var lastSubmittedAtMs = 0L
    private var timeStatsSummary = "Timing…"
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            refreshPreparation()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PreparationNotifier.createChannel(this)
        galleryIndexer = GalleryIndexer(this)
        setContentView(createContent())
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(30), dp(24), dp(30))
            setBackgroundColor(Color.WHITE)
        }

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
        header.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_manage)
            setContentDescription("Settings")
            setColorFilter(Color.rgb(50, 53, 62))
            setBackgroundColor(Color.TRANSPARENT)
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
            background = roundedBackground(Color.rgb(245, 246, 249), dp(24).toFloat())
        }
        searchCard.addView(TextView(this).apply {
            text = "⌕"
            textSize = 28f
            setTextColor(Color.rgb(96, 99, 110))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(36), dp(60)))
        query = EditText(this).apply {
            hint = "Ask about photos, trips, or receipts"
            textSize = 16f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = null
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
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        // Keep the full search browser ahead of the bounded answer context.
        // The 8 records selected for Gemma must never look like a replacement
        // for the user-visible result set.
        resultPanel.addView(sourcePanel, matchWrap())
        searchPanel.addView(
            resultPanel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(
            searchPanel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        // GridView is now the only vertical scroller on the search surface.
        // This preserves row virtualization and makes all 200 results directly
        // reachable before, during, and after answer generation.
        return root
    }

    private fun refreshPreparation() {
        val snapshot = PreparationStore(this).read()
        val indexPrepared = snapshot.isPrepared
        val gemmaReady = ModelCatalog.gemma.isInstalled(this)
        val gemmaPart = ModelCatalog.gemma.partFile(this)
        val gemmaBytes = gemmaPart.takeIf { it.isFile }?.length() ?: 0L
        val gemmaPercent = if (ModelCatalog.gemma.expectedBytes > 0L) {
            ((gemmaBytes * 100L) / ModelCatalog.gemma.expectedBytes).toInt().coerceIn(0, 100)
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
                "Gallery index is ready. Installing Gemma 4: ${formatBytes(gemmaBytes)} / ${formatBytes(ModelCatalog.gemma.expectedBytes)}"
            } else {
                "Gallery index is ready. Gemma 4 download is queued…"
            }
        } else {
            snapshot.message
        }
        preparationPanel.visibility = if (ready) View.GONE else View.VISIBLE
        facePromptPanel.visibility = if (waitingForFaceTags) View.VISIBLE else View.GONE
        searchPanel.visibility = if (ready) View.VISIBLE else View.GONE
        if (ready && !searchReady) {
            searchReady = true
            SigLipTextEncoder.preloadAsync(this)
            NativeVectorIndex.preloadAsync(this)
        } else if (!ready) {
            searchReady = false
            plannerPreloadStarted = false
        }
        if (ready && !plannerPreloadStarted) {
            // Gemma occupies several GiB of mapped/native state on the target
            // device. Do not make it compete with the one-time OCR migration;
            // a user search can still load it on demand.
            val pendingOcr = runCatching { galleryIndexer.pendingOcrCount() }
                .getOrDefault(Int.MAX_VALUE)
            if (pendingOcr == 0) {
                plannerPreloadStarted = true
                GemmaRuntime.preloadPlannerAsync(
                    this,
                    QueryPlannerRuntime.plannerSystemInstruction(),
                )
            }
        }
        if (waitingForFaceTags && !facePromptRequested) refreshFacePrompt()
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
        val text = query.text?.toString().orEmpty().trim()
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        if (text.equals(lastSubmittedQuery, ignoreCase = true) && now - lastSubmittedAtMs < 1_500L) {
            return
        }
        lastSubmittedQuery = text
        lastSubmittedAtMs = now
        val generation = ++searchGeneration
        // Keep Gemma 4 resident across planning and answer generation. The
        // prewarmed planner conversation is intentionally claimed by this
        // query so its system-preface KV cache is reused.
        SigLipTextEncoder.preloadAsync(this)
        NativeVectorIndex.preloadAsync(this)
        resultPanel.visibility = View.VISIBLE
        answer.text = "Searching your private gallery…"
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
                                "Gemma 4 E4B is planning this query locally…"
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
                    setModelLoading(false, "")
                    renderQpFailure()
                    answer.text = if (
                        error.message.orEmpty().contains("Gemma 4 E4B", ignoreCase = true) ||
                        error.cause?.message.orEmpty().contains("Gemma 4 E4B", ignoreCase = true)
                    ) {
                        "Gemma 4 E4B couldn't prepare this search query yet."
                    } else {
                        "Search paused while the on-device index is unavailable."
                    }
                    return@runOnUiThread
                }
                renderQpOutput(response.effectivePlanJson)
                renderTimeStats(response.timings, "Search")
                if (response.gallery.isEmpty() && response.personalContext.isEmpty()) {
                    response.plannerSession?.close()
                    setModelLoading(false, "")
                    answer.text = "I couldn't find matching photos or personal context yet."
                    return@runOnUiThread
                }
                if (response.gallery.isNotEmpty()) {
                    renderResults(response.gallery, generation, response.totalGalleryMatches)
                    answer.text = "I found ${response.totalGalleryMatches} matching item${if (response.totalGalleryMatches == 1) "" else "s"}."
                } else {
                    answer.text = "I found relevant personal context."
                }
                val contextCount = response.answerContext?.items?.size
                    ?: response.answerGallery.size.coerceAtMost(8)
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
                        "Gemma 4 E4B is joining $visualCount downscaled scenery images with $contextCount text records and personal context…"
                    } else if (hasVisualContext) {
                        "Gemma 4 E4B is joining $visualCount downscaled scenery images with $contextCount text records…"
                    } else {
                        "Gemma 4 E4B is reading $contextCount scoped OCR/metadata record${if (contextCount == 1) "" else "s"}…"
                    },
                )
                galleryIndexer.answerAsync(text, response) { answerResult ->
                    runOnUiThread {
                        if (generation != searchGeneration) return@runOnUiThread
                        setModelLoading(false, "")
                        answerResult.fold(
                            onSuccess = {
                                answer.text = it.text.ifBlank {
                                    "I couldn't find enough photos or details to answer that yet."
                                }
                                // The top-8 Context Picker output is private
                                // answer input, not a second user-facing result
                                // set. Keep only the full search browser visible.
                                sourcePanel.visibility = View.GONE
                                sourceRow.removeAllViews()
                                sourceDetails.removeAllViews()
                                renderFollowUps(it.followUps, emptyList(), generation)
                                renderQpOutput(it.effectivePlanJson)
                                renderTimeStats(it.timings, "Total")
                            },
                            onFailure = {
                                followUpPanel.visibility = View.GONE
                                followUpRow.removeAllViews()
                                sourcePanel.visibility = View.GONE
                                renderTimeStats(response.timings, "Search")
                                answer.text = "I found matching photos, but couldn't generate an answer on this device yet."
                            },
                        )
                    }
                }
            }
        }
    }

    private fun setModelLoading(visible: Boolean, message: String) {
        if (!::modelProgress.isInitialized || !::modelStatus.isInitialized) return
        modelProgress.visibility = if (visible) View.VISIBLE else View.GONE
        modelStatus.visibility = if (visible) View.VISIBLE else View.GONE
        modelStatus.text = message
        if (visible) {
            if (modelStatus.animation == null) {
                modelStatus.startAnimation(AlphaAnimation(0.45f, 1f).apply {
                    duration = 900L
                    repeatMode = android.view.animation.Animation.REVERSE
                    repeatCount = android.view.animation.Animation.INFINITE
                })
            }
        } else {
            modelStatus.clearAnimation()
        }
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
            "Top 8 context selection" to timings.diverseRerankingMs,
            "Evidence curation" to timings.evidenceCurationMs,
            "Answer generation" to timings.answerGenerationMs,
            "Follow-up query/actions" to timings.followUpMs,
        ).filter { (_, durationMs) -> durationMs > 0L }
        val rows = phaseRows + listOf(
            (if (phaseLabel == "Total") "Total" else "Total so far") to timings.totalMs,
        )
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
                textSize = if (index == rows.lastIndex) 13f else 12f
                setTextColor(if (index == rows.lastIndex) Color.rgb(35, 38, 49) else Color.rgb(91, 95, 110))
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
                            query.setText(suggestion.text)
                            query.setSelection(query.text.length)
                            search()
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

    private fun roundedBackground(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
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
