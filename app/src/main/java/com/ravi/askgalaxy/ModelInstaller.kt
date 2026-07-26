package com.ravi.askgalaxy

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.min

data class ModelInstallProgress(
    val artifact: ModelArtifact,
    val artifactIndex: Int,
    val artifactTotal: Int,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
)

data class ModelInstallReport(
    val missingSources: List<ModelArtifact>,
)

/** Resumable, checksum-aware model provisioning owned by the background worker. */
class ModelInstaller(context: Context) {
    private val appContext = context.applicationContext

    fun installAll(onProgress: (ModelInstallProgress) -> Unit): ModelInstallReport {
        return installArtifacts(ModelCatalog.all.filter { it.required }, onProgress)
    }

    fun installArtifacts(
        artifacts: List<ModelArtifact>,
        onProgress: (ModelInstallProgress) -> Unit,
    ): ModelInstallReport {
        if (artifacts.isEmpty()) return ModelInstallReport(emptyList())

        artifacts.forEachIndexed { index, artifact ->
            if (artifact.isInstalled(appContext)) return@forEachIndexed
            if (artifact.packagedAssetPath != null && installPackagedAsset(
                    artifact = artifact,
                    artifactIndex = index + 1,
                    artifactTotal = artifacts.size,
                    onProgress = onProgress,
                )
            ) {
                return@forEachIndexed
            }
            val url = artifact.downloadUrl ?: return@forEachIndexed
            download(
                artifact = artifact,
                url = url,
                artifactIndex = index + 1,
                artifactTotal = artifacts.size,
                onProgress = onProgress,
            )
        }

        return ModelInstallReport(
            missingSources = artifacts.filter { !it.isInstalled(appContext) && !it.hasDownloadSource() },
        )
    }

    private fun installPackagedAsset(
        artifact: ModelArtifact,
        artifactIndex: Int,
        artifactTotal: Int,
        onProgress: (ModelInstallProgress) -> Unit,
    ): Boolean {
        val assetPath = artifact.packagedAssetPath ?: return false
        val target = artifact.file(appContext)
        target.parentFile?.mkdirs()
        val partial = artifact.partFile(appContext)
        try {
            appContext.assets.open(assetPath, android.content.res.AssetManager.ACCESS_STREAMING).use { input ->
                val total = artifact.expectedBytes.coerceAtLeast(0L)
                onProgress(ModelInstallProgress(artifact, artifactIndex, artifactTotal, 0L, total))
                java.io.FileOutputStream(partial, false).use { output ->
                    transfer(
                        input,
                        output,
                        artifact,
                        artifactIndex,
                        artifactTotal,
                        0L,
                        total,
                        artifact.expectedBytes,
                        onProgress,
                    )
                }
            }
        } catch (_: FileNotFoundException) {
            partial.delete()
            return false
        }

        val downloaded = partial.length()
        if (artifact.expectedBytes > 0L && downloaded != artifact.expectedBytes) {
            partial.delete()
            throw IOException(
                "Bundled ${artifact.name} has $downloaded bytes, expected ${artifact.expectedBytes}",
            )
        }
        if (!artifact.sha256.isNullOrBlank() && sha256(partial) != artifact.sha256) {
            partial.delete()
            throw IOException("Checksum mismatch for bundled ${artifact.name}")
        }
        atomicInstall(partial, target)
        onProgress(ModelInstallProgress(artifact, artifactIndex, artifactTotal, downloaded, downloaded))
        return true
    }

