package com.ze.iptvplayer

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import com.ze.iptvplayer.data.PlaylistRepository
import com.ze.iptvplayer.model.Channel
import com.ze.iptvplayer.ui.ChannelCardPresenter

class SearchActivity : FragmentActivity(R.layout.activity_search) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.search_fragment, ChannelSearchFragment())
                .commit()
        }
    }
}

class ChannelSearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    private val resultsAdapter = ArrayObjectAdapter(ChannelCardPresenter())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)
        setOnItemViewClickedListener(OnItemViewClickedListener { _, item, _, _ ->
            if (item is Channel) {
                val intent = Intent(requireActivity(), PlayerActivity::class.java)
                intent.putExtra(PlayerActivity.EXTRA_CHANNEL_ID, item.key)
                intent.putExtra(PlayerActivity.EXTRA_CATEGORY_ID, PlaylistRepository.CATEGORY_ALL)
                startActivity(intent)
            }
        })
    }

    override fun getResultsAdapter(): ObjectAdapter = resultsAdapter

    override fun onQueryTextChange(newQuery: String?): Boolean {
        runSearch(newQuery)
        return true
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        runSearch(query)
        return true
    }

    private fun runSearch(query: String?) {
        resultsAdapter.clear()
        val trimmed = query?.trim().orEmpty()
        if (trimmed.isEmpty()) return
        val matches = PlaylistRepository.channels.filter { it.name.contains(trimmed, ignoreCase = true) }
        resultsAdapter.addAll(0, matches)
    }
}
