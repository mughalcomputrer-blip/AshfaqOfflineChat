package com.ashfaq.offlinechat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import android.util.Base64
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
import java.io.ByteArrayOutputStream
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
 * Edits and deletes: a message's author can correct or take back
 * their own message. An edit rides an ordinary text payload whose
 * text is "#EDIT#" + the message's id + "#" + the new text; a
 * delete-for-everyone rides one whose text is "#DEL#" + the id.
 * Both therefore carry the author's name in the "#FROM#" section,
 * get the usual dedupe, and the hub relays them word for word,
 * exactly like a reaction; the receiving side recognises them
 * here, never shows them as chat texts, and reports them to the
 * listener. Only the author may do either — the screen applies a
 * command only when its stamped name matches the target message's
 * author, and drops it quietly otherwise. A delete does not remove
 * the chat row: the bubble becomes a placeholder on every phone,
 * so quotes and history around it stay in place.
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
 *
 * Typing indicator: while the user types, a tiny "#TYPE#ON#name"
 * text says so and a "#TYPE#OFF#name" text takes it back (see
 * [sendTypingSignal]). Unlike the other control texts these do
 * NOT ride [sendMessage]: they carry no "#ID#" (so they can never
 * take a slot in the duplicate protection, however often they
 * repeat), are never tracked for the delivered ticks, and are
 * never shown or saved anywhere. The typer's name is stamped
 * into the text itself (as in the file info texts), and the hub
 * relays the text word for word, so in a group the name still
 * names the typer, not the hub. A lost signal costs nothing:
 * the receiving side forgets a typer a few seconds after their
 * last signal, stop or no stop.
 *
 * Profiles: a phone can carry a small profile — a photo and a
 * one-line status (see [sendProfile]). It rides a bare control
 * text of its own, "#PROF#" + name + "#" + status + "#" + the
 * photo as base64 (a small square JPEG, stepped down in size and
 * quality until the whole text fits a tight cap), sent exactly
 * like the typing signals: no message id (no duplicate-protection
 * slot), no delivered ticks, never shown or saved as a message,
 * and relayed word for word by the hub, so the name inside keeps
 * naming the profile's owner rather than the hub. The photo rides
 * inside the text itself — no file payload — so a profile can
 * never be paired with, or mistaken for, a chat file. Profiles
 * are exchanged as soon as a connection stands (a host also
 * hands a new joiner the profiles it already holds, so the
 * joiner sees the whole group, not just the host) and again
 * whenever the user edits theirs while connected. What arrives
 * is kept per sender name in [peerProfiles] — the status in
 * memory, the photo as a small file — for the screen to show.
 * An older app version knows none of this and shows the text as
 * one long chat message: the same accepted mixed-version cost
 * as "#TYPE#".
 *
 * Forwarding: there is only ever one conversation on a phone, so
 * "forward" means re-sending an earlier message as a NEW message
 * from the person forwarding it, labelled as forwarded. A text
 * forward prefixes the text with "#FWD#" + the original author's
 * name + "#" (the name empty when the forwarder is the author);
 * a forwarded location packs its "#LOC#" text after the same
 * prefix. Both ride [sendMessage], so they get a fresh message
 * id, the forwarder's "#FROM#" name, the delivered ticks and the
 * hub's word-for-word relay like any text, and are recognised
 * here after unpacking. A media forward re-sends the file
 * through the ordinary media send, with the flag appended to
 * the "#MID#" text instead: "#MID#" + id + "#FWD#" + the
 * original author's name. It rides there because an older app
 * version reads a "#MID#" text as exactly an id and drops one
 * that does not parse whole — a forwarded file therefore still
 * arrives there (only without its id), while the info texts
 * themselves ("#PHOTO#" and friends) cannot carry an extra
 * segment safely (a photo's name must end in ".jpg", a file's
 * name would swallow the segment). A forward never carries the
 * original's quote or reactions: it starts life clean.
 *
 * Transfer progress: while a voice, photo, video or file is being
 * sent or received, the bubble shows how far the transfer has got
 * (see [Listener.onSendProgress] and the onReceive... callbacks).
 * Nothing about this rides the wire — Nearby reports every file
 * payload's progress locally in onPayloadTransferUpdate — so older
 * app versions are unaffected. On the sending side only our own
 * file sends are tracked (a second map next to [outgoingPayloads]);
 * the bar ends with the delivered tick on success, or with
 * [Listener.onSendProgressEnded] on failure. On the receiving side,
 * when a file payload starts and its info text has already arrived
 * (the normal order: the info always goes first), the screen is
 * told at once, so it can show the bubble as a "receiving"
 * placeholder and fill it with progress until the transfer
 * finishes; the finished file is then handed over through
 * [Listener.onReceiveCompleted] INSTEAD of the usual
 * onVoiceMessageReceived / onPhotoMessageReceived / ... callbacks,
 * and a transfer that fails, is cancelled, or loses its sender
 * ends the placeholder through [Listener.onReceiveFailed]. A file
 * that starts before its info (or while another file from the
 * same phone is already showing progress) keeps the old behaviour
 * exactly: no placeholder, the bubble appears at completion. The
 * hub's receive-then-relay flow is untouched by all of this — the
 * relay starts at completion, as before, and shows no progress.
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
         * [forwarded] says the message is a forward — a fresh copy
         * of an earlier message, sent by [senderName] — and
         * [forwardedFrom] is who wrote the original when that was
         * somebody else (null when the forwarder is the author or
         * the author is unknown).
         */
        fun onMessageReceived(
            text: String,
            senderName: String?,
            quotedSender: String?,
            quotedText: String?,
            wireId: String?,
            forwarded: Boolean = false,
            forwardedFrom: String? = null
        )

        /**
         * A shared position arrived. [senderName] is who sent it
         * (null if unknown), like for a text message. [wireId] is
         * the id other phones know the position's message by, like
         * for a text message. [forwarded]/[forwardedFrom] are as
         * for [onMessageReceived].
         */
        fun onLocationMessageReceived(
            latitude: Double,
            longitude: Double,
            senderName: String?,
            wireId: String?,
            forwarded: Boolean = false,
            forwardedFrom: String? = null
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
         * The author of a message edited it: the message known by
         * [targetWireId] now reads [newText]. [editorName] is the
         * stamped "#FROM#" name on the edit command (null only when
         * the payload carried no name); the screen applies the edit
         * only when that name is the target message's author, so an
         * edit command can never rewrite somebody else's message.
         */
        fun onMessageEdited(
            targetWireId: String,
            newText: String,
            editorName: String?
        ) {}

        /**
         * The author of a message deleted it for everyone: the
         * message known by [targetWireId] becomes a placeholder (its
         * row stays). [deleterName] is the stamped "#FROM#" name on
         * the delete command; the same authorship rule as for
         * [onMessageEdited] applies.
         */
        fun onMessageDeleted(
            targetWireId: String,
            deleterName: String?
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

        /**
         * Someone's typing state changed: the person named
         * [senderName] (null when the signal carried no name) is
         * typing when [isTyping] is true and stopped when it is
         * false. Signals repeat while the person types; one that
         * is never followed by a stop is best forgotten a few
         * seconds after the last one (see the class comment).
         * Display-only: no message exists for this anywhere — it
         * is never shown as a chat text and never saved.
         */
        fun onTypingChanged(senderName: String?, isTyping: Boolean) {}

        /**
         * A profile arrived from the person named [senderName]
         * (see the class comment): their status and photo are
         * stored with the manager, ready via [peerProfiles].
         * Display-only bookkeeping — no message exists for this
         * anywhere: it is never shown as a chat text and never
         * saved in the chat history.
         */
        fun onProfileReceived(senderName: String) {}

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
         * Progress of one of our own file sends: the file payload
         * of the message sent with [localId] is [percent] percent
         * transferred (0–100). Reported only while the transfer is
         * running and only for voice, photo, video and file sends —
         * text messages are instant and never report. The bar this
         * drives ends when the message is delivered
         * ([onMessageDelivered]) or the send ends without success
         * ([onSendProgressEnded]). Display-only: nothing extra goes
         * on the wire for it.
         */
        fun onSendProgress(localId: Long, percent: Int) {}

        /**
         * One of our own file sends (the message sent with
         * [localId]) ended without success — its transfer failed
         * or was cancelled — so any progress shown for it is over.
         * The failure itself is still reported through [onError],
         * exactly as before.
         */
        fun onSendProgressEnded(localId: Long) {}

        /**
         * A file from another phone has started arriving AND its
         * info text (kind, sender, quote, message id) was already
         * in, so the screen can show the message's bubble at once
         * as a "receiving" placeholder and fill it with progress
         * ([onReceiveProgress]) until the transfer finishes.
         * [payloadId] names the transfer in the callbacks that
         * follow. [type] is the kind of message coming;
         * [fileName] is set for a general file (and a voice note),
         * [voiceDurationSeconds] for a voice note (0 when the info
         * carried none). The remaining parameters mean what they
         * mean for the on...MessageReceived callbacks. A file that
         * starts before its info gets NO such call: it is reported
         * whole at completion, as before this feature existed.
         */
        fun onReceiveStarted(
            payloadId: Long,
            type: ChatMessage.Type,
            senderName: String?,
            fileName: String?,
            voiceDurationSeconds: Int,
            quotedSender: String?,
            quotedText: String?,
            wireId: String?,
            forwarded: Boolean = false,
            forwardedFrom: String? = null
        ) {}

        /**
         * The transfer named by [payloadId] (see [onReceiveStarted])
         * is [percent] percent in (0–100). Display-only.
         */
        fun onReceiveProgress(payloadId: Long, percent: Int) {}

        /**
         * The transfer named by [payloadId] finished: the file is
         * saved at [filePath] under the name [fileName] and is
         * [fileSizeBytes] big; [durationSeconds] is its length for
         * a voice note or a video (0 for a photo or a file). This
         * REPLACES the matching on...MessageReceived callback for
         * this message — the placeholder the screen showed becomes
         * the real message, and only now is it saved to history.
         */
        fun onReceiveCompleted(
            payloadId: Long,
            filePath: String,
            fileName: String,
            fileSizeBytes: Long,
            durationSeconds: Int
        ) {}

        /**
         * The transfer named by [payloadId] will never finish —
         * it failed, was cancelled, or the sending phone left — so
         * the placeholder shown for it goes away again. Nothing of
         * it was ever saved to history.
         */
        fun onReceiveFailed(payloadId: Long) {}

        /**
         * A voice message arrived; [filePath] points to our own copy
         * of it. [wireId] is the id the sender announced for it in a
         * "#MID#" text (null when the sender's app version announced
         * none), which reactions refer to. [forwarded] and
         * [forwardedFrom] are as for [onMessageReceived] (for media
         * they were announced in the same "#MID#" text).
         */
        fun onVoiceMessageReceived(
            filePath: String,
            durationSeconds: Int,
            senderName: String?,
            quotedSender: String?,
            quotedText: String?,
            wireId: String?,
            forwarded: Boolean = false,
            forwardedFrom: String? = null
        ) {}

        /** A photo arrived; [filePath] points to our own copy of it.
         *  [wireId] as for a voice message. */
        fun onPhotoMessageReceived(
            filePath: String,
            senderName: String?,
            quotedSender: String?,
            quotedText: String?,
            wireId: String?,
            forwarded: Boolean = false,
            forwardedFrom: String? = null
        ) {}

        /** A video arrived; [filePath] points to our own copy of it.
         *  [wireId] as for a voice message. */
        fun onVideoMessageReceived(
            filePath: String,
            durationSeconds: Int,
            senderName: String?,
            quotedSender: String?,
            quotedText: String?,
            wireId: String?,
            forwarded: Boolean = false,
            forwardedFrom: String? = null
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
            wireId: String?,
            forwarded: Boolean = false,
            forwardedFrom: String? = null
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

    // The subset of [outgoingPayloads] that are FILE payloads (the
    // file of a voice, photo, video or file message of ours):
    // Nearby payload id -> the message's local id, kept so transfer
    // progress can be reported for exactly the sends whose bubble
    // shows a progress bar. Text payloads are never in here (they
    // are instant). The host's relays are never in here either
    // (their local id is 0 — no bubble of the host's waits on them).
    // An entry leaves with its transfer: on success (the delivered
    // tick ends the bar), on failure or cancel (reported through
    // Listener.onSendProgressEnded), or in stopEverything.
    private val outgoingFilePayloads = mutableMapOf<Long, Long>()

    // Payloads of our own typing signals ("#TYPE#...") that Nearby
    // has not finished sending yet — ours, and the hub's relays of
    // other members' signals. A typing signal is invisible and
    // worthless once late, so its transfer is never reported: not
    // as a delivery (it is not in [outgoingPayloads]) and not as
    // an error either — this set is how a finished or failed
    // typing transfer is recognised in onPayloadTransferUpdate
    // and quietly forgotten there.
    private val typingPayloadIds = mutableSetOf<Long>()

    // Payloads of our own profile texts ("#PROF#...") — ours, a
    // host's hand-over of held profiles to a new joiner, and the
    // hub's relays. Kept quiet in onPayloadTransferUpdate exactly
    // like the typing payloads: a profile is a courtesy, so its
    // failure is not an error worth a status line, and it earns
    // no delivered tick either.
    private val profilePayloadIds = mutableSetOf<Long>()

    /**
     * A profile received from another phone: their wire name (the
     * key every profile is kept under), their status line ("" when
     * they have none), the photo exactly as it rode the wire
     * (base64, "" when they have none — the host keeps it so it
     * can hand the profile on to a new joiner unchanged) and the
     * file the photo was written to (null when there is none).
     */
    class PeerProfile(
        val name: String,
        val status: String,
        val photoBase64: String,
        val photoFile: File?
    )

    // Profiles received from other phones, by their wire name, in
    // the order they first arrived. Deliberately NOT cleared when
    // a connection ends: like the friends list, they are this
    // phone's memory of people it has met, refreshed automatically
    // on every new connection.
    private val peerProfiles = LinkedHashMap<String, PeerProfile>()

    // File payloads that arrived and are still being transferred.
    private val incomingFilePayloads = mutableMapOf<Long, Payload>()

    // Which endpoint each incoming file payload came from, so its pieces
    // can be cleaned up when that phone leaves the group.
    private val filePayloadEndpoints = mutableMapOf<Long, String>()

    // Incoming file payloads whose bubble the screen is already
    // showing as a "receiving" placeholder: Nearby payload id ->
    // the endpoint the file is coming from. A payload joins when it
    // starts and its info text is already in (see onPayloadReceived);
    // at most one per endpoint, like [pendingFiles]. An entry
    // leaves when the transfer finishes (Listener.onReceiveCompleted
    // replaces the usual arrival callback), fails or is cancelled,
    // or the sending phone leaves (both Listener.onReceiveFailed).
    private val incomingProgressPayloads = mutableMapOf<Long, String>()

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

    // What a phone announced (in a small "#MID#" text) for the
    // voice, photo, video or file it is about to send: the id
    // reactions can refer to that message by later, and whether the
    // message is a forward of an earlier one (and whose). Kept per
    // sending endpoint and cleaned up exactly like [pendingQuotes]:
    // used when the file's transfer finishes, dropped with the file
    // info when the transfer fails or the phone leaves. A file that
    // arrives with no "#MID#" (an older app version) simply has no
    // entry here.
    private data class PendingMediaInfo(
        val wireId: String,
        val forwarded: Boolean,
        val forwardedFrom: String?
    )
    private val pendingMediaInfos = mutableMapOf<String, PendingMediaInfo>()

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

    /**
     * Edits one of our own text messages: the message the other
     * phones know by [targetWireId] now reads [newText]. The edit
     * is packed into a control text ("#EDIT#" + target id + "#" +
     * new text) and sent through the ordinary text path
     * ([sendMessage]), so it carries this phone's name in the
     * "#FROM#" section — the name is how the other phones check
     * that the editor really is the message's author — and the hub
     * relays it word for word; the receiving side recognises the
     * control text in [parseEditText] and never shows it as a chat
     * text. The new text rides after the id verbatim: the id is a
     * fixed-length UUID with no '#' in it, so the text is taken as
     * everything after the one separator, and a '#' inside the text
     * itself cannot break the format (the same arrangement as a
     * file name after its sender in "#FILE#").
     */
    fun sendEditMessage(targetWireId: String, newText: String): Boolean {
        if (targetWireId.isEmpty() || newText.isEmpty()) return false
        return sendMessage(EDIT_PREFIX + targetWireId + "#" + newText)
    }

    /**
     * Deletes one of our own messages for everyone: the message
     * the other phones know by [targetWireId] becomes a "This
     * message was deleted" placeholder there (its row stays). The
     * command is packed into a control text ("#DEL#" + target id)
     * and sent through the ordinary text path ([sendMessage]), so
     * it carries this phone's name for the authorship check, gets
     * the usual dedupe, and the hub relays it word for word; the
     * receiving side recognises it in [parseDeleteText] and never
     * shows it as a chat text.
     */
    fun sendDeleteMessage(targetWireId: String): Boolean {
        if (targetWireId.isEmpty()) return false
        return sendMessage(DELETE_PREFIX + targetWireId)
    }

    /**
     * The prefix a forwarded text or location carries: "#FWD#" +
     * the original author's name + "#". The name goes through
     * [wireQuoteField], so it can never contain the '#' separator;
     * it is empty when the forwarder is the author (or the author
     * is unknown), which the reading side reports as a forward
     * with no name. Read back by [parseForwardText].
     */
    private fun forwardPrefix(forwardedFrom: String?): String =
        FORWARD_PREFIX +
            wireQuoteField(forwardedFrom ?: "", MAX_SENDER_NAME_LENGTH) + "#"

    /**
     * Forwards a text message: [text] is sent again as a new
     * message from this phone, labelled as a forward of the
     * message [forwardedFrom] originally wrote (null when that
     * was this phone, or the author is unknown). The text rides
     * the ordinary text path ([sendMessage]) behind the forward
     * prefix, so the copy gets a fresh message id, this phone's
     * name as its sender, the delivered ticks ([localId] and
     * [wireId] work exactly as for a typed message) and the hub's
     * word-for-word relay; the receiving side recognises the
     * prefix in [parseForwardText] and shows the text with the
     * "↪ Forwarded" caption. No quote and no reactions are
     * carried over: the copy starts life clean.
     */
    fun sendForwardedText(
        text: String,
        forwardedFrom: String?,
        localId: Long = 0,
        wireId: String? = null
    ): Boolean {
        if (text.isEmpty()) return false
        return sendMessage(
            forwardPrefix(forwardedFrom) + text, localId, wireId = wireId
        )
    }

    /**
     * Forwards a shared position: the "#LOC#" text a position
     * normally rides in, packed behind the forward prefix (see
     * [sendForwardedText]) and sent through the ordinary text
     * path, so the copy is a new location message from this
     * phone, flagged as a forward; the receiving side strips
     * the prefix and reads the position inside it.
     */
    fun sendForwardedLocation(
        latitude: Double,
        longitude: Double,
        forwardedFrom: String?,
        localId: Long = 0,
        wireId: String? = null
    ): Boolean {
        val body = LOCATION_PREFIX +
            String.format(Locale.US, "%.6f,%.6f", latitude, longitude)
        return sendMessage(
            forwardPrefix(forwardedFrom) + body, localId, wireId = wireId
        )
    }

    /**
     * Tells the connected phones that the user is typing a
     * message ([isTyping] true) or stopped (false). The signal
     * is a bare control text — "#TYPE#" + "ON" or "OFF", plus
     * "#" and this phone's name when it has one — sent as its
     * own payload, NOT through [sendMessage]: it must not take
     * a message id (the duplicate protection stays for real
     * messages, however often the signal repeats), must not
     * earn delivered ticks, and must never be shown or saved
     * (see the class comment). The name is stamped in exactly
     * like a file info text's sender, so the hub's word-for-word
     * relay keeps naming the typer. There is deliberately no
     * failure reporting: a signal that cannot be sent is not an
     * error worth showing, because the other side forgets a
     * typer on its own after a few seconds anyway (and a failed
     * transfer is kept quiet via [typingPayloadIds]). Returns
     * false when nobody is connected.
     */
    fun sendTypingSignal(isTyping: Boolean): Boolean {
        val targets = sendTargets()
        if (targets.isEmpty()) return false
        val name = wireSenderName()
        val text = StringBuilder(TYPING_PREFIX)
            .append(if (isTyping) "ON" else "OFF")
        if (name.isNotEmpty()) {
            text.append('#').append(name)
        }
        val payload = Payload.fromBytes(
            text.toString().toByteArray(Charsets.UTF_8)
        )
        typingPayloadIds.add(payload.id)
        client.sendPayload(targets, payload)
        return true
    }

    // ----- Profiles -----

    /** The user's own profile status line ("" when none was set). */
    fun profileStatus(): String =
        prefs.getString(KEY_PROFILE_STATUS, null) ?: ""

    /**
     * Saves a new profile status, cleaned the same way it rides
     * the wire (see [sanitizeProfileStatus]); blank clears it.
     * Takes effect on the next [sendProfile] — the screen calls
     * that right after saving while connected, so the other
     * phones show the new status without reconnecting.
     */
    fun updateProfileStatus(newStatus: String) {
        val clean = sanitizeProfileStatus(newStatus)
        val editor = prefs.edit()
        if (clean.isEmpty()) {
            editor.remove(KEY_PROFILE_STATUS)
        } else {
            editor.putString(KEY_PROFILE_STATUS, clean)
        }
        editor.apply()
    }

    /**
     * Makes a status safe to ride the wire: '#' is the field
     * separator of the profile format (and line breaks would break
     * its one-line layout), so both become spaces, and the status
     * is capped in length — the same cleaning [wireQuoteField]
     * gives the quote fields.
     */
    private fun sanitizeProfileStatus(raw: String): String =
        wireQuoteField(raw, MAX_PROFILE_STATUS_LENGTH)

    /**
     * The file the user's own profile photo is stored in. The
     * screen writes the picked photo here (square, shrunk — the
     * wire encoding in [encodeOwnProfilePhoto] shrinks it further
     * on the way out); [sendProfile] reads it from here.
     */
    fun ownProfilePhotoFile(): File =
        File(File(appContext.filesDir, PROFILE_DIR_NAME), OWN_PROFILE_PHOTO_NAME)

    /** The profile received from the person named [name], if any. */
    fun peerProfile(name: String): PeerProfile? = peerProfiles[name]

    /** Every profile received so far, in first-arrival order. */
    fun peerProfiles(): List<PeerProfile> = peerProfiles.values.toList()

    /** True while this phone is hosting a group. */
    fun isGroupHost(): Boolean = role == Role.HUB

    /**
     * Sends our profile — name, status and photo — to everyone we
     * are connected with (see the class comment). The text is a
     * bare control payload like the typing signals', NOT routed
     * through [sendMessage], for the same reasons: no message id
     * slot, no ticks, never shown or saved. Called when a
     * connection stands and again after the user edits their
     * profile. Returns false when nobody is connected.
     */
    fun sendProfile(): Boolean {
        val targets = sendTargets()
        if (targets.isEmpty()) return false
        return sendProfileText(
            profileWireText(
                wireSenderName(),
                sanitizeProfileStatus(profileStatus()),
                encodeOwnProfilePhoto()
            ),
            targets
        )
    }

    /** One profile text: "#PROF#" + name + "#" + status + "#" + photo. */
    private fun profileWireText(
        name: String,
        status: String,
        photoBase64: String
    ): String =
        PROFILE_PREFIX + name + "#" + status + "#" + photoBase64

    /**
     * Sends one already-built profile text to [targets], keeping
     * its transfer quiet via [profilePayloadIds]. Used for our
     * own profile, for the host's relays, and for the host handing
     * the profiles it holds to a new joiner (the text is rebuilt
     * from the stored parts, so the name inside still names the
     * profile's owner, not the host).
     */
    private fun sendProfileText(text: String, targets: List<String>): Boolean {
        if (targets.isEmpty()) return false
        val payload = Payload.fromBytes(text.toByteArray(Charsets.UTF_8))
        profilePayloadIds.add(payload.id)
        client.sendPayload(targets, payload)
        return true
    }

    /**
     * Our profile photo as base64, small enough to ride inside
     * the profile text: the stored photo is centre-cropped to a
     * square, then encoded as a JPEG stepped down in size and
     * quality until the WHOLE profile text fits
     * [MAX_PROFILE_PAYLOAD_CHARS]. "" when there is no usable
     * photo — and in the (practically impossible) case that even
     * the smallest encoding is too big, so what goes out is a
     * status-only profile rather than an oversized payload.
     */
    private fun encodeOwnProfilePhoto(): String {
        val file = ownProfilePhotoFile()
        if (!file.isFile || file.length() == 0L) return ""
        val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return ""
        val side = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(
            decoded,
            (decoded.width - side) / 2,
            (decoded.height - side) / 2,
            side,
            side
        )
        if (square !== decoded) decoded.recycle()
        // Every fixed character of the profile text besides the
        // photo itself: the prefix, the name, the status and the
        // two '#' separators.
        val overhead = PROFILE_PREFIX.length + wireSenderName().length +
            sanitizeProfileStatus(profileStatus()).length + 2
        for (size in PROFILE_PHOTO_SIZES) {
            val scaled = Bitmap.createScaledBitmap(square, size, size, true)
            for (quality in PROFILE_JPEG_QUALITIES) {
                val stream = ByteArrayOutputStream()
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                    continue
                }
                val encoded = Base64.encodeToString(
                    stream.toByteArray(), Base64.NO_WRAP
                )
                if (overhead + encoded.length <= MAX_PROFILE_PAYLOAD_CHARS) {
                    if (scaled !== square) scaled.recycle()
                    square.recycle()
                    return encoded
                }
            }
            if (scaled !== square) scaled.recycle()
        }
        square.recycle()
        return ""
    }

    /**
     * Reads a profile text: "#PROF#" + name + "#" + status + "#" +
     * base64 photo. None of the three fields can contain '#'
     * (the name and status are cleaned on the sending side, the
     * base64 alphabet has no '#'), so the split is unambiguous.
     * Returns the name, the status and the decoded photo bytes
     * (null when no photo rides along). Anything malformed — no
     * name, the wrong number of fields, photo bytes that are not
     * base64, or a text longer than any sender of this version
     * would build — is null: a control text gone wrong, dropped
     * silently like the other control texts.
     */
    private fun parseProfileText(raw: String): Triple<String, String, ByteArray?>? {
        if (raw.length > MAX_PROFILE_PAYLOAD_CHARS) return null
        val parts = raw.substring(PROFILE_PREFIX.length).split('#')
        if (parts.size != 3) return null
        val name = parts[0]
        if (name.isEmpty() || name.length > MAX_PROFILE_NAME_LENGTH) return null
        val status = parts[1].take(MAX_PROFILE_STATUS_LENGTH)
        val photoPart = parts[2]
        val photoBytes = if (photoPart.isEmpty()) {
            null
        } else {
            try {
                Base64.decode(photoPart, Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                return null
            }
        }
        return Triple(name, status, photoBytes)
    }

    /**
     * Keeps a received profile: the photo bytes become the file
     * the screen shows (a profile that arrives without a photo
     * clears the file an earlier one left), and the entry is
     * (re-)inserted at the end of [peerProfiles] — most recent
     * last, like the typing map. The stored base64 is re-encoded
     * from the bytes, so what the host later hands to a new
     * joiner is the canonical form of exactly what arrived.
     */
    private fun storePeerProfile(name: String, status: String, photoBytes: ByteArray?) {
        var photoFile: File? = null
        var photoBase64 = ""
        val file = peerProfilePhotoFile(name)
        if (photoBytes != null && photoBytes.isNotEmpty()) {
            photoBase64 = Base64.encodeToString(photoBytes, Base64.NO_WRAP)
            try {
                file.parentFile?.mkdirs()
                file.writeBytes(photoBytes)
                photoFile = file
            } catch (e: Exception) {
                photoFile = null
            }
        } else {
            file.delete()
        }
        peerProfiles.remove(name)
        peerProfiles[name] = PeerProfile(name, status, photoBase64, photoFile)
    }

    /** Where the photo of the profile named [name] is kept. */
    private fun peerProfilePhotoFile(name: String): File {
        val base = sanitizeFileName(name).ifEmpty { "peer" }
        return File(
            File(appContext.filesDir, PEER_PROFILES_DIR_NAME), "$base.jpg"
        )
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
            this.name = "walkie-talk-pump"
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
        wireId: String? = null,
        forwarded: Boolean = false,
        forwardedFrom: String? = null
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
            messageIdText(wireId, forwarded, forwardedFrom)
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
        wireId: String? = null,
        forwarded: Boolean = false,
        forwardedFrom: String? = null
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
            messageIdText(wireId, forwarded, forwardedFrom)
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
        wireId: String? = null,
        forwarded: Boolean = false,
        forwardedFrom: String? = null
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
            messageIdText(wireId, forwarded, forwardedFrom)
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
        wireId: String? = null,
        forwarded: Boolean = false,
        forwardedFrom: String? = null
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
            messageIdText(wireId, forwarded, forwardedFrom)
        )
    }

    /**
     * The tiny message-id text sent just before a voice, photo, video
     * or file send: "#MID#" + the id the other phones will know this
     * message by (so reactions can refer to it). When the message is
     * a forward, "#FWD#" and the original author's name are appended
     * (the name empty when the forwarder is the author) — the flag
     * rides here, not in the file's own info text, for the reason
     * the class comment gives. Null when the send carries no id —
     * the host's relays always re-announce the original id, so
     * relayed files keep theirs (and their forward flag with it).
     */
    private fun messageIdText(
        wireId: String?,
        forwarded: Boolean = false,
        forwardedFrom: String? = null
    ): String? =
        wireId?.takeIf { it.isNotEmpty() }?.let { id ->
            val base = MEDIA_ID_PREFIX + id
            if (!forwarded) {
                base
            } else {
                base + FORWARD_PREFIX +
                    wireQuoteField(
                        forwardedFrom ?: "", MAX_SENDER_NAME_LENGTH
                    )
            }
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
        if (localId != 0L) {
            outgoingPayloads[filePayload.id] = localId
            // Our own file send also reports its transfer progress,
            // so its bubble can show a progress bar while it goes.
            outgoingFilePayloads[filePayload.id] = localId
        }
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
        incomingProgressPayloads.clear()
        pendingFiles.clear()
        pendingQuotes.clear()
        pendingMediaInfos.clear()
        // Transfers that never finished will never report now; their
        // messages simply keep the single "sent" tick.
        outgoingPayloads.clear()
        outgoingFilePayloads.clear()
        typingPayloadIds.clear()
        profilePayloadIds.clear()
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
                // Profiles are exchanged as soon as a connection
                // stands (see the class comment): ours goes to
                // everyone we are now connected with — for the host
                // that includes the phone that just joined — and a
                // host also hands the joiner the profiles it already
                // holds, rebuilt from the stored parts so the name
                // inside keeps naming each profile's owner, so the
                // joiner sees the whole group and not just the host.
                sendProfile()
                if (role == Role.HUB) {
                    for (profile in peerProfiles.values) {
                        sendProfileText(
                            profileWireText(
                                profile.name,
                                profile.status,
                                profile.photoBase64
                            ),
                            listOf(endpointId)
                        )
                    }
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
            pendingMediaInfos.remove(endpointId)
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
            // A "receiving" placeholder for one of this phone's
            // files can never be filled now that it is gone; the
            // screen takes it away again, like a failed transfer.
            // (Nearby may still report the transfer's own failure
            // afterwards — the entry is gone from the map by then,
            // so the placeholder is only ever ended once.)
            val progressIterator = incomingProgressPayloads.entries.iterator()
            while (progressIterator.hasNext()) {
                val entry = progressIterator.next()
                if (entry.value == endpointId) {
                    progressIterator.remove()
                    listener.onReceiveFailed(entry.key)
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
                    // When the file's info text is already in (the
                    // normal order — every sender sends it first),
                    // the screen can show the bubble at once as a
                    // "receiving" placeholder and fill it with this
                    // transfer's progress. The info, quote and
                    // message id are only PEEKED here: the completion
                    // in onPayloadTransferUpdate still takes them out
                    // of their maps, exactly as before. At most one
                    // placeholder per sending phone (pendingFiles is
                    // single-slot per endpoint too); a file that
                    // starts before its info, or while another file
                    // from the same phone is already showing one,
                    // keeps the old behaviour — no placeholder, the
                    // bubble appears at completion.
                    val info = pendingFiles[endpointId]
                    if (info != null &&
                        !incomingProgressPayloads.containsValue(endpointId)
                    ) {
                        incomingProgressPayloads[payload.id] = endpointId
                        val quote = pendingQuotes[endpointId]
                        val mediaInfo = pendingMediaInfos[endpointId]
                        listener.onReceiveStarted(
                            payload.id,
                            chatTypeOf(info.kind),
                            info.senderName,
                            info.fileName,
                            info.voiceDurationSeconds ?: 0,
                            quote?.sender,
                            quote?.text,
                            mediaInfo?.wireId,
                            mediaInfo?.forwarded ?: false,
                            mediaInfo?.forwardedFrom
                        )
                    }
                }
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    val raw = String(bytes, Charsets.UTF_8)
                    if (raw.startsWith(MEDIA_ID_PREFIX)) {
                        // The id of the voice, photo, video or file
                        // this phone is about to send, so reactions can
                        // refer to that message later — and, when the
                        // message is a forward, that it is one and whose
                        // message it forwards. It is held until the
                        // file's transfer finishes, like the quote.
                        // A malformed one is simply dropped — it is a
                        // control text, not a chat message.
                        parseMessageIdInfo(raw)?.let {
                            pendingMediaInfos[endpointId] = it
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
                    if (raw.startsWith(TYPING_PREFIX)) {
                        // A typing signal ("#TYPE#ON#name" /
                        // "#TYPE#OFF#name"): reported to the
                        // listener, and relayed by the host below
                        // like every control text, but never
                        // unpacked — it carries no message id, so
                        // it cannot take a slot in the duplicate
                        // protection, and it is never shown as a
                        // chat text or saved. A damaged one parses
                        // to null and is simply dropped here; the
                        // relay still carries it, so the group
                        // stays in step.
                        parseTypingSignal(raw)?.let { (typing, name) ->
                            listener.onTypingChanged(name, typing)
                        }
                        if (role == Role.HUB) {
                            val targets = sendTargets(exclude = endpointId)
                            if (targets.isNotEmpty()) {
                                val relay = Payload.fromBytes(bytes)
                                typingPayloadIds.add(relay.id)
                                client.sendPayload(targets, relay)
                            }
                        }
                        return
                    }
                    if (raw.startsWith(PROFILE_PREFIX)) {
                        // A profile ("#PROF#name#status#base64
                        // photo"): stored with the manager and
                        // reported to the listener, and relayed by
                        // the host below like the typing signals,
                        // but never unpacked — it carries no message
                        // id, is never shown as a chat text and
                        // never reaches the history. A damaged or
                        // oversized one parses to null and is simply
                        // dropped here; the relay still carries it,
                        // so the group stays in step and every phone
                        // judges for itself.
                        parseProfileText(raw)?.let { (name, status, photoBytes) ->
                            storePeerProfile(name, status, photoBytes)
                            listener.onProfileReceived(name)
                        }
                        if (role == Role.HUB) {
                            val targets = sendTargets(exclude = endpointId)
                            if (targets.isNotEmpty()) {
                                val relay = Payload.fromBytes(bytes)
                                profilePayloadIds.add(relay.id)
                                client.sendPayload(targets, relay)
                            }
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
                    // A forward ("#FWD#" prefix, see parseForwardText)
                    // wraps the real content instead: what follows the
                    // prefix is the message — a text, or a "#LOC#" text
                    // when a position was forwarded — and the name in
                    // the prefix says who wrote the original.
                    val location = parseLocationText(message.text)
                    val reaction = parseReactionText(message.text)
                    val edit = parseEditText(message.text)
                    val deletion = parseDeleteText(message.text)
                    val forward = parseForwardText(message.text)
                    val forwardLocation = forward?.let {
                        parseLocationText(it.second)
                    }
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
                        // An edit or a delete-for-everyone ("#EDIT#" /
                        // "#DEL#" control texts) is handled the same
                        // way: reported to the listener (which applies
                        // it only when its stamped name is the target
                        // message's author), never shown as a chat
                        // text, and relayed by the host below.
                        edit != null -> listener.onMessageEdited(
                            edit.first, edit.second, message.senderName
                        )
                        deletion != null -> listener.onMessageDeleted(
                            deletion, message.senderName
                        )
                        location != null -> listener.onLocationMessageReceived(
                            location.first, location.second, message.senderName,
                            message.id
                        )
                        // A forwarded position: the "#FWD#" prefix is
                        // stripped and the "#LOC#" text inside it is
                        // what counts; the message is reported as a
                        // location, flagged as a forward. A forwarded
                        // text is reported the same way — its real
                        // text, no quote (a forward never carries
                        // one), flagged, with the original author's
                        // name from the prefix.
                        forwardLocation != null -> listener
                            .onLocationMessageReceived(
                                forwardLocation.first,
                                forwardLocation.second,
                                message.senderName, message.id,
                                forwarded = true,
                                forwardedFrom = forward?.first
                            )
                        forward != null -> listener.onMessageReceived(
                            forward.second, message.senderName,
                            null, null, message.id,
                            forwarded = true,
                            forwardedFrom = forward.first
                        )
                        message.text.startsWith(LOCATION_PREFIX) -> Unit
                        message.text.startsWith(REACT_PREFIX) -> Unit
                        message.text.startsWith(EDIT_PREFIX) -> Unit
                        message.text.startsWith(DELETE_PREFIX) -> Unit
                        message.text.startsWith(TALK_PREFIX) -> Unit
                        message.text.startsWith(FORWARD_PREFIX) -> Unit
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
            if (update.status == PayloadTransferUpdate.Status.FAILURE ||
                update.status == PayloadTransferUpdate.Status.CANCELED
            ) {
                // A cancel gets the same quiet cleanup as a failure
                // (before this feature it was not handled at all, and
                // a half-finished transfer's pieces lingered until
                // the next full stop), but it is never reported as an
                // error: a cancel is usually our own disconnect or
                // the other phone going away, both already signalled
                // their own way.
                val failed = update.status == PayloadTransferUpdate.Status.FAILURE
                val wasIncoming = incomingFilePayloads.remove(update.payloadId) != null
                filePayloadEndpoints.remove(update.payloadId)
                // A failed send of ours is forgotten here; its message
                // simply never earns the second tick (the error below
                // is the same feedback as before).
                outgoingPayloads.remove(update.payloadId)
                // If it was one of our file sends, its progress bar
                // is over too (a success ends the bar through the
                // delivered tick instead).
                outgoingFilePayloads.remove(update.payloadId)?.let { localId ->
                    listener.onSendProgressEnded(localId)
                }
                // A failed typing signal is forgotten silently: it
                // was never a message, so it must not produce the
                // "could not be transferred" error below either.
                val wasTyping = typingPayloadIds.remove(update.payloadId)
                // The same quiet forgetting for a profile text:
                // it was never a message either.
                val wasProfile = profilePayloadIds.remove(update.payloadId)
                if (wasIncoming) {
                    // The file is gone, so its info message — and any
                    // quote or message id waiting for it — are stale too.
                    pendingFiles.remove(endpointId)
                    pendingQuotes.remove(endpointId)
                    pendingMediaInfos.remove(endpointId)
                }
                // A "receiving" placeholder for this file can never
                // be filled now; the screen takes it away again. The
                // half-arrived file itself is Nearby's own temp copy,
                // discarded by Nearby — nothing of ours was ever
                // saved for it, and nothing reaches the history.
                if (incomingProgressPayloads.remove(update.payloadId) != null) {
                    listener.onReceiveFailed(update.payloadId)
                }
                if (failed && !wasTyping && !wasProfile) {
                    listener.onError("A message could not be transferred.")
                }
            } else if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                // A typing signal that got through is simply
                // forgotten; it was never tracked for the ticks.
                typingPayloadIds.remove(update.payloadId)
                // A profile text that got through is forgotten the
                // same way.
                profilePayloadIds.remove(update.payloadId)
                // One of our own file sends got through (to the first
                // phone, in a group); the delivered tick below is
                // what ends its progress bar, so it leaves the
                // progress map here and later per-phone updates for
                // the same payload find nothing to report.
                outgoingFilePayloads.remove(update.payloadId)
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
                // Whether the screen is showing a "receiving"
                // placeholder for this file: it is completed through
                // onReceiveCompleted below, instead of the usual
                // arrival callbacks, and a file that cannot be saved
                // ends it through onReceiveFailed.
                val receiving =
                    incomingProgressPayloads.remove(update.payloadId) != null
                if (filePayload != null) {
                    // The info message said what kind of file this is. No
                    // info (or a lost one) keeps the old behavior: voice.
                    // A quote that was sent just before the file belongs
                    // to this message and is used up now; so is what was
                    // announced for it in the "#MID#" text — the id the
                    // message takes as its own (and the relay below
                    // re-announces) and whether it is a forward.
                    val info = pendingFiles.remove(endpointId)
                    val quote = pendingQuotes.remove(endpointId)
                    val mediaInfo = pendingMediaInfos.remove(endpointId)
                    val wireId = mediaInfo?.wireId
                    val forwarded = mediaInfo?.forwarded ?: false
                    val forwardedFrom = mediaInfo?.forwardedFrom
                    when (info?.kind ?: IncomingFileKind.VOICE) {
                        IncomingFileKind.VOICE -> {
                            val dest = saveReceivedVoiceFile(filePayload)
                            if (dest == null) {
                                if (receiving) {
                                    listener.onReceiveFailed(update.payloadId)
                                }
                                return
                            }
                            val duration = info?.voiceDurationSeconds
                                ?: fileDurationSeconds(dest)
                            if (receiving) {
                                listener.onReceiveCompleted(
                                    update.payloadId, dest.absolutePath,
                                    dest.name, dest.length(), duration
                                )
                            } else {
                                listener.onVoiceMessageReceived(
                                    dest.absolutePath, duration, info?.senderName,
                                    quote?.sender, quote?.text, wireId,
                                    forwarded, forwardedFrom
                                )
                            }
                            if (role == Role.HUB) {
                                relayVoiceFile(
                                    dest, duration, info?.senderName, endpointId,
                                    quote, wireId, forwarded, forwardedFrom
                                )
                            }
                        }
                        IncomingFileKind.PHOTO -> {
                            val dest = saveReceivedPhotoFile(filePayload)
                            if (dest == null) {
                                if (receiving) {
                                    listener.onReceiveFailed(update.payloadId)
                                }
                                return
                            }
                            if (receiving) {
                                listener.onReceiveCompleted(
                                    update.payloadId, dest.absolutePath,
                                    dest.name, dest.length(), 0
                                )
                            } else {
                                listener.onPhotoMessageReceived(
                                    dest.absolutePath, info?.senderName,
                                    quote?.sender, quote?.text, wireId,
                                    forwarded, forwardedFrom
                                )
                            }
                            if (role == Role.HUB) {
                                relayPhotoFile(
                                    dest, info?.senderName, endpointId, quote,
                                    wireId, forwarded, forwardedFrom
                                )
                            }
                        }
                        IncomingFileKind.FILE -> {
                            val dest = saveReceivedFile(filePayload, info?.fileName)
                            if (dest == null) {
                                if (receiving) {
                                    listener.onReceiveFailed(update.payloadId)
                                }
                                return
                            }
                            if (receiving) {
                                listener.onReceiveCompleted(
                                    update.payloadId, dest.absolutePath,
                                    dest.name, dest.length(), 0
                                )
                            } else {
                                listener.onFileMessageReceived(
                                    dest.absolutePath, dest.name, dest.length(),
                                    info?.senderName, quote?.sender, quote?.text,
                                    wireId, forwarded, forwardedFrom
                                )
                            }
                            if (role == Role.HUB) {
                                relayFile(
                                    dest, info?.senderName, endpointId, quote,
                                    wireId, forwarded, forwardedFrom
                                )
                            }
                        }
                        IncomingFileKind.VIDEO -> {
                            val dest = saveReceivedVideoFile(filePayload, info?.fileName)
                            if (dest == null) {
                                if (receiving) {
                                    listener.onReceiveFailed(update.payloadId)
                                }
                                return
                            }
                            // The video info carries no length, so it
                            // is read from the file we just saved.
                            val duration = fileDurationSeconds(dest)
                            if (receiving) {
                                listener.onReceiveCompleted(
                                    update.payloadId, dest.absolutePath,
                                    dest.name, dest.length(), duration
                                )
                            } else {
                                listener.onVideoMessageReceived(
                                    dest.absolutePath, duration, info?.senderName,
                                    quote?.sender, quote?.text, wireId,
                                    forwarded, forwardedFrom
                                )
                            }
                            if (role == Role.HUB) {
                                relayVideoFile(
                                    dest, info?.senderName, endpointId, quote,
                                    wireId, forwarded, forwardedFrom
                                )
                            }
                        }
                    }
                }
            } else if (update.status == PayloadTransferUpdate.Status.IN_PROGRESS) {
                // A file payload on its way, in either direction:
                // Nearby reports how much of it has gone. Only the
                // transfers the screen shows a bar for are reported
                // — one of our own file sends, or an incoming file
                // whose bubble is already up as a placeholder. A
                // transfer whose total size is not (yet) known
                // reports nothing; its bar simply waits.
                val totalBytes = update.totalBytes
                if (totalBytes > 0) {
                    val percent = ((update.bytesTransferred * 100) / totalBytes)
                        .toInt().coerceIn(0, 100)
                    outgoingFilePayloads[update.payloadId]?.let { localId ->
                        listener.onSendProgress(localId, percent)
                    }
                    if (incomingProgressPayloads.containsKey(update.payloadId)) {
                        listener.onReceiveProgress(update.payloadId, percent)
                    }
                }
            }
        }
    }

    /** The chat-row kind an incoming file of this kind becomes. */
    private fun chatTypeOf(kind: IncomingFileKind): ChatMessage.Type =
        when (kind) {
            IncomingFileKind.VOICE -> ChatMessage.Type.VOICE
            IncomingFileKind.PHOTO -> ChatMessage.Type.PHOTO
            IncomingFileKind.FILE -> ChatMessage.Type.FILE
            IncomingFileKind.VIDEO -> ChatMessage.Type.VIDEO
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
        wireId: String? = null,
        forwarded: Boolean = false,
        forwardedFrom: String? = null
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
            messageIdText(wireId, forwarded, forwardedFrom)
        )
    }

    private fun relayPhotoFile(
        file: File,
        senderName: String?,
        exceptEndpointId: String,
        quote: PendingQuote? = null,
        wireId: String? = null,
        forwarded: Boolean = false,
        forwardedFrom: String? = null
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
            messageIdText(wireId, forwarded, forwardedFrom)
        )
    }

    private fun relayFile(
        file: File,
        senderName: String?,
        exceptEndpointId: String,
        quote: PendingQuote? = null,
        wireId: String? = null,
        forwarded: Boolean = false,
        forwardedFrom: String? = null
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
            messageIdText(wireId, forwarded, forwardedFrom)
        )
    }

    private fun relayVideoFile(
        file: File,
        senderName: String?,
        exceptEndpointId: String,
        quote: PendingQuote? = null,
        wireId: String? = null,
        forwarded: Boolean = false,
        forwardedFrom: String? = null
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
            messageIdText(wireId, forwarded, forwardedFrom)
        )
    }

    /**
     * Reads a message-id info text: "#MID#" + a UUID, plus — when the
     * media message is a forward — "#FWD#" and the original author's
     * name (which may be empty: the forwarder is the author). Only
     * the exact UUID shape is accepted for the id (36 characters,
     * dashes where a UUID has them — the same check [unpackMessage]
     * makes), so a typed chat text that happens to start with the
     * prefix can never be taken for one; and a tail that is not a
     * forward segment makes the whole text unreadable, never
     * half-read. Null then, and the text is dropped as a control
     * text gone wrong, never shown as a chat message. (An older app
     * version reading a forwarded "#MID#" text drops it the same way
     * — it insists on exactly the id — so the file still arrives
     * there, just without an id; see the class comment.)
     */
    private fun parseMessageIdInfo(raw: String): PendingMediaInfo? {
        val rest = raw.substring(MEDIA_ID_PREFIX.length)
        if (rest.length < MESSAGE_ID_LENGTH) return null
        val id = rest.substring(0, MESSAGE_ID_LENGTH)
        if (!isMessageIdShape(id)) return null
        val tail = rest.substring(MESSAGE_ID_LENGTH)
        if (tail.isEmpty()) return PendingMediaInfo(id, false, null)
        if (!tail.startsWith(FORWARD_PREFIX)) return null
        val author = tail.substring(FORWARD_PREFIX.length)
        // A name never contains '#': [wireQuoteField] removed it on
        // the sending side. One that does is not a name at all.
        if (author.contains('#')) return null
        return PendingMediaInfo(id, true, author.takeIf { it.isNotEmpty() })
    }

    /**
     * Reads a forward prefix in a received text: "#FWD#" + the
     * original author's name + "#" + the real content (a text, or a
     * "#LOC#" control text when a position was forwarded). Returns
     * the author (null when the name is empty — the forwarder is the
     * author, or the author is unknown) and the content. The name
     * ends at the FIRST '#': it never contains one, because
     * [wireQuoteField] removed it on the sending side — the content
     * after the separator may contain as many as it likes. Null
     * when there is no second '#' or the content is empty: a
     * control text gone wrong, dropped silently (but still relayed
     * by the hub, like every control text).
     */
    private fun parseForwardText(text: String): Pair<String?, String>? {
        if (!text.startsWith(FORWARD_PREFIX)) return null
        val rest = text.substring(FORWARD_PREFIX.length)
        val separator = rest.indexOf('#')
        if (separator < 0) return null
        val author = rest.substring(0, separator).takeIf { it.isNotEmpty() }
        val body = rest.substring(separator + 1)
        if (body.isEmpty()) return null
        return author to body
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
     * Whether [value] has the shape of a message id: a UUID, 36
     * characters with dashes where a UUID has them (the same check
     * [parseMessageIdInfo] makes). Every id a message is known by
     * — the "#ID#" of a text or location, the "#MID#" of a media
     * message — has this shape.
     */
    private fun isMessageIdShape(value: String): Boolean =
        value.length == MESSAGE_ID_LENGTH &&
            value[8] == '-' && value[13] == '-' && value[18] == '-' &&
            value[23] == '-'

    /**
     * Reads an edit control text: "#EDIT#" + target message id +
     * "#" + the new text. The id must have the exact message-id
     * shape, so a typed chat text can never be taken for an edit;
     * the new text is everything after the separator and must be
     * non-empty — it may itself contain '#', because the fixed-
     * length id makes the one separator unambiguous (the same
     * arrangement as a file name after its sender in "#FILE#").
     * Anything else is null: a control text gone wrong, dropped
     * silently (but still relayed by the hub, like every control
     * text).
     */
    private fun parseEditText(text: String): Pair<String, String>? {
        if (!text.startsWith(EDIT_PREFIX)) return null
        val rest = text.substring(EDIT_PREFIX.length)
        if (rest.length < MESSAGE_ID_LENGTH + 1) return null
        val target = rest.substring(0, MESSAGE_ID_LENGTH)
        if (!isMessageIdShape(target)) return null
        if (rest[MESSAGE_ID_LENGTH] != '#') return null
        val newText = rest.substring(MESSAGE_ID_LENGTH + 1)
        if (newText.isEmpty()) return null
        return target to newText
    }

    /**
     * Reads a delete-for-everyone control text: "#DEL#" + target
     * message id, and nothing else — the id must have the exact
     * message-id shape, so a typed chat text can never be taken
     * for a delete. Null for anything else, with the same
     * drop-silently rule as [parseEditText].
     */
    private fun parseDeleteText(text: String): String? {
        if (!text.startsWith(DELETE_PREFIX)) return null
        val rest = text.substring(DELETE_PREFIX.length)
        if (!isMessageIdShape(rest)) return null
        return rest
    }

    /**
     * Reads a typing signal: "#TYPE#" + "ON" or "OFF", plus "#"
     * and the typer's name when the sender stamped one (the name
     * itself never contains '#'; [wireSenderName] saw to that).
     * Returns the typing state and the name (null when no name
     * rides along). Anything else is null — a control text gone
     * wrong, dropped silently. The signal is read from the raw
     * payload, before unpacking, like the file info texts: it
     * never went through [sendMessage], so there is no message
     * id to unpack, and a typed chat text (which always carries
     * an id) can never be taken for one.
     */
    private fun parseTypingSignal(raw: String): Pair<Boolean, String?>? {
        val rest = raw.substring(TYPING_PREFIX.length)
        val separator = rest.indexOf('#')
        val state = if (separator >= 0) {
            rest.substring(0, separator)
        } else {
            rest
        }
        val isTyping = when (state) {
            "ON" -> true
            "OFF" -> false
            else -> return null
        }
        val name = if (separator >= 0) {
            rest.substring(separator + 1).takeIf { it.isNotEmpty() }
        } else {
            null
        }
        return isTyping to name
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
        // An edit of one of the author's own text messages, packed
        // inside an ordinary text payload: "#EDIT#" + target
        // message id + "#" + the new text (see sendEditMessage /
        // parseEditText).
        private const val EDIT_PREFIX = "#EDIT#"
        // A delete-for-everyone of one of the author's own
        // messages, packed inside an ordinary text payload:
        // "#DEL#" + target message id (see sendDeleteMessage /
        // parseDeleteText).
        private const val DELETE_PREFIX = "#DEL#"
        // A forward of an earlier message. In a text payload:
        // "#FWD#" + the original author's name + "#" + the real
        // content (a text, or a "#LOC#" text for a forwarded
        // position) — see sendForwardedText / parseForwardText.
        // Appended to a media message's "#MID#" text instead:
        // "#MID#" + id + "#FWD#" + the author's name — see
        // messageIdText / parseMessageIdInfo.
        private const val FORWARD_PREFIX = "#FWD#"
        // Walkie-talkie control texts, packed inside ordinary text
        // payloads like "#LOC#" and "#REACT#" are: "#TALK#START"
        // just before the audio stream opens, "#TALK#END" just
        // after it closes (see startTalk / stopTalk). The audio
        // itself never rides a text; it is a STREAM payload.
        private const val TALK_PREFIX = "#TALK#"
        private const val TALK_START_TEXT = "#TALK#START"
        private const val TALK_END_TEXT = "#TALK#END"
        // A typing signal, a bare text payload of its own — never
        // wrapped by sendMessage, so it carries no "#ID#" and is
        // read from the raw payload like the info texts:
        // "#TYPE#" + "ON" or "OFF" + "#" + the typer's name
        // (see sendTypingSignal / parseTypingSignal).
        private const val TYPING_PREFIX = "#TYPE#"
        // A profile, a bare text payload of its own like the
        // typing signal — never wrapped by sendMessage, so it
        // carries no "#ID#" and is read from the raw payload:
        // "#PROF#" + name + "#" + status + "#" + the photo as
        // base64 ("" when there is none) — see sendProfile /
        // parseProfileText. The whole text is built to fit
        // MAX_PROFILE_PAYLOAD_CHARS, name and status included.
        private const val PROFILE_PREFIX = "#PROF#"
        private const val KEY_PROFILE_STATUS = "profile_status"
        private const val MAX_PROFILE_STATUS_LENGTH = 60
        private const val MAX_PROFILE_NAME_LENGTH = 100
        private const val MAX_PROFILE_PAYLOAD_CHARS = 24 * 1024
        // Our own profile photo lives in filesDir/profile/; the
        // photos received with other phones' profiles live in
        // filesDir/profiles/, one file per sender name.
        private const val PROFILE_DIR_NAME = "profile"
        private const val PEER_PROFILES_DIR_NAME = "profiles"
        private const val OWN_PROFILE_PHOTO_NAME = "my_photo.jpg"
        // The wire photo's step-down ladder: square sizes and
        // JPEG qualities, biggest first; the first pair whose
        // encoding fits the payload cap wins.
        private val PROFILE_PHOTO_SIZES = intArrayOf(192, 144, 96, 64)
        private val PROFILE_JPEG_QUALITIES = intArrayOf(70, 55, 40)
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
