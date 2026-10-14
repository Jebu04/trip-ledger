package app.tripledger.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Writes a backup file into a folder the user picked, at most once per chosen interval.
 *
 * There is no background service: the check runs when the app is opened and when it is left,
 * which is when new expenses exist. One file is kept per day, and only the newest [KEEP] files.
 */
object AutoBackup {
    const val PREFIX = "tripledger-auto-"
    private const val KEEP = 10
    private val mutex = Mutex()

    /** Runs a backup if one is due, or unconditionally when [force] is set. Returns the status text. */
    suspend fun run(context: Context, repo: Repository, force: Boolean): String? = mutex.withLock {
        val s = repo.refData.first().settings
        if (s.autoBackupFolder.isBlank()) return@withLock if (force) "Choose a folder first" else null
        if (!force) {
            if (s.autoBackupInterval == BackupInterval.OFF) return@withLock null
            val due = s.autoBackupLast + s.autoBackupInterval.days * 24L * 60 * 60 * 1000
            if (System.currentTimeMillis() < due) return@withLock null
        }
        val status = try {
            val json = repo.exportBackup()
            withContext(Dispatchers.IO) { write(context, Uri.parse(s.autoBackupFolder), json) }
            repo.setSetting(Keys.AUTO_BACKUP_LAST, System.currentTimeMillis().toString())
            "Last backup: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH))
        } catch (e: Exception) {
            "Backup failed: " + (e.message ?: "the folder could not be written to") + ". Choose the folder again."
        }
        repo.setSetting(Keys.AUTO_BACKUP_STATUS, status)
        status
    }

    private fun write(context: Context, tree: Uri, json: String) {
        val resolver = context.contentResolver
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = "$PREFIX${LocalDate.now()}.json"

        // Replace today's file rather than piling up "name (1).json" copies.
        children(context, tree).filter { it.second == name }.forEach {
            DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, it.first))
        }
        val file = DocumentsContract.createDocument(resolver, folder, "application/json", name)
            ?: error("the file could not be created")
        (resolver.openOutputStream(file, "wt") ?: error("the file could not be opened")).use {
            it.write(json.toByteArray(Charsets.UTF_8))
        }

        // ISO dates in the names sort chronologically, so the oldest are simply the first ones.
        val ours = children(context, tree).filter { it.second.startsWith(PREFIX) && it.second.endsWith(".json") }.sortedBy { it.second }
        ours.dropLast(KEEP).forEach {
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, it.first)) }
        }
    }

    /** Document id and display name of everything directly inside the folder. */
    private fun children(context: Context, tree: Uri): List<Pair<String, String>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val result = ArrayList<Pair<String, String>>()
        context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.getString(0) to (cursor.getString(1) ?: ""))
        }
        return result
    }
}
