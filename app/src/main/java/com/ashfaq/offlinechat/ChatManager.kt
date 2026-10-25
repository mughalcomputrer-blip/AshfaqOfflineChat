package com.ashfaq.offlinechat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
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
import java.io.InputStream
import java.io.OutputStream
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
 *
 * Reactions: every message has an id the other phones know it by —
 * a text (or location) by the "#ID#" uuid inside its own payload, a
 * voice, photo, video or file by a uuid announced in a tiny "#MID#"
 * text just before its info text and file (paired per endpoint
 * exactly like the "#RPLY#" quote). A reaction rides an ordinary
 * text payload whose text is "#REACT#" + target id + "#" + emoji, so
 * it gets the reactor's name from the "#FROM#" section, the usual
 * dedupe, and the hub's word-for-word relay for free; it is taken
 * out of the text stream here, like a location, and never shown as
 * a chat text. The hub also applies a reaction it relays, so its
 * own screen stays in step with the group.
 *
 * Friends: every phone a connection is made with is remembered in
 * [FriendsStore] under its base name ([baseNameOf]), whichever side
 * asked and whichever side accepted. [findFriend] then reconnects
 * with one tap: it runs the ordinary search, and the first phone
 * found whose base name is the friend's is asked to connect at
 * once — the "smaller name asks" rule does not apply, because the
 * user picked this exact person. Other phones found meanwhile are
 * left alone (a normal search would have asked one of them). The
 * search gives up after a while and reports [Listener.onFriendNotFound].
 *
 * Walkie-talkie: a live push-to-talk voice (see [startTalk]). A tiny
 * "#TALK#START" text goes out first through the ordinary text path —
 * so it carries the talker's name and the hub relays it to the whole
 * group like any text — and "#TALK#END" follows when the talk stops.
 * The audio itself rides a STREAM payload: the microphone is copied
 * into a pipe whose other end Nearby sends (one pipe per target,
 * because a stream payload belongs to one pipe). On this side a pump
 * thread plays the incoming stream through an AudioTrack; when this
 * phone is the hub, the pump also copies every chunk into pipes
 * whose streams go to the other members, so the whole group hears
 * the talk. It is half-duplex, like a real walkie-talkie: a stream
 * that arrives while we are talking ourselves, or while another
 * talk is already playing, is drained and dropped, never mixed in.
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
         * [wireId] is the id other phones know this message by (its
         * "#ID#" uuid), which reactions refer to; null only for
         * payloads from very old app versions that carried no id.
         */
        fun onMessageReceived(
            text: String,
            senderName: String?,
            quotedSender: String?,
            quotedText: String?,
            wireId: String?
        )

        /**
         * A shared position arrived. [senderName] is who sent it
         * (null if unknown), like for a text message. [wireId] is
         * the id other phones know the position's message by, like
         * for a text message.
         */
        fun onLocationMessageReceived(
            latitude: Double,
            longitude: Double,
            senderName: String?,
            wireId: String?
        ) {}

        /**
         * Someone reacted to a message: [emoji] (one of
         * [REACTION_EMOJIS]) on the message known by [targetWireId],
         * from the person named [reactorName]. The reaction toggles:
         * the same person sending the same emoji again takes it back,
         * and a different emoji replaces their earlier one. The name
         * is the reactor's stamped "#FROM#" name; it is null only
         * when the payload carried no name at all, in which case the
         * reaction cannot be attributed and is best ignored.
         */
        fun onReactionReceived(
            targetWireId: String,
            emoji: String,
            reactorName: String?
        ) {}

        /**
         * A walkie-talkie talk started: the person named
         * [senderName] (null when the name is unknown) is holding
         * their talk button, and their voice is playing — or about
         * to play — on this phone. Reported once per talk, from the
         * "#TALK#START" text that precedes the audio stream (or from
         * the stream itself, when no such text was seen for it).
         */
        fun onTalkStarted(senderName: String?) {}

        /**
         * The current walkie-talkie talk ended and the channel is
         * free again. Reported once per talk.
         */
        fun onTalkEnded() {}

        /**
         * Our own walkie-talkie talk ended by itself — the time cap
         * was reached, every pipe died, or the microphone failed —
         * rather than through [stopTalk]. The screen uses this to
         * reset its talk button while the user may still be holding
         * it down.
         */
        fun onOwnTalkEnded() {}

        fun onError(message: String)

        /** The connection dropped and an automatic retry is about to happen. */
        fun onReconnecting(attempt: Int, maxAttempts: Int) {}

        /**
         * A one-tap friend search ([findFriend]) ran out of time
         * without finding the friend named [friendName]; the search
         * has stopped. (This phone still advertises, so the friend
         * can still find and ask us instead.)
         */
        fun onFriendNotFound(friendName: String) {}

        /**
         * One of our own sent messages finished transferring to the
         * next phone (its payload transfer reported SUCCESS). [localId]
         * is the id the message was sent with; 0 is never reported.
         * This is local bookkeeping only — nothing extra goes on the wire.
         */
        fun onMessageDelivered(localId: Long) {}

        /**
         * A voice message arrived; [filePath] points to our own copy
         * of it. [wireId] is the id the sender announced for it in a
         * "#MID#" text (null when the sender's app version announced
         * none), which reactions refer to.
         */
        fun onVoiceMessageReceived(
            filePath: String,
            durationSeconds: Int,
            senderName: String?,
            quotedSender: String?,
            quotedText: String?,
            wireId: String?
        ) {}

        /** A photo arrived; [filePath] points to our own copy of it.
         *  [wireId] as for a voice message. */
        fun onPhotoMessageReceived(
            filePath: String,
            senderName: String?,
            quotedSender: String?,
            quotedText: String?,
            wireId: String?
        ) {}

        /** A video arrived; [filePath] points to our own copy of it.
         *  [wireId] as for a voice message. */
        fun onVideoMessageReceived(
            filePath: String,
            durationSeconds: Int,
            senderName: String?,
            quotedSender: String?,
            quotedText: String?,
            wireId: String?
        ) {}

        /**
         * A file arrived; [filePath] points to our own copy of it, saved
         * under its original name ([fileName]), [fileSizeBytes] big.
         * [wireId] as for a voice message.
         */
        fun onFileMessageReceived(
            filePath: String,
            fileName: String,
            fileSizeBytes: Long,
            senderName: String?,
            quotedSender: String?,
            quotedText: String?,
            wireId: String?
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
     * the field separator of the payload formats. Also used by the
     * screen as the reactor name for this phone's own reactions, so a
     * reaction means the same person on every phone.
     */
    fun wireSenderName(): String =
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

    // One-tap friend search state (see findFriend): the base name of
    // the friend being looked for, or null when no friend search is
    // running. The timeout ends a search that never finds them.
    private var pendingFriendName: String? = null
    private val friendSearchTimeout = Runnable { onFriendSearchTimedOut() }

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

    // The id a phone announced (in a small "#MID#" text) for the
    // voice, photo, video or file it is about to send, so reactions
    // can refer to that message later. Kept per sending endpoint and
    // cleaned up exactly like [pendingQuotes]: used when the file's
    // transfer finishes, dropped with the file info when the transfer
    // fails or the phone leaves. A file that arrives with no "#MID#"
    // (an older app version) simply has no id here.
    private val pendingWireIds = mutableMapOf<String, String>()

    // ----- Walkie-talkie state (see the class comment) -----

    /** Guards the two pipe lists below: the capture and pump threads
     *  write to them while the main thread opens and closes them. */
    private val talkLock = Object()

    /** True while our own talk button is held and we are sending. */
    @Volatile
    private var talkCapturing = false
    private var talkCaptureThread: Thread? = null

    /** The pipes our microphone is copied into: endpoint id -> the
     *  writable end of that target's stream pipe. */
    private val talkOutStreams = mutableListOf<Pair<String, OutputStream>>()

    /** The talk currently playing on this phone, if any. */
    @Volatile
    private var incomingTalk: IncomingTalk? = null

    /** The writable ends of the pipes carrying the current incoming
     *  talk on to the other members (hub only; empty for a member). */
    private val talkRelayStreams = mutableListOf<OutputStream>()

    /** The announced current talker, from their "#TALK#START" text:
     *  the endpoint that text came from (the hub's, for a relayed
     *  talk) and the talker's display name. Both are null while the
     *  channel is free. The name is also what an incoming stream is
     *  paired with, because the stream itself carries no name. */
    private var peerTalkEndpointId: String? = null
    private var peerTalkName: String? = null

    /** One incoming talk being played: where its stream came from,
     *  the stream itself, and who is talking (when known). */
    private class IncomingTalk(
        val endpointId: String,
        val input: InputStream,
        val name: String?
    )

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

    /**
     * Starts a one-tap search for the friend with this base name (see
     * [baseNameOf] and the class comment). It is the ordinary [start]
     * search underneath: the first phone found whose base name
     * matches is asked to connect at once, and every other phone
     * found meanwhile is left alone. Does nothing while connected.
     *
     * The pending name is set only after start() returns, because
     * start() tidies up leftover state through [stopEverything] —
     * which ends any friend search, this one included. (That is also
     * how a plain Find press, which calls start() directly, cancels
     * a friend search.) A phone that is hosting a group (even an
     * empty one) never discovers, so there the search can only be
     * answered the other way round: the friend finds our advertised
     * host name and asks us.
     */
    fun findFriend(baseName: String) {
        val name = baseName.trim()
        if (name.isEmpty() || isConnected()) return
        start()
        pendingFriendName = name
        handler.removeCallbacks(friendSearchTimeout)
        handler.postDelayed(friendSearchTimeout, FRIEND_SEARCH_TIMEOUT_MS)
    }

    /** A friend search that never found its friend gives up here. */
    private fun onFriendSearchTimedOut() {
        val name = pendingFriendName ?: return
        pendingFriendName = null
        client.stopDiscovery()
        listener.onFriendNotFound(name)
    }

    /** Ends any running friend search; the search's timeout too. */
    private fun clearFriendSearch() {
        pendingFriendName = null
        handler.removeCallbacks(friendSearchTimeout)
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
     * [wireId] is the id the payload carries (a fresh one is made when
     * null); the caller passes one when it needs to know the id itself,
     * so the chat row it shows can carry the same id reactions use.
     */
    fun sendMessage(
        text: String,
        localId: Long = 0,
        quotedSender: String? = null,
        quotedText: String? = null,
        wireId: String? = null
    ): Boolean {
        val targets = sendTargets()
        if (targets.isEmpty()) return false
        // Every message carries its own random id, so the other phones can
        // spot and drop the same message if it is ever delivered twice,
        // plus the sender's name, so group members can see who wrote it.
        // Old format "#ID#" + uuid + text still works on the other side.
        val id = wireId ?: UUID.randomUUID().toString()
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
        localId: Long = 0,
        wireId: String? = null
    ): Boolean {
        val text = LOCATION_PREFIX +
            String.format(Locale.US, "%.6f,%.6f", latitude, longitude)
        return sendMessage(text, localId, wireId = wireId)
    }

    /**
     * Reacts to a message: [emoji] (one of [REACTION_EMOJIS]) on the
     * message the other phones know by [targetWireId]. The reaction is
     * packed into a control text ("#REACT#" + target id + "#" + emoji)
     * and sent through the ordinary text path ([sendMessage]), so it
     * carries this phone's name in the "#FROM#" section (that name is
     * who reacted), gets the usual dedupe, and the hub relays it word
     * for word to the rest of the group; the receiving side recognises
     * the control text in [parseReactionText] and never shows it as a
     * chat text. Reactions toggle: the same person sending the same
     * emoji for the same message again takes their reaction back.
     */
    fun sendReaction(targetWireId: String, emoji: String): Boolean {
        if (targetWireId.isEmpty() || emoji !in REACTION_EMOJIS) return false
        return sendMessage(REACT_PREFIX + targetWireId + "#" + emoji)
    }

    // ----- Walkie-talkie (live push-to-talk voice) -----

    /** True while someone else's talk is on the channel: announced by
     *  their "#TALK#START" text, or already playing here. */
    fun isChannelBusy(): Boolean =
        incomingTalk != null || peerTalkEndpointId != null

    /** The announced current talker's name, or null when the
     *  channel is free (or the talker's name is unknown). */
    fun currentTalkerName(): String? = peerTalkName

    /**
     * Starts a live talk to everyone we are connected with (see the
     * class comment). The "#TALK#START" text goes first, then one
     * stream payload per target, then the microphone starts copying
     * into every target's pipe on its own thread.
     *
     * Returns false — and sends nothing — when there is no
     * connection, when the channel is busy (a talk is playing or
     * announced), when a previous talk is still winding down, or
     * when the microphone cannot be had (busy, or no permission).
     */
    fun startTalk(): Boolean {
        if (talkCapturing) return false
        if (talkCaptureThread?.isAlive == true) return false
        if (isChannelBusy()) return false
        val targets = sendTargets()
        if (targets.isEmpty()) return false

        // The microphone first: a talk that cannot record must not
        // even announce itself.
        val minBuffer = AudioRecord.getMinBufferSize(
            TALK_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(TALK_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, TALK_CHUNK_BYTES * 2))
                .build()
        } catch (e: Exception) {
            null
        }
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            try {
                recorder?.release()
            } catch (ignored: Exception) {
                // Nothing to release then.
            }
            return false
        }

        // Announce, then open one stream pipe per target: a stream
        // payload carries one pipe, so every phone gets its own.
        sendMessage(TALK_START_TEXT)
        val outs = mutableListOf<Pair<String, OutputStream>>()
        for (target in targets) {
            try {
                val pipe = ParcelFileDescriptor.createPipe()
                client.sendPayload(target, Payload.fromStream(pipe[0]))
                    .addOnFailureListener {
                        // A target that cannot take the stream is
                        // dropped when its pipe fails on write.
                    }
                outs.add(
                    target to ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
                )
            } catch (e: Exception) {
                // This target simply does not hear the talk.
            }
        }
        if (outs.isEmpty()) {
            try {
                recorder.release()
            } catch (ignored: Exception) {
                // Recorder already gone.
            }
            sendMessage(TALK_END_TEXT)
            return false
        }
        synchronized(talkLock) {
            talkOutStreams.clear()
            talkOutStreams.addAll(outs)
        }
        talkCapturing = true
        talkCaptureThread = Thread { runTalkCapture(recorder) }.apply {
            name = "walkie-talk-capture"
            isDaemon = true
            start()
        }
        return true
    }

    /**
     * Ends our own talk (the talk button was released). Closing the
     * pipes is what ends the stream on the other phones — they read
     * to the end of it and stop — so it happens at once; the capture
     * thread notices the cleared flag and releases the microphone on
     * its own. The "#TALK#END" text follows, for any phone whose
     * stream never arrived.
     */
    fun stopTalk() {
        if (!talkCapturing && talkCaptureThread?.isAlive != true) return
        talkCapturing = false
        closeTalkOutStreams()
        sendMessage(TALK_END_TEXT)
    }

    /**
     * The capture thread: copies the microphone into every target's
     * pipe, chunk by chunk, until [stopTalk] clears the flag, the
     * time cap is reached, or every pipe has died. A natural end
     * (anything but [stopTalk], which clears the flag first) sends
     * the closing text and reports [Listener.onOwnTalkEnded].
     */
    private fun runTalkCapture(recorder: AudioRecord) {
        val buffer = ByteArray(TALK_CHUNK_BYTES)
        val startNanos = System.nanoTime()
        try {
            recorder.startRecording()
            while (talkCapturing) {
                if (System.nanoTime() - startNanos > TALK_MAX_NANOS) break
                val read = try {
                    recorder.read(buffer, 0, buffer.size)
                } catch (e: Exception) {
                    break
                }
                if (read < 0) break
                if (read == 0) continue
                val chunk = buffer.copyOf(read)
                // Write outside the lock (on a snapshot): a pipe
                // write can block while its stream is stalled, and
                // stopTalk() — on the main thread — must never wait
                // behind one.
                val snapshot = synchronized(talkLock) {
                    talkOutStreams.map { it.second }
                }
                val dead = mutableListOf<OutputStream>()
                for (out in snapshot) {
                    try {
                        out.write(chunk)
                        out.flush()
                    } catch (e: Exception) {
                        dead.add(out)
                    }
                }
                if (dead.isNotEmpty()) {
                    synchronized(talkLock) {
                        talkOutStreams.removeAll { it.second in dead }
                    }
                    dead.forEach { closeQuietly(it) }
                }
                val anyLeft = synchronized(talkLock) { talkOutStreams.isNotEmpty() }
                if (!anyLeft) break
            }
        } catch (e: Exception) {
            // Microphone trouble ends the talk like a release does.
        } finally {
            try {
                recorder.stop()
            } catch (ignored: Exception) {
                // Recorder was never started, or already stopped.
            }
            try {
                recorder.release()
            } catch (ignored: Exception) {
                // Recorder already gone.
            }
            val endedOnItsOwn = talkCapturing
            talkCapturing = false
            closeTalkOutStreams()
            if (endedOnItsOwn) {
                sendMessage(TALK_END_TEXT)
                handler.post { listener.onOwnTalkEnded() }
            }
        }
    }

    /** Closes every outgoing talk pipe (see [stopTalk]). */
    private fun closeTalkOutStreams() {
        val outs: List<OutputStream>
        synchronized(talkLock) {
            outs = talkOutStreams.map { it.second }
            talkOutStreams.clear()
        }
        outs.forEach { closeQuietly(it) }
    }

    /** Closes one target's outgoing talk pipe (it left the group). */
    private fun closeTalkOutStream(endpointId: String) {
        var out: OutputStream? = null
        synchronized(talkLock) {
            val iterator = talkOutStreams.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.first == endpointId) {
                    out = entry.second
                    iterator.remove()
                }
            }
        }
        out?.let { closeQuietly(it) }
    }

    /**
     * Stops our own talk without the closing text (the connection
     * itself is going away, so nobody is listening any more). The
     * capture thread winds down on its own; see [runTalkCapture].
     */
    private fun stopTalkInternal() {
        talkCapturing = false
        closeTalkOutStreams()
    }

    private fun closeQuietly(out: OutputStream) {
        try {
            out.close()
        } catch (ignored: Exception) {
            // Already closed, or broken: nothing more to do.
        }
    }

    /**
     * A "#TALK#START" text arrived: a talk is announced. The first
     * announcer holds the channel; a second one (two people pressed
     * at once) changes nothing here — its stream is drained when it
     * arrives, and its "#TALK#END" will not clear the first talk.
     */
    private fun onTalkControlStart(endpointId: String, senderName: String?) {
        if (peerTalkEndpointId != null) return
        peerTalkEndpointId = endpointId
        peerTalkName = senderName
        listener.onTalkStarted(senderName)
    }

    /**
     * A "#TALK#END" text arrived. When it belongs to the talk that
     * is playing, the stream is cut short (the pump's clean-up then
     * ends the announced talk as it unwinds); when the stream never
     * played here (it was drained, or never came), the announced
     * talk simply ends. Another phone's end text changes nothing.
     */
    private fun onTalkControlEnd(endpointId: String) {
        if (peerTalkEndpointId != endpointId) return
        val session = incomingTalk
        if (session != null && session.endpointId == endpointId) {
            try {
                session.input.close()
            } catch (ignored: Exception) {
                // The pump notices either way.
            }
        } else {
            peerTalkEndpointId = null
            peerTalkName = null
            listener.onTalkEnded()
        }
    }

    /**
     * A stream payload arrived: someone is talking to us (or, on
     * the hub, a member is talking and the group must hear it too).
     * While we are talking ourselves, or while another talk is
     * already playing, the stream is drained and dropped instead —
     * half-duplex, and draining keeps the sender's transfer
     * finishing cleanly.
     */
    private fun onTalkStreamReceived(endpointId: String, payload: Payload) {
        val input = try {
            payload.asStream()?.asInputStream()
        } catch (e: Exception) {
            null
        } ?: return
        if (talkCapturing || incomingTalk != null) {
            drainTalkStream(input)
            return
        }
        val name = peerTalkName
            ?: connectedEndpoints[endpointId]
                ?.let { baseNameOf(it) }
                ?.takeIf { it.isNotEmpty() }
        val session = IncomingTalk(endpointId, input, name)
        incomingTalk = session
        if (peerTalkEndpointId == null) {
            // No "#TALK#START" was seen for this talk (it may have
            // raced the stream): announce it from the stream itself.
            peerTalkEndpointId = endpointId
            peerTalkName = name
            listener.onTalkStarted(name)
        }
        if (role == Role.HUB) {
            openTalkRelays(endpointId)
        }
        Thread { runTalkPump(session) }.apply {
            name = "walkie-talk-pump"
            isDaemon = true
            start()
        }
    }

    /** Opens the hub's onward streams for a talk that just started:
     *  one fresh pipe per other member, fed by the pump. */
    private fun openTalkRelays(exceptEndpointId: String) {
        val relays = mutableListOf<OutputStream>()
        for (target in sendTargets(exclude = exceptEndpointId)) {
            try {
                val pipe = ParcelFileDescriptor.createPipe()
                client.sendPayload(target, Payload.fromStream(pipe[0]))
                    .addOnFailureListener {
                        // A member that cannot take the stream is
                        // dropped when its pipe fails on write.
                    }
                relays.add(ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]))
            } catch (e: Exception) {
                // This member simply does not hear the talk.
            }
        }
        synchronized(talkLock) {
            talkRelayStreams.clear()
            talkRelayStreams.addAll(relays)
        }
    }

    /** Closes the hub's onward pipes: their streams end, so the
     *  members' playback stops, exactly as the talker's own did. */
    private fun closeTalkRelays() {
        val outs: List<OutputStream>
        synchronized(talkLock) {
            outs = talkRelayStreams.toList()
            talkRelayStreams.clear()
        }
        outs.forEach { closeQuietly(it) }
    }

    /** Copies one chunk of the playing talk into every relay pipe;
     *  a pipe that fails is dropped and closed. Like the capture
     *  loop, the writes happen outside the lock, on a snapshot. */
    private fun relayTalkChunk(buffer: ByteArray, length: Int) {
        val chunk = buffer.copyOf(length)
        val snapshot = synchronized(talkLock) { talkRelayStreams.toList() }
        val dead = mutableListOf<OutputStream>()
        for (out in snapshot) {
            try {
                out.write(chunk)
                out.flush()
            } catch (e: Exception) {
                dead.add(out)
            }
        }
        if (dead.isNotEmpty()) {
            synchronized(talkLock) { talkRelayStreams.removeAll(dead) }
            dead.forEach { closeQuietly(it) }
        }
    }

    /**
     * The pump thread of an incoming talk: reads the stream chunk
     * by chunk, plays each chunk through the speaker, and (on the
     * hub) copies it into the relay pipes. Reading is what paces
     * playback, so the voice is heard in near-real time. When the
     * stream ends — the talker let go, or the stream was cut by a
     * "#TALK#END" or a disconnect — the player is released, the
     * relay pipes are closed (ending the members' streams), and the
     * announced talk is ended.
     */
    private fun runTalkPump(session: IncomingTalk) {
        val minBuffer = AudioTrack.getMinBufferSize(
            TALK_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(TALK_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, TALK_CHUNK_BYTES * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            null // No player: the stream is still drained (and relayed).
        }
        try {
            try {
                track?.play()
            } catch (ignored: Exception) {
                // A player that will not start just stays silent.
            }
            val buffer = ByteArray(TALK_CHUNK_BYTES)
            while (true) {
                val read = try {
                    session.input.read(buffer)
                } catch (e: Exception) {
                    -1
                }
                if (read < 0) break
                if (read == 0) continue
                if (track != null) {
                    try {
                        track.write(buffer, 0, read)
                    } catch (ignored: Exception) {
                        // A write that fails must not stop the stream.
                    }
                }
                if (role == Role.HUB) {
                    relayTalkChunk(buffer, read)
                }
            }
        } finally {
            try {
                track?.stop()
            } catch (ignored: Exception) {
                // Player was never started, or already stopped.
            }
            try {
                track?.release()
            } catch (ignored: Exception) {
                // Player already gone.
            }
            try {
                session.input.close()
            } catch (ignored: Exception) {
                // Stream already closed.
            }
            closeTalkRelays()
            if (incomingTalk === session) {
                incomingTalk = null
                if (peerTalkEndpointId == session.endpointId) {
                    peerTalkEndpointId = null
                    peerTalkName = null
                    handler.post { listener.onTalkEnded() }
                }
            }
        }
    }

    /**
     * Reads a stream we are not going to play (see
     * [onTalkStreamReceived]) to its end and throws the audio away.
     */
    private fun drainTalkStream(input: InputStream) {
        Thread {
            try {
                val buffer = ByteArray(TALK_CHUNK_BYTES)
                while (input.read(buffer) >= 0) {
                    // Dropped on purpose: the channel was busy.
                }
            } catch (ignored: Exception) {
                // A broken stream drains just as completely.
            } finally {
                try {
                    input.close()
                } catch (ignored: Exception) {
                    // Stream already closed.
                }
            }
        }.apply {
            name = "walkie-talk-drain"
            isDaemon = true
            start()
        }
    }

    /**
     * Ends whatever talk state is live, without any listener word
     * (the connection itself is going away). A playing talk is cut
     * by closing its stream — its pump unwinds on its own and finds
     * the state already cleared — and the relay pipes close too.
     */
    private fun endTalkStateSilently() {
        val session = incomingTalk
        incomingTalk = null
        peerTalkEndpointId = null
        peerTalkName = null
        if (session != null) {
            try {
                session.input.close()
            } catch (ignored: Exception) {
                // Stream already closed.
            }
        }
        closeTalkRelays()
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
        quotedText: String? = null,
        wireId: String? = null
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
            replyInfo(quotedSender, quotedText),
            messageIdText(wireId)
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
        quotedText: String? = null,
        wireId: String? = null
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
            replyInfo(quotedSender, quotedText),
            messageIdText(wireId)
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
        quotedText: String? = null,
        wireId: String? = null
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
            replyInfo(quotedSender, quotedText),
            messageIdText(wireId)
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
        quotedText: String? = null,
        wireId: String? = null
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
            replyInfo(quotedSender, quotedText),
            messageIdText(wireId)
        )
    }

    /**
     * The tiny message-id text sent just before a voice, photo, video
     * or file send: "#MID#" + the id the other phones will know this
     * message by (so reactions can refer to it). Null when the send
     * carries no id — the host's relays always re-announce the
     * original id, so relayed files keep theirs.
     */
    private fun messageIdText(wireId: String?): String? =
        wireId?.takeIf { it.isNotEmpty() }?.let { MEDIA_ID_PREFIX + it }

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
     * [messageIdText] (built by [messageIdText]) goes before even that:
     * the id the receiving phones file the coming message under.
     */
    private fun sendInfoAndFile(
        targets: List<String>,
        info: String,
        file: File,
        errorMessage: String?,
        localId: Long = 0,
        replyInfoText: String? = null,
        messageIdText: String? = null
    ): Boolean {
        if (targets.isEmpty()) return false
        val filePayload = try {
            Payload.fromFile(file)
        } catch (e: Exception) {
            if (errorMessage != null) listener.onError(errorMessage)
            return false
        }
        if (localId != 0L) outgoingPayloads[filePayload.id] = localId
        if (messageIdText != null) {
            client.sendPayload(
                targets, Payload.fromBytes(messageIdText.toByteArray(Charsets.UTF_8))
            ).addOnFailureListener { e -> listener.onError(describe(e)) }
        }
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
        // Every full stop ends a friend search too: disconnect(),
        // fail(), and the fresh start() behind a plain Find press
        // all come through here.
        clearFriendSearch()
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
        pendingWireIds.clear()
        // Transfers that never finished will never report now; their
        // messages simply keep the single "sent" tick.
        outgoingPayloads.clear()
        // Any live walkie-talkie talk dies with the connection:
        // our capture stops and a playing talk is cut off.
        stopTalkInternal()
        endTalkStateSilently()
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

    /**
     * The person's own name inside an endpoint name: the host marker
     * taken off (see [visibleName]) and the random suffix that
     * [buildLocalName] adds — a "-" plus four digits at the very end
     * — taken off too. This is the name the friends list remembers
     * and matches on, because the suffix changes on every connection
     * while the base name is the person's own. An endpoint name with
     * no such suffix (nothing this app version sends) keeps its whole
     * visible name.
     */
    private fun baseNameOf(endpointName: String): String {
        val visible = visibleName(endpointName)
        val dash = visible.lastIndexOf('-')
        if (dash > 0) {
            val suffix = visible.substring(dash + 1)
            if (suffix.length == 4 && suffix.all { it.isDigit() }) {
                return visible.substring(0, dash)
            }
        }
        return visible
    }

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

            // A friend search (see findFriend) is aimed at one
            // person: only a phone whose base name is the friend's is
            // asked — at once, whichever name is "smaller", because
            // the user picked this exact person — and every other
            // phone found meanwhile is left alone.
            val friend = pendingFriendName
            if (friend != null) {
                if (baseNameOf(info.endpointName) == friend) {
                    clearFriendSearch()
                    client.stopDiscovery()
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
                // A made connection ends any friend search (the
                // friend was found, or someone else connected first),
                // and the phone we just connected with is remembered
                // as a friend under its base name — whichever side
                // asked, whichever side accepted.
                clearFriendSearch()
                FriendsStore.record(appContext, baseNameOf(name))
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
            // any quote waiting for that file, any message id waiting for
            // that file, and any half-arrived file of it are stale now.
            pendingFiles.remove(endpointId)
            pendingQuotes.remove(endpointId)
            pendingWireIds.remove(endpointId)
            // The same for a walkie-talkie talk with this phone: its
            // outgoing talk pipe closes, a talk it was sending us is
            // cut short (its pump ends the announced talk as it
            // unwinds), and a talk it had only announced ends here.
            // When nobody is left, our own talk stops too.
            closeTalkOutStream(endpointId)
            val talkSession = incomingTalk
            if (talkSession != null && talkSession.endpointId == endpointId) {
                try {
                    talkSession.input.close()
                } catch (ignored: Exception) {
                    // The pump notices either way.
                }
            } else if (peerTalkEndpointId == endpointId) {
                peerTalkEndpointId = null
                peerTalkName = null
                listener.onTalkEnded()
            }
            if (connectedEndpoints.isEmpty()) stopTalkInternal()
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
                Payload.Type.STREAM -> {
                    // A walkie-talkie talk: live audio, played (and,
                    // on the hub, passed on) chunk by chunk. It is
                    // never stored and never shown as a message.
                    onTalkStreamReceived(endpointId, payload)
                }
                Payload.Type.FILE -> {
                    // Remember the file; it is usable once the transfer
                    // finishes with SUCCESS in onPayloadTransferUpdate.
                    incomingFilePayloads[payload.id] = payload
                    filePayloadEndpoints[payload.id] = endpointId
                }
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    val raw = String(bytes, Charsets.UTF_8)
                    if (raw.startsWith(MEDIA_ID_PREFIX)) {
                        // The id of the voice, photo, video or file
                        // this phone is about to send, so reactions can
                        // refer to that message later. It is held until
                        // the file's transfer finishes, like the quote.
                        // A malformed one is simply dropped — it is a
                        // control text, not a chat message.
                        parseMessageIdInfo(raw)?.let {
                            pendingWireIds[endpointId] = it
                        }
                        return
                    }
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
                    // A reaction ("#REACT#" control text) is handled the
                    // same way: reported to the listener, never shown.
                    val location = parseLocationText(message.text)
                    val reaction = parseReactionText(message.text)
                    when {
                        // Walkie-talkie control texts ("#TALK#START"
                        // / "#TALK#END"), handled exactly like the
                        // location and reaction ones: acted on here,
                        // never shown as a chat text, and relayed by
                        // the host below like everything else.
                        message.text == TALK_START_TEXT ->
                            onTalkControlStart(endpointId, message.senderName)
                        message.text == TALK_END_TEXT ->
                            onTalkControlEnd(endpointId)
                        reaction != null -> listener.onReactionReceived(
                            reaction.first, reaction.second, message.senderName
                        )
                        location != null -> listener.onLocationMessageReceived(
                            location.first, location.second, message.senderName,
                            message.id
                        )
                        message.text.startsWith(LOCATION_PREFIX) -> Unit
                        message.text.startsWith(REACT_PREFIX) -> Unit
                        message.text.startsWith(TALK_PREFIX) -> Unit
                        else -> listener.onMessageReceived(
                            message.text, message.senderName,
                            message.quotedSender, message.quotedText,
                            message.id
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
                    // quote or message id waiting for it — are stale too.
                    pendingFiles.remove(endpointId)
                    pendingQuotes.remove(endpointId)
                    pendingWireIds.remove(endpointId)
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
                    // to this message and is used up now; so is the id
                    // that was announced for it, which the message takes
                    // as its own (and the relay below re-announces).
                    val info = pendingFiles.remove(endpointId)
                    val quote = pendingQuotes.remove(endpointId)
                    val wireId = pendingWireIds.remove(endpointId)
                    when (info?.kind ?: IncomingFileKind.VOICE) {
                        IncomingFileKind.VOICE -> {
                            val dest = saveReceivedVoiceFile(filePayload) ?: return
                            val duration = info?.voiceDurationSeconds
                                ?: fileDurationSeconds(dest)
                            listener.onVoiceMessageReceived(
                                dest.absolutePath, duration, info?.senderName,
                                quote?.sender, quote?.text, wireId
                            )
                            if (role == Role.HUB) {
                                relayVoiceFile(
                                    dest, duration, info?.senderName, endpointId,
                                    quote, wireId
                                )
                            }
                        }
                        IncomingFileKind.PHOTO -> {
                            val dest = saveReceivedPhotoFile(filePayload) ?: return
                            listener.onPhotoMessageReceived(
                                dest.absolutePath, info?.senderName,
                                quote?.sender, quote?.text, wireId
                            )
                            if (role == Role.HUB) {
                                relayPhotoFile(
                                    dest, info?.senderName, endpointId, quote, wireId
                                )
                            }
                        }
                        IncomingFileKind.FILE -> {
                            val dest = saveReceivedFile(filePayload, info?.fileName) ?: return
                            listener.onFileMessageReceived(
                                dest.absolutePath, dest.name, dest.length(),
                                info?.senderName, quote?.sender, quote?.text, wireId
                            )
                            if (role == Role.HUB) {
                                relayFile(dest, info?.senderName, endpointId, quote, wireId)
                            }
                        }
                        IncomingFileKind.VIDEO -> {
                            val dest = saveReceivedVideoFile(filePayload, info?.fileName) ?: return
                            // The video info carries no length, so it
                            // is read from the file we just saved.
                            val duration = fileDurationSeconds(dest)
                            listener.onVideoMessageReceived(
                                dest.absolutePath, duration, info?.senderName,
                                quote?.sender, quote?.text, wireId
                            )
                            if (role == Role.HUB) {
                                relayVideoFile(
                                    dest, info?.senderName, endpointId, quote, wireId
                                )
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
        quote: PendingQuote? = null,
        wireId: String? = null
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
            replyInfo(quote?.sender, quote?.text),
            messageIdText(wireId)
        )
    }

    private fun relayPhotoFile(
        file: File,
        senderName: String?,
        exceptEndpointId: String,
        quote: PendingQuote? = null,
        wireId: String? = null
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
            replyInfo(quote?.sender, quote?.text),
            messageIdText(wireId)
        )
    }

    private fun relayFile(
        file: File,
        senderName: String?,
        exceptEndpointId: String,
        quote: PendingQuote? = null,
        wireId: String? = null
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
            replyInfo(quote?.sender, quote?.text),
            messageIdText(wireId)
        )
    }

    private fun relayVideoFile(
        file: File,
        senderName: String?,
        exceptEndpointId: String,
        quote: PendingQuote? = null,
        wireId: String? = null
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
            replyInfo(quote?.sender, quote?.text),
            messageIdText(wireId)
        )
    }

    /**
     * Reads a message-id info text: "#MID#" + a UUID. Only the exact
     * UUID shape is accepted (36 characters, dashes where a UUID has
     * them — the same check [unpackMessage] makes), so a typed chat
     * text that happens to start with the prefix can never be taken
     * for one; null then, and the text is dropped as a control text
     * gone wrong, never shown as a chat message.
     */
    private fun parseMessageIdInfo(raw: String): String? {
        val rest = raw.substring(MEDIA_ID_PREFIX.length)
        if (rest.length != MESSAGE_ID_LENGTH) return null
        if (rest[8] != '-' || rest[13] != '-' || rest[18] != '-' ||
            rest[23] != '-'
        ) {
            return null
        }
        return rest
    }

    /**
     * Reads a reaction control text: "#REACT#" + target message id +
     * "#" + emoji (split at the FIRST '#'; ids never contain one). The
     * emoji must be exactly one of [REACTION_EMOJIS] with nothing after
     * it, and the target must be non-empty — anything else is null, so
     * a typed chat text can never be mistaken for a reaction, and a
     * damaged control text is dropped silently (but still relayed by
     * the hub, like every control text).
     */
    private fun parseReactionText(text: String): Pair<String, String>? {
        if (!text.startsWith(REACT_PREFIX)) return null
        val rest = text.substring(REACT_PREFIX.length)
        val separator = rest.indexOf('#')
        if (separator <= 0) return null
        val target = rest.substring(0, separator)
        val emoji = rest.substring(separator + 1)
        if (emoji !in REACTION_EMOJIS) return null
        return target to emoji
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
        // How long a one-tap friend search looks before giving up.
        private const val FRIEND_SEARCH_TIMEOUT_MS = 20_000L
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
        // The id of a voice, photo, video or file message, announced
        // in its own tiny text just before the info text: "#MID#" +
        // a UUID (see messageIdText / parseMessageIdInfo).
        private const val MEDIA_ID_PREFIX = "#MID#"
        // A reaction, packed inside an ordinary text payload:
        // "#REACT#" + target message id + "#" + emoji
        // (see sendReaction / parseReactionText).
        private const val REACT_PREFIX = "#REACT#"
        // Walkie-talkie control texts, packed inside ordinary text
        // payloads like "#LOC#" and "#REACT#" are: "#TALK#START"
        // just before the audio stream opens, "#TALK#END" just
        // after it closes (see startTalk / stopTalk). The audio
        // itself never rides a text; it is a STREAM payload.
        private const val TALK_PREFIX = "#TALK#"
        private const val TALK_START_TEXT = "#TALK#START"
        private const val TALK_END_TEXT = "#TALK#END"
        // The live voice format: 16 kHz mono PCM16, in chunks of
        // 100 ms — small chunks keep the delay low. A talk is
        // capped like a voice message is (2 minutes), so a stuck
        // or forgotten button cannot stream forever.
        private const val TALK_SAMPLE_RATE = 16_000
        private const val TALK_CHUNK_BYTES = 3_200 // 100 ms of audio
        private const val TALK_MAX_NANOS = 120_000_000_000L // 2 minutes

        /**
         * The emojis a message can be reacted with, in the order the
         * long-press menu shows them. A reaction carrying anything
         * else is not a reaction (see [parseReactionText]). Note the
         * heart is two UTF-16 code units (a heart plus a variation
         * selector); matching is by exact string everywhere.
         */
        val REACTION_EMOJIS: List<String> =
            listOf("❤️", "😂", "👍", "😮", "😢", "🙏")
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
