package com.ashfaq.offlinechat

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Chat export, backup and restore (Update #22) — the three ⋮ menu
 * items that take the saved chat off this phone, or bring one back.
 *
 * Export builds a plain-text transcript (WhatsApp style) of the
 * saved history and writes it as a .txt file in the cache folder;
 * MainActivity then hands it to the share sheet.
 *
 * A backup is a .zip in the cache folder holding the history JSON
 * exactly as ChatHistoryStore persists it, every media file that
 * history points at (under the same folder names the files live in
 * on the phone: voice/, photos/, videos/, files/), and a small
 * backup_info.txt. Because the history JSON names its media by
 * absolute path, restoring rewrites those paths to this phone's
 * own folders first (see [resolveHistoryPaths]) — that is what lets
 * a backup made on one phone restore on another.
 *
 * Restore never touches the live chat until the backup has been
 * fully extracted to a staging folder in the cache and checked: the
 * zip must contain a history that parses, and no zip entry may
 * point outside the staging folder (zip-slip). Only after the user
 * confirms the replacement are the media files copied into place
 * and the history handed to ChatHistoryStore — media first, history
 * last, so a failure half-way leaves the current chat exactly as
 * it was.
 *
 * All file work runs on one background thread; every callback is
 * delivered on the main thread.
 */
object ChatBackup {

    private const val HISTORY_ENTRY_NAME = "chat_history.json"
    private const val INFO_ENTRY_NAME = "backup_info.txt"
    private const val STAGING_DIR_NAME = "backup_restore"

    // The media keys of a history JSON entry, and the folder the
    // file lives in under the app's private folder. The folder
    // names are ChatManager's (VOICE_DIR_NAME and friends).
    private val MEDIA_KEYS: List<Pair<String, String>> = listOf(
        "audio" to "voice",
        "image" to "photos",
        "video" to "videos",
        "file" to "files"
    )
    private val MEDIA_FOLDERS: Set<String> =
        MEDIA_KEYS.map { it.second }.toSet()

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * A backup that has been extracted and checked, waiting in the
     * cache for the user's go-ahead. [historyText] is the backup's
     * own history JSON, not yet touched.
     */
    class Staging internal constructor(val dir: File, val historyText: String)

    // ---------- Transcript ----------

    /**
     * The chat as readable text, oldest first, one line per message:
     * "[26/10/2026, 3:45 PM] Name: text". Media messages become a
     * short placeholder each; quotes and reactions are left out, so
     * the transcript stays a clean record of what was said.
     */
    fun buildTranscript(
        context: Context,
        messages: List<ChatMessage>,
        ownName: String,
        fallbackName: String?
    ): String {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy, h:mm a", Locale.getDefault())
        val builder = StringBuilder()
        for (message in messages) {
            val name = if (message.isSent) {
                ownName
            } else {
                message.senderName ?: fallbackName ?: "Friend"
            }
            builder.append('[')
                .append(dateFormat.format(Date(message.timestamp)))
                .append("] ")
                .append(name)
                .append(": ")
                .append(transcriptBody(context, message))
                .append('\n')
        }
        return builder.toString()
    }

    /** What one message says in the transcript, without its prefix. */
    private fun transcriptBody(context: Context, message: ChatMessage): String {
        if (message.deleted) return context.getString(R.string.message_deleted)
        val body = when (message.type) {
            ChatMessage.Type.TEXT ->
                if (message.edited) message.text + " (edited)" else message.text
            // 🎤 — a voice note shows its length, like its bubble does.
            ChatMessage.Type.VOICE ->
                "\uD83C\uDFA4 Voice message (" +
                    formatDuration(message.durationSeconds) + ")"
            // 📷
            ChatMessage.Type.PHOTO -> "\uD83D\uDCF7 Photo"
            // 📹
            ChatMessage.Type.VIDEO -> "\uD83D\uDCF9 Video"
            // 📄
            ChatMessage.Type.FILE -> "\uD83D\uDCC4 " + (message.fileName ?: "File")
            ChatMessage.Type.LOCATION -> locationBody(message)
        }
        // A forward keeps its label, so a reader can tell the words
        // were passed along rather than written by the sender.
        return if (message.forwarded) "\u21AA Forwarded: " + body else body
    }

    /** 📍 plus the position, when the message carries one. */
    private fun locationBody(message: ChatMessage): String {
        val latitude = message.latitude
        val longitude = message.longitude
        return if (latitude != null && longitude != null) {
            "\uD83D\uDCCD Location: " +
                String.format(Locale.US, "%.6f", latitude) + ", " +
                String.format(Locale.US, "%.6f", longitude)
        } else {
            "\uD83D\uDCCD Location"
        }
    }

    /** "0:32" — the same shape the voice and video bubbles show. */
    private fun formatDuration(totalSeconds: Int): String =
        String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60)

    // ---------- Export ----------

    /** Writes the transcript file; the callback gets it, or null. */
    fun exportChat(
        context: Context,
        messages: List<ChatMessage>,
        ownName: String,
        fallbackName: String?,
        callback: (File?) -> Unit
    ) {
        val appContext = context.applicationContext
        worker.execute {
            val result = try {
                val text = buildTranscript(appContext, messages, ownName, fallbackName)
                val file = File(
                    appContext.cacheDir,
                    "AshfaqChat-Export-" + fileStamp() + ".txt"
                )
                file.writeText(text, Charsets.UTF_8)
                file
            } catch (e: Exception) {
                null
            }
            mainHandler.post { callback(result) }
        }
    }

    // ---------- Backup ----------

    /** Builds the backup zip; the callback gets it, or null. */
    fun backupChat(context: Context, callback: (File?) -> Unit) {
        val appContext = context.applicationContext
        worker.execute {
            val result = try {
                writeBackupZip(appContext)
            } catch (e: Exception) {
                null
            }
            mainHandler.post { callback(result) }
        }
    }

    private fun writeBackupZip(context: Context): File? {
        val historyFile = ChatHistoryStore.historyFile(context)
        if (!historyFile.exists()) return null
        val rawText = historyFile.readText(Charsets.UTF_8)
        val array = try {
            JSONArray(rawText)
        } catch (e: Exception) {
            return null
        }
        if (array.length() == 0) return null

        // Plan the media: each file the history points at goes into
        // the zip under "<its folder>/<its name>". Two different
        // files can share a name in one folder (a received photo and
        // a sent one, both photo_<millis>.jpg); the second then gets
        // a " (1)" suffix, and exactly in that case the history copy
        // inside the zip has that one path rewritten to the zip entry
        // name — resolvable again at restore time. Without a
        // collision the zipped history is the persisted file, byte
        // for byte.
        val entries = LinkedHashMap<String, String>()
        val naturalNames = HashMap<String, String>()
        val seenPaths = HashSet<String>()
        var missing = 0
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            for ((key, folder) in MEDIA_KEYS) {
                if (item.isNull(key)) continue
                val path = item.optString(key).takeIf { it.isNotEmpty() } ?: continue
                if (!seenPaths.add(path)) continue
                val source = File(path)
                if (!source.exists()) {
                    missing++
                    continue
                }
                val natural = folder + "/" + source.name
                val entryName = if (entries.containsValue(natural)) {
                    uniqueEntryName(folder, source.name, entries.values.toSet())
                } else {
                    natural
                }
                entries[path] = entryName
                naturalNames[path] = natural
            }
        }
        var historyTextForZip = rawText
        val renames = entries.filter { (path, entryName) ->
            entryName != naturalNames[path]
        }
        if (renames.isNotEmpty()) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                for ((key, _) in MEDIA_KEYS) {
                    if (item.isNull(key)) continue
                    val renamed = renames[item.optString(key)]
                    if (renamed != null) item.put(key, renamed)
                }
            }
            historyTextForZip = array.toString()
        }

        val zipFile = File(
            context.cacheDir,
            "AshfaqChat-Backup-" + fileStamp() + ".zip"
        )
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zip ->
            zip.putNextEntry(ZipEntry(HISTORY_ENTRY_NAME))
            zip.write(historyTextForZip.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for ((path, entryName) in entries) {
                zip.putNextEntry(ZipEntry(entryName))
                File(path).inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
            val infoText = buildString {
                append("Ashfaq Offline Chat backup\n")
                append(
                    "Created: " + SimpleDateFormat(
                        "d MMM yyyy, h:mm a", Locale.getDefault()
                    ).format(Date()) + "\n"
                )
                append("Messages: " + array.length() + "\n")
                append("Media files included: " + entries.size + "\n")
                append("Media files missing (skipped): " + missing + "\n")
            }
            zip.putNextEntry(ZipEntry(INFO_ENTRY_NAME))
            zip.write(infoText.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return zipFile
    }

    /** "name (1).ext", "name (2).ext", ... until the name is free. */
    private fun uniqueEntryName(
        folder: String,
        fileName: String,
        used: Set<String>
    ): String {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val extension = if (dot > 0) fileName.substring(dot) else ""
        var counter = 1
        var candidate: String
        do {
            candidate = folder + "/" + base + " (" + counter + ")" + extension
            counter++
        } while (candidate in used)
        return candidate
    }

    // ---------- Restore ----------

    /**
     * Copies the picked backup into the staging folder and checks
     * it: the zip must hold a history that parses, and every entry
     * name must stay inside the folder. The callback gets the
     * [Staging], or null for anything that is not a usable backup —
     * the current chat is untouched either way.
     */
    fun extractBackup(context: Context, uri: Uri, callback: (Staging?) -> Unit) {
        val appContext = context.applicationContext
        worker.execute {
            val result = try {
                doExtract(appContext, uri)
            } catch (e: Exception) {
                null
            }
            mainHandler.post { callback(result) }
        }
    }

    private fun doExtract(context: Context, uri: Uri): Staging? {
        val stagingDir = File(context.cacheDir, STAGING_DIR_NAME)
        stagingDir.deleteRecursively()
        if (!stagingDir.mkdirs() && !stagingDir.exists()) return null
        val input = context.contentResolver.openInputStream(uri)
        if (input == null) {
            stagingDir.deleteRecursively()
            return null
        }
        var sawHistory = false
        input.use { stream ->
            ZipInputStream(stream).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val entryName = entry.name
                    // Zip-slip guard: a name that climbs out of the
                    // folder (or is absolute) makes the whole file
                    // untrusted. A genuine backup never has one.
                    val segments = entryName.split('/')
                    if (entryName.startsWith("/") || ".." in segments) {
                        stagingDir.deleteRecursively()
                        return null
                    }
                    if (!entry.isDirectory &&
                        (entryName == HISTORY_ENTRY_NAME || isMediaEntry(entryName))
                    ) {
                        val dest = File(stagingDir, entryName)
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { out -> zip.copyTo(out) }
                        if (entryName == HISTORY_ENTRY_NAME) sawHistory = true
                    }
                    zip.closeEntry()
                }
            }
        }
        if (!sawHistory) {
            stagingDir.deleteRecursively()
            return null
        }
        val historyText = File(stagingDir, HISTORY_ENTRY_NAME)
            .readText(Charsets.UTF_8)
        // It must really be a history — a JSON array — before the
        // user is asked anything.
        try {
            JSONArray(historyText)
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            return null
        }
        return Staging(stagingDir, historyText)
    }

    /** A flat media entry directly under one of the media folders. */
    private fun isMediaEntry(entryName: String): Boolean {
        val slash = entryName.indexOf('/')
        if (slash <= 0 || slash != entryName.lastIndexOf('/')) return false
        if (entryName.length <= slash + 1) return false
        return entryName.substring(0, slash) in MEDIA_FOLDERS
    }

    /**
     * The confirmed half of a restore: installs the staged media,
     * points the history at this phone, and lets ChatHistoryStore
     * parse and save it. The callback gets the restored messages,
     * or null when the install itself failed (in which case the
     * saved history was never replaced).
     */
    fun finishRestore(
        context: Context,
        staging: Staging,
        callback: (List<ChatMessage>?) -> Unit
    ) {
        val appContext = context.applicationContext
        worker.execute {
            val result = try {
                doFinishRestore(appContext, staging)
            } catch (e: Exception) {
                null
            }
            mainHandler.post { callback(result) }
        }
    }

    private fun doFinishRestore(
        context: Context,
        staging: Staging
    ): List<ChatMessage> {
        val filesDir = context.filesDir
        // Media first: the history parse below (inside the store)
        // keeps only entries whose files are really there.
        for (folder in MEDIA_FOLDERS) {
            val stagedFiles = File(staging.dir, folder).listFiles() ?: continue
            val destFolder = File(filesDir, folder)
            if (!destFolder.exists()) destFolder.mkdirs()
            for (staged in stagedFiles) {
                if (staged.isFile) {
                    staged.copyTo(File(destFolder, staged.name), overwrite = true)
                }
            }
        }
        val resolved = resolveHistoryPaths(staging.historyText, filesDir)
        val messages = ChatHistoryStore.importJson(context, resolved)
        staging.dir.deleteRecursively()
        return messages
    }

    /** Throws away a checked backup the user decided not to use. */
    fun discardStaging(staging: Staging) {
        worker.execute {
            staging.dir.deleteRecursively()
        }
    }

    /**
     * Points every media path in a backup's history at this phone.
     * The file name always survives the move between phones; the
     * folder is the one the entry's key stands for, which is also
     * where the backup put the file and where [doFinishRestore]
     * installed it. A value that is not an absolute path (written
     * when a backup had to rename a colliding file) carries its
     * final name just the same, so the same rule finds it.
     */
    private fun resolveHistoryPaths(jsonText: String, filesDir: File): String {
        val array = try {
            JSONArray(jsonText)
        } catch (e: Exception) {
            return jsonText
        }
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            for ((key, folder) in MEDIA_KEYS) {
                if (item.isNull(key)) continue
                val value = item.optString(key)
                if (value.isEmpty()) continue
                val fileName = File(value).name
                if (fileName.isEmpty()) continue
                item.put(key, File(File(filesDir, folder), fileName).absolutePath)
            }
        }
        return array.toString()
    }

    /** The "20261026-0915" stamp the export and backup names carry. */
    private fun fileStamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
}
