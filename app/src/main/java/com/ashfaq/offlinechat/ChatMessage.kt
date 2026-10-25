package com.ashfaq.offlinechat

data class ChatMessage(
    val text: String,
    val isSent: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val type: Type = Type.TEXT,
    // Only used for voice messages: where the audio file lives on this
    // phone, and how long it is.
    val audioPath: String? = null,
    val durationSeconds: Int = 0,
    // Only used for photo messages: where the image file lives on this
    // phone.
    val imagePath: String? = null,
    // Only used for file messages: where the file lives on this phone,
    // the name it shows in the chat, and how big it is.
    val filePath: String? = null,
    val fileName: String? = null,
    val fileSizeBytes: Long = 0,
    // Who originally sent this message, in a group chat. Only set on
    // received messages that carried a name; our own messages and
    // messages from older app versions have none.
    val senderName: String? = null
) {
    enum class Type { TEXT, VOICE, PHOTO, FILE }
}
