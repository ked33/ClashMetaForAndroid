package com.github.kr328.clash.design.adapter

import android.content.Context
import android.text.TextUtils
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.AdapterProxyGroupBinding
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.util.getPixels
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
    private var columnCount = 1
    private var visibleIndices: List<Int> = groupNames.indices.toList()
    private var recyclerView: RecyclerView? = null
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            for (index in 0 until recyclerView.childCount) {
                val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(index)) as Holder
                updateMarquee(holder)
            }
        }
    }

    init {
        setHasStableIds(true)
    }

    fun filter(keyword: String): Boolean {
        this.keyword = keyword.trim()
        visibleIndices = filteredIndices()
        notifyDataSetChanged()

        return visibleIndices.isEmpty()
    }

    fun setColumnCount(columns: Int) {
        val normalized = columns.coerceIn(1, 3)
        if (columnCount == normalized) return

        columnCount = normalized
        notifyDataSetChanged()
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
            applyColumnChrome()
            root.setOnClickListener {
                requestSelection(index)
            }
            executePendingBindings()
        }
        updateMarquee(holder)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
        recyclerView.addOnScrollListener(scrollListener)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.removeOnScrollListener(scrollListener)
        for (index in 0 until recyclerView.childCount) {
            stopMarquee(recyclerView.getChildViewHolder(recyclerView.getChildAt(index)) as Holder)
        }
        this.recyclerView = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        super.onViewAttachedToWindow(holder)
        updateMarquee(holder)
    }

    override fun onViewDetachedFromWindow(holder: Holder) {
        stopMarquee(holder)
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewRecycled(holder: Holder) {
        stopMarquee(holder)
        super.onViewRecycled(holder)
    }

    private fun updateMarquee(holder: Holder) {
        val enabled = holder.binding.selected == true &&
                recyclerView?.scrollState == RecyclerView.SCROLL_STATE_IDLE
        holder.binding.groupNameView.marqueeEnabled = enabled
        holder.binding.summaryView.marqueeEnabled = enabled
    }

    private fun stopMarquee(holder: Holder) {
        holder.binding.groupNameView.marqueeEnabled = false
        holder.binding.summaryView.marqueeEnabled = false
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

    private fun AdapterProxyGroupBinding.applyColumnChrome() {
        val singleColumn = columnCount == 1
        val horizontalPadding = context.getPixels(
            if (singleColumn) R.dimen.dialog_padding else R.dimen.proxy_group_grid_padding
        )
        val indicatorSize = context.getPixels(
            if (singleColumn) {
                R.dimen.proxy_group_navigation_action_size
            } else {
                R.dimen.proxy_group_column_indicator_size
            }
        )

        root.minimumHeight = context.getPixels(
            if (singleColumn) R.dimen.item_min_height else R.dimen.proxy_group_grid_min_height
        )
        root.setPaddingRelative(
            horizontalPadding,
            root.paddingTop,
            if (singleColumn) 0 else horizontalPadding,
            root.paddingBottom,
        )
        val singleLine = singleColumn || selected == true
        groupNameView.setSingleLine(singleLine)
        groupNameView.maxLines = if (singleLine) 1 else 2
        groupNameView.ellipsize = if (selected == true) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END

        val indicator = selectedView.layoutParams
        if (indicator.width != indicatorSize || indicator.height != indicatorSize) {
            indicator.width = indicatorSize
            indicator.height = indicatorSize
            selectedView.layoutParams = indicator
        }
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
