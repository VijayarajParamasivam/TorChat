package com.torchat.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.torchat.R
import com.torchat.core.model.Message
import com.torchat.core.model.MessageState
import com.torchat.databinding.ItemDayBinding
import com.torchat.databinding.ItemMessageInBinding
import com.torchat.databinding.ItemMessageOutBinding
import com.torchat.ui.common.Format

/** What a thread is made of: the banner, day separators, and messages. */
sealed interface ChatItem {
    val key: String

    data object Banner : ChatItem {
        override val key = "banner"
    }

    data class Day(val ts: Long) : ChatItem {
        override val key = "day-$ts"
    }

    data class Msg(val message: Message) : ChatItem {
        // Direction is part of the key: the two directions share an ID space
        // (§8.6), so an ID alone is not unique within a conversation.
        override val key = "${if (message.mine) "m" else "t"}-${message.id}"
    }

    companion object {
        /** A day separator before the first message of each day. */
        fun build(messages: List<Message>): List<ChatItem> = buildList {
            add(Banner)
            var previous = 0L
            for (message in messages) {
                if (previous == 0L || !Format.sameDay(previous, message.ts)) add(Day(message.ts))
                add(Msg(message))
                previous = message.ts
            }
        }
    }
}

/**
 * The thread. DiffUtil works out what changed, so a delivery receipt flipping
 * one tick to two redraws one row rather than the whole conversation.
 */
class MessageAdapter : ListAdapter<ChatItem, RecyclerView.ViewHolder>(Diff) {

    override fun getItemViewType(position: Int): Int = when (val item = getItem(position)) {
        ChatItem.Banner -> TYPE_BANNER
        is ChatItem.Day -> TYPE_DAY
        is ChatItem.Msg -> if (item.message.mine) TYPE_OUT else TYPE_IN
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_BANNER -> Plain(inflater.inflate(R.layout.item_chat_banner, parent, false))
            TYPE_DAY -> DayHolder(ItemDayBinding.inflate(inflater, parent, false))
            TYPE_OUT -> OutHolder(ItemMessageOutBinding.inflate(inflater, parent, false))
            else -> InHolder(ItemMessageInBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        val context = holder.itemView.context
        when (holder) {
            is DayHolder -> holder.binding.day.text = Format.day(context, (item as ChatItem.Day).ts)

            is InHolder -> {
                val message = (item as ChatItem.Msg).message
                holder.binding.body.text = message.body
                holder.binding.time.text = Format.time(context, message.ts)
            }

            is OutHolder -> {
                val message = (item as ChatItem.Msg).message
                holder.binding.body.text = message.body
                holder.binding.time.text = Format.time(context, message.ts)

                // Only an ack from their app earns the second tick (§8.1).
                val (icon, label) = when (message.state) {
                    MessageState.DELIVERED -> R.drawable.ic_done_all to R.string.state_delivered
                    MessageState.SENT -> R.drawable.ic_check to R.string.state_sent
                    else -> R.drawable.ic_schedule to R.string.state_queued
                }
                holder.binding.state.setImageResource(icon)
                holder.binding.state.contentDescription = context.getString(label)
                holder.binding.state.setColorFilter(
                    context.getColor(if (message.state == MessageState.DELIVERED) R.color.tc_signal else R.color.tc_on_primary_container)
                )
            }
        }
    }

    private class Plain(view: View) : RecyclerView.ViewHolder(view)
    private class DayHolder(val binding: ItemDayBinding) : RecyclerView.ViewHolder(binding.root)
    private class InHolder(val binding: ItemMessageInBinding) : RecyclerView.ViewHolder(binding.root)
    private class OutHolder(val binding: ItemMessageOutBinding) : RecyclerView.ViewHolder(binding.root)

    private object Diff : DiffUtil.ItemCallback<ChatItem>() {
        override fun areItemsTheSame(old: ChatItem, new: ChatItem) = old.key == new.key
        override fun areContentsTheSame(old: ChatItem, new: ChatItem) = old == new
    }

    private companion object {
        const val TYPE_BANNER = 0
        const val TYPE_DAY = 1
        const val TYPE_IN = 2
        const val TYPE_OUT = 3
    }
}
