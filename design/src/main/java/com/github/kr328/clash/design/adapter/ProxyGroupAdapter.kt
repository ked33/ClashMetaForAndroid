package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.AdapterProxyGroupBinding
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.util.layoutInflater

class ProxyGroupAdapter(
    private val context: Context,
    private val groupNames: List<String>,
    private val groupStates: List<ProxyState>,
    selectedIndex: Int,
    private val requestSelection: (Int) -> Unit,
) : RecyclerView.Adapter<ProxyGroupAdapter.Holder>() {
    class Holder(val binding: AdapterProxyGroupBinding) : RecyclerView.ViewHolder(binding.root)

    private var selectedIndex = selectedIndex
    private var keyword = ""
    private var visibleIndices: List<Int> = groupNames.indices.toList()

    init {
        setHasStableIds(true)
    }

    fun filter(keyword: String): Boolean {
        this.keyword = keyword.trim()
        visibleIndices = filteredIndices()
        notifyDataSetChanged()

        return visibleIndices.isEmpty()
    }

    fun select(index: Int) {
        if (selectedIndex == index) return

        val previous = selectedIndex
        selectedIndex = index
        notifyOriginalItemChanged(previous)
        notifyOriginalItemChanged(index)
    }

    fun notifyGroupChanged(index: Int): Boolean {
        if (keyword.isEmpty()) {
            notifyOriginalItemChanged(index)
        } else {
            visibleIndices = filteredIndices()
            notifyDataSetChanged()
        }

        return visibleIndices.isEmpty()
    }

    fun visiblePositionOf(index: Int): Int {
        return visibleIndices.indexOf(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterProxyGroupBinding.inflate(context.layoutInflater, parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val index = visibleIndices[position]

        holder.binding.apply {
            groupName = groupNames[index]
            summary = selectionSummary(index)
            this.selected = index == selectedIndex
            root.setOnClickListener {
                requestSelection(index)
            }
            executePendingBindings()
        }
    }

    override fun getItemId(position: Int): Long {
        return visibleIndices[position].toLong()
    }

    override fun getItemCount(): Int {
        return visibleIndices.size
    }

    private fun filteredIndices(): List<Int> {
        if (keyword.isEmpty()) return groupNames.indices.toList()

        return groupNames.indices.filter { index ->
            groupNames[index].contains(keyword, ignoreCase = true) ||
                    groupStates[index].now
                        .takeUnless { it == UNKNOWN_SELECTION }
                        ?.contains(keyword, ignoreCase = true) == true
        }
    }

    private fun notifyOriginalItemChanged(index: Int) {
        val position = visibleIndices.indexOf(index)
        if (position >= 0) notifyItemChanged(position)
    }

    private fun selectionSummary(index: Int): String {
        val current = groupStates[index].now

        return if (current == UNKNOWN_SELECTION) {
            context.getString(R.string.loading)
        } else {
            context.getString(R.string.format_current_proxy, current.ifEmpty { "*" })
        }
    }

    companion object {
        private const val UNKNOWN_SELECTION = "?"
    }
}
