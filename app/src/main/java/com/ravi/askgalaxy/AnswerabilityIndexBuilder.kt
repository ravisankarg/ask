package com.ravi.askgalaxy

import android.content.Context

data class AnswerabilityBuildProgress(
    val completed: Int,
    val total: Int,
    val phase: String,
)

/** Builds only the derived answerability tables; source indexes are read-only inputs. */
class AnswerabilityIndexBuilder(context: Context) {
    private val appContext = context.applicationContext

    fun rebuildBlocking(onProgress: (AnswerabilityBuildProgress) -> Unit = {}) {
        GalleryDatabase(appContext).use { gallery ->
            DocumentDatabase(appContext).use { documents ->
                val total = gallery.answerabilityRecordCount() + documents.answerabilityRecordCount()
                var completed = 0
                onProgress(AnswerabilityBuildProgress(0, total, "Preparing"))
                gallery.rebuildAnswerabilityFacts { current, _ ->
                    completed = current
                    onProgress(AnswerabilityBuildProgress(completed, total, "Gallery OCR"))
                }
                documents.rebuildAnswerabilityFacts { current, _ ->
                    completed = gallery.answerabilityRecordCount() + current
                    onProgress(AnswerabilityBuildProgress(completed, total, "Personal sources"))
                }
                onProgress(AnswerabilityBuildProgress(total, total, "Ready"))
            }
        }
    }
}
