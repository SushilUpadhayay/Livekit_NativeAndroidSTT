package com.example.ondevicestt

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(
    private val messages: MutableList<ChatMessage> = mutableListOf()
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_NEXA = 2
    }

    private var hasActiveUserInterim = false
    private var hasActiveNexaInterim = false

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) VIEW_TYPE_USER else VIEW_TYPE_NEXA
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER) {
            val view = inflater.inflate(R.layout.item_chat_user, parent, false)
            UserViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_chat_nexa, parent, false)
            NexaViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        if (holder is UserViewHolder) {
            holder.bind(message)
        } else if (holder is NexaViewHolder) {
            holder.bind(message)
        }
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(message: ChatMessage) {
        hasActiveUserInterim = false
        hasActiveNexaInterim = false
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun updateUserInterimMessage(sender: String, text: String, timestamp: String) {
        hasActiveNexaInterim = false
        if (hasActiveUserInterim && messages.isNotEmpty() && messages.last().isUser) {
            val lastIndex = messages.size - 1
            messages[lastIndex] = ChatMessage(sender, text, isUser = true, timestamp = timestamp)
            notifyItemChanged(lastIndex)
        } else {
            messages.add(ChatMessage(sender, text, isUser = true, timestamp = timestamp))
            hasActiveUserInterim = true
            notifyItemInserted(messages.size - 1)
        }
    }

    fun discardUserInterimMessage() {
        if (hasActiveUserInterim && messages.isNotEmpty() && messages.last().isUser) {
            val lastIndex = messages.size - 1
            messages.removeAt(lastIndex)
            notifyItemRemoved(lastIndex)
        }
        hasActiveUserInterim = false
    }

    fun finalizeUserInterimMessage(sender: String, text: String, timestamp: String) {
        if (hasActiveUserInterim && messages.isNotEmpty() && messages.last().isUser) {
            val lastIndex = messages.size - 1
            messages[lastIndex] = ChatMessage(sender, text, isUser = true, timestamp = timestamp)
            notifyItemChanged(lastIndex)
        } else {
            messages.add(ChatMessage(sender, text, isUser = true, timestamp = timestamp))
            notifyItemInserted(messages.size - 1)
        }
        hasActiveUserInterim = false
    }

    fun updateNexaInterimMessage(sender: String, text: String, timestamp: String) {
        hasActiveUserInterim = false
        if (hasActiveNexaInterim && messages.isNotEmpty() && !messages.last().isUser) {
            val lastIndex = messages.size - 1
            messages[lastIndex] = ChatMessage(sender, text, isUser = false, timestamp = timestamp)
            notifyItemChanged(lastIndex)
        } else {
            messages.add(ChatMessage(sender, text, isUser = false, timestamp = timestamp))
            hasActiveNexaInterim = true
            notifyItemInserted(messages.size - 1)
        }
    }

    fun finalizeNexaInterimMessage(sender: String, text: String, timestamp: String) {
        if (hasActiveNexaInterim && messages.isNotEmpty() && !messages.last().isUser) {
            val lastIndex = messages.size - 1
            messages[lastIndex] = ChatMessage(sender, text, isUser = false, timestamp = timestamp)
            notifyItemChanged(lastIndex)
        } else {
            messages.add(ChatMessage(sender, text, isUser = false, timestamp = timestamp))
            notifyItemInserted(messages.size - 1)
        }
        hasActiveNexaInterim = false
    }

    fun isEmpty(): Boolean = messages.isEmpty()

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.userMessageText)
        private val timestampText: TextView = itemView.findViewById(R.id.userTimestamp)
        private val userName: TextView = itemView.findViewById(R.id.userName)

        fun bind(message: ChatMessage) {
            messageText.text = message.text
            timestampText.text = message.timestamp
            userName.text = message.sender
        }
    }

    class NexaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.nexaMessageText)
        private val timestampText: TextView = itemView.findViewById(R.id.nexaTimestamp)
        private val nexaName: TextView = itemView.findViewById(R.id.nexaName)

        fun bind(message: ChatMessage) {
            messageText.text = message.text
            timestampText.text = message.timestamp
            nexaName.text = message.sender
        }
    }
}
