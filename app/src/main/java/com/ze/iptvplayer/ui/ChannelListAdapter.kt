package com.ze.iptvplayer.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.ze.iptvplayer.R
import com.ze.iptvplayer.data.FavoritesManager
import com.ze.iptvplayer.data.PlaylistRepository
import com.ze.iptvplayer.databinding.ItemChannelGridCellBinding
import com.ze.iptvplayer.databinding.ItemChannelListRowBinding
import com.ze.iptvplayer.model.Channel
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Shows channels either as list rows (logo left, name/group right) or grid cells
 * (big logo, name below) depending on [isGrid]. Call [setGridMode] to switch — the
 * caller is also responsible for swapping the RecyclerView's LayoutManager to match.
 */
class ChannelListAdapter(
    private val context: Context,
    private var channels: List<Channel>,
    private var isGrid: Boolean,
    private val onClick: (Channel) -> Unit,
    private val onLongClick: (Channel, View) -> Unit,
    /** Overrides the logo square's background to match wherever this list is actually shown —
     *  the two layouts default to R.color.background_color, which is right for MainActivity's
     *  own (matching) background but visibly off in a context with a different background, like
     *  PlayerActivity's channel sidebar (#1A1D24). Null keeps each layout's own XML default. */
    private val logoBackgroundColor: Int? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val VIEW_TYPE_LIST = 0
        const val VIEW_TYPE_GRID = 1
    }

    inner class ListViewHolder(val binding: ItemChannelListRowBinding) : RecyclerView.ViewHolder(binding.root)
    inner class GridViewHolder(val binding: ItemChannelGridCellBinding) : RecyclerView.ViewHolder(binding.root)

    fun submitList(newChannels: List<Channel>) {
        channels = newChannels
        notifyDataSetChanged()
    }

    /** Re-binds every row in place (same list, same positions, so scroll position and focus
     *  stay put) — for when data the rows show, like EPG now/next, arrives after first display. */
    fun rebindAll() {
        notifyItemRangeChanged(0, itemCount)
    }

    fun setGridMode(grid: Boolean) {
        if (isGrid == grid) return
        isGrid = grid
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = if (isGrid) VIEW_TYPE_GRID else VIEW_TYPE_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_GRID) {
            GridViewHolder(ItemChannelGridCellBinding.inflate(inflater, parent, false))
        } else {
            ListViewHolder(ItemChannelListRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val channel = channels[position]
        val isFavorite = FavoritesManager.isFavorite(context, channel.key)
        val displayName = if (isFavorite) "★ ${channel.name}" else channel.name

        when (holder) {
            is ListViewHolder -> {
                holder.binding.channelName.text = displayName
                val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                val (current, next) = PlaylistRepository.nowNext(context, channel)
                if (current != null) {
                    holder.binding.channelNowProgramme.text =
                        "${fmt.format(current.startMillis)}–${fmt.format(current.stopMillis)}  ${current.title}"
                    holder.binding.channelNowProgramme.visibility = View.VISIBLE
                } else {
                    holder.binding.channelNowProgramme.visibility = View.GONE
                }
                if (next != null) {
                    holder.binding.channelNextProgramme.text = "${fmt.format(next.startMillis)}  ${next.title}"
                    holder.binding.channelNextProgramme.visibility = View.VISIBLE
                } else {
                    holder.binding.channelNextProgramme.visibility = View.GONE
                }
                loadLogo(holder.binding.channelLogo, channel.logoUrl)
                holder.binding.root.setOnClickListener { onClick(channel) }
                holder.binding.root.setOnLongClickListener { onLongClick(channel, holder.binding.root); true }
            }
            is GridViewHolder -> {
                holder.binding.channelName.text = displayName
                loadLogo(holder.binding.channelLogo, channel.logoUrl)
                holder.binding.root.setOnClickListener { onClick(channel) }
                holder.binding.root.setOnLongClickListener { onLongClick(channel, holder.binding.root); true }
            }
        }
    }

    private fun loadLogo(view: ImageView, url: String?) {
        logoBackgroundColor?.let { view.setBackgroundColor(it) }
        if (!url.isNullOrBlank()) {
            Glide.with(view.context)
                .load(url)
                .placeholder(R.drawable.default_channel_logo)
                .error(R.drawable.default_channel_logo)
                .into(view)
        } else {
            Glide.with(view.context).clear(view)
            view.setImageResource(R.drawable.default_channel_logo)
        }
    }

    override fun getItemCount(): Int = channels.size
}