    private fun download(
        artifact: ModelArtifact,
        url: String,
        artifactIndex: Int,
        artifactTotal: Int,
        onProgress: (ModelInstallProgress) -> Unit,
    ) {
        val target = artifact.file(appContext)
        target.parentFile?.mkdirs()
        val partial = artifact.partFile(appContext)
        var existing = partial.length()
        if (artifact.expectedBytes > 0L && existing > artifact.expectedBytes) {
            check(partial.delete()) { "Could not reset oversized partial model: ${partial.absolutePath}" }
            existing = 0L
        }
        if (artifact.expectedBytes > 0L && existing == artifact.expectedBytes) {
            if (artifact.sha256.isNullOrBlank() || sha256(partial) == artifact.sha256) {
                atomicInstall(partial, target)
                onProgress(
                    ModelInstallProgress(
                        artifact,
                        artifactIndex,
                        artifactTotal,
                        artifact.expectedBytes,
                        artifact.expectedBytes,
                    ),
                )
                return
            }
            check(partial.delete()) { "Could not reset corrupt partial model: ${partial.absolutePath}" }
            existing = 0L
        }

        var connection = openConnection(url, existing)
        var responseCode = connection.responseCode
        if (existing > 0L && responseCode != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect()
            check(partial.delete()) { "Could not restart model download: ${partial.absolutePath}" }
            existing = 0L
            connection = openConnection(url, 0L)
            responseCode = connection.responseCode
        }
        if (responseCode !in 200..299) {
            connection.disconnect()
            throw IOException("Model download HTTP $responseCode for ${artifact.name}")
        }

        if (existing > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL) {
            val rangeStart = contentRangeStart(connection.getHeaderField("Content-Range"))
            if (rangeStart != existing) {
                connection.disconnect()
                check(partial.delete()) { "Could not reset invalid partial model: ${partial.absolutePath}" }
                existing = 0L
                connection = openConnection(url, 0L)
                responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    connection.disconnect()
                    throw IOException("Model download HTTP $responseCode for ${artifact.name}")
                }
            }
        }

        val responseLength = connection.contentLengthLong.coerceAtLeast(0L)
        val total = artifact.expectedBytes.takeIf { it > 0L }
            ?: if (responseLength > 0L) existing + responseLength else 0L
        var downloaded = existing
        onProgress(ModelInstallProgress(artifact, artifactIndex, artifactTotal, downloaded, total))
        val append = existing > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
        try {
            connection.inputStream.use { input ->
                java.io.FileOutputStream(partial, append).use { output ->
                    transfer(
                        input,
                        output,
                        artifact,
                        artifactIndex,
                        artifactTotal,
                        downloaded,
                        total,
                        artifact.expectedBytes,
                        onProgress,
                    )
                    downloaded = partial.length()
                }
            }
        } finally {
            connection.disconnect()
        }

        if (artifact.expectedBytes > 0L && downloaded != artifact.expectedBytes) {
            throw IOException(
                "Incomplete ${artifact.name}: got $downloaded bytes, expected ${artifact.expectedBytes}",
            )
        }
        if (!artifact.sha256.isNullOrBlank() && sha256(partial) != artifact.sha256) {
            partial.delete()
            throw IOException("Checksum mismatch for ${artifact.name}")
        }
        atomicInstall(partial, target)
        onProgress(ModelInstallProgress(artifact, artifactIndex, artifactTotal, downloaded, total))
    }

    private fun transfer(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        artifact: ModelArtifact,
        artifactIndex: Int,
        artifactTotal: Int,
        startingBytes: Long,
        total: Long,
        expectedBytes: Long,
        onProgress: (ModelInstallProgress) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var downloaded = startingBytes
        var lastReport = downloaded
        while (true) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedIOException("Model download interrupted")
            }
            val remaining = if (expectedBytes > 0L) expectedBytes - downloaded else buffer.size.toLong()
            if (remaining <= 0L) break
            val count = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
            if (count < 0) break
            output.write(buffer, 0, count)
            downloaded += count
            if (downloaded - lastReport >= REPORT_BYTES) {
                onProgress(ModelInstallProgress(artifact, artifactIndex, artifactTotal, downloaded, total))
                lastReport = downloaded
            }
        }
        output.flush()
    }

    private fun openConnection(url: String, offset: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "AskGalaxy/0.1 Android")
            if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun atomicInstall(partial: File, target: File) {
        try {
            Files.move(
                partial.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                partial.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    companion object {
        private const val BUFFER_SIZE = 1024 * 1024
        private const val REPORT_BYTES = 4L * 1024L * 1024L
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000

        internal fun contentRangeStart(header: String?): Long? {
            val match = CONTENT_RANGE_PATTERN.matchEntire(header?.trim().orEmpty()) ?: return null
            return match.groupValues[1].toLongOrNull()
        }

        private val CONTENT_RANGE_PATTERN = Regex("bytes\\s+(\\d+)-\\d+/\\d+")
    }
}
