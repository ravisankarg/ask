package com.ravi.askgalaxy

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/** Private, bounded cache for the JPEG bytes repeatedly supplied to the answer model. */
internal class AnswerImageCache(context: Context) {
    private val directory = File(context.applicationContext.cacheDir, CACHE_DIRECTORY).apply {
        mkdirs()
    }

    @Synchronized
    fun get(key: String): ByteArray? {
        val file = fileFor(key)
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_ENTRY_BYTES) return null
        return runCatching {
            file.setLastModified(System.currentTimeMillis())
            file.readBytes()
        }.onFailure {
            file.delete()
        }.getOrNull()
    }

    @Synchronized
    fun put(key: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAX_ENTRY_BYTES) return
        val destination = fileFor(key)
        if (destination.isFile && destination.length() == bytes.size.toLong()) {
            destination.setLastModified(System.currentTimeMillis())
            return
        }
        val temporary = File(directory, destination.name + ".tmp")
        runCatching {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(destination)) {
                destination.delete()
                check(temporary.renameTo(destination))
            }
            pruneIfNeeded()
        }.onFailure { error ->
            temporary.delete()
            Log.w(TAG, "Could not cache an answer image", error)
        }
    }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((CACHE_VERSION + '|' + key).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.jpg")
    }

    private fun pruneIfNeeded() {
        val files = directory.listFiles { file -> file.isFile && file.extension == "jpg" }.orEmpty()
        var totalBytes = files.sumOf(File::length)
        if (totalBytes <= MAX_CACHE_BYTES) return
        files.sortedBy(File::lastModified).forEach { file ->
            if (totalBytes <= TARGET_CACHE_BYTES) return
            val length = file.length()
            if (file.delete()) totalBytes -= length
        }
    }

    companion object {
        private const val TAG = "AskGalaxy"
        private const val CACHE_DIRECTORY = "answer-images-v2"
        private const val CACHE_VERSION = "jpeg90-bounded-v2"
        private const val MAX_ENTRY_BYTES = 16L * 1_024L * 1_024L
        private const val MAX_CACHE_BYTES = 128L * 1_024L * 1_024L
        private const val TARGET_CACHE_BYTES = 96L * 1_024L * 1_024L
    }
}
