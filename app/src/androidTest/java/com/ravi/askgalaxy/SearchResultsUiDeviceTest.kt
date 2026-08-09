package com.ravi.askgalaxy

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Installed-device contract for the public search browser.
 *
 * The phone may remain keyguard-locked: this test shows only its own Activity
 * above the lock screen and never unlocks or changes device credentials.
 */
class SearchResultsUiDeviceTest {
    @Test
    fun broadSearchShowsTop16InFourColumnsAndPreservesItAcrossDetails() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
        ) as MainActivity
        try {
            onMain(instrumentation) {
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
            }
            instrumentation.waitForIdleSync()

            val editText = requireNotNull(onMain(instrumentation) {
                activity.window.decorView.descendants()
                    .filterIsInstance<EditText>()
                    .firstOrNull()
            }) { "Search field is missing" }
            onMain(instrumentation) {
                editText.setText(BROAD_QUERY)
                val search = MainActivity::class.java.getDeclaredMethod("search")
                search.isAccessible = true
                search.invoke(activity)
            }

            var qpReadyAtMs = 0L
            var resultsAtMs = 0L
            var finalSnapshot = UiSnapshot()
            var priorState = ""
            val deadline = SystemClock.elapsedRealtime() + SEARCH_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                val snapshot = snapshot(instrumentation, activity)
                val now = SystemClock.elapsedRealtime()
                if (snapshot.stateKey() != priorState) {
                    priorState = snapshot.stateKey()
                    Log.i(TAG, "UI_STATE|$priorState")
                }
                if (qpReadyAtMs == 0L && snapshot.qpReady) qpReadyAtMs = now
                if (resultsAtMs == 0L && snapshot.resultCount > 0) resultsAtMs = now
                finalSnapshot = snapshot
                if (
                    snapshot.qpReady &&
                    snapshot.resultCount == PUBLIC_RESULT_LIMIT &&
                    snapshot.resultLabel.contains("Showing top 16") &&
                    snapshot.qpSpec.contains("mime type") &&
                    !snapshot.answerGenerating
                ) {
                    break
                }
                SystemClock.sleep(POLL_INTERVAL_MS)
            }

            assertTrue("QP output never became ready: ${finalSnapshot.stateKey()}", finalSnapshot.qpReady)
            assertTrue("Executable QP spec was not shown", finalSnapshot.qpSpec.contains("mime type"))
            assertTrue("QP output was not published before search results", qpReadyAtMs in 1..resultsAtMs)
            assertFalse("Answer generation must be disabled in search-only mode", finalSnapshot.answerGenerating)
            assertEquals(PUBLIC_RESULT_LIMIT, finalSnapshot.resultCount)
            assertTrue(finalSnapshot.resultLabel.contains("Showing top 16"))
            assertFalse(
                "The private top-16 context leaked into the public surface",
                finalSnapshot.visibleTexts.any { it.startsWith("Answer context") },
            )
            assertFalse(
                "A raw filename leaked into a visible result caption",
                finalSnapshot.visibleTexts.any {
                    RAW_FILENAME_PATTERN.containsMatchIn(it)
                },
            )

            val grid = onMain(instrumentation) {
                activity.field<GridView>("resultGrid")
            }
            val gridColumns = onMain(instrumentation) { grid.numColumns }
            assertEquals(4, gridColumns)

            val detailMonitor = instrumentation.addMonitor(
                MediaDetailActivity::class.java.name,
                null,
                false,
            )
            onMain(instrumentation) {
                val position = 0
                val item = grid.adapter.getView(position, null, grid)
                item.performClick()
            }
            val detail = detailMonitor.waitForActivityWithTimeout(DETAIL_TIMEOUT_MS)
            assertNotNull("Clicking a search result did not open details", detail)
            requireNotNull(detail).finish()
            instrumentation.waitForIdleSync()

            val afterBack = snapshot(instrumentation, activity)
            assertEquals(
                "Returning from details replaced the public result set",
                PUBLIC_RESULT_LIMIT,
                afterBack.resultCount,
            )
            assertFalse("MainActivity finished during back navigation", activity.isFinishing)
            Log.i(
                TAG,
                "SEARCH_UI|query=$BROAD_QUERY|count=${afterBack.resultCount}|" +
                    "qpBeforeResults=${qpReadyAtMs in 1..resultsAtMs}|" +
                    "answerDisabled=true|columns=$gridColumns|" +
                    "detailBackPreserved=true|rawFilenameVisible=false|contextVisible=false",
            )
        } finally {
            onMain(instrumentation) {
                if (!activity.isFinishing) activity.finish()
            }
        }
    }

    private fun snapshot(
        instrumentation: Instrumentation,
        activity: MainActivity,
    ): UiSnapshot = onMain(instrumentation) {
        val views = activity.window.decorView.descendants().toList()
        val texts = views.filterIsInstance<TextView>()
        val grid = activity.field<GridView>("resultGrid")
        val qpLabel = activity.field<TextView>("qpOutputLabel")
        val qpText = activity.field<TextView>("qpOutputText")
        val resultLabel = activity.field<TextView>("resultCount")
        val modelStatus = activity.field<TextView>("modelStatus")
        UiSnapshot(
            qpReady = qpLabel.text.toString() == "QP output • ready",
            qpSpec = qpText.text.toString(),
            resultCount = grid.adapter?.count ?: 0,
            resultLabel = resultLabel.text.toString(),
            answerGenerating =
                modelStatus.visibility == View.VISIBLE &&
                    (
                        modelStatus.text.toString().startsWith("Gemma 4 E4B is joining") ||
                            modelStatus.text.toString().startsWith("Gemma 4 E4B is reading")
                        ),
            gridHeight = grid.height,
            gridChildCount = grid.childCount,
            visibleTexts = texts
                .filter { it.isVisibleInAppHierarchy() }
                .map { it.text.toString() },
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> MainActivity.field(name: String): T {
        val field = MainActivity::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(this) as T
    }

    /**
     * Keyguard can make [View.isShown] false for the whole app even though the
     * app's own visibility contract is correct. This intentionally checks the
     * production hierarchy without treating the secure system overlay as an
     * app-level GONE state.
     */
    private fun View.isVisibleInAppHierarchy(): Boolean {
        var current: View? = this
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            current = current.parent as? View
        }
        return true
    }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) {
            for (index in 0 until childCount) {
                yieldAll(getChildAt(index).descendants())
            }
        }
    }

    private fun <T> onMain(
        instrumentation: Instrumentation,
        block: () -> T,
    ): T {
        val value = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync {
            value.set(runCatching(block))
        }
        return requireNotNull(value.get()).getOrThrow()
    }

    private data class UiSnapshot(
        val qpReady: Boolean = false,
        val qpSpec: String = "",
        val resultCount: Int = 0,
        val resultLabel: String = "",
        val answerGenerating: Boolean = false,
        val gridHeight: Int = 0,
        val gridChildCount: Int = 0,
        val visibleTexts: List<String> = emptyList(),
    ) {
        fun stateKey(): String =
            "qp=$qpReady|count=$resultCount|answer=$answerGenerating|" +
                "grid=${gridHeight}x$gridChildCount|label=${resultLabel.take(60)}"
    }

    private companion object {
        const val BROAD_QUERY = "Show photos"
        const val PUBLIC_RESULT_LIMIT = 16
        const val SIMULATED_VIEWPORT_WIDTH = 1_080
        const val SIMULATED_VIEWPORT_HEIGHT = 1_600
        const val POLL_INTERVAL_MS = 250L
        const val SEARCH_TIMEOUT_MS = 240_000L
        const val SCROLL_TIMEOUT_MS = 10_000L
        const val DETAIL_TIMEOUT_MS = 10_000L
        const val TAG = "AskGalaxySearchUi"
        val RAW_FILENAME_PATTERN = Regex(
            "(?i)\\b[^\\s]+\\.(?:jpe?g|png|webp|heic|gif|mp4|mov|mkv)\\b",
        )
    }
}
