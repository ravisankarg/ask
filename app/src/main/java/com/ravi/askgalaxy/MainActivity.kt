package com.ravi.askgalaxy

import android.animation.ObjectAnimator
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
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
import android.widget.Toast
import androidx.work.WorkManager
import android.graphics.drawable.GradientDrawable

class MainActivity : Activity() {
    private lateinit var preparationPanel: LinearLayout
    private lateinit var preparationStatus: TextView
    private lateinit var preparationProgress: ProgressBar
    private lateinit var preparationAction: Button
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
    private lateinit var nextBriefPanel: LinearLayout
    private lateinit var nextBriefRow: LinearLayout
    private lateinit var sourcePanel: LinearLayout
    private lateinit var sourceRow: LinearLayout
    private lateinit var sourceDetails: LinearLayout
    private lateinit var modelProgress: ProgressBar
    private lateinit var modelStatus: TextView
    private lateinit var answerPipelinePanel: LinearLayout
    private lateinit var answerPipelineLabel: TextView
    private lateinit var answerPipelineProgress: ProgressBar
    private lateinit var featuredResultCount: TextView
    private lateinit var featuredResultGrid: GridView
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
    private var featuredResultAdapter: SearchResultAdapter? = null
    private var resultAdapter: SearchResultAdapter? = null
    private var lastSubmittedQuery = ""
    private var lastSubmittedAtMs = 0L
    /** The visible result set is reused by follow-up chips; no new QP/search. */
    private var activeSearchResponse: SearchResponse? = null
    /** One accepted turn only; supplied locally to E2B for indirect follow-up resolution. */
    private var previousGroundedQuery = ""
    private var previousGroundedAnswer = ""
    private var followUpInFlight = false
    private var deferredFreshQuery: String? = null
    private var deferredPreserveConversation = false
    private var deferredDisplayQuestion: String? = null
    private var timeStatsSummary = "Timing…"
    private var answerPipelineAnimator: ObjectAnimator? = null
    private val handler = Handler(Looper.getMainLooper())

    private enum class PipelineUiStage(val title: String, val progress: Int) {
        PLANNING("Query planning", 12),
        SEARCHING("Searching", 38),
        ANSWERING("Answer generation", 66),
        REVIEWING("Review", 84),
        ACCEPTING("Accept", 96),
    }

    private sealed class SearchResultItem {
        data class Gallery(
            val media: GalleryMedia,
            val cosineScore: Float?,
        ) : SearchResultItem()

