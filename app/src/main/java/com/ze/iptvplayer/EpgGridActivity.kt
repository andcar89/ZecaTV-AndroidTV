package com.ze.iptvplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.ze.iptvplayer.data.PlaylistRepository
import com.ze.iptvplayer.databinding.ActivityEpgGridBinding
import com.ze.iptvplayer.databinding.ItemEpgChannelRowBinding
import com.ze.iptvplayer.model.Channel
import com.ze.iptvplayer.model.EpgProgramme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * A TiviMate-style EPG grid: channels down the side, a shared timeline across the top,
 * and every row's horizontal scroll position kept in sync with the header and each other.
 *
 * First version covers a fixed window (now, rounded down to the half hour, through +6h) —
 * no day paging yet.
 */
class EpgGridActivity : AppCompatActivity() {

    companion object {
        private const val WINDOW_HOURS = 6
        private const val PX_PER_MINUTE_DP = 4f
    }

    private lateinit var binding: ActivityEpgGridBinding
    private var pxPerMinute = 0f
    private var windowStart = 0L
    private var windowEnd = 0L

    /** Every row's inner scroll view currently attached to the RecyclerView, kept in sync as a group. */
    private val attachedRowScrollViews = mutableSetOf<HorizontalScrollView>()
    private var isSyncingScroll = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEpgGridBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pxPerMinute = PX_PER_MINUTE_DP * resources.displayMetrics.density

        val cal = Calendar.getInstance()
        cal.set(Calendar.MINUTE, if (cal.get(Calendar.MINUTE) < 30) 0 else 30)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        windowStart = cal.timeInMillis
        windowEnd = windowStart + WINDOW_HOURS * 60L * 60L * 1000L

        buildHeader()
        binding.headerScroll.setOnScrollChangeListener { v, scrollX, _, _, _ -> syncScroll(scrollX, v as View) }

        binding.channelRows.layoutManager = LinearLayoutManager(this)
        // The TV Guide is a live-TV concept — movies/TV show episodes never have programme data.
        binding.channelRows.adapter = ChannelRowAdapter(
            PlaylistRepository.channels.filter { it.contentType == com.ze.iptvplayer.model.ContentType.LIVE }
        )
    }

    private fun buildHeader() {
        binding.headerTicks.removeAllViews()
        val totalMinutes = ((windowEnd - windowStart) / 60000L).toInt()
        binding.headerTicks.layoutParams = binding.headerTicks.layoutParams.apply {
            width = (totalMinutes * pxPerMinute).toInt()
        }

        var cursor = windowStart
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (cursor < windowEnd) {
            val label = TextView(this).apply {
                text = fmt.format(cursor)
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 12f
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams((30 * pxPerMinute).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
                setPadding(8, 0, 0, 0)
            }
            binding.headerTicks.addView(label)
            cursor += 30 * 60 * 1000L
        }
    }

    /** Applies [scrollX] to the header and every other attached row, guarding against feedback loops. */
    private fun syncScroll(scrollX: Int, source: View) {
        if (isSyncingScroll) return
        isSyncingScroll = true
        if (source !== binding.headerScroll) binding.headerScroll.scrollTo(scrollX, 0)
        attachedRowScrollViews.forEach { if (it !== source) it.scrollTo(scrollX, 0) }
        isSyncingScroll = false
    }

    private inner class ChannelRowAdapter(private val channels: List<Channel>) :
        RecyclerView.Adapter<ChannelRowAdapter.RowViewHolder>() {

        inner class RowViewHolder(val rowBinding: ItemEpgChannelRowBinding) :
            RecyclerView.ViewHolder(rowBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
            val rowBinding = ItemEpgChannelRowBinding.inflate(layoutInflater, parent, false)
            return RowViewHolder(rowBinding)
        }

        override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
            val channel = channels[position]
            holder.rowBinding.channelNameText.text = channel.name
            if (!channel.logoUrl.isNullOrBlank()) {
                Glide.with(this@EpgGridActivity)
                    .load(channel.logoUrl)
                    .placeholder(R.drawable.default_channel_logo)
                    .error(R.drawable.default_channel_logo)
                    .into(holder.rowBinding.channelLogo)
            } else {
                Glide.with(this@EpgGridActivity).clear(holder.rowBinding.channelLogo)
                holder.rowBinding.channelLogo.setImageResource(R.drawable.default_channel_logo)
            }

            val container = holder.rowBinding.programmesContainer
            container.removeAllViews()

            val programmes = PlaylistRepository.programmesFor(this@EpgGridActivity, channel, windowStart, windowEnd)
            if (programmes.isEmpty()) {
                container.addView(fillerCell((windowEnd - windowStart) / 60000L))
            } else {
                var cursor = windowStart
                for (p in programmes) {
                    val start = maxOf(p.startMillis, windowStart)
                    val stop = minOf(p.stopMillis, windowEnd)
                    if (start > cursor) {
                        container.addView(fillerCell((start - cursor) / 60000L))
                    }
                    container.addView(programmeCell(p, channel, start, stop))
                    cursor = stop
                }
                if (cursor < windowEnd) {
                    container.addView(fillerCell((windowEnd - cursor) / 60000L))
                }
            }

            attachedRowScrollViews.add(holder.rowBinding.programmesScroll)
            holder.rowBinding.programmesScroll.scrollTo(0, 0)
            holder.rowBinding.programmesScroll.setOnScrollChangeListener { v, scrollX, _, _, _ ->
                syncScroll(scrollX, v as View)
            }
        }

        override fun onViewRecycled(holder: RowViewHolder) {
            super.onViewRecycled(holder)
            attachedRowScrollViews.remove(holder.rowBinding.programmesScroll)
        }

        override fun getItemCount(): Int = channels.size

        private fun fillerCell(durationMinutes: Long): View = TextView(this@EpgGridActivity).apply {
            layoutParams = LinearLayout.LayoutParams(
                maxOf(1, (durationMinutes * pxPerMinute).toInt()),
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { marginEnd = 1; marginStart = 1 }
            text = if (durationMinutes >= 20) this@EpgGridActivity.getString(R.string.label_no_programme_info) else ""
            setTextColor(0xFF666666.toInt())
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0xFF15171C.toInt())
        }

        private fun programmeCell(p: EpgProgramme, channel: Channel, start: Long, stop: Long): View {
            val durationMinutes = (stop - start) / 60000L
            val now = System.currentTimeMillis()
            val isCurrent = now in p.startMillis until p.stopMillis
            return TextView(this@EpgGridActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    maxOf(1, (durationMinutes * pxPerMinute).toInt()),
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply { marginEnd = 1; marginStart = 1 }
                text = p.title
                setTextColor(if (isCurrent) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt())
                textSize = 12f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(12, 0, 12, 0)
                setBackgroundResource(R.drawable.epg_cell_background)
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener {
                    val intent = Intent(this@EpgGridActivity, PlayerActivity::class.java)
                    intent.putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.key)
                    intent.putExtra(PlayerActivity.EXTRA_CATEGORY_ID, PlaylistRepository.CATEGORY_ALL)
                    startActivity(intent)
                }
            }
        }
    }
}
