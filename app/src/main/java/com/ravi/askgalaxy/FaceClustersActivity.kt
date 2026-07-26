package com.ravi.askgalaxy

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Exhaustive, virtualized review for the face-cluster index.
 *
 * The old top-frequency window changed after every merge, which made review
 * feel endless and hid lower-frequency duplicates. This screen loads one
 * complete inventory and lets ListView recycle rows; only visible face crops
 * are decoded and retained in a bounded cache.
 */
class FaceClustersActivity : Activity() {
    private lateinit var clusterList: ListView
    private lateinit var instructions: TextView
    private lateinit var status: TextView
    private lateinit var mergeButton: Button
    private lateinit var mergeProgressRow: LinearLayout
    private lateinit var continueButton: Button
    private lateinit var skipButton: Button
    private lateinit var galleryIndexer: GalleryIndexer
    private lateinit var clusterAdapter: FaceClusterAdapter

    private val selectedClusterIds = LinkedHashSet<String>()
    private val thumbnailRequests = HashSet<String>()
    private val stableRankByClusterId = LinkedHashMap<String, Int>()
    private val thumbnailCache = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)

        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: Bitmap,
            newValue: Bitmap?,
        ) {
            if (oldValue !== newValue && !oldValue.isRecycled) oldValue.recycle()
        }
    }

    private var clusters: List<FaceCluster> = emptyList()
    private var loadGeneration = 0L
    private var thumbnailGeneration = 0L
    private var stage = FaceTaggingStage.MERGE
    private var mergeInProgress = false
    private var inventoryLoaded = false

    private enum class FaceTaggingStage {
        MERGE,
        TAG,
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stage = savedInstanceState?.getString(STATE_STAGE)
            ?.let { runCatching { FaceTaggingStage.valueOf(it) }.getOrNull() }
            ?: FaceTaggingStage.MERGE
        savedInstanceState?.getStringArrayList(STATE_SELECTION)
            ?.let(selectedClusterIds::addAll)
        galleryIndexer = GalleryIndexer(this)
        setContentView(createContent())
        loadClusters(preserveScroll = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_STAGE, stage.name)
        outState.putStringArrayList(STATE_SELECTION, ArrayList(selectedClusterIds))
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        thumbnailGeneration += 1
        thumbnailRequests.clear()
        thumbnailCache.evictAll()
        galleryIndexer.close()
        super.onDestroy()
    }

    private fun createContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setBackgroundColor(Color.rgb(249, 250, 253))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            contentDescription = "Back"
            setColorFilter(Color.rgb(44, 47, 57))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(this).apply {
            text = "People in your photos"
            textSize = 25f
            setTextColor(Color.rgb(20, 23, 32))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        root.addView(header, matchWrap())

        instructions = TextView(this).apply {
            text = "Loading every private face group once…"
            textSize = 15f
            setTextColor(Color.rgb(77, 80, 91))
            setPadding(dp(8), dp(4), dp(8), dp(9))
        }
        root.addView(instructions, matchWrap())
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(67, 70, 80))
            setPadding(dp(8), 0, dp(8), dp(9))
        }
        root.addView(status, matchWrap())

        clusterAdapter = FaceClusterAdapter()
        clusterList = ListView(this).apply {
            adapter = clusterAdapter
            divider = null
            dividerHeight = dp(10)
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(8))
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        root.addView(
            clusterList,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        mergeButton = Button(this).apply {
            text = "Merge selected groups"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.rgb(63, 82, 181), dp(16).toFloat())
            isEnabled = false
            setOnClickListener { mergeSelectedGroups() }
        }
        actions.addView(mergeButton, matchButton())
        mergeProgressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        mergeProgressRow.addView(ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.rgb(63, 82, 181))
        }, LinearLayout.LayoutParams(dp(28), dp(28)))
        mergeProgressRow.addView(TextView(this).apply {
            text = "Combining selected groups…"
            textSize = 14f
            setTextColor(Color.rgb(63, 66, 78))
            setPadding(dp(10), 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)))
        actions.addView(mergeProgressRow, matchWrap())
        continueButton = Button(this).apply {
            text = "Continue to naming"
            isAllCaps = false
            setTextColor(Color.rgb(63, 82, 181))
            background = roundedBackground(Color.rgb(232, 235, 252), dp(16).toFloat())
            isEnabled = false
            setOnClickListener {
                if (stage == FaceTaggingStage.MERGE) {
                    stage = FaceTaggingStage.TAG
                    selectedClusterIds.clear()
                    refreshUiState()
                    clusterList.setSelection(0)
                } else {
                    PreparationStore(this@FaceClustersActivity).markFaceTagsComplete()
                    finish()
                }
            }
        }
        actions.addView(continueButton, matchButton().apply { topMargin = dp(8) })
        skipButton = Button(this).apply {
            text = "Skip remaining groups"
            isAllCaps = false
            setTextColor(Color.rgb(63, 82, 181))
            background = roundedBackground(Color.rgb(246, 247, 250), dp(16).toFloat())
            visibility = View.GONE
            setOnClickListener { skipRemainingGroups() }
        }
        actions.addView(skipButton, matchButton().apply { topMargin = dp(8) })
        root.addView(actions, matchWrap())
        return root
    }

    private fun loadClusters(preserveScroll: Boolean) {
        val firstPosition = if (preserveScroll) clusterList.firstVisiblePosition else 0
        val firstOffset = if (preserveScroll) {
            clusterList.getChildAt(0)?.top ?: 0
        } else {
            0
        }
        val requestGeneration = ++loadGeneration
        inventoryLoaded = false
        status.text = "Loading the complete private face inventory…"
        setInteractionEnabled(false)
        galleryIndexer.faceClustersAsync { result ->
            runOnUiThread {
                if (requestGeneration != loadGeneration || isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { loaded ->
                        if (stableRankByClusterId.isEmpty()) {
                            loaded.forEachIndexed { index, cluster ->
                                stableRankByClusterId[cluster.clusterId] = index
                            }
                        } else {
                            loaded.filterNot { it.clusterId in stableRankByClusterId }
                                .forEach { cluster ->
                                    stableRankByClusterId[cluster.clusterId] =
                                        stableRankByClusterId.size
                                }
                        }
                        clusters = loaded.sortedBy {
                            stableRankByClusterId[it.clusterId] ?: Int.MAX_VALUE
                        }
                        selectedClusterIds.retainAll(loaded.mapTo(HashSet()) { it.clusterId })
                        inventoryLoaded = true
                        clusterAdapter.notifyDataSetChanged()
                        refreshUiState()
                        if (preserveScroll && loaded.isNotEmpty()) {
                            clusterList.setSelectionFromTop(
                                firstPosition.coerceAtMost(loaded.lastIndex),
                                firstOffset,
                            )
                        }
                    },
                    onFailure = {
                        clusters = emptyList()
                        clusterAdapter.notifyDataSetChanged()
                        status.text = "I couldn't load the private face inventory yet."
                        instructions.text = "Face review is temporarily unavailable."
                        setInteractionEnabled(false)
                    },
                )
            }
        }
    }

    private fun refreshUiState() {
        if (!inventoryLoaded) return
        val unnamed = clusters.count { it.label.isBlank() }
        val named = clusters.size - unnamed
        val selfCluster = clusters.firstOrNull { it.isSelf }
        val isMerge = stage == FaceTaggingStage.MERGE

        instructions.text = if (isMerge) {
            "This is the complete cluster set. Select every group that belongs to one person, then merge them together. You can repeat without a changing top-list."
        } else if (selfCluster == null && clusters.isNotEmpty()) {
            "First identify yourself: tap Name on your face group, enter one name, and select “This is me.” Ask Galaxy will map I, me, my, mine, and myself to that one person."
        } else if (named < FaceTaggingPolicy.MIN_NAMED_GROUPS_FOR_SKIP && unnamed > 0) {
            "Tap Name beside the important people. Name at least ${FaceTaggingPolicy.MIN_NAMED_GROUPS_FOR_SKIP} groups to unlock Skip; the complete inventory remains available."
        } else {
            "Name any other people you recognize, or skip the remaining anonymous groups."
        }
        status.text = when {
            clusters.isEmpty() -> "No face groups were found in the indexed gallery."
            isMerge -> "All ${clusters.size} groups • ${selectedClusterIds.size} selected"
            selfCluster != null && unnamed > 0 ->
                "You: ${selfCluster.label} • $named named • $unnamed remaining"
            unnamed == 0 -> "All ${clusters.size} groups are named."
            named >= FaceTaggingPolicy.MIN_NAMED_GROUPS_FOR_SKIP ->
                "$named named • $unnamed remaining • all ${clusters.size} groups shown"
            else ->
                "$named named • name ${FaceTaggingPolicy.MIN_NAMED_GROUPS_FOR_SKIP - named} more to unlock Skip"
        }

        mergeButton.visibility = if (isMerge && clusters.isNotEmpty()) View.VISIBLE else View.GONE
        mergeButton.isEnabled = !mergeInProgress && selectedClusterIds.size >= 2
        mergeButton.text = when {
            mergeInProgress -> "Merging selected groups…"
            selectedClusterIds.size >= 2 -> "Merge ${selectedClusterIds.size} selected groups"
            else -> "Merge selected groups"
        }
        mergeProgressRow.visibility = if (mergeInProgress) View.VISIBLE else View.GONE
        val canFinish = !isMerge && unnamed == 0
        val canSkip = !isMerge &&
            unnamed > 0 &&
            named >= FaceTaggingPolicy.MIN_NAMED_GROUPS_FOR_SKIP
        continueButton.visibility = if (
            clusters.isEmpty() || (!isMerge && !canFinish)
        ) {
            View.GONE
        } else {
            View.VISIBLE
        }
        continueButton.text = if (isMerge) "Continue to naming" else "Finish face tagging"
        continueButton.isEnabled = !mergeInProgress && (isMerge || canFinish)
        skipButton.visibility = if (canSkip) View.VISIBLE else View.GONE
        skipButton.isEnabled = !mergeInProgress && canSkip
        clusterList.isEnabled = !mergeInProgress && clusters.isNotEmpty()
        clusterAdapter.notifyDataSetChanged()
    }

    private fun setInteractionEnabled(enabled: Boolean) {
        clusterList.isEnabled = enabled
        mergeButton.isEnabled = enabled && selectedClusterIds.size >= 2
        continueButton.isEnabled = enabled
        skipButton.isEnabled = enabled
    }

    private fun mergeSelectedGroups() {
        val ids = selectedClusterIds.toList()
        if (ids.size < 2 || mergeInProgress) return
        mergeInProgress = true
        refreshUiState()
        status.text = "Merging ${ids.size} selected groups privately…"
        galleryIndexer.mergeFaceClustersAsync(ids) { result ->
            runOnUiThread {
                mergeInProgress = false
                if (result.isSuccess) {
                    selectedClusterIds.clear()
                    thumbnailGeneration += 1
                    thumbnailRequests.clear()
                    ids.forEach(thumbnailCache::remove)
                    loadClusters(preserveScroll = true)
                } else {
                    status.text = "Those groups could not be merged. Please try again."
                    refreshUiState()
                }
            }
        }
    }

    private fun showNameDialog(cluster: FaceCluster) {
        val input = EditText(this).apply {
            hint = "Person's name"
            setSingleLine(true)
            setText(cluster.label)
            selectAll()
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        val selfCheck = CheckBox(this).apply {
            text = "This is me"
            isChecked = cluster.isSelf
            textSize = 16f
            setTextColor(Color.rgb(29, 32, 42))
            setPadding(dp(4), dp(8), 0, 0)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(
                input,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)),
            )
            addView(selfCheck, matchWrap())
            addView(TextView(this@FaceClustersActivity).apply {
                text = "Use this one identity for I, me, my, mine, and myself in gallery queries."
                textSize = 13f
                setTextColor(Color.rgb(91, 94, 105))
                setPadding(dp(12), 0, dp(8), dp(4))
            }, matchWrap())
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (cluster.label.isBlank()) "Name this person" else "Update name")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val label = input.text.toString().trim()
                if (label.isBlank()) {
                    input.error = "Enter a name"
                    return@setOnClickListener
                }
                input.isEnabled = false
                selfCheck.isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                galleryIndexer.saveFaceClusterIdentityAsync(
                    cluster.clusterId,
                    label,
                    selfCheck.isChecked,
                ) { result ->
                    runOnUiThread {
                        if (result.isSuccess) {
                            clusters = clusters.map {
                                when {
                                    it.clusterId == cluster.clusterId ->
                                        it.copy(label = label, isSelf = selfCheck.isChecked)
                                    selfCheck.isChecked -> it.copy(isSelf = false)
                                    else -> it
                                }
                            }
                            dialog.dismiss()
                            refreshUiState()
                        } else {
                            input.isEnabled = true
                            selfCheck.isEnabled = true
                            input.error = "Couldn't save this name. Try again."
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        }
                    }
                }
            }
            input.requestFocus()
        }
        dialog.show()
    }

    private fun skipRemainingGroups() {
        if (stage != FaceTaggingStage.TAG) return
        PreparationStore(this).markFaceTagsComplete()
        finish()
    }

    private inner class FaceClusterAdapter : BaseAdapter() {
        override fun getCount(): Int = clusters.size
        override fun getItem(position: Int): FaceCluster = clusters[position]
        override fun getItemId(position: Int): Long = getItem(position).clusterId.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder: ClusterViewHolder
            val row: LinearLayout
            if (convertView is LinearLayout && convertView.tag is ClusterViewHolder) {
                row = convertView
                holder = convertView.tag as ClusterViewHolder
            } else {
                val selector = CheckBox(this@FaceClustersActivity).apply {
                    gravity = Gravity.CENTER
                }
                val image = ImageView(this@FaceClustersActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.rgb(231, 233, 240))
                    contentDescription = "Face group preview"
                }
                val title = TextView(this@FaceClustersActivity).apply {
                    textSize = 17f
                    setTextColor(Color.rgb(29, 32, 42))
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 2
                }
                val count = TextView(this@FaceClustersActivity).apply {
                    textSize = 13f
                    setTextColor(Color.rgb(99, 102, 113))
                    setPadding(0, dp(6), 0, 0)
                }
                val details = LinearLayout(this@FaceClustersActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), 0, dp(8), 0)
                    addView(title, matchWrap())
                    addView(count, matchWrap())
                }
                val name = Button(this@FaceClustersActivity).apply {
                    isAllCaps = false
                    setTextColor(Color.rgb(63, 82, 181))
                    background = roundedBackground(Color.rgb(232, 235, 252), dp(14).toFloat())
                }
                holder = ClusterViewHolder(selector, image, title, count, name)
                row = LinearLayout(this@FaceClustersActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(10), dp(10), dp(10))
                    background = roundedBackground(Color.WHITE, dp(20).toFloat())
                    addView(selector, LinearLayout.LayoutParams(dp(44), dp(52)))
                    addView(image, LinearLayout.LayoutParams(dp(88), dp(88)))
                    addView(details, LinearLayout.LayoutParams(0, dp(88), 1f))
                    addView(name, LinearLayout.LayoutParams(dp(82), dp(46)))
                    tag = holder
                }
            }

            val cluster = getItem(position)
            holder.selector.setOnCheckedChangeListener(null)
            holder.selector.visibility = if (stage == FaceTaggingStage.MERGE) View.VISIBLE else View.GONE
            holder.selector.isChecked = cluster.clusterId in selectedClusterIds
            holder.selector.isEnabled = !mergeInProgress
            holder.selector.setOnCheckedChangeListener { _, checked ->
                if (mergeInProgress) return@setOnCheckedChangeListener
                if (checked) selectedClusterIds += cluster.clusterId
                else selectedClusterIds -= cluster.clusterId
                refreshUiState()
            }
            holder.title.text = if (cluster.isSelf) {
                "${cluster.label.ifBlank { "Face group ${position + 1}" }} · Me"
            } else {
                cluster.label.ifBlank { "Face group ${position + 1}" }
            }
            holder.count.text =
                "${cluster.faceCount} appearance${if (cluster.faceCount == 1) "" else "s"}" +
                    if (cluster.isSelf) " • your identity" else ""
            holder.name.visibility = if (stage == FaceTaggingStage.TAG) View.VISIBLE else View.GONE
            holder.name.text = if (cluster.label.isBlank()) "Name" else "Edit"
            holder.name.setOnClickListener { showNameDialog(cluster) }
            row.setOnClickListener {
                if (stage == FaceTaggingStage.MERGE && !mergeInProgress) {
                    holder.selector.isChecked = !holder.selector.isChecked
                } else if (stage == FaceTaggingStage.TAG) {
                    showNameDialog(cluster)
                }
            }

            holder.image.tag = cluster.clusterId
            val cached = thumbnailCache.get(cluster.clusterId)
            if (cached != null && !cached.isRecycled) {
                holder.image.setImageBitmap(cached)
            } else {
                holder.image.setImageDrawable(null)
                requestThumbnail(cluster, holder.image)
            }
            return row
        }
    }

    private fun requestThumbnail(cluster: FaceCluster, target: ImageView) {
        if (!thumbnailRequests.add(cluster.clusterId)) return
        val generation = thumbnailGeneration
        galleryIndexer.loadFaceThumbnailAsync(cluster) { result ->
            val bitmap = result.getOrNull()
            runOnUiThread {
                thumbnailRequests -= cluster.clusterId
                if (bitmap == null) return@runOnUiThread
                if (generation != thumbnailGeneration || isFinishing || isDestroyed) {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    return@runOnUiThread
                }
                thumbnailCache.put(cluster.clusterId, bitmap)
                if (target.tag == cluster.clusterId && !bitmap.isRecycled) {
                    target.setImageBitmap(bitmap)
                }
            }
        }
    }

    private data class ClusterViewHolder(
        val selector: CheckBox,
        val image: ImageView,
        val title: TextView,
        val count: TextView,
        val name: Button,
    )

    private fun roundedBackground(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchButton(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(48),
    )

    private fun matchWrap(): ViewGroup.LayoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private companion object {
        /** Face crops are small, but keep the review surface comfortably bounded. */
        const val THUMBNAIL_CACHE_KB = 12 * 1024
        const val STATE_STAGE = "face_review_stage"
        const val STATE_SELECTION = "face_review_selection"
    }
}
