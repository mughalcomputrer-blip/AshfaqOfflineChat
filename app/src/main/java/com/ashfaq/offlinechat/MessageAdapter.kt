package com.ashfaq.offlinechat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()

    // The chat-search filter: the query the user typed (trimmed,
    // lower-cased) and the slice of [messages] it currently shows.
    // [messages] stays the full chat and the only list anything ever
    // mutates — reactions, delivered ticks and the history snapshot
    // all work on it. [visibleMessages] is a derived view, rebuilt
    // after every change to the list or the query; item count and
    // binding read only the visible slice, and every helper that
    // finds a message finds it in the full list first, then works
    // out whether (and where) it is currently visible.
    private var filterQuery: String = ""
    private var visibleMessages: List<ChatMessage> = emptyList()

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    // Transfer progress, by the local id of the message whose file
    // is on its way: how far its transfer has got, in percent
    // (0–100). TRANSIENT by design — it is never written into a
    // ChatMessage and never saved to the history: a transfer does
    // not survive an app restart, so on a freshly loaded chat no
    // row has an entry here and no bar shows. Entries appear when
    // the screen reports a transfer running for a message (its own
    // file send, or a received file whose bubble is already up as
    // a "receiving" placeholder) and are removed when the transfer
    // ends, whichever way it ends.
    private val transferProgress = mutableMapOf<Long, Int>()

    /**
     * Called when the user long-presses a message (the screen shows
     * the menu — the reaction emojis and "Reply" — and owns the reply
     * and reaction state; the adapter only reports which message was
     * chosen).
     */
    var onMessageMenuRequested: ((ChatMessage) -> Unit)? = null

    // Voice playback state: at most one voice message plays at a time.
    private var mediaPlayer: MediaPlayer? = null
    private var playingAudioPath: String? = null
    private var playingPosition: Int = -1
    private var playerPlaying = false

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val bubble: View = view.findViewById(R.id.bubbleView)
        val senderNameText: TextView = view.findViewById(R.id.senderNameText)
        val forwardedText: TextView = view.findViewById(R.id.forwardedText)
        val quoteBlock: View = view.findViewById(R.id.quoteBlock)
        val quoteStrip: View = view.findViewById(R.id.quoteStrip)
        val quoteSenderText: TextView = view.findViewById(R.id.quoteSenderText)
        val quoteText: TextView = view.findViewById(R.id.quoteText)
        val messageText: TextView = view.findViewById(R.id.messageText)
        val voiceRow: View = view.findViewById(R.id.voiceRow)
        val buttonPlay: ImageButton = view.findViewById(R.id.buttonPlay)
        val durationText: TextView = view.findViewById(R.id.durationText)
        val photoImage: ImageView = view.findViewById(R.id.photoImage)
        val videoContainer: View = view.findViewById(R.id.videoContainer)
        val videoThumbnail: ImageView = view.findViewById(R.id.videoThumbnail)
        val videoPlayIcon: ImageView = view.findViewById(R.id.videoPlayIcon)
        val videoDurationText: TextView = view.findViewById(R.id.videoDurationText)
        val fileRow: View = view.findViewById(R.id.fileRow)
        val fileIcon: ImageView = view.findViewById(R.id.fileIcon)
        val fileNameText: TextView = view.findViewById(R.id.fileNameText)
        val fileSizeText: TextView = view.findViewById(R.id.fileSizeText)
        val locationRow: View = view.findViewById(R.id.locationRow)
        val locationTitleText: TextView = view.findViewById(R.id.locationTitleText)
        val locationCoordsText: TextView = view.findViewById(R.id.locationCoordsText)
        val reactionsText: TextView = view.findViewById(R.id.reactionsText)
        val progressRow: View = view.findViewById(R.id.progressRow)
        val transferProgressBar: ProgressBar =
            view.findViewById(R.id.transferProgressBar)
        val transferPercentText: TextView =
            view.findViewById(R.id.transferPercentText)
        val editedText: TextView = view.findViewById(R.id.editedText)
        val timeText: TextView = view.findViewById(R.id.timeText)
        val ticksText: TextView = view.findViewById(R.id.ticksText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = visibleMessages[position]
        val context = holder.itemView.context

        // A long press on a message opens its menu (reactions and
        // "Reply"). The photo and the play button consume their own
        // touches, so they get the same listener; everything else on
        // the row reaches the bubble.
        val replyOnLongClick = View.OnLongClickListener {
            onMessageMenuRequested?.invoke(message)
            true
        }
        holder.bubble.setOnLongClickListener(replyOnLongClick)
        holder.photoImage.setOnLongClickListener(replyOnLongClick)
        holder.videoThumbnail.setOnLongClickListener(replyOnLongClick)
        holder.buttonPlay.setOnLongClickListener(replyOnLongClick)

        holder.messageText.text = message.text
        holder.timeText.text = timeFormat.format(Date(message.timestamp))
        // The sender-name label's color is only named in the layout
        // (the light accent); repaint it per the current palette.
        holder.senderNameText.setTextColor(AppColors.accent(context))

        // WhatsApp-style ticks next to the time, on our own messages
        // only: one tick = handed to Nearby for sending, two ticks =
        // the transfer to the next phone finished.
        if (message.isSent) {
            holder.ticksText.visibility = View.VISIBLE
            holder.ticksText.text = if (message.delivered) "✓✓" else "✓"
        } else {
            holder.ticksText.visibility = View.GONE
            holder.ticksText.text = null
        }

        // The small "edited" caption before the time, on a message
        // its author corrected. A deleted message is a placeholder
        // now and shows no caption.
        if (message.edited && !message.deleted) {
            holder.editedText.visibility = View.VISIBLE
        } else {
            holder.editedText.visibility = View.GONE
        }

        // The "↪ Forwarded" caption at the top of the bubble, on a
        // message that is a forward of an earlier one; it names the
        // original author when that was somebody other than the
        // phone that sent this copy. A deleted message is a
        // placeholder now and shows no caption either.
        if (message.forwarded && !message.deleted) {
            holder.forwardedText.visibility = View.VISIBLE
            holder.forwardedText.text =
                if (!message.forwardedFrom.isNullOrBlank()) {
                    context.getString(
                        R.string.message_forwarded_from, message.forwardedFrom
                    )
                } else {
                    context.getString(R.string.message_forwarded)
                }
        } else {
            holder.forwardedText.visibility = View.GONE
            holder.forwardedText.text = null
        }

        // In a group, a received message shows who originally sent it.
        // Our own messages (and nameless ones from older versions) don't.
        val senderName = message.senderName
        if (!message.isSent && !senderName.isNullOrBlank()) {
            holder.senderNameText.visibility = View.VISIBLE
            holder.senderNameText.text = senderName
        } else {
            holder.senderNameText.visibility = View.GONE
            holder.senderNameText.text = null
        }

        val params = holder.bubble.layoutParams as LinearLayout.LayoutParams
        if (message.isSent) {
            params.gravity = Gravity.END
            holder.bubble.background = AppColors.bubbleSentBackground(context)
            holder.messageText.setTextColor(
                ContextCompat.getColor(context, R.color.bubble_sent_text)
            )
            holder.timeText.setTextColor(
                ContextCompat.getColor(context, R.color.bubble_sent_text)
            )
            holder.editedText.setTextColor(
                ContextCompat.getColor(context, R.color.bubble_sent_text)
            )
            holder.forwardedText.setTextColor(
                ContextCompat.getColor(context, R.color.bubble_sent_text)
            )
            holder.durationText.setTextColor(
                ContextCompat.getColor(context, R.color.bubble_sent_text)
            )
            holder.buttonPlay.setColorFilter(
                ContextCompat.getColor(context, R.color.bubble_sent_text)
            )
            holder.fileNameText.setTextColor(
                ContextCompat.getColor(context, R.color.bubble_sent_text)
            )
            holder.fileSizeText.setTextColor(
                ContextCompat.getColor(context, R.color.bubble_sent_text)
            )
            holder.fileIcon.setColorFilter(
                ContextCompat.getColor(context, R.color.bubble_sent_text)
            )
            holder.locationTitleText.setTextColor(
                ContextCompat.getColor(context, R.color.bubble_sent_text)
            )
            holder.locationCoordsText.setTextColor(
                ContextCompat.getColor(context, R.color.bubble_sent_text)
            )
            holder.reactionsText.setTextColor(
                ContextCompat.getColor(context, R.color.bubble_sent_text)
            )
        } else {
            params.gravity = Gravity.START
            holder.bubble.setBackgroundResource(AppColors.bubbleReceivedDrawable(context))
            holder.messageText.setTextColor(AppColors.bubbleReceivedText(context))
            holder.timeText.setTextColor(AppColors.textSecondary(context))
            holder.editedText.setTextColor(AppColors.textSecondary(context))
            holder.forwardedText.setTextColor(AppColors.textSecondary(context))
            holder.durationText.setTextColor(AppColors.bubbleReceivedText(context))
            holder.buttonPlay.setColorFilter(AppColors.accent(context))
            holder.fileNameText.setTextColor(AppColors.bubbleReceivedText(context))
            holder.fileSizeText.setTextColor(AppColors.textSecondary(context))
            holder.fileIcon.setColorFilter(AppColors.accent(context))
            holder.locationTitleText.setTextColor(AppColors.bubbleReceivedText(context))
            holder.locationCoordsText.setTextColor(AppColors.textSecondary(context))
            holder.reactionsText.setTextColor(AppColors.textSecondary(context))
        }
        holder.bubble.layoutParams = params

        // The reactions on this message, as one small line at the
        // bottom of the bubble ("❤️ 2  👍 1") — only the emojis that
        // were used, in the menu's fixed order, each with how many
        // people picked it. Hidden while nobody reacted.
        val reactionsLine = formatReactions(message)
        if (reactionsLine == null) {
            holder.reactionsText.visibility = View.GONE
            holder.reactionsText.text = null
        } else {
            holder.reactionsText.visibility = View.VISIBLE
            holder.reactionsText.text = reactionsLine
        }

        // When this message is a reply, a quote strip at the top of the
        // bubble shows who was quoted and a preview of their message.
        // Its colors follow the bubble side, like everything else here.
        val quotedSender = message.quotedSender
        val quotedText = message.quotedText
        if (!quotedSender.isNullOrBlank() && !quotedText.isNullOrBlank()) {
            holder.quoteBlock.visibility = View.VISIBLE
            holder.quoteSenderText.text = quotedSender
            holder.quoteText.text = quotedText
            if (message.isSent) {
                holder.quoteBlock.background = AppColors.quoteSentBackground(context)
                holder.quoteStrip.setBackgroundColor(
                    ContextCompat.getColor(context, R.color.on_primary)
                )
                holder.quoteSenderText.setTextColor(
                    ContextCompat.getColor(context, R.color.bubble_sent_text)
                )
                holder.quoteText.setTextColor(
                    ContextCompat.getColor(context, R.color.quote_sent_text)
                )
            } else {
                holder.quoteBlock.setBackgroundResource(
                    AppColors.quoteReceivedDrawable(context)
                )
                holder.quoteStrip.setBackgroundColor(AppColors.accent(context))
                holder.quoteSenderText.setTextColor(AppColors.accent(context))
                holder.quoteText.setTextColor(AppColors.textSecondary(context))
            }
        } else {
            holder.quoteBlock.visibility = View.GONE
            holder.quoteSenderText.text = null
            holder.quoteText.text = null
        }

        if (message.deleted) {
            // The author deleted this message for everyone: every
            // content row goes away and the bubble is just the
            // placeholder line, in italic like WhatsApp's. The quote
            // it carried and its reactions went with the delete, so
            // those blocks hide too; the sender label, the time and
            // the ticks stay, and the row itself keeps its place.
            holder.messageText.visibility = View.VISIBLE
            holder.messageText.text = context.getString(R.string.message_deleted)
            holder.messageText.setTypeface(
                holder.messageText.typeface, Typeface.ITALIC
            )
            if (!message.isSent) {
                holder.messageText.setTextColor(AppColors.textSecondary(context))
            }
            holder.voiceRow.visibility = View.GONE
            holder.buttonPlay.setOnClickListener(null)
            holder.photoImage.visibility = View.GONE
            holder.photoImage.setOnClickListener(null)
            holder.videoContainer.visibility = View.GONE
            holder.videoThumbnail.setOnClickListener(null)
            holder.fileRow.visibility = View.GONE
            holder.locationRow.visibility = View.GONE
            holder.quoteBlock.visibility = View.GONE
            holder.reactionsText.visibility = View.GONE
            holder.bubble.setOnClickListener(null)
        } else when (message.type) {
            ChatMessage.Type.VOICE -> {
                holder.messageText.visibility = View.GONE
                holder.photoImage.visibility = View.GONE
                holder.photoImage.setOnClickListener(null)
                holder.videoContainer.visibility = View.GONE
                holder.videoThumbnail.setOnClickListener(null)
                holder.fileRow.visibility = View.GONE
                holder.locationRow.visibility = View.GONE
                holder.bubble.setOnClickListener(null)
                holder.voiceRow.visibility = View.VISIBLE
                holder.durationText.text = formatDuration(message.durationSeconds)
                val thisOnePlaying = playerPlaying && playingAudioPath == message.audioPath
                holder.buttonPlay.setImageResource(
                    if (thisOnePlaying) {
                        android.R.drawable.ic_media_pause
                    } else {
                        android.R.drawable.ic_media_play
                    }
                )
                holder.buttonPlay.setOnClickListener { togglePlay(message, position) }
            }
            ChatMessage.Type.PHOTO -> {
                holder.messageText.visibility = View.GONE
                holder.voiceRow.visibility = View.GONE
                holder.buttonPlay.setOnClickListener(null)
                holder.videoContainer.visibility = View.GONE
                holder.videoThumbnail.setOnClickListener(null)
                holder.fileRow.visibility = View.GONE
                holder.locationRow.visibility = View.GONE
                holder.bubble.setOnClickListener(null)
                holder.photoImage.visibility = View.VISIBLE
                val path = message.imagePath
                val thumbnailPx = (220 * context.resources.displayMetrics.density).toInt()
                val thumbnail = path?.let {
                    decodeSampledBitmap(it, thumbnailPx, thumbnailPx)
                }
                holder.photoImage.setImageBitmap(thumbnail)
                holder.photoImage.setOnClickListener {
                    if (path != null) showPhotoFullScreen(context, path)
                }
            }
            ChatMessage.Type.FILE -> {
                holder.messageText.visibility = View.GONE
                holder.voiceRow.visibility = View.GONE
                holder.buttonPlay.setOnClickListener(null)
                holder.photoImage.visibility = View.GONE
                holder.photoImage.setOnClickListener(null)
                holder.videoContainer.visibility = View.GONE
                holder.videoThumbnail.setOnClickListener(null)
                holder.locationRow.visibility = View.GONE
                holder.fileRow.visibility = View.VISIBLE
                holder.fileNameText.text = message.fileName
                    ?: message.filePath?.let { File(it).name }
                    ?: ""
                holder.fileSizeText.text = formatFileSize(message.fileSizeBytes)
                holder.bubble.setOnClickListener { openFile(context, message) }
            }
            ChatMessage.Type.VIDEO -> {
                holder.messageText.visibility = View.GONE
                holder.voiceRow.visibility = View.GONE
                holder.buttonPlay.setOnClickListener(null)
                holder.photoImage.visibility = View.GONE
                holder.photoImage.setOnClickListener(null)
                holder.fileRow.visibility = View.GONE
                holder.locationRow.visibility = View.GONE
                holder.videoContainer.visibility = View.VISIBLE
                val path = message.videoPath
                val thumbWidthPx = (220 * context.resources.displayMetrics.density).toInt()
                val frame = path?.let { grabVideoFrame(it, thumbWidthPx) }
                holder.videoThumbnail.setImageBitmap(
                    frame ?: videoPlaceholderBitmap(context)
                )
                holder.videoPlayIcon.setColorFilter(
                    ContextCompat.getColor(context, android.R.color.white)
                )
                holder.videoDurationText.text = formatDuration(message.durationSeconds)
                val openOnClick = View.OnClickListener { openVideo(context, message) }
                holder.videoThumbnail.setOnClickListener(openOnClick)
                holder.bubble.setOnClickListener(openOnClick)
            }
            ChatMessage.Type.LOCATION -> {
                holder.messageText.visibility = View.GONE
                holder.voiceRow.visibility = View.GONE
                holder.buttonPlay.setOnClickListener(null)
                holder.photoImage.visibility = View.GONE
                holder.photoImage.setOnClickListener(null)
                holder.videoContainer.visibility = View.GONE
                holder.videoThumbnail.setOnClickListener(null)
                holder.fileRow.visibility = View.GONE
                holder.locationRow.visibility = View.VISIBLE
                holder.locationTitleText.text =
                    context.getString(R.string.location_title)
                holder.locationCoordsText.text = formatCoordinates(message)
                // The title and coordinates are plain text views, so a
                // tap anywhere on the bubble lands here; a long press
                // reaches the bubble's reply listener the same way.
                holder.bubble.setOnClickListener { openLocation(context, message) }
            }
            ChatMessage.Type.TEXT -> {
                holder.messageText.visibility = View.VISIBLE
                // A recycled row may come from a deleted message,
                // whose placeholder set the text italic.
                holder.messageText.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
                holder.voiceRow.visibility = View.GONE
                holder.photoImage.visibility = View.GONE
                holder.photoImage.setOnClickListener(null)
                holder.buttonPlay.setOnClickListener(null)
                holder.videoContainer.visibility = View.GONE
                holder.videoThumbnail.setOnClickListener(null)
                holder.fileRow.visibility = View.GONE
                holder.locationRow.visibility = View.GONE
                holder.bubble.setOnClickListener(null)
            }
        }

        // The transfer progress bar under the content, on a media
        // message whose file is still on its way — our own send
        // going out, or an incoming file whose bubble is already up
        // as a "receiving" placeholder. The state is the transient
        // [transferProgress] map (never the message itself), so a
        // row bound from history never shows one. Bar and percent
        // follow the bubble's side like everything else here: the
        // accent on a received bubble, the bubble's own text color
        // on a sent one.
        val progress = if (message.localId != 0L) {
            transferProgress[message.localId]
        } else {
            null
        }
        val showProgress = progress != null && !message.deleted &&
            (message.type == ChatMessage.Type.VOICE ||
                message.type == ChatMessage.Type.PHOTO ||
                message.type == ChatMessage.Type.VIDEO ||
                message.type == ChatMessage.Type.FILE)
        if (showProgress && progress != null) {
            holder.progressRow.visibility = View.VISIBLE
            holder.transferProgressBar.progress = progress
            holder.transferPercentText.text =
                context.getString(R.string.transfer_percent, progress)
            val progressTint = if (message.isSent) {
                ContextCompat.getColor(context, R.color.bubble_sent_text)
            } else {
                AppColors.accent(context)
            }
            holder.transferProgressBar.progressTintList =
                ColorStateList.valueOf(progressTint)
            holder.transferPercentText.setTextColor(
                if (message.isSent) {
                    ContextCompat.getColor(context, R.color.bubble_sent_text)
                } else {
                    AppColors.textSecondary(context)
                }
            )
        } else {
            holder.progressRow.visibility = View.GONE
            holder.transferPercentText.text = null
        }
        // A file's size is only known once the file is here, so
        // while it is still on its way the size line stays hidden
        // rather than showing a meaningless "0 B" (the name above
        // it already says what is coming).
        if (message.type == ChatMessage.Type.FILE) {
            holder.fileSizeText.visibility =
                if (showProgress) View.GONE else View.VISIBLE
        }
    }

    override fun getItemCount(): Int = visibleMessages.size

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        releasePlayer()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        refreshVisible()
        // The new message is last in the full list, so when the
        // search lets it through it is also the last visible row.
        // When it does not match, nothing on screen changed and
        // there is nothing to announce.
        if (filterQuery.isEmpty() || matchesQuery(message)) {
            notifyItemInserted(visibleMessages.size - 1)
        }
    }

    /**
     * Puts the second tick on one of our own sent messages: its
     * transfer to the next phone finished. Returns true when a shown
     * message really changed (so the caller can save the history).
     */
    fun markDelivered(localId: Long): Boolean {
        if (localId == 0L) return false
        val index = messages.indexOfFirst {
            it.localId == localId && it.isSent && !it.delivered
        }
        if (index < 0) return false
        messages[index] = messages[index].copy(delivered = true)
        refreshVisible()
        // Redraw the row only when the search currently shows it.
        val visibleIndex = visibleMessages.indexOfFirst {
            it.localId == localId && it.isSent
        }
        if (visibleIndex >= 0) notifyItemChanged(visibleIndex)
        return true
    }

    // ---------- Transfer progress ----------

    /**
     * Reports how far the transfer of the message with [localId]
     * has got, in percent (clamped to 0–100), and redraws its row.
     * Progress reports can arrive in a flood (Nearby updates as
     * chunks land, and a group send reports once per phone), so
     * this is the throttle: a report that does not RAISE the shown
     * integer percent changes nothing and redraws nothing — the
     * bar only ever moves up, and only when the number really
     * moved. (A transfer never restarts under the same id, so a
     * lower report can only be another group member's slower hop
     * of the same send — the bar keeps the furthest hop's number.)
     */
    fun setProgress(localId: Long, percent: Int) {
        if (localId == 0L) return
        val clamped = percent.coerceIn(0, 100)
        val current = transferProgress[localId]
        if (current != null && clamped <= current) return
        transferProgress[localId] = clamped
        val visibleIndex = visibleMessages.indexOfFirst { it.localId == localId }
        if (visibleIndex >= 0) notifyItemChanged(visibleIndex)
    }

    /**
     * Ends the progress shown for the message with [localId] (its
     * transfer finished, one way or the other) and redraws its row.
     */
    fun clearProgress(localId: Long) {
        if (localId == 0L) return
        if (transferProgress.remove(localId) == null) return
        val visibleIndex = visibleMessages.indexOfFirst { it.localId == localId }
        if (visibleIndex >= 0) notifyItemChanged(visibleIndex)
    }

    /**
     * Drops every in-flight progress state at once (the connection
     * is gone, so no transfer is running any more) and redraws.
     */
    fun clearTransferProgress() {
        if (transferProgress.isEmpty()) return
        transferProgress.clear()
        notifyDataSetChanged()
    }

    /** The shown message with this local id, if there is one. */
    fun messageByLocalId(localId: Long): ChatMessage? =
        messages.firstOrNull { it.localId == localId }

    /**
     * Swaps the message with [localId] for [replacement], in place:
     * same row, new content. Used when a "receiving" placeholder is
     * filled by the file that just finished arriving — the caller
     * builds the finished message from the placeholder, so its
     * sender, quote, id and any reactions it collected on the way
     * carry over. Returns false when no message has that id.
     */
    fun replaceMessage(localId: Long, replacement: ChatMessage): Boolean {
        if (localId == 0L) return false
        val index = messages.indexOfFirst { it.localId == localId }
        if (index < 0) return false
        messages[index] = replacement
        refreshVisible()
        // The visible slice holds the same objects as the full list,
        // so the row is found by the object itself (the replacement
        // may carry local id 0, which many rows share).
        val visibleIndex = visibleMessages.indexOfFirst { it === replacement }
        if (visibleIndex >= 0) notifyItemChanged(visibleIndex)
        return true
    }

    /**
     * Takes the message with [localId] off the screen entirely.
     * Used for a "receiving" placeholder whose file never arrived:
     * the row was never saved to the history, so removing it here
     * leaves no trace anywhere. Returns false when no message has
     * that id.
     */
    fun removeMessage(localId: Long): Boolean {
        if (localId == 0L) return false
        val index = messages.indexOfFirst { it.localId == localId }
        if (index < 0) return false
        val visibleIndex = visibleMessages.indexOfFirst { it.localId == localId }
        messages.removeAt(index)
        transferProgress.remove(localId)
        refreshVisible()
        if (visibleIndex >= 0) notifyItemRemoved(visibleIndex)
        return true
    }

    /**
     * Applies one person's reaction to the message known by [wireId]:
     * [reactorName] reacting with [emoji]. Reactions toggle and each
     * person has at most one: the same emoji again removes theirs, a
     * different emoji moves theirs to the new one. The message's
     * reaction map is updated in place and its row redrawn. Returns
     * false when no shown message has that id (a reaction for a
     * message this phone does not have is simply ignored), true when
     * the reaction was applied.
     */
    fun applyReaction(wireId: String, emoji: String, reactorName: String): Boolean {
        val index = messages.indexOfFirst { it.wireId == wireId }
        if (index < 0) return false
        // A deleted message is a placeholder; its reactions were
        // cleared with it and a late reaction (sent before the
        // delete arrived) must not bring them back.
        if (messages[index].deleted) return false
        val reactions = messages[index].reactions
        // The emoji this person has on the message now, if any.
        val current = reactions.entries
            .firstOrNull { reactorName in it.value }?.key
        if (current == emoji) {
            reactions[emoji]?.remove(reactorName)
            if (reactions[emoji].isNullOrEmpty()) reactions.remove(emoji)
        } else {
            if (current != null) {
                reactions[current]?.remove(reactorName)
                if (reactions[current].isNullOrEmpty()) reactions.remove(current)
            }
            reactions.getOrPut(emoji) { mutableSetOf() }.add(reactorName)
        }
        // The reaction map lives on the shared message object, so
        // the full list (and the history saved from it) is already
        // right; only the row's redraw depends on the search filter.
        refreshVisible()
        val visibleIndex = visibleMessages.indexOfFirst { it.wireId == wireId }
        if (visibleIndex >= 0) notifyItemChanged(visibleIndex)
        return true
    }

    // ---------- Edits & deletes ----------

    /**
     * Whether a command that arrived from another phone may touch
     * [message]: the command's stamped name ([commandName]) must be
     * the name of the person who sent the message. Our own messages
     * are never anybody else's to edit or delete (we change those
     * ourselves, locally); a received message whose author is
     * unknown (saved by an older app version) cannot be verified
     * either, so commands for it are dropped too. In a group the
     * name is the original author's, which the hub's word-for-word
     * relay preserves — so this check means the same thing on every
     * phone, exactly like a reaction's name.
     */
    private fun isCommandFromAuthor(message: ChatMessage, commandName: String?): Boolean =
        !message.isSent && !commandName.isNullOrBlank() &&
            message.senderName == commandName

    /** Redraws the row of the message known by [wireId], if shown. */
    private fun notifyMessageChanged(wireId: String) {
        val visibleIndex = visibleMessages.indexOfFirst { it.wireId == wireId }
        if (visibleIndex >= 0) notifyItemChanged(visibleIndex)
    }

    /**
     * Applies an edit that arrived from another phone to the text
     * message known by [wireId]: its text becomes [newText] and the
     * "edited" caption appears. Applies only when [editorName] is
     * the message's author (see [isCommandFromAuthor]); a deleted
     * message and a media message cannot be edited at all. The
     * quote a reply made of this message is a snapshot and is NOT
     * rewritten. Returns false when nothing changed, so the caller
     * only saves the history on a real change.
     */
    fun applyEdit(wireId: String, newText: String, editorName: String?): Boolean {
        val index = messages.indexOfFirst { it.wireId == wireId }
        if (index < 0) return false
        val message = messages[index]
        if (message.deleted || message.type != ChatMessage.Type.TEXT) return false
        if (!isCommandFromAuthor(message, editorName)) return false
        messages[index] = message.copy(text = newText, edited = true)
        refreshVisible()
        notifyMessageChanged(wireId)
        return true
    }

    /**
     * Applies a delete-for-everyone that arrived from another
     * phone to the message known by [wireId]. Applies only when
     * [deleterName] is the message's author (see
     * [isCommandFromAuthor]). The row becomes the deleted
     * placeholder (see [markDeleted]). Returns false when nothing
     * changed.
     */
    fun applyDelete(wireId: String, deleterName: String?): Boolean {
        val index = messages.indexOfFirst { it.wireId == wireId }
        if (index < 0) return false
        val message = messages[index]
        if (message.deleted) return false
        if (!isCommandFromAuthor(message, deleterName)) return false
        markDeleted(index)
        return true
    }

    /**
     * Edits one of OUR OWN text messages on our own screen (the
     * edit command itself is broadcast by the caller through
     * ChatManager). Same rules as [applyEdit] otherwise.
     */
    fun editOwnMessage(wireId: String, newText: String): Boolean {
        val index = messages.indexOfFirst { it.wireId == wireId && it.isSent }
        if (index < 0) return false
        val message = messages[index]
        if (message.deleted || message.type != ChatMessage.Type.TEXT) return false
        messages[index] = message.copy(text = newText, edited = true)
        refreshVisible()
        notifyMessageChanged(wireId)
        return true
    }

    /**
     * Deletes one of OUR OWN messages on our own screen (the
     * delete command itself is broadcast by the caller through
     * ChatManager), exactly like a delete that arrives from the
     * author does on the other phones.
     */
    fun deleteOwnMessage(wireId: String): Boolean {
        val index = messages.indexOfFirst { it.wireId == wireId && it.isSent }
        if (index < 0) return false
        if (messages[index].deleted) return false
        markDeleted(index)
        return true
    }

    /**
     * Turns the message at [index] into the deleted placeholder:
     * the text is dropped, the reactions are cleared, and the
     * message is flagged deleted — the row itself stays, so quotes
     * of it, other messages' reactions and the history positions
     * are unaffected. The media file behind a voice, photo, video
     * or file message is deleted from this phone on a best-effort
     * basis (a voice that is playing right now stops first); the
     * recorded path stays on the row, unused, so a history saved
     * from here reloads the placeholder (the history store keeps
     * deleted rows whose file is gone).
     */
    private fun markDeleted(index: Int) {
        val message = messages[index]
        val mediaPath = when (message.type) {
            ChatMessage.Type.VOICE -> message.audioPath
            ChatMessage.Type.PHOTO -> message.imagePath
            ChatMessage.Type.VIDEO -> message.videoPath
            ChatMessage.Type.FILE -> message.filePath
            ChatMessage.Type.TEXT, ChatMessage.Type.LOCATION -> null
        }
        if (mediaPath != null && mediaPath == playingAudioPath) {
            releasePlayer()
        }
        message.reactions.clear()
        messages[index] = message.copy(text = "", deleted = true)
        if (mediaPath != null) {
            try {
                File(mediaPath).delete()
            } catch (ignored: Exception) {
                // Best effort only: the placeholder shows either way.
            }
        }
        refreshVisible()
        message.wireId?.let { notifyMessageChanged(it) }
    }

    /**
     * The reactions line for a bubble: every used emoji (in the
     * menu's fixed order, so all phones render the same line) with
     * the number of people who picked it, joined by wide spaces —
     * "❤️ 2  👍 1". Null when the message has no reactions.
     */
    private fun formatReactions(message: ChatMessage): String? {
        if (message.reactions.isEmpty()) return null
        val parts = mutableListOf<String>()
        for (emoji in ChatManager.REACTION_EMOJIS) {
            val count = message.reactions[emoji]?.size ?: 0
            if (count > 0) parts.add("$emoji $count")
        }
        // An emoji outside the known six cannot arrive (the wire
        // parser rejects it), but a hand-edited history could hold
        // one; it is simply not shown.
        return parts.takeIf { it.isNotEmpty() }?.joinToString("  ")
    }

    /**
     * A copy of the full chat, oldest first — never the filtered
     * slice, so a history saved while searching loses nothing.
     */
    fun snapshot(): List<ChatMessage> = messages.toList()

    /** Replaces everything shown with [history] (used at startup). */
    fun setMessages(history: List<ChatMessage>) {
        messages.clear()
        messages.addAll(history)
        refreshVisible()
        notifyDataSetChanged()
    }

    /** Removes all messages from the screen (used by Clear chat). */
    fun clearMessages() {
        releasePlayer()
        messages.clear()
        refreshVisible()
        notifyDataSetChanged()
    }

    // ---------- Chat search ----------

    /**
     * Narrows the shown rows to the messages matching [query], or
     * restores the full chat when it is blank. Only the visible
     * slice changes — the full list (and with it the saved history,
     * the reactions and the delivered ticks) is never touched.
     */
    fun setFilterQuery(query: String) {
        val normalized = query.trim().lowercase(Locale.ROOT)
        if (normalized == filterQuery) return
        filterQuery = normalized
        refreshVisible()
        notifyDataSetChanged()
    }

    /** Rebuilds the visible slice from the full list and the query. */
    private fun refreshVisible() {
        visibleMessages = if (filterQuery.isEmpty()) {
            messages.toList()
        } else {
            messages.filter { matchesQuery(it) }
        }
    }

    /**
     * Whether [message] matches the current search text: a
     * case-insensitive substring hit on what a chat row actually
     * shows — the message text, the sender label of a group message,
     * the quote a reply carries, or a file's name. Photos and videos
     * also match on the name of the file they are stored under;
     * voice notes and locations carry no searchable words of their
     * own, so for them only the sender and quote fields count.
     */
    private fun matchesQuery(message: ChatMessage): Boolean {
        val query = filterQuery
        if (query.isEmpty()) return true
        fun hit(value: String?): Boolean =
            value != null && value.lowercase(Locale.ROOT).contains(query)
        if (hit(message.senderName) || hit(message.quotedSender) ||
            hit(message.quotedText)
        ) {
            return true
        }
        return when (message.type) {
            ChatMessage.Type.TEXT -> hit(message.text)
            ChatMessage.Type.FILE -> hit(
                message.fileName ?: message.filePath?.let { File(it).name }
            )
            ChatMessage.Type.PHOTO ->
                hit(message.imagePath?.let { File(it).name })
            ChatMessage.Type.VIDEO ->
                hit(message.videoPath?.let { File(it).name })
            ChatMessage.Type.VOICE, ChatMessage.Type.LOCATION -> false
        }
    }

    // ---------- Voice playback ----------

    private fun togglePlay(message: ChatMessage, position: Int) {
        val path = message.audioPath ?: return

        // Tapping the message that is already loaded just pauses/resumes it.
        val current = mediaPlayer
        if (current != null && playingAudioPath == path) {
            try {
                if (playerPlaying) {
                    current.pause()
                    playerPlaying = false
                } else {
                    current.start()
                    playerPlaying = true
                }
            } catch (e: Exception) {
                releasePlayer()
            }
            notifyItemChanged(position)
            return
        }

        // A different message: stop the old one first. The old row
        // is found by its audio file rather than by the position it
        // had when playback started: a search typed in the meantime
        // may have reshuffled (or hidden) the rows since then.
        val previousPosition = playingVisibleIndex()
            .takeIf { it >= 0 } ?: playingPosition
        releasePlayer()
        if (previousPosition >= 0 && previousPosition != position &&
            previousPosition < visibleMessages.size
        ) {
            notifyItemChanged(previousPosition)
        }

        val player = MediaPlayer()
        try {
            player.setDataSource(path)
            player.setOnCompletionListener { onPlaybackFinished() }
            player.prepare()
            player.start()
        } catch (e: Exception) {
            try {
                player.release()
            } catch (ignored: Exception) {
                // Player already gone.
            }
            // No context handy here for a Toast; the icon simply stays
            // on "play", which is the honest state.
            return
        }
        mediaPlayer = player
        playingAudioPath = path
        playingPosition = position
        playerPlaying = true
        notifyItemChanged(position)
    }

    private fun onPlaybackFinished() {
        // Same lookup as when switching messages: the finished row
        // is identified by its file, not by a position that a search
        // may have moved or hidden while it played.
        val finishedPosition = playingVisibleIndex()
            .takeIf { it >= 0 } ?: playingPosition
        releasePlayer()
        if (finishedPosition >= 0 && finishedPosition < visibleMessages.size) {
            notifyItemChanged(finishedPosition)
        }
    }

    /**
     * The visible row of the voice message loaded in the player, or
     * -1 when nothing is loaded or the search currently hides it.
     * The bind logic picks the pause icon by the same key (the audio
     * file), so the icon stays right even when this returns -1 — the
     * row simply redraws with the play icon next time it is bound.
     */
    private fun playingVisibleIndex(): Int {
        val path = playingAudioPath ?: return -1
        return visibleMessages.indexOfFirst { it.audioPath == path }
    }

    private fun releasePlayer() {
        try {
            mediaPlayer?.release()
        } catch (ignored: Exception) {
            // Player already gone.
        }
        mediaPlayer = null
        playingAudioPath = null
        playingPosition = -1
        playerPlaying = false
    }

    private fun formatDuration(totalSeconds: Int): String =
        String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60)

    // ---------- Photos ----------

    /**
     * Loads a picture from [path] decoded only as large as asked for, so
     * the chat list never holds a full-size photo in memory.
     */
    private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                bounds.outWidth, bounds.outHeight, reqWidth, reqHeight
            )
        }
        return try {
            BitmapFactory.decodeFile(path, options)
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (
                halfHeight / inSampleSize >= reqHeight &&
                halfWidth / inSampleSize >= reqWidth
            ) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /** Shows the tapped photo large, in a simple dialog. Tap OK to close. */
    private fun showPhotoFullScreen(context: Context, path: String) {
        val metrics = context.resources.displayMetrics
        val bitmap = decodeSampledBitmap(path, metrics.widthPixels, metrics.heightPixels)
            ?: return
        val imageView = ImageView(context).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = ViewGroup.LayoutParams(
                metrics.widthPixels,
                (metrics.heightPixels * 0.8).toInt()
            )
        }
        AlertDialog.Builder(context)
            .setView(imageView)
            .setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // ---------- Videos ----------

    // The dark stand-in shown when a video has no readable frame;
    // built once and shared by every video row.
    private var videoPlaceholder: Bitmap? = null

    private fun videoPlaceholderBitmap(context: Context): Bitmap {
        videoPlaceholder?.let { return it }
        val bitmap = Bitmap.createBitmap(440, 260, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(AppColors.videoPlaceholder(context))
        videoPlaceholder = bitmap
        return bitmap
    }

    /**
     * Grabs one frame from the video at [path] (about a second in,
     * falling back to the very first frame), scaled down to at most
     * [maxWidthPx] wide so the chat list never holds a full-size frame.
     * Null when the file cannot be read as a video at all.
     */
    private fun grabVideoFrame(path: String, maxWidthPx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val frame = retriever.getFrameAtTime(
                1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime(
                0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            if (frame != null && frame.width > maxWidthPx) {
                val scale = maxWidthPx.toFloat() / frame.width
                val scaled = Bitmap.createScaledBitmap(
                    frame,
                    maxWidthPx,
                    (frame.height * scale).toInt().coerceAtLeast(1),
                    true
                )
                if (scaled !== frame) {
                    frame.recycle()
                }
                scaled
            } else {
                frame
            }
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {
                // Nothing to clean up then.
            }
        }
    }

    /**
     * Plays a tapped video in the phone's own video player, through
     * FileProvider (the private folder and the cache folder are both
     * shared roots, so received and sent videos both work). A friendly
     * Toast appears when that is not possible.
     */
    private fun openVideo(context: Context, message: ChatMessage) {
        val path = message.videoPath
        val file = path?.let { File(it) }
        if (file == null || !file.exists()) {
            Toast.makeText(context, R.string.file_gone, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = try {
            FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", file
            )
        } catch (e: Exception) {
            Toast.makeText(context, R.string.file_gone, Toast.LENGTH_SHORT).show()
            return
        }
        val extensionMime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT))
        val mimeType = if (extensionMime != null && extensionMime.startsWith("video/")) {
            extensionMime
        } else {
            "video/*"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, R.string.no_app_for_file, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Locations ----------

    /** The coordinates as shown in a location bubble: "29.236055, 71.065222". */
    private fun formatCoordinates(message: ChatMessage): String =
        String.format(
            Locale.US, "%.6f, %.6f",
            message.latitude ?: 0.0, message.longitude ?: 0.0
        )

    /**
     * Opens a tapped location in the phone's maps app (whichever the
     * user picks in the chooser). A friendly Toast appears when no app
     * on the phone can show a position.
     */
    private fun openLocation(context: Context, message: ChatMessage) {
        val latitude = message.latitude
        val longitude = message.longitude
        if (latitude == null || longitude == null) return
        val coordinates = String.format(Locale.US, "%.6f,%.6f", latitude, longitude)
        val geoUri = Uri.parse(
            "geo:$coordinates?q=$coordinates(Shared%20location)"
        )
        val intent = Intent(Intent.ACTION_VIEW, geoUri)
        try {
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.location_title))
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_app_for_location, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, R.string.no_app_for_location, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Files ----------

    /** Writes a byte count the way people expect: B, KB or MB. */
    private fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 ->
            String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        else ->
            String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /**
     * Opens a tapped file in another app (a PDF viewer, a gallery, ...)
     * through FileProvider, so the other app gets read access to this one
     * file only. A friendly Toast appears when that is not possible.
     */
    private fun openFile(context: Context, message: ChatMessage) {
        val path = message.filePath
        val file = path?.let { File(it) }
        if (file == null || !file.exists()) {
            Toast.makeText(context, R.string.file_gone, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = try {
            FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", file
            )
        } catch (e: Exception) {
            Toast.makeText(context, R.string.file_gone, Toast.LENGTH_SHORT).show()
            return
        }
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT))
            ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_app_for_file, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, R.string.no_app_for_file, Toast.LENGTH_SHORT).show()
        }
    }
}
