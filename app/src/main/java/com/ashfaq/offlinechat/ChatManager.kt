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
import java.util.Locale
import java.util.UUID

/**
 * The Nearby Connections side of the app, now for GROUPS (P2P_STAR).
 *
 * How a group forms: phones advertise and search like before, and the
 * phone with the "smaller" name sends the connection request. Whoever
 * ACCEPTS that first request becomes the group host (the "hub"): it
 * keeps advertising — under a marked name that always sorts last — so
 * more phones can find it and join. Whoever REQUESTED becomes a member
 * ("spoke") and stops advertising/searching. Later phones discover the
 * host's marked name, the name rule makes them request it, and the host
 * gets the same accept dialog for every joiner (at most [MAX_SPOKES]).
 *
 * Messaging: a spoke only talks to the hub. The hub shows everything it
 * receives and passes it on to the other spokes, so everyone in the
 * group sees every message. Every payload carries the name of the
 * person who originally sent it, so phones can label who said what.
 *
 * Replies: a message can quote an earlier one. A text reply carries the
 * quote inside its own payload (so the hub's relay keeps it for free);
 * a voice, photo, video or file reply is preceded by a tiny "#RPLY#"
 * text holding the quote, paired with the file that follows it in the
 * same per-endpoint way as the file info texts.
 *
 * Location sharing: a shared position rides in an ordinary text
 * payload whose text is "#LOC#" + latitude + "," + longitude. It
 * therefore travels exactly like a text message — same id, same
 * dedupe, same "delivered" ticks, and the hub's word-for-word relay
 * carries it to the whole group — and is only recognised (and taken
 * out of the text stream) here, after the payload is unpacked.
 */
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

        /**
         * This phone is the group host and the member count changed.
         * Replaces onConnected/onDisconnected for host-side updates.
         */
        fun onGroupHostUpdate(connectedCount: Int) {}

        /**
         * [senderName] is who originally wrote the message (null if
         * unknown). [quotedSender]/[quotedText] describe the message
         * this one replies to (both null when it is not a reply).
         */
        fun onMessageReceived(
            text: String,
            senderName: String?,
            quotedSender: String?,
            quotedText: String?
        )

        /**
         * A shared position arrived. [senderName] is who sent it
         * (null if unknown), like for a text message.
         */
        fun onLocationMessageReceived(
            latitude: Double,
            longitude: Double,
            senderName: String?
        ) {}

        fun onError(message: String)

        /** The connection dropped and an automatic retry is about to happen. */
        fun onReconnecting(attempt: Int, maxAttempts: Int) {}

        /**
         * One of our own sent messages finished transferring to the
         * next phone (its payload transfer reported SUCCESS). [localId]
         * is the id the message was sent with; 0 is never reported.
         * This is local bookkeeping only — nothing extra goes on the wire.
         */
        fun onMessageDelivered(localId: Long) {}

        /** A voice message arrived; [filePath] points to our own copy of it. */
        fun onVoiceMessageReceived(
            filePath: String,
            durationSeconds: Int,
            senderName: String?,
            quotedSender: String?,
            quotedText: String?
        ) {}

        /** A photo arrived; [filePath] points to our own copy of it. */
        fun onPhotoMessageReceived(
            filePath: String,
            senderName: String?,
            quotedSender: String?,
            quotedText: String?
        ) {}

        /** A video arrived; [filePath] points to our own copy of it. */
        fun onVideoMessageReceived(
            filePath: String,
            durationSeconds: Int,
            senderName: String?,
            quotedSender: String?,
            quotedText: String?
        ) {}

        /**
         * A file arrived; [filePath] points to our own copy of it, saved
         * under its original name ([fileName]), [fileSizeBytes] big.
         */
        fun onFileMessageReceived(
            filePath: String,
            fileName: String,
            fileSizeBytes: Long,
            senderName: String?,
            quotedSender: String?,
            quotedText: String?
        ) {}
    }

    private val appContext: Context = context.applicationContext
    private val client = Nearby.getConnectionsClient(context.applicationContext)
    private val strategy = Strategy.P2P_STAR
    private val handler = Handler(Looper.getMainLooper())

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // The name other phones see for us: the user's own display name when
    // they set one, otherwise the phone model. A random suffix is added
    // so two phones with the same name still have different names.
    private var localName: String = buildLocalName()

    private fun buildLocalName(): String =
        "${displayBaseName()}-${(1000..9999).random()}"

    /** The name the user picked, or the phone model when none was picked. */
    fun displayBaseName(): String =
        prefs.getString(KEY_DISPLAY_NAME, null)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /**
     * Saves a new display name (blank means "back to the phone model")
     * and rebuilds the endpoint name from it. The new name is used from
     * the next search on; a chat that is already connected keeps the
     * name it started with. The name stays a plain endpoint-name string,
     * so the "smaller name sends the request" logic is untouched.
     */
    fun updateDisplayName(newName: String) {
        val trimmed = newName.trim()
        val editor = prefs.edit()
        if (trimmed.isEmpty()) {
            editor.remove(KEY_DISPLAY_NAME)
        } else {
            editor.putString(KEY_DISPLAY_NAME, trimmed)
        }
        editor.apply()
        localName = buildLocalName()
    }

    /**
     * The display name we stamp into outgoing payloads, so the other
     * phones can show who sent a message. '#' is removed because it is
     * the field separator of the payload formats.
     */
    private fun wireSenderName(): String =
        displayBaseName().replace('#', ' ').trim()
            .take(MAX_SENDER_NAME_LENGTH).trim()

    /**
     * Makes a value safe to put inside one of the '#' separated payload
     * formats: '#' and line breaks (which would break the field layout)
     * become spaces, and the value is capped in length. Quote fields go
     * through this, so the reading side can always split on '#' safely —
     * a quoted preview that itself contains '#' loses only those '#'
     * characters, never its place in the format.
     */
    private fun wireQuoteField(value: String, maxLength: Int): String =
        value.replace('#', ' ').replace('\n', ' ').replace('\r', ' ')
            .trim().take(maxLength).trim()

    /**
     * The tiny reply info sent just before a voice, photo or file send
     * that carries a quote: "#RPLY#" + quoted sender + "#" + quoted
     * preview. Null when there is no quote. Text messages do not use
     * this; their quote rides inside the text payload itself.
     */
    private fun replyInfo(quotedSender: String?, quotedText: String?): String? {
        if (quotedSender == null || quotedText == null) return null
        val sender = wireQuoteField(quotedSender, MAX_SENDER_NAME_LENGTH)
        val preview = wireQuoteField(quotedText, MAX_QUOTED_TEXT_LENGTH)
        if (sender.isEmpty() || preview.isEmpty()) return null
        return REPLY_PREFIX + sender + "#" + preview
    }

    // ----- Group state -----

    /** What this phone currently is: not in a group, the host, or a member. */
    private enum class Role { NONE, HUB, SPOKE }
    private var role = Role.NONE

    /** Connected phones: endpoint id -> their (display) name. */
    private val connectedEndpoints = LinkedHashMap<String, String>()

    /** The one endpoint we are currently asking to join, if any. */
    private var pendingRequestId: String? = null

    /** Display names of endpoints we are shaking hands with, by endpoint id. */
    private val pendingNames = mutableMapOf<String, String>()

    /** Endpoints whose join we accepted and are waiting to finish connecting. */
    private val acceptedEndpointIds = mutableSetOf<String>()

    /** A join request that is waiting for the accept dialog. */
    private data class PendingRequest(val endpointId: String, val authDigits: String)

    // Join requests are shown one at a time; the rest wait here.
    private val pendingRequests = ArrayDeque<PendingRequest>()
    private var activeRequestEndpointId: String? = null

    // Auto-reconnect state (spokes only). autoReconnect turns true after
    // the first successful connection and stays true until the user taps
    // Disconnect. The host never reconnects; losing one member is normal.
    private var autoReconnect = false
    private var reconnectAttempts = 0
    private val reconnectRunnable = Runnable { start() }

    // Ids of messages we already showed, so a repeated delivery is dropped.
    private val seenMessageIds = LinkedHashSet<String>()

    // Payloads of our own messages that Nearby has not finished sending
    // yet: Nearby payload id -> the local id of the message, so a
    // finished transfer can put the "delivered" ticks on the right
    // chat row. Only the content payload is tracked (for voice, photo
    // and file messages that is the file payload, not the tiny info
    // text before it). Local id 0 means "not tracked" and is never
    // stored here — the host's relays of other people's messages are
    // therefore never reported as deliveries.
    private val outgoingPayloads = mutableMapOf<Long, Long>()

    // File payloads that arrived and are still being transferred.
    private val incomingFilePayloads = mutableMapOf<Long, Payload>()

    // Which endpoint each incoming file payload came from, so its pieces
    // can be cleaned up when that phone leaves the group.
    private val filePayloadEndpoints = mutableMapOf<Long, String>()

    // What kind of file is on its way, told to us by the small info
    // message the sender sends just before every file. Kept per sending
    // endpoint, because in a group two phones can send files around the
    // same time. A file that arrives with no info is treated as voice,
    // like before Phase 5.
    private enum class IncomingFileKind { VOICE, PHOTO, FILE, VIDEO }
    private data class PendingFileInfo(
        val kind: IncomingFileKind,
        val voiceDurationSeconds: Int?,
        val fileName: String?,
        val senderName: String?
    )
    private val pendingFiles = mutableMapOf<String, PendingFileInfo>()

    // A quote that arrived (as a small "#RPLY#" text) just before a
    // voice, photo, video or file from the same phone and belongs to
    // that file's message. Kept per sending endpoint, exactly like
    // [pendingFiles]: it is used when the file's transfer finishes,
    // and dropped together with the file info when the transfer fails
    // or the phone leaves, so a quote can never stick to a later,
    // unrelated file.
    private data class PendingQuote(val sender: String, val text: String)
    private val pendingQuotes = mutableMapOf<String, PendingQuote>()

    fun isConnected(): Boolean = connectedEndpoints.isNotEmpty()

    /**
     * Both phones advertise and search at the same time. A phone that is
     * already hosting a group only makes sure it can still be found.
     */
    fun start() {
        if (role == Role.HUB) {
            startHubAdvertising()
            listener.onGroupHostUpdate(connectedEndpoints.size)
            return
        }
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

    /**
     * (Re)starts advertising under the host's marked name. The marker
     * makes the name sort after every normal name, so a searching phone
     * always ends up requesting the host (the "smaller name requests"
     * rule) and the host stays the host.
     */
    private fun startHubAdvertising() {
        client.stopAdvertising()
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(strategy)
            .build()
        client.startAdvertising(
            HUB_NAME_PREFIX + localName, SERVICE_ID, connectionCallback, advertisingOptions
        ).addOnFailureListener { e ->
            listener.onError(describe(e))
            listener.onGroupHostUpdate(connectedEndpoints.size)
        }
    }

    fun disconnect() {
        autoReconnect = false
        reconnectAttempts = 0
        stopEverything()
        listener.onDisconnected()
    }

    /** Who an outgoing message goes to: for the host everyone in the
     *  group (except [exclude]), for a member just its one connection
     *  (the host), for a lone phone its one connection if it has one. */
    private fun sendTargets(exclude: String? = null): List<String> =
        if (role == Role.HUB) {
            connectedEndpoints.keys.filter { it != exclude }
        } else {
            connectedEndpoints.keys.toList()
        }

    /**
     * Sends a text message. [localId] is the sender app's own id for
     * this message (0 = do not track); when the transfer to the next
     * phone finishes, the listener hears about it via onMessageDelivered.
     * When [quotedSender] and [quotedText] are both given, the message
     * is a reply and carries that quote inside the payload (after the
     * sender-name section), so the host's word-for-word relay keeps it.
     */
    fun sendMessage(
        text: String,
        localId: Long = 0,
        quotedSender: String? = null,
        quotedText: String? = null
    ): Boolean {
        val targets = sendTargets()
        if (targets.isEmpty()) return false
        // Every message carries its own random id, so the other phones can
        // spot and drop the same message if it is ever delivered twice,
        // plus the sender's name, so group members can see who wrote it.
        // Old format "#ID#" + uuid + text still works on the other side.
        val id = UUID.randomUUID().toString()
        val name = wireSenderName()
        val wire = StringBuilder(MESSAGE_ID_PREFIX).append(id)
        if (name.isNotEmpty()) {
            wire.append(FROM_INFIX).append(name).append('#')
        }
        if (quotedSender != null && quotedText != null) {
            val qSender = wireQuoteField(quotedSender, MAX_SENDER_NAME_LENGTH)
            val qText = wireQuoteField(quotedText, MAX_QUOTED_TEXT_LENGTH)
            if (qSender.isNotEmpty() && qText.isNotEmpty()) {
                wire.append(QUOTE_NAME_INFIX).append(qSender)
                    .append(QUOTE_TEXT_INFIX).append(qText).append('#')
            }
        }
        wire.append(text)
        val payload = Payload.fromBytes(wire.toString().toByteArray(Charsets.UTF_8))
        if (localId != 0L) outgoingPayloads[payload.id] = localId
        client.sendPayload(targets, payload)
            .addOnFailureListener { e -> listener.onError(describe(e)) }
        return true
    }

    /**
     * Shares this phone's position. The position is packed into a
     * control text ("#LOC#" + latitude + "," + longitude, six decimal
     * places) and sent through the ordinary text path ([sendMessage]),
     * so it gets a message id, the sender's name, the "delivered"
     * ticks and the hub's word-for-word relay exactly like a typed
     * message; the receiving side recognises the control text in
     * [parseLocationText] and never shows it as a chat text.
     */
    fun sendLocationMessage(
        latitude: Double,
        longitude: Double,
        localId: Long = 0
    ): Boolean {
        val text = LOCATION_PREFIX +
            String.format(Locale.US, "%.6f,%.6f", latitude, longitude)
        return sendMessage(text, localId)
    }

    /**
     * Sends a recorded voice message. A file payload cannot carry any extra
     * info, so we first send a tiny text info ("#VOICE#" + seconds + "#" +
     * sender name + "#" + file name) and then the audio file itself.
     */
    fun sendVoiceMessage(
        file: File,
        durationSeconds: Int,
        localId: Long = 0,
        quotedSender: String? = null,
        quotedText: String? = null
    ): Boolean {
        val targets = sendTargets()
        if (targets.isEmpty()) return false
        val name = wireSenderName()
        val info = if (name.isEmpty()) {
            VOICE_PREFIX + durationSeconds + "#" + file.name
        } else {
            VOICE_PREFIX + durationSeconds + "#" + name + "#" + file.name
        }
        return sendInfoAndFile(
            targets, info, file,
            "The voice message could not be sent.", localId,
            replyInfo(quotedSender, quotedText)
        )
    }

    /**
     * Sends a photo. Same pattern as voice: a file payload cannot carry any
     * extra info, so we first send a tiny text info ("#PHOTO#" + sender
     * name + "#" + file name) and then the image file itself.
     */
    fun sendPhotoMessage(
        file: File,
        localId: Long = 0,
        quotedSender: String? = null,
        quotedText: String? = null
    ): Boolean {
        val targets = sendTargets()
        if (targets.isEmpty()) return false
        val name = wireSenderName()
        val info = if (name.isEmpty()) {
            PHOTO_PREFIX + file.name
        } else {
            PHOTO_PREFIX + name + "#" + file.name
        }
        return sendInfoAndFile(
            targets, info, file,
            "The photo could not be sent.", localId,
            replyInfo(quotedSender, quotedText)
        )
    }

    /**
     * Sends a video. Same pattern as photo and file: a file payload
     * cannot carry any extra info, so we first send a tiny text info
     * ("#VIDEO#" + sender name + "#" + file name) and then the video
     * file itself. The length is not in the info text — each phone
     * reads it from the video file it holds, like the fallback voice
     * messages already use.
     */
    fun sendVideoMessage(
        file: File,
        localId: Long = 0,
        quotedSender: String? = null,
        quotedText: String? = null
    ): Boolean {
        val targets = sendTargets()
        if (targets.isEmpty()) return false
        val name = wireSenderName()
        val info = if (name.isEmpty()) {
            VIDEO_PREFIX + file.name
        } else {
            VIDEO_PREFIX + name + "#" + file.name
        }
        return sendInfoAndFile(
            targets, info, file,
            "The video could not be sent.", localId,
            replyInfo(quotedSender, quotedText)
        )
    }

    /**
     * Sends any file (a PDF, a document, ...). Same pattern as voice and
     * photo: a file payload cannot carry any extra info, so we first send
     * a tiny text info ("#FILE#" + sender name + "#" + the file's original
     * name) and then the file itself. The file must already carry its
     * final name, because that name is what the other phones save and show.
     */
    fun sendFileMessage(
        file: File,
        localId: Long = 0,
        quotedSender: String? = null,
        quotedText: String? = null
    ): Boolean {
        val targets = sendTargets()
        if (targets.isEmpty()) return false
        val name = wireSenderName()
        val info = if (name.isEmpty()) {
            FILE_PREFIX + file.name
        } else {
            FILE_PREFIX + name + "#" + file.name
        }
        return sendInfoAndFile(
            targets, info, file,
            "The file could not be sent.", localId,
            replyInfo(quotedSender, quotedText)
        )
    }

    /**
     * Sends one info text and then one file to all [targets]. Used for
     * our own sends ([errorMessage] is shown when the file itself cannot
     * even be started) and for the host passing a received file on to the
     * rest of the group (then there is no useful error to show, so
     * [errorMessage] is null and a failure stays silent, and [localId]
     * stays 0 because the file is not the host's own message).
     * When [localId] is not 0, the FILE payload (not the info text) is
     * what counts for the "delivered" ticks of our own message.
     * When the message is a reply, [replyInfoText] (built by [replyInfo])
     * goes first, before the info text, so the receiving side can hold
     * it next to the file info. It is only sent once the file payload
     * exists, so a send that cannot even start sends nothing at all.
     */
    private fun sendInfoAndFile(
        targets: List<String>,
        info: String,
        file: File,
        errorMessage: String?,
        localId: Long = 0,
        replyInfoText: String? = null
    ): Boolean {
        if (targets.isEmpty()) return false
        val filePayload = try {
            Payload.fromFile(file)
        } catch (e: Exception) {
            if (errorMessage != null) listener.onError(errorMessage)
            return false
        }
        if (localId != 0L) outgoingPayloads[filePayload.id] = localId
        if (replyInfoText != null) {
            client.sendPayload(
                targets, Payload.fromBytes(replyInfoText.toByteArray(Charsets.UTF_8))
            ).addOnFailureListener { e -> listener.onError(describe(e)) }
        }
        client.sendPayload(targets, Payload.fromBytes(info.toByteArray(Charsets.UTF_8)))
            .addOnFailureListener { e -> listener.onError(describe(e)) }
        client.sendPayload(targets, filePayload)
            .addOnFailureListener { e -> listener.onError(describe(e)) }
        return true
    }

    private fun stopEverything() {
        handler.removeCallbacks(reconnectRunnable)
        role = Role.NONE
        connectedEndpoints.clear()
        pendingRequestId = null
        pendingNames.clear()
        acceptedEndpointIds.clear()
        pendingRequests.clear()
        activeRequestEndpointId = null
        incomingFilePayloads.clear()
        filePayloadEndpoints.clear()
        pendingFiles.clear()
        pendingQuotes.clear()
        // Transfers that never finished will never report now; their
        // messages simply keep the single "sent" tick.
        outgoingPayloads.clear()
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
        pendingRequestId = null
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

    /** The endpoint name without the host marker, for showing to the user. */
    private fun visibleName(rawName: String): String = rawName.removePrefix(HUB_NAME_PREFIX)

    // ----- Join requests (accept dialog, one at a time) -----

    /** Shows the next waiting join request, if no dialog is open. */
    private fun presentNextRequestIfIdle() {
        if (activeRequestEndpointId != null) return
        while (pendingRequests.isNotEmpty()) {
            val next = pendingRequests.first()
            // A member accepts nobody, and a full host takes nobody new;
            // such waiting requests are simply turned away.
            if (role == Role.SPOKE ||
                (role == Role.HUB && connectedEndpoints.size >= MAX_SPOKES)
            ) {
                pendingRequests.removeFirst()
                client.rejectConnection(next.endpointId)
                pendingNames.remove(next.endpointId)
                continue
            }
            pendingRequests.removeFirst()
            activeRequestEndpointId = next.endpointId
            listener.onConnectionRequest(
                endpointName = pendingNames[next.endpointId] ?: "",
                authDigits = next.authDigits,
                accept = { acceptPendingRequest(next.endpointId) },
                reject = { rejectPendingRequest(next.endpointId) }
            )
            return
        }
    }

    private fun acceptPendingRequest(endpointId: String) {
        if (activeRequestEndpointId == endpointId) activeRequestEndpointId = null
        acceptedEndpointIds.add(endpointId)
        listener.onConnecting(pendingNames[endpointId] ?: "")
        client.acceptConnection(endpointId, payloadCallback)
            .addOnFailureListener { e ->
                acceptedEndpointIds.remove(endpointId)
                if (role == Role.HUB) {
                    // One join failed; the group itself carries on.
                    presentNextRequestIfIdle()
                } else {
                    fail(describe(e))
                }
            }
        // The next waiting request is shown when this one finishes
        // connecting (or fails), so handshakes stay one at a time.
    }

    private fun rejectPendingRequest(endpointId: String) {
        if (activeRequestEndpointId == endpointId) activeRequestEndpointId = null
        client.rejectConnection(endpointId)
        pendingNames.remove(endpointId)
        // Restore the state line for the role we are in now.
        if (role == Role.HUB) {
            listener.onGroupHostUpdate(connectedEndpoints.size)
        } else {
            listener.onSearching()
        }
        presentNextRequestIfIdle()
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Only a phone that is not in a group yet goes looking for one,
            // and only one request at a time (the accept dialog included).
            if (role != Role.NONE || pendingRequestId != null ||
                activeRequestEndpointId != null || pendingRequests.isNotEmpty()
            ) {
                return
            }

            // Only the phone with the "smaller" name sends the request,
            // so the two phones do not request each other at once. The
            // host's marked name always sorts last, so a searching phone
            // always requests the host when it finds one.
            if (localName <= info.endpointName) {
                pendingRequestId = endpointId
                pendingNames[endpointId] = visibleName(info.endpointName)
                client.requestConnection(localName, endpointId, connectionCallback)
                    .addOnFailureListener { e ->
                        pendingRequestId = null
                        pendingNames.remove(endpointId)
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
            if (connectedEndpoints.containsKey(endpointId)) {
                client.rejectConnection(endpointId)
                return
            }
            val busyWithOther = when {
                // The (defensive) case of both phones asking each other at
                // once: the request from the phone we asked is let through,
                // like before.
                endpointId == pendingRequestId -> false
                // A member of a group accepts nobody new.
                role == Role.SPOKE -> true
                // A full group takes nobody new; the joiner is told the
                // connection was rejected.
                role == Role.HUB && connectedEndpoints.size >= MAX_SPOKES -> true
                // A phone that is already asking someone else stays busy.
                role == Role.NONE && pendingRequestId != null -> true
                else -> false
            }
            if (busyWithOther) {
                client.rejectConnection(endpointId)
                return
            }

            pendingNames[endpointId] = visibleName(info.endpointName)
            pendingRequests.addLast(
                PendingRequest(endpointId, info.authenticationDigits)
            )
            presentNextRequestIfIdle()
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val code = result.status.statusCode
            val roleBefore = role
            val wasRequestedByUs = endpointId == pendingRequestId
            if (wasRequestedByUs) pendingRequestId = null
            val wasAcceptedByUs = acceptedEndpointIds.remove(endpointId)
            val name = pendingNames.remove(endpointId) ?: ""

            if (code == ConnectionsStatusCodes.STATUS_OK) {
                // Whoever asked joins as a member; whoever accepted the
                // first request becomes (or stays) the host.
                role = when {
                    roleBefore == Role.HUB -> Role.HUB
                    wasRequestedByUs -> Role.SPOKE
                    wasAcceptedByUs -> Role.HUB
                    else -> Role.SPOKE
                }
                connectedEndpoints[endpointId] = name
                reconnectAttempts = 0
                if (role == Role.HUB) {
                    autoReconnect = false
                    client.stopDiscovery()
                    if (roleBefore != Role.HUB) {
                        // We just became the host: advertise under the
                        // marked host name, so more phones can join.
                        startHubAdvertising()
                    }
                    listener.onGroupHostUpdate(connectedEndpoints.size)
                } else {
                    autoReconnect = true
                    client.stopAdvertising()
                    client.stopDiscovery()
                    listener.onConnected(name)
                }
            } else if (roleBefore == Role.HUB) {
                // One join did not work out; the group carries on.
            } else if (code == ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED) {
                // The other person said no, so retrying makes no sense.
                fail("The connection was rejected.")
            } else if (reconnectAttempts > 0) {
                // A reconnect attempt failed; try again or give up.
                scheduleReconnectOrGiveUp()
            } else {
                fail("Connection failed (code $code).")
            }
            if (wasAcceptedByUs) presentNextRequestIfIdle()
        }

        override fun onDisconnected(endpointId: String) {
            pendingNames.remove(endpointId)
            acceptedEndpointIds.remove(endpointId)
            if (endpointId == pendingRequestId) pendingRequestId = null

            if (!connectedEndpoints.containsKey(endpointId)) return
            connectedEndpoints.remove(endpointId)
            // The phone is gone, so the info about a file it was sending,
            // any quote waiting for that file, and any half-arrived file
            // of it are stale now.
            pendingFiles.remove(endpointId)
            pendingQuotes.remove(endpointId)
            val iterator = filePayloadEndpoints.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value == endpointId) {
                    incomingFilePayloads.remove(entry.key)
                    iterator.remove()
                }
            }
            when (role) {
                Role.HUB -> {
                    // One member left; the group carries on.
                    listener.onGroupHostUpdate(connectedEndpoints.size)
                }
                else -> {
                    if (autoReconnect) {
                        scheduleReconnectOrGiveUp()
                    } else {
                        listener.onDisconnected()
                    }
                }
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            // Ignore anything that does not come from a phone in our group.
            if (!connectedEndpoints.containsKey(endpointId)) return
            when (payload.type) {
                Payload.Type.FILE -> {
                    // Remember the file; it is usable once the transfer
                    // finishes with SUCCESS in onPayloadTransferUpdate.
                    incomingFilePayloads[payload.id] = payload
                    filePayloadEndpoints[payload.id] = endpointId
                }
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    val raw = String(bytes, Charsets.UTF_8)
                    if (raw.startsWith(REPLY_PREFIX)) {
                        // The quote belonging to the voice, photo or
                        // file this phone is about to send. It is held
                        // until that file's transfer finishes; text
                        // messages carry their quote inside themselves,
                        // so this is never one of those. A malformed one
                        // is simply dropped — it is a control text, not
                        // a chat message.
                        parseReplyInfo(raw)?.let {
                            pendingQuotes[endpointId] = it
                        }
                        return
                    }
                    if (raw.startsWith(VOICE_PREFIX)) {
                        // Info about the voice file that follows.
                        parseVoiceInfo(raw)?.let {
                            pendingFiles[endpointId] = it
                        }
                        return
                    }
                    if (raw.startsWith(PHOTO_PREFIX)) {
                        // Info about the photo file that follows.
                        parsePhotoInfo(raw)?.let {
                            pendingFiles[endpointId] = it
                        }
                        return
                    }
                    if (raw.startsWith(FILE_PREFIX)) {
                        // Info about the general file that follows.
                        parseFileInfo(raw)?.let {
                            pendingFiles[endpointId] = it
                        }
                        return
                    }
                    if (raw.startsWith(VIDEO_PREFIX)) {
                        // Info about the video file that follows.
                        parseVideoInfo(raw)?.let {
                            pendingFiles[endpointId] = it
                        }
                        return
                    }
                    val message = unpackMessage(raw)
                    if (message.id != null && !rememberMessageId(message.id)) {
                        return // We already showed this message once.
                    }
                    // A location arrives as an ordinary text payload
                    // whose text is the "#LOC#" control text; it is
                    // recognised here and reported as a location, never
                    // shown as a chat text. One that starts with the
                    // prefix but does not parse is a control text gone
                    // wrong and is dropped silently — but relayed below
                    // like everything else, so the group stays in step.
                    val location = parseLocationText(message.text)
                    when {
                        location != null -> listener.onLocationMessageReceived(
                            location.first, location.second, message.senderName
                        )
                        message.text.startsWith(LOCATION_PREFIX) -> Unit
                        else -> listener.onMessageReceived(
                            message.text, message.senderName,
                            message.quotedSender, message.quotedText
                        )
                    }
                    if (role == Role.HUB) {
                        // The host passes every member's message on to the
                        // rest of the group, word for word (same id, same
                        // sender name), so everyone sees the same chat.
                        val targets = sendTargets(exclude = endpointId)
                        if (targets.isNotEmpty()) {
                            client.sendPayload(
                                targets,
                                Payload.fromBytes(bytes)
                            ).addOnFailureListener { e -> listener.onError(describe(e)) }
                        }
                    }
                }
                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(
            endpointId: String,
            update: PayloadTransferUpdate
        ) {
            if (!connectedEndpoints.containsKey(endpointId)) return
            if (update.status == PayloadTransferUpdate.Status.FAILURE) {
                val wasIncoming = incomingFilePayloads.remove(update.payloadId) != null
                filePayloadEndpoints.remove(update.payloadId)
                // A failed send of ours is forgotten here; its message
                // simply never earns the second tick (the error below
                // is the same feedback as before).
                outgoingPayloads.remove(update.payloadId)
                if (wasIncoming) {
                    // The file is gone, so its info message — and any
                    // quote waiting for it — are stale too.
                    pendingFiles.remove(endpointId)
                    pendingQuotes.remove(endpointId)
                }
                listener.onError("A message could not be transferred.")
            } else if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                // One of our own messages finished transferring to the
                // next phone. In a group send the payload goes to
                // several phones with one payload id, so the FIRST
                // success is the one that counts as delivered; later
                // ones find nothing left in the map.
                outgoingPayloads.remove(update.payloadId)?.let { localId ->
                    listener.onMessageDelivered(localId)
                }
                val filePayload = incomingFilePayloads.remove(update.payloadId)
                filePayloadEndpoints.remove(update.payloadId)
                if (filePayload != null) {
                    // The info message said what kind of file this is. No
                    // info (or a lost one) keeps the old behavior: voice.
                    // A quote that was sent just before the file belongs
                    // to this message and is used up now.
                    val info = pendingFiles.remove(endpointId)
                    val quote = pendingQuotes.remove(endpointId)
                    when (info?.kind ?: IncomingFileKind.VOICE) {
                        IncomingFileKind.VOICE -> {
                            val dest = saveReceivedVoiceFile(filePayload) ?: return
                            val duration = info?.voiceDurationSeconds
                                ?: fileDurationSeconds(dest)
                            listener.onVoiceMessageReceived(
                                dest.absolutePath, duration, info?.senderName,
                                quote?.sender, quote?.text
                            )
                            if (role == Role.HUB) {
                                relayVoiceFile(
                                    dest, duration, info?.senderName, endpointId, quote
                                )
                            }
                        }
                        IncomingFileKind.PHOTO -> {
                            val dest = saveReceivedPhotoFile(filePayload) ?: return
                            listener.onPhotoMessageReceived(
                                dest.absolutePath, info?.senderName,
                                quote?.sender, quote?.text
                            )
                            if (role == Role.HUB) {
                                relayPhotoFile(dest, info?.senderName, endpointId, quote)
                            }
                        }
                        IncomingFileKind.FILE -> {
                            val dest = saveReceivedFile(filePayload, info?.fileName) ?: return
                            listener.onFileMessageReceived(
                                dest.absolutePath, dest.name, dest.length(),
                                info?.senderName, quote?.sender, quote?.text
                            )
                            if (role == Role.HUB) {
                                relayFile(dest, info?.senderName, endpointId, quote)
                            }
                        }
                        IncomingFileKind.VIDEO -> {
                            val dest = saveReceivedVideoFile(filePayload, info?.fileName) ?: return
                            // The video info carries no length, so it
                            // is read from the file we just saved.
                            val duration = fileDurationSeconds(dest)
                            listener.onVideoMessageReceived(
                                dest.absolutePath, duration, info?.senderName,
                                quote?.sender, quote?.text
                            )
                            if (role == Role.HUB) {
                                relayVideoFile(dest, info?.senderName, endpointId, quote)
                            }
                        }
                    }
                }
            }
        }
    }

    // ----- Host relaying of received files -----
    // The host re-sends a fully received file (its own saved copy) to all
    // other members, always with the ORIGINAL sender's name in the info
    // text. Members never pass files on.

    private fun relayVoiceFile(
        file: File,
        durationSeconds: Int,
        senderName: String?,
        exceptEndpointId: String,
        quote: PendingQuote? = null
    ) {
        val targets = sendTargets(exclude = exceptEndpointId)
        if (targets.isEmpty()) return
        val info = if (senderName.isNullOrEmpty()) {
            VOICE_PREFIX + durationSeconds + "#" + file.name
        } else {
            VOICE_PREFIX + durationSeconds + "#" + senderName + "#" + file.name
        }
        sendInfoAndFile(
            targets, info, file, null, 0,
            replyInfo(quote?.sender, quote?.text)
        )
    }

    private fun relayPhotoFile(
        file: File,
        senderName: String?,
        exceptEndpointId: String,
        quote: PendingQuote? = null
    ) {
        val targets = sendTargets(exclude = exceptEndpointId)
        if (targets.isEmpty()) return
        val info = if (senderName.isNullOrEmpty()) {
            PHOTO_PREFIX + file.name
        } else {
            PHOTO_PREFIX + senderName + "#" + file.name
        }
        sendInfoAndFile(
            targets, info, file, null, 0,
            replyInfo(quote?.sender, quote?.text)
        )
    }

    private fun relayFile(
        file: File,
        senderName: String?,
        exceptEndpointId: String,
        quote: PendingQuote? = null
    ) {
        val targets = sendTargets(exclude = exceptEndpointId)
        if (targets.isEmpty()) return
        val info = if (senderName.isNullOrEmpty()) {
            FILE_PREFIX + file.name
        } else {
            FILE_PREFIX + senderName + "#" + file.name
        }
        sendInfoAndFile(
            targets, info, file, null, 0,
            replyInfo(quote?.sender, quote?.text)
        )
    }

    private fun relayVideoFile(
        file: File,
        senderName: String?,
        exceptEndpointId: String,
        quote: PendingQuote? = null
    ) {
        val targets = sendTargets(exclude = exceptEndpointId)
        if (targets.isEmpty()) return
        val info = if (senderName.isNullOrEmpty()) {
            VIDEO_PREFIX + file.name
        } else {
            VIDEO_PREFIX + senderName + "#" + file.name
        }
        sendInfoAndFile(
            targets, info, file, null, 0,
            replyInfo(quote?.sender, quote?.text)
        )
    }

    /**
     * Reads a reply info text: "#RPLY#" + quoted sender + "#" + quoted
     * preview (split at the FIRST '#'; the sender never contains one,
     * because [wireQuoteField] removed it on the sending side). Null
     * when either part is missing or empty — such a text is a control
     * text gone wrong and is dropped, never shown as a chat message.
     */
    private fun parseReplyInfo(raw: String): PendingQuote? {
        val rest = raw.substring(REPLY_PREFIX.length)
        val separator = rest.indexOf('#')
        if (separator <= 0) return null
        val sender = rest.substring(0, separator)
        val preview = rest.substring(separator + 1)
        if (preview.isEmpty()) return null
        return PendingQuote(sender, preview)
    }

    /**
     * Reads a voice info text. New form: "#VOICE#" + seconds + "#" +
     * sender name + "#" + file name. Old form: "#VOICE#" + seconds + "#"
     * + file name. Null when it does not parse at all; the length is
     * then read from the audio file itself instead.
     */
    private fun parseVoiceInfo(raw: String): PendingFileInfo? {
        val parts = raw.substring(VOICE_PREFIX.length).split('#')
        if (parts.size < 2 || parts[0].isEmpty()) return null
        return PendingFileInfo(
            kind = IncomingFileKind.VOICE,
            voiceDurationSeconds = parts[0].toIntOrNull(),
            fileName = null,
            senderName = if (parts.size >= 3) parts[1].takeIf { it.isNotEmpty() } else null
        )
    }

    /**
     * Reads a photo info text. New form: "#PHOTO#" + sender name + "#" +
     * file name. Old form: "#PHOTO#" + file name. We only prepare ".jpg"
     * files, so requiring that ending keeps a normal chat text that
     * happens to start with the prefix from being mistaken for photo info.
     */
    private fun parsePhotoInfo(raw: String): PendingFileInfo? {
        val rest = raw.substring(PHOTO_PREFIX.length)
        val separator = rest.indexOf('#')
        if (separator > 0) {
            val name = rest.substring(0, separator)
            val fileName = rest.substring(separator + 1)
            if (fileName.endsWith(".jpg", ignoreCase = true)) {
                return PendingFileInfo(IncomingFileKind.PHOTO, null, null, name)
            }
            return null
        }
        if (rest.isNotEmpty() && rest.endsWith(".jpg", ignoreCase = true)) {
            return PendingFileInfo(IncomingFileKind.PHOTO, null, null, null)
        }
        return null
    }

    /**
     * Reads a file info text. New form: "#FILE#" + sender name + "#" +
     * file name (split at the FIRST '#', because a file name may itself
     * contain one). Old form: "#FILE#" + file name. The name is cleaned
     * with [sanitizeFileName]; null when nothing sane is left, so a
     * normal chat text that happens to start with the prefix is never
     * mistaken for file info.
     */
    private fun parseFileInfo(raw: String): PendingFileInfo? {
        val rest = raw.substring(FILE_PREFIX.length)
        val separator = rest.indexOf('#')
        if (separator > 0) {
            val senderName = rest.substring(0, separator)
            val fileName = sanitizeFileName(rest.substring(separator + 1))
            if (fileName.isNotEmpty()) {
                return PendingFileInfo(IncomingFileKind.FILE, null, fileName, senderName)
            }
            return null
        }
        val fileName = sanitizeFileName(rest)
        if (fileName.isEmpty()) return null
        return PendingFileInfo(IncomingFileKind.FILE, null, fileName, null)
    }

    /**
     * Reads a video info text. New form: "#VIDEO#" + sender name + "#" +
     * file name (split at the FIRST '#', like the file info, because a
     * file name may itself contain one). Old form: "#VIDEO#" + file
     * name. The name is cleaned with [sanitizeFileName]; null when
     * nothing sane is left, so a normal chat text that happens to start
     * with the prefix is never mistaken for video info.
     */
    private fun parseVideoInfo(raw: String): PendingFileInfo? {
        val rest = raw.substring(VIDEO_PREFIX.length)
        val separator = rest.indexOf('#')
        if (separator > 0) {
            val senderName = rest.substring(0, separator)
            val fileName = sanitizeFileName(rest.substring(separator + 1))
            if (fileName.isNotEmpty()) {
                return PendingFileInfo(IncomingFileKind.VIDEO, null, fileName, senderName)
            }
            return null
        }
        val fileName = sanitizeFileName(rest)
        if (fileName.isEmpty()) return null
        return PendingFileInfo(IncomingFileKind.VIDEO, null, fileName, null)
    }

    /**
     * Copies a fully received general file into our own storage, keeping
     * its original name. When a file with that name is already there, a
     * suffix like " (1)" is added, so an older file is never overwritten.
     */
    private fun saveReceivedFile(payload: Payload, wantedName: String?): File? {
        val filePart = payload.asFile() ?: return null
        val fileDir = File(appContext.filesDir, FILE_DIR_NAME)
        if (!fileDir.exists()) fileDir.mkdirs()
        val dest = uniqueFile(fileDir, wantedName ?: "file_${System.currentTimeMillis()}")
        if (!copyPayloadFile(filePart, dest) || !dest.exists() || dest.length() == 0L) {
            dest.delete()
            listener.onError("A file could not be saved.")
            return null
        }
        return dest
    }

    /** Finds a free name in [dir]: the name itself, or "name (1)", ... */
    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($counter)$extension")
            counter++
        }
        return candidate
    }

    /** Copies a fully received photo into our own storage. */
    private fun saveReceivedPhotoFile(payload: Payload): File? {
        val filePart = payload.asFile() ?: return null
        val photoDir = File(appContext.filesDir, PHOTO_DIR_NAME)
        if (!photoDir.exists()) photoDir.mkdirs()
        val dest = File(photoDir, "photo_${System.currentTimeMillis()}.jpg")
        if (!copyPayloadFile(filePart, dest) || !dest.exists() || dest.length() == 0L) {
            dest.delete()
            listener.onError("A photo could not be saved.")
            return null
        }
        return dest
    }

    /**
     * Copies a fully received video into our own storage, keeping its
     * original name like a general file does (a suffix like " (1)" is
     * added when the name is taken, so an older video is never lost).
     */
    private fun saveReceivedVideoFile(payload: Payload, wantedName: String?): File? {
        val filePart = payload.asFile() ?: return null
        val videoDir = File(appContext.filesDir, VIDEO_DIR_NAME)
        if (!videoDir.exists()) videoDir.mkdirs()
        val dest = uniqueFile(videoDir, wantedName ?: "video_${System.currentTimeMillis()}.mp4")
        if (!copyPayloadFile(filePart, dest) || !dest.exists() || dest.length() == 0L) {
            dest.delete()
            listener.onError("A video could not be saved.")
            return null
        }
        return dest
    }

    /** Copies a fully received voice file into our own storage. */
    private fun saveReceivedVoiceFile(payload: Payload): File? {
        val filePart = payload.asFile() ?: return null
        val voiceDir = File(appContext.filesDir, VOICE_DIR_NAME)
        if (!voiceDir.exists()) voiceDir.mkdirs()
        val dest = File(voiceDir, "voice_${System.currentTimeMillis()}.m4a")
        if (!copyPayloadFile(filePart, dest) || !dest.exists() || dest.length() == 0L) {
            dest.delete()
            listener.onError("A voice message could not be saved.")
            return null
        }
        return dest
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

    /** A received text message, split into its parts. */
    private data class UnpackedMessage(
        val id: String?,
        val senderName: String?,
        val text: String,
        val quotedSender: String? = null,
        val quotedText: String? = null
    )

    /**
     * Splits a received payload into its message id, the sender's name,
     * its quote (when it is a reply) and the real text. New messages
     * look like "#ID#" + a 36-char UUID + "#FROM#" + sender name + "#" +
     * the text; a reply inserts "#QNAME#" + quoted sender + "#QTEXT#" +
     * quoted preview + "#" between the name and the text. The fields
     * are read in that fixed order and each ends at the next '#', which
     * is unambiguous because the sending side strips '#' out of every
     * field. Older messages have no "#FROM#" part (the name is then
     * unknown) and no quote part; when the quote part does not parse
     * completely, the message is shown without a quote and the text is
     * kept whole. A plain payload with no id at all (from a very old app
     * version) is shown as-is.
     */
    private fun unpackMessage(raw: String): UnpackedMessage {
        val idStart = MESSAGE_ID_PREFIX.length
        val idEnd = idStart + MESSAGE_ID_LENGTH
        if (raw.length >= idEnd && raw.startsWith(MESSAGE_ID_PREFIX)) {
            val id = raw.substring(idStart, idEnd)
            // A real UUID has dashes at fixed places; this check keeps
            // normal text that happens to start with the prefix safe.
            if (id[8] == '-' && id[13] == '-' && id[18] == '-' && id[23] == '-') {
                var rest = raw.substring(idEnd)
                var senderName: String? = null
                if (rest.startsWith(FROM_INFIX)) {
                    val afterFrom = rest.substring(FROM_INFIX.length)
                    val separator = afterFrom.indexOf('#')
                    if (separator >= 0) {
                        senderName = afterFrom.substring(0, separator)
                            .takeIf { it.isNotEmpty() }
                        rest = afterFrom.substring(separator + 1)
                    }
                    // No closing '#' at all: malformed; keep rest as-is.
                }
                var quotedSender: String? = null
                var quotedText: String? = null
                if (rest.startsWith(QUOTE_NAME_INFIX)) {
                    val afterName = rest.substring(QUOTE_NAME_INFIX.length)
                    val quoteTextIndex = afterName.indexOf(QUOTE_TEXT_INFIX)
                    if (quoteTextIndex > 0) {
                        val afterQuoteText = afterName.substring(
                            quoteTextIndex + QUOTE_TEXT_INFIX.length
                        )
                        val end = afterQuoteText.indexOf('#')
                        if (end > 0) {
                            quotedSender = afterName.substring(0, quoteTextIndex)
                            quotedText = afterQuoteText.substring(0, end)
                            rest = afterQuoteText.substring(end + 1)
                        }
                    }
                    // Incomplete quote section: not a quote; the whole
                    // remaining text is kept, as older versions show it.
                }
                return UnpackedMessage(id, senderName, rest, quotedSender, quotedText)
            }
        }
        return UnpackedMessage(null, null, raw)
    }

    /**
     * Reads a location control text: "#LOC#" + latitude + "," +
     * longitude. Returns the position only when the text is exactly
     * that — two plain numbers, a latitude between -90 and 90 and a
     * longitude between -180 and 180 — and null in every other case,
     * so a typed chat text can never be mistaken for a position by
     * accident, and a damaged control text is dropped, never shown.
     */
    private fun parseLocationText(text: String): Pair<Double, Double>? {
        if (!text.startsWith(LOCATION_PREFIX)) return null
        val parts = text.substring(LOCATION_PREFIX.length).split(',')
        if (parts.size != 2) return null
        val latitude = parts[0].trim().toDoubleOrNull() ?: return null
        val longitude = parts[1].trim().toDoubleOrNull() ?: return null
        if (latitude.isNaN() || latitude.isInfinite() ||
            longitude.isNaN() || longitude.isInfinite()
        ) {
            return null
        }
        if (latitude < -90.0 || latitude > 90.0) return null
        if (longitude < -180.0 || longitude > 180.0) return null
        return latitude to longitude
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
        private const val FROM_INFIX = "#FROM#"
        private const val MESSAGE_ID_LENGTH = 36 // length of a UUID string
        private const val MAX_SEEN_MESSAGE_IDS = 200
        private const val MAX_SENDER_NAME_LENGTH = 40
        // Reply/quote sections. In a text payload: "#QNAME#" + quoted
        // sender + "#QTEXT#" + quoted preview + "#", right before the
        // text. For voice, photo and file sends, one "#RPLY#" + quoted
        // sender + "#" + quoted preview text goes just before the usual
        // info text instead.
        private const val QUOTE_NAME_INFIX = "#QNAME#"
        private const val QUOTE_TEXT_INFIX = "#QTEXT#"
        private const val REPLY_PREFIX = "#RPLY#"
        private const val MAX_QUOTED_TEXT_LENGTH = 80
        private const val VOICE_PREFIX = "#VOICE#"
        private const val VOICE_DIR_NAME = "voice"
        private const val PHOTO_PREFIX = "#PHOTO#"
        private const val PHOTO_DIR_NAME = "photos"
        private const val FILE_PREFIX = "#FILE#"
        private const val FILE_DIR_NAME = "files"
        private const val VIDEO_PREFIX = "#VIDEO#"
        private const val VIDEO_DIR_NAME = "videos"
        // A shared position, packed inside an ordinary text payload:
        // "#LOC#" + latitude + "," + longitude (see sendLocationMessage).
        private const val LOCATION_PREFIX = "#LOC#"
        private const val MAX_FILE_NAME_LENGTH = 128

        /** How many members ("spokes") the host can have: a group is at
         *  most the host plus [MAX_SPOKES] phones. */
        private const val MAX_SPOKES = 5

        /**
         * Written in front of the host's advertised endpoint name. The
         * character sorts after every normal letter or digit, so in the
         * "smaller name requests" comparison a searching phone always
         * requests the host, and the host stays the host. It is stripped
         * again before any name is shown to the user.
         */
        private const val HUB_NAME_PREFIX = "\uFFFF"

        /**
         * Makes a file name safe to store: any folder parts are dropped
         * (both "/" and "\" count), control characters are removed, and
         * the result is capped in length. "" when nothing usable remains.
         */
        fun sanitizeFileName(raw: String): String {
            var name = raw.trim().replace('\\', '/').substringAfterLast('/')
            name = name.filter { it >= ' ' }
            name = name.trim().take(MAX_FILE_NAME_LENGTH).trim()
            if (name == "." || name == "..") return ""
            return name
        }
        private const val PREFS_NAME = "offline_chat_prefs"
        private const val KEY_DISPLAY_NAME = "display_name"
    }
}
