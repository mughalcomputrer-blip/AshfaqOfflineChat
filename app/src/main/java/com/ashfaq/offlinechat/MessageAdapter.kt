package com.ashfaq.offlinechat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
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
    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    // Voice playback state: at most one voice message plays at a time.
    private var mediaPlayer: MediaPlayer? = null
    private var playingAudioPath: String? = null
    private var playingPosition: Int = -1
    private var playerPlaying = false

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val bubble: View = view.findViewById(R.id.bubbleView)
        val messageText: TextView = view.findViewById(R.id.messageText)
        val voiceRow: View = view.findViewById(R.id.voiceRow)
        val buttonPlay: ImageButton = view.findViewById(R.id.buttonPlay)
        val durationText: TextView = view.findViewById(R.id.durationText)
        val photoImage: ImageView = view.findViewById(R.id.photoImage)
        val fileRow: View = view.findViewById(R.id.fileRow)
        val fileIcon: ImageView = view.findViewById(R.id.fileIcon)
        val fileNameText: TextView = view.findViewById(R.id.fileNameText)
        val fileSizeText: TextView = view.findViewById(R.id.fileSizeText)
        val timeText: TextView = view.findViewById(R.id.timeText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        val context = holder.itemView.context

        holder.messageText.text = message.text
        holder.timeText.text = timeFormat.format(Date(message.timestamp))

        val params = holder.bubble.layoutParams as LinearLayout.LayoutParams
        if (message.isSent) {
            params.gravity = Gravity.END
            holder.bubble.setBackgroundResource(R.drawable.bg_bubble_sent)
            holder.messageText.setTextColor(
                ContextCompat.getColor(context, R.color.bubble_sent_text)
            )
            holder.timeText.setTextColor(
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
        } else {
            params.gravity = Gravity.START
            holder.bubble.setBackgroundResource(R.drawable.bg_bubble_received)
            holder.messageText.setTextColor(
                ContextCompat.getColor(context, R.color.bubble_received_text)
            )
            holder.timeText.setTextColor(
                ContextCompat.getColor(context, R.color.text_secondary)
            )
            holder.durationText.setTextColor(
                ContextCompat.getColor(context, R.color.bubble_received_text)
            )
            holder.buttonPlay.setColorFilter(
                ContextCompat.getColor(context, R.color.accent)
            )
            holder.fileNameText.setTextColor(
                ContextCompat.getColor(context, R.color.bubble_received_text)
            )
            holder.fileSizeText.setTextColor(
                ContextCompat.getColor(context, R.color.text_secondary)
            )
            holder.fileIcon.setColorFilter(
                ContextCompat.getColor(context, R.color.accent)
            )
        }
        holder.bubble.layoutParams = params

        when (message.type) {
            ChatMessage.Type.VOICE -> {
                holder.messageText.visibility = View.GONE
                holder.photoImage.visibility = View.GONE
                holder.photoImage.setOnClickListener(null)
                holder.fileRow.visibility = View.GONE
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
                holder.fileRow.visibility = View.GONE
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
                holder.fileRow.visibility = View.VISIBLE
                holder.fileNameText.text = message.fileName
                    ?: message.filePath?.let { File(it).name }
                    ?: ""
                holder.fileSizeText.text = formatFileSize(message.fileSizeBytes)
                holder.bubble.setOnClickListener { openFile(context, message) }
            }
            ChatMessage.Type.TEXT -> {
                holder.messageText.visibility = View.VISIBLE
                holder.voiceRow.visibility = View.GONE
                holder.photoImage.visibility = View.GONE
                holder.photoImage.setOnClickListener(null)
                holder.buttonPlay.setOnClickListener(null)
                holder.fileRow.visibility = View.GONE
                holder.bubble.setOnClickListener(null)
            }
        }
    }

    override fun getItemCount(): Int = messages.size

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        releasePlayer()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    /** A copy of the messages currently shown, oldest first. */
    fun snapshot(): List<ChatMessage> = messages.toList()

    /** Replaces everything shown with [history] (used at startup). */
    fun setMessages(history: List<ChatMessage>) {
        messages.clear()
        messages.addAll(history)
        notifyDataSetChanged()
    }

    /** Removes all messages from the screen (used by Clear chat). */
    fun clearMessages() {
        releasePlayer()
        messages.clear()
        notifyDataSetChanged()
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

        // A different message: stop the old one first.
        val previousPosition = playingPosition
        releasePlayer()
        if (previousPosition >= 0 && previousPosition != position) {
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
        val finishedPosition = playingPosition
        releasePlayer()
        if (finishedPosition >= 0) {
            notifyItemChanged(finishedPosition)
        }
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
