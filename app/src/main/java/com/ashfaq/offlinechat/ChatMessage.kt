package com.ashfaq.offlinechat

data class ChatMessage(
    val text: String,
    val isSent: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val type: Type = Type.TEXT,
    // Only used for voice messages: where the audio file lives on this
    // phone, and how long it is.
    val audioPath: String? = null,
    val durationSeconds: Int = 0
) {
    enum class Type { TEXT, VOICE }
}
