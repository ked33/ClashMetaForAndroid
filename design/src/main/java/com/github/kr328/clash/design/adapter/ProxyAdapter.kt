package com.github.kr328.clash.design.adapter

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.ProxyDelay
import com.github.kr328.clash.design.component.ProxyView
import com.github.kr328.clash.design.component.ProxyViewConfig
import com.github.kr328.clash.design.component.ProxyViewState

class ProxyAdapter(
    private val config: ProxyViewConfig,
    private val clicked: (String) -> Unit,
) : RecyclerView.Adapter<ProxyAdapter.Holder>() {
    class Holder(val view: ProxyView) : RecyclerView.ViewHolder(view)

    var selectable: Boolean = false
    private var statesByName: Map<String, List<ProxyViewState>> = emptyMap()
    private val liveDelays = mutableMapOf<String, Int>()
    private var testing = false
    var states: List<ProxyViewState> = emptyList()
        set(value) {
            field = value
            statesByName = value.groupBy { it.proxy.name }
            // A selection/settings reload during a test must retain completed results.
            if (testing) {
                liveDelays.forEach { (name, delay) ->
                    statesByName[name]?.forEach { it.updateDelay(delay) }
                }
            }
        }

    fun beginUrlTest() {
        liveDelays.clear()
        testing = true
    }

    fun finishUrlTest() {
        testing = false
        liveDelays.clear()
    }

    fun updateDelays(results: List<ProxyDelay>): Set<ProxyViewState> {
        if (results.isEmpty()) return emptySet()
        val changed = mutableSetOf<ProxyViewState>()
        for (result in results) {
            if (testing) liveDelays[result.name] = result.delay
            statesByName[result.name]?.forEach {
                if (it.updateDelay(result.delay)) changed.add(it)
            }
        }
        return changed
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(ProxyView(config.context, config))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = states[position]

        holder.view.apply {
            state = current

            setOnClickListener {
                clicked(current.proxy.name)
            }

            val isSelector = selectable

            isFocusable = isSelector
            isClickable = isSelector

            current.update(true)
        }
    }

    override fun getItemCount(): Int {
        return states.size
    }

    override fun onViewRecycled(holder: Holder) {
        holder.view.marqueeAllowed = false
        holder.view.state = null
        super.onViewRecycled(holder)
    }
}
