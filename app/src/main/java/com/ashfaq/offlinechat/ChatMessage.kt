package com.ashfaq.offlinechat

data class ChatMessage(
    val text: String,
    val isSent: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
