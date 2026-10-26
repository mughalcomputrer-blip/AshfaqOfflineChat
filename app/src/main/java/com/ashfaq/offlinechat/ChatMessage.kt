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
    // Only used for video messages: where the video file lives on this
    // phone. The length rides along in durationSeconds, like voice.
    val videoPath: String? = null,
    // Only used for file messages: where the file lives on this phone,
    // the name it shows in the chat, and how big it is.
    val filePath: String? = null,
    val fileName: String? = null,
    val fileSizeBytes: Long = 0,
    // Only used for location messages: the shared position, in
    // degrees. Both are set together, or both stay null.
    val latitude: Double? = null,
    val longitude: Double? = null,
    // Who originally sent this message, in a group chat. Only set on
    // received messages that carried a name; our own messages and
    // messages from older app versions have none.
    val senderName: String? = null,
    // The message this one replies to, when it was sent as a reply:
    // the quoted message's author name and a short preview of it (a
    // text snippet, or a label like "📷 Photo"). Both are null when
    // this is not a reply; they are always set (or unset) together.
    val quotedSender: String? = null,
    val quotedText: String? = null,
    // Local-only bookkeeping for our own sent messages (never put on
    // the wire): an id this app hands out when the message is sent, so
    // a finished transfer can be matched back to this chat row, and
    // whether that transfer has finished (the "delivered" ticks).
    // Received messages and history from older versions keep 0/false.
    val localId: Long = 0,
    val delivered: Boolean = false,
    // The id the OTHER phones know this message by (unlike [localId],
    // which never leaves this phone): for a text or location message
    // it is the "#ID#" uuid inside its payload; for a voice, photo,
    // video or file message it is the uuid the sender announced in a
    // "#MID#" text just before the file. Reactions refer to a message
    // by this id. Null for media saved by older app versions, which
    // announced no id — those messages can be replied to but not
    // reacted to.
    val wireId: String? = null,
    // Emoji reactions on this message: emoji -> the names of the
    // people who reacted with it. A person appears under at most one
    // emoji (reacting with another one moves them, reacting with the
    // same one again removes them). Empty when nobody reacted.
    val reactions: MutableMap<String, MutableSet<String>> = mutableMapOf(),
    // True once the author edited this message (only text messages
    // can be edited): the bubble then shows a small "edited" caption
    // next to the time, on every phone.
    val edited: Boolean = false,
    // True once the author deleted this message for everyone: every
    // phone replaces the bubble with the "This message was deleted"
    // placeholder instead of removing the row, so quotes, reactions
    // and history positions around it stay sane; its own reactions
    // are cleared and (on the phones that received it) its media
    // file is deleted. Our own copy is marked exactly the same way.
    val deleted: Boolean = false,
    // True when this message is a forward: a fresh copy of an
    // earlier message, re-sent by the person who forwarded it (the
    // bubble then shows a small "↪ Forwarded" caption). A forward
    // is a new message in every other respect — new wire id, no
    // quote, no reactions carried over.
    val forwarded: Boolean = false,
    // Who originally wrote the forwarded message, when that is
    // someone other than the phone that sent this copy (forwarding
    // a message you received). Shown in the caption as
    // "↪ Forwarded · from <name>". Null when the forwarder is the
    // author (forwarding your own message) or the author is
    // unknown; the caption is then just "↪ Forwarded".
    val forwardedFrom: String? = null
) {
    enum class Type { TEXT, VOICE, PHOTO, FILE, VIDEO, LOCATION }
}
