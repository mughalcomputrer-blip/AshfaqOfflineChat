package com.ashfaq.offlinechat

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.io.File
import java.util.UUID

class ChatManager(
    context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onSearching()
        fun onConnectionRequest(
            endpointName: String,
            authDigits: String,
            accept: () -> Unit,
            reject: () -> Unit
        )
        fun onConnecting(endpointName: String)
        fun onConnected(endpointName: String)
        fun onDisconnected()
        fun onMessageReceived(text: String)
        fun onError(message: String)

        /** The connection dropped and an automatic retry is about to happen. */
        fun onReconnecting(attempt: Int, maxAttempts: Int) {}

        /** A voice message arrived; [filePath] points to our own copy of it. */
        fun onVoiceMessageReceived(filePath: String, durationSeconds: Int) {}

        /** A photo arrived; [filePath] points to our own copy of it. */
        fun onPhotoMessageReceived(filePath: String) {}
    }

    private val appContext: Context = context.applicationContext
    private val client = Nearby.getConnectionsClient(context.applicationContext)
    private val strategy = Strategy.P2P_POINT_TO_POINT
    private val handler = Handler(Looper.getMainLooper())

    // Random suffix so two phones of the same model still have different names.
    private val localName: String = "${Build.MODEL}-${(1000..9999).random()}"

    private var pendingEndpointId: String? = null
    private var pendingName: String = ""
    private var connectedEndpointId: String? = null
    private var connectedName: String = ""

    // Auto-reconnect state. autoReconnect turns true after the first
    // successful connection and stays true until the user taps Disconnect.
    private var autoReconnect = false
    private var reconnectAttempts = 0
    private val reconnectRunnable = Runnable { start() }

    // Ids of messages we already showed, so a repeated delivery is dropped.
    private val seenMessageIds = LinkedHashSet<String>()

    // File payloads that arrived and are still being transferred.
    private val incomingFilePayloads = mutableMapOf<Long, Payload>()

    // Length (seconds) of the voice file that is on its way, told to us by
    // the small info message the sender sends just before the file.
    private var pendingVoiceDurationSeconds: Int? = null

    // What kind of file is on its way (voice or photo), told to us by the
    // small info message the sender sends just before every file. A file
    // that arrives with no info is treated as voice, like before Phase 5.
    private enum class IncomingFileKind { VOICE, PHOTO }
    private var pendingFileKind: IncomingFileKind? = null

    fun isConnected(): Boolean = connectedEndpointId != null

    /** Both phones advertise and search at the same time. */
    fun start() {
        stopEverything()
        listener.onSearching()

        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(strategy)
            .build()
        client.startAdvertising(
            localName, SERVICE_ID, connectionCallback, advertisingOptions
        ).addOnFailureListener { e -> fail(describe(e)) }

        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .build()
        client.startDiscovery(
            SERVICE_ID, discoveryCallback, discoveryOptions
        ).addOnFailureListener { e -> fail(describe(e)) }
    }

    fun disconnect() {
        autoReconnect = false
        reconnectAttempts = 0
        stopEverything()
        listener.onDisconnected()
    }

    fun sendMessage(text: String): Boolean {
        val endpointId = connectedEndpointId ?: return false
        // Every message carries its own random id, so the other phone can
        // spot and drop the same message if it is ever delivered twice.
        val wireText = MESSAGE_ID_PREFIX + UUID.randomUUID().toString() + text
        val payload = Payload.fromBytes(wireText.toByteArray(Charsets.UTF_8))
        client.sendPayload(endpointId, payload)
            .addOnFailureListener { e -> listener.onError(describe(e)) }
        return true
    }

    /**
     * Sends a recorded voice message. A file payload cannot carry any extra
     * info, so we first send a tiny text info ("#VOICE#" + seconds + "#" +
     * file name) and then the audio file itself.
     */
    fun sendVoiceMessage(file: File, durationSeconds: Int): Boolean {
        val endpointId = connectedEndpointId ?: return false
        val info = VOICE_PREFIX + durationSeconds + "#" + file.name
        client.sendPayload(endpointId, Payload.fromBytes(info.toByteArray(Charsets.UTF_8)))
            .addOnFailureListener { e -> listener.onError(describe(e)) }
        val filePayload = try {
            Payload.fromFile(file)
        } catch (e: Exception) {
            listener.onError("The voice message could not be sent.")
            return false
        }
        client.sendPayload(endpointId, filePayload)
            .addOnFailureListener { e -> listener.onError(describe(e)) }
        return true
    }

    /**
     * Sends a photo. Same pattern as voice: a file payload cannot carry any
     * extra info, so we first send a tiny text info ("#PHOTO#" + file name)
     * and then the image file itself.
     */
    fun sendPhotoMessage(file: File): Boolean {
        val endpointId = connectedEndpointId ?: return false
        val info = PHOTO_PREFIX + file.name
        client.sendPayload(endpointId, Payload.fromBytes(info.toByteArray(Charsets.UTF_8)))
            .addOnFailureListener { e -> listener.onError(describe(e)) }
        val filePayload = try {
            Payload.fromFile(file)
        } catch (e: Exception) {
            listener.onError("The photo could not be sent.")
            return false
        }
        client.sendPayload(endpointId, filePayload)
            .addOnFailureListener { e -> listener.onError(describe(e)) }
        return true
    }

    private fun stopEverything() {
        handler.removeCallbacks(reconnectRunnable)
        connectedEndpointId = null
        pendingEndpointId = null
        incomingFilePayloads.clear()
        pendingVoiceDurationSeconds = null
        pendingFileKind = null
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
    }

    private fun fail(message: String) {
        reconnectAttempts = 0
        stopEverything()
        listener.onError(message)
    }

    /**
     * After the connection drops or a reconnect attempt fails, either wait a
     * few seconds and search again, or give up after a few tries and report
     * the normal disconnected state. It never loops forever.
     */
    private fun scheduleReconnectOrGiveUp() {
        pendingEndpointId = null
        if (!autoReconnect || reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts = 0
            stopEverything()
            listener.onDisconnected()
            return
        }
        reconnectAttempts++
        listener.onReconnecting(reconnectAttempts, MAX_RECONNECT_ATTEMPTS)
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun describe(e: Exception): String = e.message ?: e.toString()

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (connectedEndpointId != null || pendingEndpointId != null) return

            // Only the phone with the "smaller" name sends the request,
            // so the two phones do not request each other at once.
            if (localName <= info.endpointName) {
                pendingEndpointId = endpointId
                pendingName = info.endpointName
                client.requestConnection(localName, endpointId, connectionCallback)
                    .addOnFailureListener { e ->
                        pendingEndpointId = null
                        if (reconnectAttempts > 0) {
                            // A reconnect attempt failed; try again or give up.
                            scheduleReconnectOrGiveUp()
                        } else {
                            fail(describe(e))
                        }
                    }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            // Nothing to do. Searching continues.
        }
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val busyWithOther = connectedEndpointId != null ||
                (pendingEndpointId != null && pendingEndpointId != endpointId)
            if (busyWithOther) {
                client.rejectConnection(endpointId)
                return
            }

            pendingEndpointId = endpointId
            pendingName = info.endpointName

            listener.onConnectionRequest(
                endpointName = info.endpointName,
                authDigits = info.authenticationDigits,
                accept = {
                    listener.onConnecting(info.endpointName)
                    client.acceptConnection(endpointId, payloadCallback)
                        .addOnFailureListener { e -> fail(describe(e)) }
                },
                reject = {
                    client.rejectConnection(endpointId)
                    pendingEndpointId = null
                }
            )
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val code = result.status.statusCode
            if (code == ConnectionsStatusCodes.STATUS_OK) {
                connectedEndpointId = endpointId
                connectedName = pendingName
                pendingEndpointId = null
                autoReconnect = true
                reconnectAttempts = 0
                client.stopAdvertising()
                client.stopDiscovery()
                listener.onConnected(connectedName)
            } else if (code == ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED) {
                // The other person said no, so retrying makes no sense.
                fail("The connection was rejected.")
            } else if (reconnectAttempts > 0) {
                // A reconnect attempt failed; try again or give up.
                scheduleReconnectOrGiveUp()
            } else {
                fail("Connection failed (code $code).")
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (endpointId == connectedEndpointId) {
                connectedEndpointId = null
                if (autoReconnect) {
                    scheduleReconnectOrGiveUp()
                } else {
                    listener.onDisconnected()
                }
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            // Ignore anything that does not come from the phone we accepted.
            if (endpointId != connectedEndpointId) return
            when (payload.type) {
                Payload.Type.FILE -> {
                    // Remember the file; it is usable once the transfer
                    // finishes with SUCCESS in onPayloadTransferUpdate.
                    incomingFilePayloads[payload.id] = payload
                }
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    val raw = String(bytes, Charsets.UTF_8)
                    if (raw.startsWith(VOICE_PREFIX)) {
                        // Info about the voice file that follows.
                        parseVoiceInfo(raw)?.let {
                            pendingVoiceDurationSeconds = it
                            pendingFileKind = IncomingFileKind.VOICE
                        }
                        return
                    }
                    if (raw.startsWith(PHOTO_PREFIX)) {
                        // Info about the photo file that follows.
                        if (isPhotoInfo(raw)) {
                            pendingFileKind = IncomingFileKind.PHOTO
                        }
                        return
                    }
                    val (messageId, text) = unpackMessage(raw)
                    if (messageId != null && !rememberMessageId(messageId)) {
                        return // We already showed this message once.
                    }
                    listener.onMessageReceived(text)
                }
                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(
            endpointId: String,
            update: PayloadTransferUpdate
        ) {
            if (endpointId != connectedEndpointId) return
            if (update.status == PayloadTransferUpdate.Status.FAILURE) {
                incomingFilePayloads.remove(update.payloadId)
                // The file is gone, so its info message is stale too.
                pendingFileKind = null
                pendingVoiceDurationSeconds = null
                listener.onError("A message could not be transferred.")
            } else if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                val filePayload = incomingFilePayloads.remove(update.payloadId)
                if (filePayload != null) {
                    // The info message said what kind of file this is. No
                    // info (or a lost one) keeps the old behavior: voice.
                    val kind = pendingFileKind ?: IncomingFileKind.VOICE
                    pendingFileKind = null
                    when (kind) {
                        IncomingFileKind.VOICE -> saveReceivedVoiceFile(filePayload)
                        IncomingFileKind.PHOTO -> saveReceivedPhotoFile(filePayload)
                    }
                }
            }
        }
    }

    /**
     * Reads the length in seconds out of a voice info text
     * ("#VOICE#" + seconds + "#" + file name). Null when it does not parse;
     * the length is then read from the audio file itself instead.
     */
    private fun parseVoiceInfo(raw: String): Int? {
        val rest = raw.substring(VOICE_PREFIX.length)
        val separator = rest.indexOf('#')
        if (separator <= 0) return null
        return rest.substring(0, separator).toIntOrNull()
    }

    /**
     * Checks a photo info text ("#PHOTO#" + file name). We only prepare
     * ".jpg" files, so requiring that ending keeps a normal chat text that
     * happens to start with the prefix from being mistaken for photo info.
     */
    private fun isPhotoInfo(raw: String): Boolean {
        val name = raw.substring(PHOTO_PREFIX.length)
        return name.isNotEmpty() && name.endsWith(".jpg", ignoreCase = true)
    }

    /** Copies a fully received photo into our own storage and shows it. */
    private fun saveReceivedPhotoFile(payload: Payload) {
        val filePart = payload.asFile() ?: return
        val photoDir = File(appContext.filesDir, PHOTO_DIR_NAME)
        if (!photoDir.exists()) photoDir.mkdirs()
        val dest = File(photoDir, "photo_${System.currentTimeMillis()}.jpg")
        if (!copyPayloadFile(filePart, dest) || !dest.exists() || dest.length() == 0L) {
            dest.delete()
            listener.onError("A photo could not be saved.")
            return
        }
        listener.onPhotoMessageReceived(dest.absolutePath)
    }

    /** Copies a fully received voice file into our own storage and shows it. */
    private fun saveReceivedVoiceFile(payload: Payload) {
        val filePart = payload.asFile() ?: return
        val voiceDir = File(appContext.filesDir, VOICE_DIR_NAME)
        if (!voiceDir.exists()) voiceDir.mkdirs()
        val dest = File(voiceDir, "voice_${System.currentTimeMillis()}.m4a")
        if (!copyPayloadFile(filePart, dest) || !dest.exists() || dest.length() == 0L) {
            dest.delete()
            listener.onError("A voice message could not be saved.")
            return
        }
        // Prefer the length the sender told us; fall back to the file itself.
        val duration = pendingVoiceDurationSeconds ?: fileDurationSeconds(dest)
        pendingVoiceDurationSeconds = null
        listener.onVoiceMessageReceived(dest.absolutePath, duration)
    }

    private fun copyPayloadFile(filePart: Payload.File, dest: File): Boolean {
        // Newer Android versions: read the bytes through the file descriptor.
        val descriptor = try {
            filePart.asParcelFileDescriptor()
        } catch (e: Exception) {
            null
        }
        if (descriptor != null) {
            val ok = try {
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                dest.length() > 0
            } catch (e: Exception) {
                false
            }
            if (ok) return true
        }
        // Fallback: Nearby may already keep the bytes in a normal file.
        val source = try {
            filePart.asJavaFile()
        } catch (e: Exception) {
            null
        }
        if (source != null && source.exists()) {
            return try {
                source.copyTo(dest, overwrite = true)
                dest.length() > 0
            } catch (e: Exception) {
                false
            }
        }
        return false
    }

    /** Reads the length of an audio file, in seconds. 0 when unknown. */
    private fun fileDurationSeconds(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val millis = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            (millis / 1000).toInt()
        } catch (e: Exception) {
            0
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {
                // Nothing to clean up then.
            }
        }
    }

    /**
     * Splits a received payload into its message id and the real text.
     * New messages look like "#ID#" + a 36-char UUID + the text. Anything
     * else (for example a plain message from an older app version) has no
     * id and is shown as-is.
     */
    private fun unpackMessage(raw: String): Pair<String?, String> {
        val idStart = MESSAGE_ID_PREFIX.length
        val idEnd = idStart + MESSAGE_ID_LENGTH
        if (raw.length >= idEnd && raw.startsWith(MESSAGE_ID_PREFIX)) {
            val id = raw.substring(idStart, idEnd)
            // A real UUID has dashes at fixed places; this check keeps
            // normal text that happens to start with the prefix safe.
            if (id[8] == '-' && id[13] == '-' && id[18] == '-' && id[23] == '-') {
                return id to raw.substring(idEnd)
            }
        }
        return null to raw
    }

    /** Remembers a message id. Returns false when the id was already seen. */
    private fun rememberMessageId(id: String): Boolean {
        if (!seenMessageIds.add(id)) return false
        while (seenMessageIds.size > MAX_SEEN_MESSAGE_IDS) {
            val oldest = seenMessageIds.iterator()
            oldest.next()
            oldest.remove()
        }
        return true
    }

    companion object {
        private const val SERVICE_ID = "com.ashfaq.offlinechat.v1"
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val MESSAGE_ID_PREFIX = "#ID#"
        private const val MESSAGE_ID_LENGTH = 36 // length of a UUID string
        private const val MAX_SEEN_MESSAGE_IDS = 200
        private const val VOICE_PREFIX = "#VOICE#"
        private const val VOICE_DIR_NAME = "voice"
        private const val PHOTO_PREFIX = "#PHOTO#"
        private const val PHOTO_DIR_NAME = "photos"
    }
}
