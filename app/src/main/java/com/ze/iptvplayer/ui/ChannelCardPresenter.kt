package com.ze.iptvplayer.ui

import android.view.ViewGroup
import android.widget.Toast
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.ze.iptvplayer.R
import com.ze.iptvplayer.data.FavoritesManager
import com.ze.iptvplayer.model.Channel

/**
 * Long-press (long-click on the remote's select button) toggles favorite status directly —
 * this bypasses Leanback's row-level click listener since it's set on the card view itself.
 * Pass a [onFavoriteChanged] callback if the caller needs to refresh rows afterward (e.g. to
 * remove a channel from a "Favorites" row immediately after un-favoriting it).
 */
class ChannelCardPresenter(
    private val onFavoriteChanged: (() -> Unit)? = null
) : Presenter() {

    private val cardWidth = 300
    private val cardHeight = 170

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(cardWidth, cardHeight)
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val channel = item as Channel
        val cardView = viewHolder.view as ImageCardView
        val isFavorite = FavoritesManager.isFavorite(cardView.context, channel.key)
        cardView.titleText = if (isFavorite) "★ ${channel.name}" else channel.name
        cardView.contentText = channel.groupTitle
        cardView.setMainImageDimensions(cardWidth, cardHeight)

        cardView.setOnLongClickListener {
            val nowFavorite = FavoritesManager.toggle(cardView.context, channel.key)
            Toast.makeText(
                cardView.context,
                if (nowFavorite) cardView.context.getString(R.string.toast_added_to_favorites) else cardView.context.getString(R.string.toast_removed_from_favorites),
                Toast.LENGTH_SHORT
            ).show()
            cardView.titleText = if (nowFavorite) "★ ${channel.name}" else channel.name
            onFavoriteChanged?.invoke()
            true
        }

        if (!channel.logoUrl.isNullOrBlank()) {
            Glide.with(cardView.context)
                .load(channel.logoUrl)
                .placeholder(R.drawable.default_channel_logo)
                .error(R.drawable.default_channel_logo)
                .into(cardView.mainImageView)
        } else {
            cardView.mainImage = cardView.context.getDrawable(R.drawable.default_channel_logo)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        Glide.with(cardView.context).clear(cardView.mainImageView)
        cardView.mainImage = null
        cardView.setOnLongClickListener(null)
    }
}
