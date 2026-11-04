package com.github.kr328.clash.design.adapter

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyDelay
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.component.ProxyPageFactory
import com.github.kr328.clash.design.component.ProxyView
import com.github.kr328.clash.design.component.ProxyViewConfig
import com.github.kr328.clash.design.component.ProxyViewState
import com.github.kr328.clash.design.model.ProxyPageState
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.ui.Surface
import com.github.kr328.clash.design.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProxyPageAdapter(
    private val surface: Surface,
    private val config: ProxyViewConfig,
    private val navigationHeight: Int,
    private val adapters: List<ProxyAdapter>,
    private val stateChanged: (Int) -> Unit,
) : RecyclerView.Adapter<ProxyPageFactory.Holder>() {
    private val factory = ProxyPageFactory(config)
    private var parent: RecyclerView? = null
    private val attachedPages = mutableSetOf<ProxyPageFactory.Holder>()
    private var marqueePage = -1
    private val updateGenerations = IntArray(adapters.size)

    val states = List(adapters.size) { ProxyPageState() }

    fun beginUrlTest(position: Int) = adapters[position].beginUrlTest()

    fun finishUrlTest(position: Int) = adapters[position].finishUrlTest()

    fun updateDelays(position: Int, results: List<ProxyDelay>, redraw: Boolean) {
        val changed = adapters[position].updateDelays(results)
        if (!redraw || changed.isEmpty()) return
        for (holder in attachedPages) {
            val view = holder.recyclerView
            if (view.position != position || !view.isShown) continue
            for (index in 0 until view.childCount) {
                val child = view.getChildAt(index) as? ProxyView ?: continue
                if (child.state in changed && child.bottom > view.paddingTop &&
                    child.top < view.height - view.paddingBottom) {
                    child.postInvalidateOnAnimation()
                }
            }
        }
    }

    fun setMarqueePage(position: Int) {
        if (marqueePage == position) return
        marqueePage = position
        attachedPages.forEach { updateMarquees(it.recyclerView) }
    }

    private fun updateMarquees(view: RecyclerView) {
        for (index in 0 until view.childCount) {
            val child = view.getChildAt(index)
            (child as? ProxyView)?.marqueeAllowed = canMarquee(view, child)
        }
    }

    private fun canMarquee(view: RecyclerView, child: View): Boolean =
        marqueePage >= 0 && view.position == marqueePage && view.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
                child.bottom > view.paddingTop && child.top < view.height - view.paddingBottom

    suspend fun updateAdapter(
        position: Int,
        proxies: List<Proxy>,
        selectable: Boolean,
        parent: ProxyState,
        links: Map<String, ProxyState>
    ) {
        val generation = ++updateGenerations[position]
        val states = withContext(Dispatchers.Default) {
            proxies.map {
                val link = if (it.isGroup) links[it.name] else null

                ProxyViewState(config, it, parent, link)
            }
        }

        withContext(Dispatchers.Main) {
            if (generation != updateGenerations[position]) return@withContext
            adapters[position].apply {
                this.selectable = selectable
                val previousSize = this.states.size
                this.states = states
                if (previousSize == states.size) {
                    notifyItemRangeChanged(0, states.size)
                } else {
                    notifyItemRangeRemoved(0, previousSize)
                    notifyItemRangeInserted(0, states.size)
                }
            }

            requestRedrawVisible()
        }
    }

    fun requestRedrawVisible() {
        factory.fromRoot(parent?.firstVisibleView ?: return)
            .recyclerView.invalidateChildren()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProxyPageFactory.Holder {
        val holder = factory.newInstance()

        val toolbarHeight = config.context.getPixels(R.dimen.toolbar_height)

        holder.recyclerView.bindInsets(surface, toolbarHeight + navigationHeight)
        holder.recyclerView.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            updateMarquees(view as RecyclerView)
        }
        holder.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                updateMarquees(recyclerView)
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE) updateMarquees(recyclerView)
            }
        })
        holder.recyclerView.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                (view as? ProxyView)?.marqueeAllowed = canMarquee(holder.recyclerView, view)
            }

            override fun onChildViewDetachedFromWindow(view: View) {
                (view as? ProxyView)?.marqueeAllowed = false
            }
        })
        holder.recyclerView.addScrolledToBottomObserver { view, bottom ->
            val position = view.position
            val state = states[position]

            if (state.bottom != bottom) {
                state.bottom = bottom

                stateChanged(position)
            }
        }

        return holder
    }

    override fun onBindViewHolder(holder: ProxyPageFactory.Holder, position: Int) {
        val adapter = adapters[position]

        states[position].bottom = false

        holder.recyclerView.apply {
            this.position = position
            this.swapAdapter(adapter, false)
        }
        updateMarquees(holder.recyclerView)
    }

    override fun onViewAttachedToWindow(holder: ProxyPageFactory.Holder) {
        super.onViewAttachedToWindow(holder)
        attachedPages.add(holder)
        updateMarquees(holder.recyclerView)
    }

    override fun onViewDetachedFromWindow(holder: ProxyPageFactory.Holder) {
        attachedPages.remove(holder)
        for (index in 0 until holder.recyclerView.childCount) {
            (holder.recyclerView.getChildAt(index) as? ProxyView)?.marqueeAllowed = false
        }
        super.onViewDetachedFromWindow(holder)
    }

    override fun getItemCount(): Int {
        return adapters.size
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        this.parent = recyclerView

        recyclerView.isFocusable = false
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        setMarqueePage(-1)
        attachedPages.clear()
        this.parent = null
    }


    private var RecyclerView.position: Int
        get() {
            return tag as? Int ?: -1
        }
        set(value) {
            tag = value
        }
}