        data class Document(
            val match: DocumentMatch,
        ) : SearchResultItem()
    }
    private val documentIndexReleasedListener: () -> Unit = {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                // A launch-time warmup can be skipped while the document
                // worker owns the GPU gate. Retry only after that gate opens.
                launchPlannerWarmupRequested = false
                refreshPreparation()
            }
        }
    }
    private val refresh = object : Runnable {
        override fun run() {
            // Poll only while WorkManager is preparing the gallery/model.
            // Gemma readiness itself is delivered as a one-shot callback.
            if (refreshPreparation()) handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModelCatalog.removeRetiredModels(this)
        // Retire any persisted work from the removed experimental KV feature.
        WorkManager.getInstance(this).cancelUniqueWork("ask_galaxy_record_classification")
        PreparationNotifier.createChannel(this)
        galleryIndexer = GalleryIndexer(this)
        setContentView(createContent())
        DocumentIndexRuntimeGate.addReleaseListener(documentIndexReleasedListener)
        GemmaRuntime.setPlannerWarmupStateListener {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    refreshPreparation()
                    val deferredQuery = deferredFreshQuery
                    if (GemmaRuntime.isPlannerReady() && !deferredQuery.isNullOrBlank()) {
                        deferredFreshQuery = null
                        val preserveConversation = deferredPreserveConversation
                        val displayQuestion = deferredDisplayQuestion
                        deferredPreserveConversation = false
                        deferredDisplayQuestion = null
                        query.setText(deferredQuery)
                        query.setSelection(query.text.length)
                        query.post {
                            search(
                                queryOverride = deferredQuery,
                                preserveConversation = preserveConversation,
                                displayQuestion = displayQuestion,
                            )
                        }
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
        // Gallery preparation is resumable and intentionally does not rerun
        // after completion. Keep deletions made later in the phone gallery
        // from becoming stale searchable records with this cheap identity
        // reconciliation pass.
        galleryIndexer.reconcileStaleRecordsAsync { result ->
            result.onFailure { error ->
                Log.w("AskGalaxy", "Gallery stale-record reconciliation failed", error)
            }
        }
        GemmaDownloadScheduler.enqueueIfNeeded(this)
        // Personal indexing owns its public EmbeddingGemma download. This
        // also recovers when the gallery preparation worker completed before
        // personal-source indexing was enabled.
        val personalReader = DocumentSourceReader(this)
        if (DocumentSource.entries.any(personalReader::isAvailable)) {
            // Do not cancel/restart a failed or active pass on every app
            // launch. The worker records the error and Settings provides an
            // explicit retry action, preventing an endless WorkManager loop.
            DocumentIndexScheduler.enqueue(this)
        }
        val preparation = PreparationStore(this).read()
        val selectedGemma = ModelCatalog.gemma(this)
        val indexingModelsReady = ModelCatalog.all(this)
            .filter { it.required && it != selectedGemma }
            .all { it.isInstalled(this) }
        val visual = IndexProgressStore(this).read(IndexProgressStage.VISUAL)
        val visualWorkerStale =
            !visual.completed &&
                visual.current > 0L &&
                visual.updatedAtMs > 0L &&
                System.currentTimeMillis() - visual.updatedAtMs > STALE_WORKER_TIMEOUT_MS
        if (!preparation.isPrepared || !indexingModelsReady) {
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

    override fun onStop() {
        // Full-GPU E2B is intentionally released as soon as the UI is no
        // longer visible. Reopening the app pays a cold-load cost, but avoids
        // retaining several GiB of GL/native memory in the background.
        if (!isChangingConfigurations) {
            launchPlannerWarmupRequested = false
            GemmaRuntime.releaseResidentAsync()
        }
        super.onStop()
    }

    override fun onDestroy() {
        answerPipelineAnimator?.cancel()
        DocumentIndexRuntimeGate.removeReleaseListener(documentIndexReleasedListener)
        GemmaRuntime.setPlannerWarmupStateListener(null)
        if (::resultGrid.isInitialized) {
            featuredResultGrid.adapter = null
            featuredResultAdapter?.dispose()
            featuredResultAdapter = null
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
            setPadding(dp(20), dp(18), dp(20), dp(20))
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
            dp(52),
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
        header.addView(titleGroup, LinearLayout.LayoutParams(0, dp(52), 1f))
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
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header, matchWrap())

        preparationPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
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
            setPadding(0, dp(12), 0, dp(8))
        }
        preparationPanel.addView(preparationProgress, matchWrap())
        preparationPanel.addView(TextView(this).apply {
            text = "You can leave Ask Galaxy. Model installation and indexing continue securely in the background."
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, dp(12))
        }, matchWrap())
        preparationAction = Button(this).apply {
            setAllCaps(false)
            visibility = View.GONE
        }
        preparationPanel.addView(preparationAction, matchWrap())
        root.addView(preparationPanel, matchWrap())

        facePromptPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
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
            setPadding(0, dp(5), 0, dp(8))
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
            topMargin = dp(10)
        })
        val facePromptParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        facePromptParams.topMargin = dp(14)
        root.addView(facePromptPanel, facePromptParams)

        searchPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(18), 0, 0)
        }
        val searchCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(10), 0)
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
        }, LinearLayout.LayoutParams(dp(32), dp(52)))
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
        searchCard.addView(query, LinearLayout.LayoutParams(0, dp(52), 1f))
        searchPanel.addView(searchCard, matchWrap())
        gemmaWarmupPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), 0)
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
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(Color.rgb(246, 247, 252), dp(16).toFloat())
        }
        val queryTelemetryHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        qpOutputLabel = TextView(this).apply {
            text = "QP op • planning"
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
            setPadding(dp(4), dp(6), dp(4), 0)
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
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)),
        )
        timeStatsPanel.addView(queryTelemetryHeader, matchWrap())
        qpOutputText = TextView(this).apply {
            text = "Planning the executable query…"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(43, 48, 78))
            setTextIsSelectable(true)
            setPadding(0, dp(5), 0, 0)
        }
        timeStatsPanel.addView(qpOutputText, matchWrap())
        timeStatsPanel.addView(timeStatsDetails, matchWrap())
        searchPanel.addView(timeStatsPanel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(6)
        })

        resultPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(0), dp(12), dp(0), dp(8))
        }
        answer = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(42, 44, 52))
            setPadding(dp(6), 0, dp(6), dp(8))
        }
        resultPanel.addView(answer, matchWrap())
        conversationPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(6), 0, dp(6), dp(6))
        }
        resultPanel.addView(conversationPanel, matchWrap())
        followUpPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(6), 0, dp(6), dp(8))
        }
        followUpPanel.addView(TextView(this).apply {
            text = "Try next"
            textSize = 13f
            setTextColor(Color.rgb(91, 95, 110))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
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
        nextBriefPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(6), 0, dp(6), dp(8))
        }
        nextBriefPanel.addView(TextView(this).apply {
            text = "Actions"
            textSize = 13f
            setTextColor(Color.rgb(91, 95, 110))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }, matchWrap())
        val nextBriefScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        nextBriefRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        nextBriefScroll.addView(nextBriefRow, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        nextBriefPanel.addView(nextBriefScroll, matchWrap())
        resultPanel.addView(nextBriefPanel, matchWrap())
        sourcePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(6), 0, dp(6), dp(8))
        }
        sourcePanel.addView(TextView(this).apply {
            text = "Answer context • selected from the search results"
            textSize = 13f
            setTextColor(Color.rgb(91, 95, 110))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
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
            setPadding(dp(6), dp(4), dp(6), dp(4))
            visibility = View.GONE
        }
        resultPanel.addView(modelStatus, matchWrap())
        answerPipelinePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(6), dp(2), dp(6), dp(5))
        }
        answerPipelineLabel = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.rgb(76, 81, 105))
            setSingleLine(true)
            isHorizontalScrollBarEnabled = false
        }
        answerPipelinePanel.addView(answerPipelineLabel, matchWrap())
        answerPipelineProgress = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal,
        ).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(Color.rgb(82, 103, 205))
        }
        answerPipelinePanel.addView(
            answerPipelineProgress,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply {
                topMargin = dp(3)
            },
        )
        resultPanel.addView(answerPipelinePanel, matchWrap())
        featuredResultCount = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(63, 66, 78))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), dp(4), dp(6), dp(2))
            visibility = View.GONE
        }
        resultPanel.addView(featuredResultCount, matchWrap())
        featuredResultGrid = createResultGrid()
        resultPanel.addView(
            featuredResultGrid,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)),
        )
        resultCount = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(63, 66, 78))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), dp(10), dp(6), dp(2))
            visibility = View.GONE
        }
        resultPanel.addView(resultCount, matchWrap())
        resultGrid = createResultGrid()
        resultPanel.addView(
            resultGrid,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(456)),
        )
        // Answer context belongs directly below the answer, before follow-up
        // queries and actions. The broad result grid is never shown there.
        resultPanel.addView(sourcePanel, 2, matchWrap())
        searchPanel.addView(resultPanel, matchWrap())
        root.addView(searchPanel, matchWrap())

        // The page is the primary scroll surface, so the query, QP output,
        // history, and follow-up turns all move naturally together.
        return conversationScroll
    }

    private fun createResultGrid(): GridView = GridView(this).apply {
            numColumns = 4
            horizontalSpacing = dp(8)
            verticalSpacing = dp(6)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setPadding(0, dp(2), 0, dp(2))
            clipToPadding = false
            visibility = View.GONE
        }

    /** @return true only while preparation progress needs periodic polling. */
    private fun refreshPreparation(): Boolean {
        val snapshot = PreparationStore(this).read()
        val indexProgressStore = IndexProgressStore(this)
        val embeddingProgress = indexProgressStore.read(IndexProgressStage.EMBEDDING_GEMMA)
        val documentProgress = indexProgressStore.read(IndexProgressStage.DOCUMENT)
        val personalSourcesEnabled = runCatching {
            val reader = DocumentSourceReader(this)
            DocumentSource.entries.any(reader::isAvailable)
        }.getOrDefault(false)
        val selectedGemma = ModelCatalog.gemma(this)
        val embeddingArtifacts = listOf(ModelCatalog.embeddingGemma, ModelCatalog.embeddingGemmaTokenizer)
        val embeddingReady = embeddingArtifacts.all { it.isInstalled(this) }
        val embeddingBytes = embeddingArtifacts.sumOf { artifact ->
            when {
                artifact.isInstalled(this) -> artifact.expectedBytes
                artifact.partFile(this).isFile -> artifact.partFile(this).length()
                else -> 0L
            }
        }
        val gemmaReady = selectedGemma.isInstalled(this)
        val gemmaPart = selectedGemma.partFile(this)
        val gemmaBytes = gemmaPart.takeIf { it.isFile }?.length() ?: 0L
        val gemmaPercent = if (selectedGemma.expectedBytes > 0L) {
            ((gemmaBytes * 100L) / selectedGemma.expectedBytes).toInt().coerceIn(0, 100)
        } else {
            0
        }
        val waitingForFaceTags = snapshot.phase == PreparationPhase.WAITING_FOR_FACE_TAGS
        val galleryReady = snapshot.isReady
        val personalIndexReady = !personalSourcesEnabled || documentProgress.completed
        val allIndexingReady = galleryReady && embeddingReady && personalIndexReady
        val ready = allIndexingReady && gemmaReady
        val searchReadyNow = ready

        val statusProgress: Int
        val statusIndeterminate: Boolean
        when {
            !embeddingReady -> {
                statusProgress = if (embeddingProgress.total > 0L) {
                    embeddingProgress.percent
                } else {
                    ((embeddingBytes * 100L) / embeddingArtifacts.sumOf { it.expectedBytes })
                        .toInt().coerceIn(0, 100)
                }
                statusIndeterminate = embeddingBytes == 0L && embeddingProgress.total <= 0L
            }
            !galleryReady -> {
                statusProgress = snapshot.percent
                statusIndeterminate = snapshot.total <= 0L
            }
            !personalIndexReady -> {
                statusProgress = documentProgress.percent
                statusIndeterminate = documentProgress.total <= 0L
            }
            !gemmaReady -> {
                statusProgress = gemmaPercent
                statusIndeterminate = gemmaBytes == 0L
            }
            else -> {
                statusProgress = 100
                statusIndeterminate = false
            }
        }
        preparationProgress.isIndeterminate = statusIndeterminate
        preparationProgress.progress = statusProgress

        preparationStatus.text = when {
            !embeddingReady && embeddingProgress.phase == "authorization required" ->
                "Download EmbeddingGemma model — sign in to Hugging Face and accept the Gemma license."
            !embeddingReady && embeddingProgress.error.isNotBlank() ->
                "EmbeddingGemma download paused: ${embeddingProgress.error}"
            !embeddingReady && embeddingBytes > 0L ->
                "Download EmbeddingGemma model: ${formatBytes(embeddingBytes)} / ${formatBytes(embeddingArtifacts.sumOf { it.expectedBytes })}"
            !embeddingReady ->
                "Download EmbeddingGemma model — waiting for authorization."
            !galleryReady && waitingForFaceTags ->
                "Gallery indexing is complete. Review and name your private face groups before searching."
            !galleryReady ->
                "Indexing your gallery: ${snapshot.message}"
            !personalIndexReady && documentProgress.error.isNotBlank() ->
                "Personal indexing paused: ${documentProgress.error}"
            !personalIndexReady ->
                if (documentProgress.total > 0L) {
                    "Indexing your personal sources: ${documentProgress.current}/${documentProgress.total}"
                } else {
                    "Indexing your personal sources…"
                }
            !gemmaReady && gemmaBytes > 0L ->
                "Download Gemma 4 model: ${formatBytes(gemmaBytes)} / ${formatBytes(selectedGemma.expectedBytes)}"
            !gemmaReady ->
                "Download Gemma 4 model — waiting to start."
            else -> "All indexing is complete. Warming Gemma 4 for search…"
        }

        val actionForEmbedding = !embeddingReady
        val actionForRetry = !actionForEmbedding && documentProgress.error.isNotBlank()
        preparationAction.visibility = if (actionForEmbedding || actionForRetry) View.VISIBLE else View.GONE
        if (actionForEmbedding) {
            preparationAction.text = "Sign in and authorize EmbeddingGemma"
            preparationAction.setOnClickListener {
                startActivity(Intent(this, EmbeddingGemmaAuthorizationActivity::class.java))
            }
        } else if (actionForRetry) {
            preparationAction.text = "Retry personal indexing"
            preparationAction.setOnClickListener {
                DocumentIndexScheduler.restart(this)
                refreshPreparation()
            }
        }
        preparationPanel.visibility = if (ready) View.GONE else View.VISIBLE
        facePromptPanel.visibility = if (waitingForFaceTags) View.VISIBLE else View.GONE
        searchPanel.visibility = if (searchReadyNow) View.VISIBLE else View.GONE
        if (searchReadyNow) {
            val plannerReady = GemmaRuntime.isPlannerReady()
            val hasReusableFollowUpContext = activeSearchResponse != null && !followUpInFlight
            // A submitted query must consume the prewarmed QP system KV.
            // An uncached Conversation re-prefills the 16K system context and
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
        if (searchReadyNow && !searchReady) {
            searchReady = true
            SigLipTextEncoder.preloadAsync(this)
            NativeVectorIndex.preloadAsync(this)
            DocumentVectorIndex.preloadAsync(this)
        } else if (!searchReadyNow) {
            searchReady = false
        }
        if (searchReadyNow && !launchPlannerWarmupRequested) {
            // Warm Gemma before enabling the search bar. The runtime remains
            // bounded to the mobile-safe 16K/eight-image configuration.
            val pendingOcr = runCatching { galleryIndexer.pendingOcrCount() }
                .getOrDefault(Int.MAX_VALUE)
            if (pendingOcr == 0) {
                launchPlannerWarmupRequested = true
                GemmaRuntime.preloadPlannerAsync(
                    this,
                    QueryPlannerRuntime.plannerSystemInstruction(this),
                )
            }
        }
        if (waitingForFaceTags && !facePromptRequested) refreshFacePrompt()
        return !ready
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1_024L * 1_024L) return "${bytes / 1_024L} KB"
        if (bytes < 1_024L * 1_024L * 1_024L) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / (1_024.0 * 1_024.0))
        }
        return String.format(java.util.Locale.US, "%.1f GB", bytes / (1_024.0 * 1_024.0 * 1_024.0))
    }

    private fun search(
        queryOverride: String? = null,
        preserveConversation: Boolean = false,
        displayQuestion: String? = null,
    ) {
        if (!::query.isInitialized) return
        val requestedText = (queryOverride ?: query.text?.toString().orEmpty()).trim()
        if (!GemmaRuntime.isPlannerReady()) {
            if (requestedText.isNotBlank()) {
                // A typed fresh search intentionally trades the kept answer
                // KV for a planner KV; chip follow-ups never take this path.
                // Answer KV is intentionally resident after a turn. A plain
                // planner preload refuses to run while it is reserved, which
                // used to leave this fresh-search path stuck forever.
                deferredFreshQuery = requestedText
                deferredPreserveConversation = preserveConversation
                deferredDisplayQuestion = displayQuestion
                query.isEnabled = false
                query.alpha = 0.62f
                GemmaRuntime.preloadPlannerAfterAnswerAsync(
                    this,
                    QueryPlannerRuntime.plannerSystemInstruction(this),
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
        val answerFeatureEnabledForSearch = AnswerFeaturePreferences.isEnabled(this)
        activeSearchResponse = null
        followUpInFlight = false
        // The consumed QP KV is now unavailable by design. Keep the field
        // locked through QP and retrieval, then warm the next planner turn.
        query.isEnabled = false
        query.alpha = 0.62f
        // The prewarmed planner conversation is intentionally claimed by this
        // query so its system-preface KV cache is reused.
        SigLipTextEncoder.preloadAsync(this)
        NativeVectorIndex.preloadAsync(this)
        DocumentVectorIndex.preloadAsync(this)
        resultPanel.visibility = View.VISIBLE
        // Search-result mode is intentionally answer-free while QP and the
        // validator are being tuned.
        answer.visibility = View.GONE
        answer.text = ""
        if (preserveConversation) {
            appendConversationQuestion(displayQuestion ?: text)
        } else {
            conversationPanel.removeAllViews()
            conversationPanel.visibility = View.GONE
        }
        featuredResultGrid.adapter = null
        featuredResultAdapter?.dispose()
        featuredResultAdapter = null
        featuredResultGrid.visibility = View.GONE
        featuredResultCount.visibility = View.GONE
        resultGrid.adapter = null
        resultAdapter?.dispose()
        resultAdapter = null
        resultCount.visibility = View.GONE
        sourcePanel.visibility = View.GONE
        sourceRow.removeAllViews()
        sourceDetails.removeAllViews()
        followUpPanel.visibility = View.GONE
        followUpRow.removeAllViews()
        nextBriefPanel.visibility = View.GONE
        nextBriefRow.removeAllViews()
        showQueryPlanningTelemetry()
        showAnswerPipeline(PipelineUiStage.PLANNING)
        expandedSourceId = null
        setModelLoading(true, "Blending image, OCR, and metadata matches…")
        galleryIndexer.searchAsync(
            query = text,
            previousQuery = previousGroundedQuery,
            previousAnswer = previousGroundedAnswer,
            onMatches = { matches, totalMatches ->
                runOnUiThread {
                    if (generation != searchGeneration || matches.isEmpty()) return@runOnUiThread
                    val items = matches.map { it.toSearchResultItem() }
                    if (answerFeatureEnabledForSearch) {
                        renderResults(items.take(ANSWER_ENABLED_RESULT_LIMIT), generation, totalMatches)
                    } else {
                        renderAnswerDisabledResults(items, generation, totalMatches)
                    }
                }
            },
            onProgress = { progress ->
                runOnUiThread {
                    if (generation != searchGeneration) return@runOnUiThread
                    setModelLoading(
                        true,
                        when (progress.stage) {
                            SearchStage.QUERY_PLANNING -> {
                                showAnswerPipeline(PipelineUiStage.PLANNING)
                                "${GemmaModelSelection.selected(this).displayName} is planning this query locally…"
                            }
                            SearchStage.QUERY_PLANNED -> {
                                showAnswerPipeline(PipelineUiStage.SEARCHING)
                                renderQpOutput(
                                    progress.plannerJson.ifBlank { progress.effectivePlanJson },
                                )
                                renderTimeStats(progress.timings, "Planning")
                                "QP ready. Searching image, OCR, and metadata indexes…"
                            }
                            SearchStage.HYBRID_RETRIEVAL -> {
                                showAnswerPipeline(PipelineUiStage.SEARCHING)
                                renderTimeStats(progress.timings, "Search")
                                "Search results ready."
                            }
                            SearchStage.DIVERSE_EVIDENCE -> {
                                showAnswerPipeline(PipelineUiStage.SEARCHING)
                                renderTimeStats(progress.timings, "Search")
                                "Search results ready."
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
                    hideAnswerPipeline()
                    val plannerRejection = renderQpFailure(error)
                    warmPlannerForNextSearch()
                    answer.text = plannerRejection ?: if (
                        error.message.orEmpty().contains("Gemma 4", ignoreCase = true) ||
                        error.cause?.message.orEmpty().contains("Gemma 4", ignoreCase = true)
                    ) {
                        "${GemmaModelSelection.selected(this).displayName} couldn't prepare this search query yet."
                    } else {
                        "Search paused while the on-device index is unavailable."
                    }
                    resultCount.text = answer.text
                    resultCount.visibility = View.VISIBLE
                    return@runOnUiThread
                }
                renderQpOutput(response.plannerJson.ifBlank { response.effectivePlanJson })
                renderTimeStats(response.timings, "Search")
                if (response.gallery.isEmpty() && response.documentMatches.isEmpty()) {
                    activeSearchResponse = null
                    response.plannerSession?.close()
                    setModelLoading(false, "")
                    hideAnswerPipeline()
                    warmPlannerForNextSearch()
                    featuredResultGrid.adapter = null
                    featuredResultAdapter?.dispose()
                    featuredResultAdapter = null
                    featuredResultGrid.visibility = View.GONE
                    featuredResultCount.visibility = View.GONE
                    resultGrid.adapter = null
                    resultAdapter?.dispose()
                    resultAdapter = null
                    resultGrid.visibility = View.GONE
                    resultCount.text = "No matching records found."
                    resultCount.visibility = View.VISIBLE
                    return@runOnUiThread
                }
                activeSearchResponse = response
                val combinedResults = response.mergedResults
                    .takeIf { it.isNotEmpty() }
                    ?.map { it.toSearchResultItem(response.galleryCosineScores) }
                    ?: buildList {
                        response.gallery.forEach { media ->
                            add(SearchResultItem.Gallery(media, response.galleryCosineScores[media.mediaStoreId]))
                        }
                        response.documentMatches.forEach { match ->
                            add(SearchResultItem.Document(match))
                        }
                    }
                val totalMatches = maxOf(
                    combinedResults.size,
                    response.totalGalleryMatches + response.totalDocumentMatches,
                )
                if (response.answerFeatureEnabled) {
                    renderResults(
                        combinedResults.take(ANSWER_ENABLED_RESULT_LIMIT),
                        generation,
                        totalMatches,
                    )
                } else {
                    renderAnswerDisabledResults(combinedResults, generation, totalMatches)
                }
                answer.visibility = View.GONE
                if (!preserveConversation) conversationPanel.visibility = View.GONE
                followUpPanel.visibility = View.GONE
                nextBriefPanel.visibility = View.GONE
                sourcePanel.visibility = View.GONE
                if (response.needsAnswer) {
                    answer.visibility = View.VISIBLE
                    answer.text = "Answering from the top 8 hybrid records…"
                    showAnswerPipeline(PipelineUiStage.ANSWERING)
                    if (!preserveConversation) appendConversationQuestion(text)
                    setModelLoading(
                        true,
                        GemmaModelSelection.selected(this).displayName +
                            " is answering from the top 8 records…",
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
                                        showAnswerPipelineAccepted(generation)
                                        answer.visibility = View.GONE
                                        appendConversationAnswer(
                                            it.text.ifBlank { "I couldn't answer that yet." },
                                        )
                                        previousGroundedQuery = response.resolvedQuery.ifBlank { text }
                                        previousGroundedAnswer = it.text
                                        // The top-16 grid is the single public
                                        // evidence surface. Its first eight
                                        // are already the immutable answer
                                        // window, so do not duplicate them as
                                        // a second thumbnail/source strip.
                                        sourcePanel.visibility = View.GONE
                                        sourceRow.removeAllViews()
                                        sourceDetails.removeAllViews()
                                        expandedSourceId = null
                                        renderFollowUps(emptyList(), emptyList(), generation)
                                        renderTimeStats(it.timings, "Answer")
                                    },
                                    onFailure = { error ->
                                        hideAnswerPipeline()
                                        answer.text = when (error) {
                                            is AnswerEvidenceUnavailableException ->
                                                "I couldn't read result R${error.recordNumber}, so I stopped instead of answering from different records."
                                            else -> "I couldn't answer that yet."
                                        }
                                        answer.visibility = View.VISIBLE
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
                        onNextBriefs = { suggestions ->
                            runOnUiThread {
                                if (generation == searchGeneration && suggestions.isNotEmpty()) {
                                    renderNextBriefsAfterAnswer(suggestions, generation)
                                }
                            }
                        },
                        onStage = { stage ->
                            runOnUiThread {
                                if (generation != searchGeneration) return@runOnUiThread
                                showAnswerPipeline(
                                    when (stage) {
                                        AnswerPipelineStage.ANSWERING -> PipelineUiStage.ANSWERING
                                        AnswerPipelineStage.REVIEWING -> PipelineUiStage.REVIEWING
                                        AnswerPipelineStage.ACCEPTING -> PipelineUiStage.ACCEPTING
                                    },
                                )
                            }
                        },
                    )
                } else {
                    setModelLoading(false, "")
                    hideAnswerPipeline()
                    response.plannerSession?.close()
                    warmPlannerForNextSearch()
                }
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

    /** Small, real-time pipeline hint that shares the existing result footer. */
    private fun showAnswerPipeline(stage: PipelineUiStage) {
        if (!::answerPipelinePanel.isInitialized) return
        answerPipelinePanel.visibility = View.VISIBLE
        val activeIndex = stage.ordinal
        answerPipelineLabel.text = PipelineUiStage.entries.mapIndexed { index, item ->
            val marker = when {
                index < activeIndex -> "✓"
                index == activeIndex -> "●"
                else -> "○"
            }
            "$marker ${item.title}"
        }.joinToString("   ")
        answerPipelineAnimator?.cancel()
        answerPipelineAnimator = ObjectAnimator.ofInt(
            answerPipelineProgress,
            "progress",
            answerPipelineProgress.progress,
            stage.progress,
        ).apply {
            duration = 260L
            start()
        }
    }

    private fun showAnswerPipelineAccepted(generation: Long) {
        if (!::answerPipelinePanel.isInitialized) return
        answerPipelineAnimator?.cancel()
        answerPipelineProgress.progress = 100
        answerPipelineLabel.text = "✓ Accepted"
        answerPipelinePanel.visibility = View.VISIBLE
        handler.postDelayed({
            if (generation == searchGeneration) hideAnswerPipeline()
        }, 1_500L)
    }

    private fun hideAnswerPipeline() {
        if (!::answerPipelinePanel.isInitialized) return
        answerPipelineAnimator?.cancel()
        answerPipelinePanel.visibility = View.GONE
        answerPipelineProgress.progress = 0
    }

    /** Replaces a QP session consumed by a browse, empty, or failed search. */
    private fun warmPlannerForNextSearch() {
        GemmaRuntime.preloadPlannerAfterAnswerAsync(
            this,
            QueryPlannerRuntime.plannerSystemInstruction(this),
        )
    }

    private fun showQueryPlanningTelemetry() {
        timeStatsPanel.visibility = View.VISIBLE
        qpOutputLabel.text = "QP op • planning"
        qpOutputText.text = "Planning the executable query…"
        timeStatsDetails.visibility = View.GONE
        timeStatsDetails.removeAllViews()
        timeStatsSummary = "Timing…"
        updateTimeStatsButton()
    }

    private fun renderQpOutput(effectivePlanJson: String) {
        if (effectivePlanJson.isBlank()) return
        timeStatsPanel.visibility = View.VISIBLE
        qpOutputLabel.text = "QP op • ready"
        qpOutputText.text = effectivePlanJson
    }

    private fun renderQpFailure(error: Throwable): String? {
        timeStatsPanel.visibility = View.VISIBLE
        val rejection = QueryPlannerFailureDiagnostics.from(error)
        if (rejection != null) {
            val diagnostic = rejection.render()
            qpOutputLabel.text = "QP op • validator rejected"
            qpOutputText.text = diagnostic
            timeStatsSummary = "QP rejected"
            updateTimeStatsButton()
            return diagnostic
        }
        if (qpOutputLabel.text.toString().contains("planning", ignoreCase = true)) {
            qpOutputLabel.text = "QP op • unavailable"
            qpOutputText.text = "The executable query could not be prepared."
        }
        timeStatsSummary = "Stopped"
        updateTimeStatsButton()
        return null
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
            "Answer image preparation" to timings.answerImagePreparationMs,
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
                    if (timings.answerInitialPassMs > 0L) {
                        add("  ↳ Initial multimodal pass" to timings.answerInitialPassMs)
                    }
                    if (timings.answerRetryMs > 0L) {
                        add("  ↳ Validation retry" to timings.answerRetryMs)
                    }
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
        items: List<SearchResultItem>,
        generation: Long,
        totalMatches: Int = items.size,
    ) {
        featuredResultAdapter?.dispose()
        featuredResultAdapter = null
        featuredResultGrid.adapter = null
        featuredResultGrid.visibility = View.GONE
        featuredResultCount.visibility = View.GONE
        resultAdapter = bindResultGrid(
            grid = resultGrid,
            current = resultAdapter,
            items = items,
            generation = generation,
            maxRows = 4,
        )
        resultCount.text = if (totalMatches > items.size) {
            "Showing top ${items.size} of $totalMatches matches"
        } else {
            "Top ${items.size} match${if (items.size == 1) "" else "es"}"
        }
        resultCount.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Search-only comparison: Top 8 is intentionally repeated inside the full Top 24 below. */
    private fun renderAnswerDisabledResults(
        items: List<SearchResultItem>,
        generation: Long,
        totalMatches: Int,
    ) {
        val overall = items.take(CrossEngineFusionPolicy.OVERALL_RESULT_LIMIT)
        val featured = overall.take(CrossEngineFusionPolicy.FEATURED_RESULT_LIMIT)
        featuredResultAdapter = bindResultGrid(
            grid = featuredResultGrid,
            current = featuredResultAdapter,
            items = featured,
            generation = generation,
            maxRows = 2,
        )
        resultAdapter = bindResultGrid(
            grid = resultGrid,
            current = resultAdapter,
            items = overall,
            generation = generation,
            maxRows = 6,
        )
        featuredResultCount.text = "Top 8 • fused across enabled indexes (${featured.size} available)"
        featuredResultCount.visibility = if (featured.isEmpty()) View.GONE else View.VISIBLE
        resultCount.text = "Top 24 overall • ${overall.size} of $totalMatches matches"
        resultCount.visibility = if (overall.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun bindResultGrid(
        grid: GridView,
        current: SearchResultAdapter?,
        items: List<SearchResultItem>,
        generation: Long,
        maxRows: Int,
    ): SearchResultAdapter? {
        if (items.isEmpty()) {
            grid.adapter = null
            grid.visibility = View.GONE
            current?.dispose()
            return null
        }
        val visibleRows = ((items.size + 3) / 4).coerceIn(1, maxRows)
        grid.layoutParams = grid.layoutParams.apply {
            height = dp(visibleRows * 128 + 4)
        }
        grid.visibility = View.VISIBLE
        if (
            current != null &&
            current.generation == generation &&
            current.hasSameItems(items)
        ) {
            return current
        }
        grid.adapter = null
        current?.dispose()
        val cacheKb = if (grid === featuredResultGrid) {
            FEATURED_THUMBNAIL_CACHE_KB
        } else {
            RESULT_THUMBNAIL_CACHE_KB
        }
        return SearchResultAdapter(items, generation, cacheKb).also { grid.adapter = it }
    }

    /** Shows the answer's exact gallery inputs instead of the broad result set. */
    private fun renderAnswerEvidence(sources: List<AnswerSource>, generation: Long) {
        // Answer flows expose only the compact context chips. Full search
        // results stay hidden; tapping a chip opens its complete record.
        resultAdapter?.dispose()
        resultAdapter = null
        resultGrid.adapter = null
        resultGrid.visibility = View.GONE
        featuredResultAdapter?.dispose()
        featuredResultAdapter = null
        featuredResultGrid.adapter = null
        featuredResultGrid.visibility = View.GONE
        featuredResultCount.visibility = View.GONE
        resultCount.visibility = View.GONE
    }

    private fun renderDocumentResults(matches: List<DocumentMatch>, generation: Long) {
        (sourcePanel.getChildAt(0) as? TextView)?.text = "Personal records"
        if (resultGrid.adapter == null) {
            resultCount.text = "${matches.size} personal record${if (matches.size == 1) "" else "s"} from the private index"
            resultCount.visibility = View.VISIBLE
        }
        sourceRow.removeAllViews()
        sourceDetails.removeAllViews()
        expandedSourceId = null
        sourcePanel.visibility = View.VISIBLE
        matches.take(12).forEachIndexed { index, match ->
            val chunk = match.chunk
            sourceDetails.addView(TextView(this).apply {
                val cosine = match.cosineScore?.let { "Cosine %.2f".format(java.util.Locale.US, it) }
                    ?: "Cosine — (keyword match)"
                text = "D${index + 1}  ${chunk.source.displayName} • ${chunk.title}\n" +
                    "$cosine\n${chunk.text.trim()}"
                textSize = 14f
                setTextColor(Color.rgb(44, 46, 58))
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = roundedBackground(Color.WHITE, dp(12).toFloat())
                contentDescription = "Personal record ${chunk.title}"
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) })
        }
    }

    private inner class SearchResultAdapter(
        private val items: List<SearchResultItem>,
        val generation: Long,
        thumbnailCacheKb: Int,
    ) : BaseAdapter() {
        private val requested = HashSet<Long>()
        private val thumbnails = object : LruCache<Long, Bitmap>(thumbnailCacheKb) {
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

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): SearchResultItem = items[position]
        override fun getItemId(position: Int): Long = when (val item = getItem(position)) {
            is SearchResultItem.Gallery -> item.media.mediaStoreId
            is SearchResultItem.Document -> item.match.chunk.stableId
        }

        override fun getViewTypeCount(): Int = 2

        override fun getItemViewType(position: Int): Int = when (getItem(position)) {
            is SearchResultItem.Gallery -> 0
            is SearchResultItem.Document -> 1
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = getItem(position)
            val type = getItemViewType(position)
            val holder = (convertView?.tag as? ResultViewHolder)
                ?.takeIf { it.viewType == type }
                ?: createResultCard(type)
            val card = holder.card
            when (item) {
                is SearchResultItem.Gallery -> bindGalleryCard(holder, card, item)
                is SearchResultItem.Document -> bindDocumentCard(holder, card, item.match)
            }
            return card
        }

        fun hasSameItems(other: List<SearchResultItem>): Boolean = items == other

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

        private fun createResultCard(type: Int): ResultViewHolder {
            val thumbnail: View = if (type == 0) {
                ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.rgb(224, 226, 233))
                }
            } else {
                TextView(this@MainActivity).apply {
                    gravity = Gravity.CENTER
                    textSize = 10f
                    maxLines = 4
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Color.rgb(51, 57, 80))
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                    background = roundedBackground(Color.rgb(232, 235, 249), dp(12).toFloat())
                }
            }
            val caption = TextView(this@MainActivity).apply {
                textSize = 11f
                setTextColor(Color.rgb(63, 65, 74))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(6), dp(5), dp(6), 0)
            }
            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(5), dp(5), dp(5), dp(7))
                background = roundedBackground(Color.rgb(247, 248, 251), dp(18).toFloat())
                addView(thumbnail, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(70),
                ))
                addView(caption, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(38),
                ))
            }
            return ResultViewHolder(card, thumbnail, caption, type).also { card.tag = it }
        }

        private fun bindGalleryCard(
            holder: ResultViewHolder,
            card: LinearLayout,
            item: SearchResultItem.Gallery,
        ) {
            val image = holder.thumbnail as ImageView
            val media = item.media
            val caption = resultCaption(media, item.cosineScore)
            image.tag = media.mediaStoreId
            image.contentDescription = caption
            holder.caption.text = caption
            card.contentDescription = "Open $caption"
            card.setOnClickListener { showGalleryPopup(media) }
            val cached = thumbnails.get(media.mediaStoreId)
            if (cached != null && !cached.isRecycled) {
                image.setImageBitmap(cached)
            } else {
                image.setImageDrawable(null)
                requestThumbnail(media, image)
            }
        }

        private fun bindDocumentCard(
            holder: ResultViewHolder,
            card: LinearLayout,
            match: DocumentMatch,
        ) {
            val thumbnail = holder.thumbnail as TextView
            val chunk = match.chunk
            val preview = chunk.text.replace(Regex("\\s+"), " ").trim()
            thumbnail.text = "${documentIcon(chunk.source)}\n${chunk.title.take(30)}\n${preview.take(72)}"
            val caption = documentCaption(match)
            thumbnail.contentDescription = caption
            holder.caption.text = caption
            card.contentDescription = "Open $caption"
            card.setOnClickListener { showDocumentPopup(match) }
        }
    }

    private data class ResultViewHolder(
        val card: LinearLayout,
        val thumbnail: View,
        val caption: TextView,
        val viewType: Int,
    )

    private fun HybridSearchResult.toSearchResultItem(
        galleryCosineScores: Map<Long, Float> = emptyMap(),
    ): SearchResultItem = when (this) {
        is HybridSearchResult.Gallery ->
            SearchResultItem.Gallery(media, galleryCosineScores[media.mediaStoreId])
        is HybridSearchResult.Document -> SearchResultItem.Document(match)
    }

    private fun documentCaption(match: DocumentMatch): String = buildString {
        append(
            match.cosineScore?.let { "Cosine %.2f".format(java.util.Locale.US, it) }
                ?: "Keyword/direct match",
        )
        append("\n")
        append(match.chunk.source.displayName)
        append(" • ")
        append(match.chunk.title.ifBlank { "Personal record" }.take(32))
    }

    private fun documentIcon(source: DocumentSource): String = when (source) {
        DocumentSource.MESSAGES -> "💬 Messages"
        DocumentSource.CALENDAR -> "📅 Calendar"
        DocumentSource.CONTACTS -> "👤 Contact"
        DocumentSource.CALL_LOGS -> "📞 Call log"
        DocumentSource.FILES -> "📄 File"
    }

    /**
     * Keeps record browsing inside MainActivity. Dismissing this dialog cannot
     * remove the search/chat task or require Android to recreate its parent.
     */
    private fun showGalleryPopup(media: GalleryMedia) {
        var previewBitmap: Bitmap? = null
        var dialog: AlertDialog? = null
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageButton(this@MainActivity).apply {
                setImageResource(android.R.drawable.ic_media_previous)
                contentDescription = "Back to search results"
                setBackgroundColor(Color.TRANSPARENT)
                setColorFilter(Color.rgb(40, 43, 52))
                setOnClickListener { dialog?.dismiss() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(TextView(this@MainActivity).apply {
                text = if (media.mimeType.startsWith("video/", true)) {
                    "Video details"
                } else {
                    "Photo details"
                }
                textSize = 21f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(25, 28, 36))
                setPadding(dp(8), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(235, 237, 243))
            contentDescription = "Expanded gallery result"
        }
        val details = TextView(this).apply {
            text = buildString {
                append(resultCaption(media).substringAfter('\n'))
                if (media.width > 0 && media.height > 0) {
                    append("\nDimensions: ${media.width} × ${media.height}")
                }
                media.personLabel?.takeIf(String::isNotBlank)?.let {
                    append("\nPeople: $it")
                }
                media.ocrText.takeIf(String::isNotBlank)?.let {
                    append("\nText found: ")
                    append(it.replace(Regex("\\s+"), " ").take(MAX_SOURCE_DETAIL_CHARS))
                }
            }
            textSize = 14f
            setTextColor(Color.rgb(55, 58, 70))
            setPadding(dp(8), dp(12), dp(8), dp(8))
            setTextIsSelectable(true)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(14))
            addView(header, matchWrap())
            addView(image, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(420),
            ).apply { topMargin = dp(8) })
            addView(details, matchWrap())
        }
        dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(body, matchWrap()) })
            .create()
            .also { created ->
                created.setOnDismissListener {
                    previewBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                    previewBitmap = null
                }
                created.show()
                created.window?.setLayout(
                    (resources.displayMetrics.widthPixels * 0.96f).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
        galleryIndexer.loadDetailPreviewAsync(media) { result ->
            val bitmap = result.getOrNull()
            runOnUiThread {
                if (bitmap == null) return@runOnUiThread
                if (dialog?.isShowing == true && !isFinishing && !isDestroyed) {
                    previewBitmap = bitmap
                    image.setImageBitmap(bitmap)
                } else if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun showDocumentPopup(match: DocumentMatch) {
        val chunk = match.chunk
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
            addView(TextView(this@MainActivity).apply {
                text = documentCaption(match)
                textSize = 13f
                setTextColor(Color.rgb(62, 66, 86))
                setPadding(0, 0, 0, dp(10))
            }, matchWrap())
            addView(TextView(this@MainActivity).apply {
                text = chunk.text.trim().ifBlank { "No text content" }
                textSize = 16f
                setTextColor(Color.rgb(35, 38, 49))
            }, matchWrap())
            chunk.metadata.takeIf(String::isNotBlank)?.let { metadata ->
                addView(TextView(this@MainActivity).apply {
                    text = "\n$metadata"
                    textSize = 13f
                    setTextColor(Color.rgb(91, 95, 110))
                }, matchWrap())
            }
        }
        val scroll = ScrollView(this).apply {
            addView(body, matchWrap())
        }
        AlertDialog.Builder(this)
            .setTitle("${documentIcon(chunk.source)} • ${chunk.title.ifBlank { "Personal record" }}")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun resultCaption(media: GalleryMedia, cosineScore: Float? = null): String = buildString {
        append(
            cosineScore?.let { "Cosine %.2f".format(java.util.Locale.US, it) }
                ?: "Metadata/OCR match",
        )
        append("\n")
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
            val icon = sourceIcon(source)
            val chip = TextView(this).apply {
                text = icon
                textSize = 22f
                setTextColor(Color.rgb(50, 57, 99))
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(4))
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
                dp(48),
                dp(48),
            ).apply { marginEnd = dp(7) })
        }
    }

    private fun sourceIcon(source: AnswerSource): String = when (source.type) {
        AnswerSourceType.GALLERY_IMAGE -> "🖼️"
        AnswerSourceType.DOCUMENT_RECORD -> when (source.document?.source) {
            DocumentSource.MESSAGES -> "💬"
            DocumentSource.CALENDAR -> "📅"
            DocumentSource.CONTACTS -> "👤"
            DocumentSource.CALL_LOGS -> "📞"
            DocumentSource.FILES -> "📄"
            null -> "📄"
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

    /**
     * Starts a fresh conversational search. The old result/evidence window is
     * discarded before QP so follow-ups cannot retain its thumbnails, records,
     * or answer context.
     */
    private fun answerFollowUp(followUpQuery: String, generation: Long) {
        if (generation != searchGeneration || followUpInFlight) return
        query.setText(followUpQuery)
        search(
            queryOverride = followUpQuery,
            preserveConversation = true,
            displayQuestion = followUpQuery,
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

    private fun renderNextBriefsAfterAnswer(
        suggestions: List<NextBriefSuggestion>,
        generation: Long,
    ) {
        conversationPanel.post {
            if (generation == searchGeneration && !followUpInFlight) {
                renderNextBriefs(suggestions, generation)
            }
        }
    }

    private fun renderNextBriefs(
        suggestions: List<NextBriefSuggestion>,
        generation: Long,
    ) {
        nextBriefRow.removeAllViews()
        if (suggestions.isEmpty()) {
            nextBriefPanel.visibility = View.GONE
            return
        }
        nextBriefPanel.visibility = View.VISIBLE
        suggestions.forEach { suggestion ->
            val chip = TextView(this).apply {
                text = "↗ ${suggestion.text}"
                textSize = 13f
                setTextColor(Color.rgb(34, 91, 72))
                setPadding(dp(13), dp(8), dp(13), dp(8))
                maxLines = 2
                background = roundedBackground(Color.rgb(231, 248, 239), dp(18).toFloat())
                isClickable = true
                setOnClickListener { executeNextBrief(suggestion, generation) }
            }
            nextBriefRow.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(7) })
        }
    }

    private fun executeNextBrief(
        suggestion: NextBriefSuggestion,
        generation: Long,
    ) {
        if (generation != searchGeneration) return
        val records = activeSearchResponse?.answerContext?.records
            ?: activeSearchResponse?.answerGallery.orEmpty()
        val index = suggestion.sourceId.removePrefix("G").toIntOrNull()?.minus(1) ?: return
        val media = records.getOrNull(index) ?: return
        val intent = when (suggestion.action) {
            NextBriefActionType.SHARE_MEDIA -> Intent(Intent.ACTION_SEND).apply {
                type = media.mimeType.ifBlank { "image/*" }
                putExtra(Intent.EXTRA_STREAM, Uri.parse(media.contentUri))
                putExtra(Intent.EXTRA_TEXT, suggestion.payload)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("Ask Galaxy", Uri.parse(media.contentUri))
            }
            NextBriefActionType.MAPS_SEARCH -> {
                val place = media.locationName?.takeIf(String::isNotBlank)
                    ?: media.location?.takeIf(String::isNotBlank)
                    ?: return
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(place)}"))
            }
            NextBriefActionType.CONTACT -> {
                val email = contactEmail(media.ocrText)
                val phone = contactPhone(media.ocrText)
                when {
                    email != null -> Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:$email")
                        putExtra(Intent.EXTRA_TEXT, suggestion.payload)
                    }
                    phone != null -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:${phone.filter { it.isDigit() || it == '+' }}"))
                    else -> return
                }
            }
            NextBriefActionType.CALENDAR_REMINDER -> Intent(Intent.ACTION_INSERT).apply {
                setData(CalendarContract.Events.CONTENT_URI)
                putExtra(CalendarContract.Events.TITLE, suggestion.payload)
                putExtra(CalendarContract.Events.DESCRIPTION, "Created from Ask Galaxy. Source: ${media.displayName}")
            }
            NextBriefActionType.CONTINUE_WEB_TASK -> Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=${Uri.encode(suggestion.payload)}"),
            )
            NextBriefActionType.SEND_MESSAGE -> {
                val phone = contactPhone(media.ocrText)
                if (phone != null) {
                    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${phone.filter { it.isDigit() || it == '+' }}")).apply {
                        putExtra("sms_body", suggestion.payload)
                    }
                } else {
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, suggestion.payload)
                    }
                }
            }
        }
        runCatching {
            startActivity(Intent.createChooser(intent, suggestion.text))
        }.onFailure {
            Toast.makeText(this, "No app is available for this action yet.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun contactEmail(text: String): String? =
        Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
            .find(text)?.value

    private fun contactPhone(text: String): String? =
        Regex("(?<!\\d)\\+?[0-9][0-9 ()-]{6,}[0-9](?!\\d)")
            .findAll(text)
            .map { it.value.trim() }
            .firstOrNull { it.count(Char::isDigit) >= 7 }

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
        val accent = when (source.type) {
            AnswerSourceType.GALLERY_IMAGE -> Color.rgb(91, 95, 180)
            AnswerSourceType.DOCUMENT_RECORD -> when (source.document?.source) {
                DocumentSource.MESSAGES -> Color.rgb(44, 113, 185)
                DocumentSource.CALENDAR -> Color.rgb(185, 76, 86)
                DocumentSource.CONTACTS -> Color.rgb(46, 139, 96)
                DocumentSource.CALL_LOGS -> Color.rgb(126, 83, 163)
                DocumentSource.FILES, null -> Color.rgb(195, 119, 47)
            }
        }
        val accentSurface = Color.rgb(
            (Color.red(accent) * 0.10f + 245).toInt().coerceAtMost(255),
            (Color.green(accent) * 0.10f + 245).toInt().coerceAtMost(255),
            (Color.blue(accent) * 0.10f + 245).toInt().coerceAtMost(255),
        )
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), 0)
            background = roundedBackground(accentSurface, dp(22).toFloat())
        }
        content.addView(TextView(this).apply {
            text = "${sourceIcon(source)}  ${source.label}"
            textSize = 17f
            setTextColor(accent)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(12), dp(12), dp(8))
        }, matchWrap())
        if (source.type == AnswerSourceType.GALLERY_IMAGE && source.media != null) {
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.rgb(225, 227, 235))
                contentDescription = "Source image ${source.label}"
            }
            content.addView(image, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(190),
            ))
            galleryIndexer.loadThumbnailAsync(source.media) { result ->
                val bitmap = result.getOrNull()
                runOnUiThread {
                    if (generation == searchGeneration && expandedSourceId == source.id && bitmap != null && !isFinishing) {
                        image.setImageBitmap(bitmap)
                    }
                }
            }
        }
        content.addView(TextView(this).apply {
            text = source.detail.take(MAX_SOURCE_DETAIL_CHARS)
            textSize = 13f
            setTextColor(Color.rgb(76, 79, 91))
            setPadding(0, dp(9), 0, 0)
        }, matchWrap())
        val dialog = AlertDialog.Builder(this)
            .setTitle("Answer context")
            .setView(content)
            .setPositiveButton("Close", null)
            .show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.90f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
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
        private const val ANSWER_ENABLED_RESULT_LIMIT = 16
        private const val MAX_SOURCE_DETAIL_CHARS = 1_400
        private const val FEATURED_THUMBNAIL_CACHE_KB = 8 * 1024
        private const val RESULT_THUMBNAIL_CACHE_KB = 24 * 1024
        private const val STALE_WORKER_TIMEOUT_MS = 2 * 60 * 1_000L
    }

}
