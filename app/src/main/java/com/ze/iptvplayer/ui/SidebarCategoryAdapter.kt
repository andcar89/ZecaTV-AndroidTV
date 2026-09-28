package com.ze.iptvplayer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ze.iptvplayer.databinding.ItemSidebarCategoryBinding

data class CategoryEntry(val id: String, val label: String)

class SidebarCategoryAdapter(
    private var categories: List<CategoryEntry>,
    private var selectedId: String?,
    private val onSelect: (CategoryEntry) -> Unit
) : RecyclerView.Adapter<SidebarCategoryAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemSidebarCategoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemSidebarCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = categories[position]
        holder.binding.categoryText.text = entry.label
        holder.binding.categoryText.isSelected = entry.id == selectedId
        holder.binding.categoryText.setOnClickListener {
            val previousIndex = categories.indexOfFirst { it.id == selectedId }
            selectedId = entry.id
            if (previousIndex >= 0) notifyItemChanged(previousIndex)
            notifyItemChanged(position)
            onSelect(entry)
        }
    }

    override fun getItemCount(): Int = categories.size

    /** Replaces the full category list (e.g. after favorites changed) and re-marks the selection. */
    fun updateCategories(newCategories: List<CategoryEntry>, newSelectedId: String?) {
        categories = newCategories
        selectedId = newSelectedId
        notifyDataSetChanged()
    }
}
