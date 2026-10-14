package com.ashfaq.offlinechat

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.bubbleCard)
        val messageText: TextView = view.findViewById(R.id.messageText)
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

        val params = holder.card.layoutParams as LinearLayout.LayoutParams
        if (message.isSent) {
            params.gravity = Gravity.END
            holder.card.setCardBackgroundColor(
                ContextCompat.getColor(context, R.color.bubble_sent)
            )
            holder.messageText.setTextColor(
                ContextCompat.getColor(context, R.color.bubble_sent_text)
            )
            holder.timeText.setTextColor(
                ContextCompat.getColor(context, R.color.bubble_sent_text)
            )
        } else {
            params.gravity = Gravity.START
            holder.card.setCardBackgroundColor(
                ContextCompat.getColor(context, R.color.bubble_received)
            )
            holder.messageText.setTextColor(
                ContextCompat.getColor(context, R.color.bubble_received_text)
            )
            holder.timeText.setTextColor(
                ContextCompat.getColor(context, R.color.text_secondary)
            )
        }
        holder.card.layoutParams = params
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }
}
