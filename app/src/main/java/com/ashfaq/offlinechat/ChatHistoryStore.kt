package com.ashfaq.offlinechat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Saves the chat on this phone so it is still there after the app is
 * closed and reopened. The messages are kept as JSON in a small file in
 * the app's private folder; no database and no extra library is needed
 * (org.json is part of Android itself).
 *
 * Only the newest [MAX_HISTORY] messages are kept. Voice, photo, video
 * and file entries point at their files; if a file is gone when the
 * history is loaded (for example a sent voice that lived in the cache
 * folder), that entry is simply skipped — it never crashes the load.
 */
object ChatHistoryStore {

    private const val HISTORY_FILE_NAME = "chat_history.json"
    private const val MAX_HISTORY = 200

    // One background thread, so writes happen in the order they were
    // asked for and never block the screen.
    private val writer = Executors.newSingleThreadExecutor()

    /** Loads the saved chat, oldest first. Empty when nothing is saved. */
    fun load(context: Context): List<ChatMessage> {
        val file = File(context.applicationContext.filesDir, HISTORY_FILE_NAME)
        if (!file.exists()) return emptyList()
        val messages = mutableListOf<ChatMessage>()
        try {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                toMessage(item)?.let { messages.add(it) }
            }
        } catch (e: Exception) {
            // A damaged history file must never stop the app from opening.
            return emptyList()
        }
        return messages.takeLast(MAX_HISTORY)
    }

    /** Saves the newest messages. Writing happens on a background thread. */
    fun save(context: Context, messages: List<ChatMessage>) {
        val text = try {
            toJson(messages.takeLast(MAX_HISTORY))
        } catch (e: Exception) {
            return
        }
        val filesDir = context.applicationContext.filesDir
        writer.execute {
            try {
                File(filesDir, HISTORY_FILE_NAME).writeText(text, Charsets.UTF_8)
            } catch (ignored: Exception) {
                // If one save fails, the chat on screen is unaffected;
                // the next message saves everything again anyway.
            }
        }
    }

    /** Deletes the saved history (queued after any pending save). */
    fun clear(context: Context) {
        val filesDir = context.applicationContext.filesDir
        writer.execute {
            try {
                File(filesDir, HISTORY_FILE_NAME).delete()
            } catch (ignored: Exception) {
                // Nothing to delete then.
            }
        }
    }

    private fun toJson(messages: List<ChatMessage>): String {
        val array = JSONArray()
        for (message in messages) {
            val item = JSONObject()
            item.put("type", message.type.name)
            item.put("sent", message.isSent)
            item.put("text", message.text)
            item.put("ts", message.timestamp)
            item.put("dur", message.durationSeconds)
            message.audioPath?.let { item.put("audio", it) }
            message.imagePath?.let { item.put("image", it) }
            message.videoPath?.let { item.put("video", it) }
            message.filePath?.let { item.put("file", it) }
            message.fileName?.let { item.put("fname", it) }
            item.put("fsize", message.fileSizeBytes)
            // A shared position, for location messages.
            message.latitude?.let { item.put("lat", it) }
            message.longitude?.let { item.put("lng", it) }
            message.senderName?.let { item.put("from", it) }
            // The quote this message replies to, if any.
            message.quotedSender?.let { item.put("qsender", it) }
            message.quotedText?.let { item.put("qtext", it) }
            // The local id and the delivered ticks of our own messages,
            // so they survive a restart like the messages themselves.
            item.put("lid", message.localId)
            item.put("delivered", message.delivered)
            // The id the other phones know this message by, so a
            // reaction can still find it after a restart.
            message.wireId?.let { item.put("wid", it) }
            // The reactions on the message: emoji -> the names of the
            // people who reacted with it. Only written when non-empty.
            if (message.reactions.isNotEmpty()) {
                val reactionsJson = JSONObject()
                for ((emoji, names) in message.reactions) {
                    val namesJson = JSONArray()
                    for (name in names) namesJson.put(name)
                    reactionsJson.put(emoji, namesJson)
                }
                item.put("reactions", reactionsJson)
            }
            array.put(item)
        }
        return array.toString()
    }

    private fun toMessage(item: JSONObject): ChatMessage? {
        val type = try {
            ChatMessage.Type.valueOf(item.optString("type", "TEXT"))
        } catch (e: Exception) {
            ChatMessage.Type.TEXT
        }
        val audioPath = if (item.isNull("audio")) null else item.optString("audio")
        val imagePath = if (item.isNull("image")) null else item.optString("image")
        // Older history files have no "video" key; those entries are
        // never of type VIDEO, so the default below is never used.
        val videoPath = if (item.isNull("video")) null else item.optString("video")
        // Older history files have no "file" keys at all; those entries
        // are never of type FILE, and the defaults below keep them loading
        // exactly as before.
        val filePath = if (item.isNull("file")) null else item.optString("file")
        val fileName = if (item.isNull("fname")) null else item.optString("fname")
        // Older history files have no "lat"/"lng" keys; those entries
        // are never of type LOCATION, so both simply stay null.
        val latitude = if (item.isNull("lat")) null else item.optDouble("lat")
        val longitude = if (item.isNull("lng")) null else item.optDouble("lng")
        // Older history files have no "from" key; those messages simply
        // have no sender name, exactly as they were saved.
        val senderName = if (item.isNull("from")) {
            null
        } else {
            item.optString("from").takeIf { it.isNotEmpty() }
        }
        // Older history files have no quote keys either; their messages
        // are simply not replies. A quote shows only with both parts.
        val quotedSender = if (item.isNull("qsender")) {
            null
        } else {
            item.optString("qsender").takeIf { it.isNotEmpty() }
        }
        val quotedText = if (item.isNull("qtext")) {
            null
        } else {
            item.optString("qtext").takeIf { it.isNotEmpty() }
        }
        // Older history files have no "wid" key either; those messages
        // simply have no wire id (media announced none back then, and
        // a text's id was never saved), so they cannot be reacted to.
        val wireId = if (item.isNull("wid")) {
            null
        } else {
            item.optString("wid").takeIf { it.isNotEmpty() }
        }
        // Older history files have no "reactions" key; their messages
        // simply have no reactions. Empty name lists are dropped, so a
        // stored reaction always names at least one person.
        val reactions = mutableMapOf<String, MutableSet<String>>()
        val reactionsJson = if (item.isNull("reactions")) {
            null
        } else {
            item.optJSONObject("reactions")
        }
        if (reactionsJson != null) {
            val emojiKeys = reactionsJson.keys()
            while (emojiKeys.hasNext()) {
                val emoji = emojiKeys.next()
                val namesJson = reactionsJson.optJSONArray(emoji) ?: continue
                val names = mutableSetOf<String>()
                for (j in 0 until namesJson.length()) {
                    namesJson.optString(j).takeIf { it.isNotEmpty() }
                        ?.let { names.add(it) }
                }
                if (names.isNotEmpty()) reactions[emoji] = names
            }
        }
        // Older history files have no "lid"/"delivered" keys either;
        // those own messages then honestly show the single "sent" tick.
        // A voice, photo, video or file entry is only useful while its
        // file is still there; sent files lived in the cache and may be
        // gone by now.
        when (type) {
            ChatMessage.Type.VOICE ->
                if (audioPath == null || !File(audioPath).exists()) return null
            ChatMessage.Type.PHOTO ->
                if (imagePath == null || !File(imagePath).exists()) return null
            ChatMessage.Type.VIDEO ->
                if (videoPath == null || !File(videoPath).exists()) return null
            ChatMessage.Type.FILE ->
                if (filePath == null || !File(filePath).exists()) return null
            // A location points at no file, so nothing can be missing.
            ChatMessage.Type.LOCATION -> Unit
            ChatMessage.Type.TEXT -> Unit
        }
        return ChatMessage(
            text = item.optString("text", ""),
            isSent = item.optBoolean("sent", false),
            timestamp = item.optLong("ts", 0L),
            type = type,
            audioPath = audioPath,
            durationSeconds = item.optInt("dur", 0),
            imagePath = imagePath,
            videoPath = videoPath,
            filePath = filePath,
            fileName = fileName,
            fileSizeBytes = item.optLong("fsize", 0L),
            latitude = latitude,
            longitude = longitude,
            senderName = senderName,
            quotedSender = if (quotedText != null) quotedSender else null,
            quotedText = quotedText,
            localId = item.optLong("lid", 0L),
            delivered = item.optBoolean("delivered", false),
            wireId = wireId,
            reactions = reactions
        )
    }
}
