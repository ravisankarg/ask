package com.ravi.askgalaxy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.CallLog
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Telephony
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/** Reads Android-public providers and all files exposed through MediaStore. */
class DocumentSourceReader(private val context: Context) {
    private val resolver = context.contentResolver

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    fun canReadFiles(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        has(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun isAvailable(source: DocumentSource): Boolean = when (source) {
        DocumentSource.MESSAGES -> has(Manifest.permission.READ_SMS)
        DocumentSource.CALENDAR -> has(Manifest.permission.READ_CALENDAR)
        DocumentSource.CONTACTS -> has(Manifest.permission.READ_CONTACTS)
        DocumentSource.CALL_LOGS -> has(Manifest.permission.READ_CALL_LOG)
        DocumentSource.FILES -> canReadFiles()
    }

    fun allFiles(): Sequence<DocumentChunk> = sequence {
        if (!canReadFiles()) return@sequence
        val refs = ArrayList<FileReference>()
        resolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATE_MODIFIED),
            null, null, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1).orEmpty()
                if (name.isBlank()) continue
                refs += FileReference(
                    ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), cursor.getLong(0)),
                    name,
                    cursor.getLong(2).takeIf { !cursor.isNull(2) }?.times(1_000L),
                )
            }
        }
        refs.asSequence().forEach { reference ->
            yieldAll(file(reference.uri, reference.name, reference.modifiedMs))
        }
    }

    fun messages(): Sequence<DocumentChunk> = sequence {
        if (!has(Manifest.permission.READ_SMS)) return@sequence
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            null, null, "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val address = cursor.getString(1).orEmpty()
                val body = cursor.getString(2).orEmpty()
                if (body.isBlank()) continue
                yieldAll(record(DocumentSource.MESSAGES, id, "Message with $address", body, cursor.getLong(3), "address=$address type=${cursor.getInt(4)}"))
            }
        }
    }

    fun calendar(): Sequence<DocumentChunk> = sequence {
        if (!has(Manifest.permission.READ_CALENDAR)) return@sequence
        resolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND),
            null, null, "${CalendarContract.Events.DTSTART} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val title = cursor.getString(1).orEmpty()
                val description = cursor.getString(2).orEmpty()
                val location = cursor.getString(3).orEmpty()
                val start = cursor.getLong(4).takeIf { !cursor.isNull(4) }
                val end = cursor.getLong(5).takeIf { !cursor.isNull(5) }
                val text = listOf(title, description, location).filter(String::isNotBlank).joinToString("\n")
                if (text.isNotBlank()) yieldAll(record(DocumentSource.CALENDAR, id, title.ifBlank { "Calendar event" }, text, start, "location=$location end=$end"))
            }
        }
    }

    fun contacts(): Sequence<DocumentChunk> = sequence {
        if (!has(Manifest.permission.READ_CONTACTS)) return@sequence
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val name = cursor.getString(1).orEmpty()
                val number = cursor.getString(2).orEmpty()
                if (name.isBlank() && number.isBlank()) continue
                yield(DocumentChunk(DocumentSource.CONTACTS, "$id|$number", 0, name.ifBlank { number },
                    "Contact $name phone $number", metadata = "name=$name phone=$number"))
            }
        }
    }

    fun callLogs(): Sequence<DocumentChunk> = sequence {
        if (!has(Manifest.permission.READ_CALL_LOG)) return@sequence
        resolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null, "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val number = cursor.getString(1).orEmpty()
                val name = cursor.getString(2).orEmpty()
                val duration = cursor.getLong(5)
                yield(DocumentChunk(DocumentSource.CALL_LOGS, cursor.getString(0), 0, name.ifBlank { number },
                    "Call with $name $number duration $duration seconds type ${cursor.getInt(3)}",
                    timestampMs = cursor.getLong(4), metadata = "name=$name number=$number duration=$duration type=${cursor.getInt(3)}"))
            }
        }
    }

    fun file(uri: Uri, displayName: String = uri.toString(), timestampMs: Long? = null): Sequence<DocumentChunk> = sequence {
        val lower = displayName.lowercase()
        val text = when {
            lower.endsWith(".pdf") -> extractPdf(uri)
            lower.endsWith(".docx") -> extractDocx(uri)
            lower.endsWith(".odt") -> extractZipText(uri)
            lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".csv") ||
                lower.endsWith(".json") || lower.endsWith(".xml") || lower.endsWith(".yaml") ||
                lower.endsWith(".yml") || lower.endsWith(".html") || lower.endsWith(".rtf") ||
                lower.endsWith(".kt") || lower.endsWith(".java") || lower.endsWith(".js") ||
                lower.endsWith(".ts") || lower.endsWith(".sql") || lower.endsWith(".ini") ||
                lower.endsWith(".properties") || lower.endsWith(".log") ->
                resolver.openInputStream(uri)?.use { InputStreamReader(it).buffered().readText() }.orEmpty()
            else -> ""
        }
        val paginated = lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".odt")
        DocumentChunker.chunk(text).forEachIndexed { index, chunk ->
            yield(DocumentChunk(
                source = DocumentSource.FILES,
                recordKey = uri.toString(),
                chunkNumber = index,
                title = displayName,
                text = chunk,
                uri = uri.toString(),
                page = (index + 1).takeIf { paginated },
                timestampMs = timestampMs,
            ))
        }
    }

    private fun extractPdf(uri: Uri): String = runCatching {
        resolver.openInputStream(uri).use { input ->
            PDDocument.load(input).use { document ->
                PDFTextStripper().apply { startPage = 1; endPage = minOf(5, document.numberOfPages) }.getText(document)
            }
        }
    }.getOrDefault("")

    private fun extractDocx(uri: Uri): String = extractZipText(uri, "word/document.xml")

    private fun extractZipText(uri: Uri, wanted: String? = null): String = runCatching {
        resolver.openInputStream(uri).use { input ->
            ZipInputStream(input).use { zip ->
                val output = StringBuilder()
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (wanted == null || entry.name == wanted) output.append(zip.bufferedReader().readText()).append('\n')
                }
                output.toString().replace(Regex("<[^>]+>"), " ")
            }
        }
    }.getOrDefault("")

    private fun has(permission: String): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private data class FileReference(val uri: Uri, val name: String, val modifiedMs: Long?)

    private fun record(source: DocumentSource, key: String, title: String, text: String, timestamp: Long?, metadata: String): List<DocumentChunk> =
        DocumentChunker.chunk(text).mapIndexed { index, chunk -> DocumentChunk(source, key, index, title, chunk, timestampMs = timestamp, metadata = metadata) }
}
