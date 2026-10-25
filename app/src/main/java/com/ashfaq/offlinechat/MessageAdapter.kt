package com.ashfaq.offlinechat

import android.media.MediaPlayer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

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
        }
        holder.bubble.layoutParams = params

        if (message.type == ChatMessage.Type.VOICE) {
            holder.messageText.visibility = View.GONE
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
        } else {
            holder.messageText.visibility = View.VISIBLE
            holder.voiceRow.visibility = View.GONE
            holder.buttonPlay.setOnClickListener(null)
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
}
