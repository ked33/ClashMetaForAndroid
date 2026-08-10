package com.github.kr328.clash.design

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.viewpager2.widget.ViewPager2
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.adapter.ProxyAdapter
import com.github.kr328.clash.design.adapter.ProxyGroupAdapter
import com.github.kr328.clash.design.adapter.ProxyPageAdapter
import com.github.kr328.clash.design.component.ProxyMenu
import com.github.kr328.clash.design.component.ProxyViewConfig
import com.github.kr328.clash.design.databinding.DesignProxyBinding
import com.github.kr328.clash.design.databinding.DialogProxyGroupsBinding
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.applyLinearAdapter
import com.github.kr328.clash.design.util.getPixels
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProxyDesign(
    context: Context,
    overrideMode: TunnelState.Mode?,
    private val groupNames: List<String>,
    private val groupStates: List<ProxyState>,
    private val uiStore: UiStore,
) : Design<ProxyDesign.Request>(context) {
    sealed class Request {
        object ReloadAll : Request()
        object ReLaunch : Request()

        data class PatchMode(val mode: TunnelState.Mode?) : Request()
        data class Reload(val index: Int, val generation: Int) : Request()
        data class Select(val index: Int, val name: String) : Request()
        data class UrlTest(val index: Int) : Request()
    }

    private val binding = DesignProxyBinding
        .inflate(context.layoutInflater, context.root, false)

    private var config = ProxyViewConfig(context, uiStore.proxyLine)

    private val menu: ProxyMenu by lazy {
        ProxyMenu(context, binding.menuView, overrideMode, uiStore, requests) {
            config.proxyLine = uiStore.proxyLine
        }
    }

    private val adapter: ProxyPageAdapter
        get() = binding.pagesView.adapter!! as ProxyPageAdapter

    private val useTabs = groupNames.size in 2..MAX_INLINE_GROUPS
    private val useGroupNavigator = groupNames.isNotEmpty() && !useTabs
    private val navigationHeight = when {
        useTabs -> context.getPixels(R.dimen.tab_layout_height)
        useGroupNavigator -> context.getPixels(R.dimen.proxy_group_navigator_height)
        else -> 0
    }

    private var groupPickerDialog: AppBottomSheetDialog? = null
    private var groupPickerAdapter: ProxyGroupAdapter? = null
    private var groupPickerBinding: DialogProxyGroupsBinding? = null
    private var horizontalScrolling = false
    private val verticalBottomScrolled: Boolean
        get() = adapter.states[binding.pagesView.currentItem].bottom
    private var urlTesting: Boolean
        get() = adapter.states[binding.pagesView.currentItem].urlTesting
        set(value) {
            adapter.states[binding.pagesView.currentItem].urlTesting = value
        }

    override val root: View = binding.root

    suspend fun updateGroup(
        position: Int,
        proxies: List<Proxy>,
        selectable: Boolean,
        parent: ProxyState,
        links: Map<String, ProxyState>
    ) {
        adapter.updateAdapter(position, proxies, selectable, parent, links)

        withContext(Dispatchers.Main) {
            adapter.states[position].urlTesting = false
            updateGroupPicker(position)

            if (position == binding.pagesView.currentItem) {
                updateGroupNavigation()
            }

            updateUrlTestButtonStatus()
        }
    }

    suspend fun requestRedrawVisible() {
        withContext(Dispatchers.Main) {
            adapter.requestRedrawVisible()
            updateGroupPicker(binding.pagesView.currentItem)
            updateGroupNavigation()
        }
    }

    suspend fun showModeSwitchTips() {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, R.string.mode_switch_tips, Toast.LENGTH_LONG).show()
        }
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.menuView.setOnClickListener {
            menu.show()
        }
        binding.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                groupPickerDialog?.dismiss()
            }
        })

        if (groupNames.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE

            binding.urlTestView.visibility = View.GONE
            binding.tabLayoutView.visibility = View.GONE
            binding.groupNavigatorView.visibility = View.GONE
            binding.elevationView.visibility = View.GONE
            binding.pagesView.visibility = View.GONE
            binding.urlTestFloatView.visibility = View.GONE
        } else {
            binding.urlTestFloatView.supportImageTintList = ColorStateList.valueOf(
                context.resolveThemedColor(com.google.android.material.R.attr.colorOnPrimary)
            )
            binding.tabLayoutView.visibility = if (useTabs) View.VISIBLE else View.GONE
            binding.groupNavigatorView.visibility =
                if (useGroupNavigator) View.VISIBLE else View.GONE

            binding.pagesView.apply {
                adapter = ProxyPageAdapter(
                    surface,
                    config,
                    navigationHeight,
                    List(groupNames.size) { index ->
                        ProxyAdapter(config) { name ->
                            requests.trySend(Request.Select(index, name))
                        }
                    }
                ) {
                    if (it == currentItem)
                        updateUrlTestButtonStatus()
                }

                registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageScrollStateChanged(state: Int) {
                        horizontalScrolling = state != ViewPager2.SCROLL_STATE_IDLE

                        updateUrlTestButtonStatus()
                    }

                    override fun onPageSelected(position: Int) {
                        uiStore.proxyLastGroup = groupNames[position]
                        groupPickerAdapter?.select(position)
                        updateGroupNavigation()
                    }
                })
            }

            if (useTabs) {
                TabLayoutMediator(binding.tabLayoutView, binding.pagesView) { tab, index ->
                    tab.text = groupNames[index]
                }.attach()
            }

            if (useGroupNavigator) {
                configureGroupNavigator()
            }

            val initialPosition = groupNames.indexOf(uiStore.proxyLastGroup)

            binding.pagesView.post {
                if (initialPosition > 0)
                    binding.pagesView.setCurrentItem(initialPosition, false)
            }
        }
    }

    fun requestUrlTesting() {
        urlTesting = true

        requests.trySend(Request.UrlTest(binding.pagesView.currentItem))

        updateUrlTestButtonStatus()
    }

    private fun configureGroupNavigator() {
        val selectable = groupNames.size > 1

        binding.previousGroupView.visibility = if (selectable) View.VISIBLE else View.GONE
        binding.nextGroupView.visibility = if (selectable) View.VISIBLE else View.GONE
        binding.groupPositionView.visibility = if (selectable) View.VISIBLE else View.GONE
        binding.expandGroupsView.visibility = if (selectable) View.VISIBLE else View.GONE
        binding.groupSelectorView.isClickable = selectable
        binding.groupSelectorView.isFocusable = selectable

        binding.previousGroupView.setOnClickListener {
            switchGroup(-1)
        }
        binding.nextGroupView.setOnClickListener {
            switchGroup(1)
        }
        binding.groupSelectorView.setOnClickListener {
            showGroupSelector()
        }

        updateGroupNavigation()
    }

    private fun switchGroup(offset: Int) {
        val current = binding.pagesView.currentItem
        val target = (current + offset).coerceIn(groupNames.indices)

        if (target != current) {
            binding.pagesView.setCurrentItem(target, true)
        }
    }

    private fun showGroupSelector() {
        if (groupNames.size <= 1 || groupPickerDialog?.isShowing == true) return

        val dialog = AppBottomSheetDialog(context)
        val dialogBinding = DialogProxyGroupsBinding.inflate(context.layoutInflater)
        val pickerAdapter = ProxyGroupAdapter(
            context,
            groupNames,
            groupStates,
            binding.pagesView.currentItem,
        ) { index ->
            binding.pagesView.setCurrentItem(index, false)
            dialog.dismiss()
        }

        dialogBinding.groupCountView.text = groupNames.size.toString()
        dialogBinding.mainList.applyLinearAdapter(context, pickerAdapter)
        dialogBinding.closeView.setOnClickListener {
            dialog.dismiss()
        }
        dialogBinding.keywordView.addTextChangedListener { text ->
            val empty = pickerAdapter.filter(text?.toString().orEmpty())

            updateGroupPickerEmptyState(empty)
        }

        dialog.setContentView(dialogBinding.root)
        dialog.setOnDismissListener {
            if (groupPickerDialog === dialog) {
                groupPickerDialog = null
                groupPickerAdapter = null
                groupPickerBinding = null
            }
        }

        groupPickerDialog = dialog
        groupPickerAdapter = pickerAdapter
        groupPickerBinding = dialogBinding
        dialog.show()

        dialogBinding.mainList.post {
            val position = pickerAdapter.visiblePositionOf(binding.pagesView.currentItem)
            if (position >= 0) dialogBinding.mainList.scrollToPosition(position)
        }
    }

    private fun updateGroupPicker(position: Int) {
        val empty = groupPickerAdapter?.notifyGroupChanged(position) ?: return

        updateGroupPickerEmptyState(empty)
    }

    private fun updateGroupPickerEmptyState(empty: Boolean) {
        val dialogBinding = groupPickerBinding ?: return

        dialogBinding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        dialogBinding.mainList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun updateGroupNavigation() {
        if (!useGroupNavigator) return

        val position = binding.pagesView.currentItem.coerceIn(groupNames.indices)
        val summary = selectionSummary(position)
        val hasPrevious = position > 0
        val hasNext = position < groupNames.lastIndex

        binding.currentGroupView.text = groupNames[position]
        binding.currentGroupSelectionView.text = summary
        binding.groupPositionView.text = context.getString(
            R.string.format_group_position,
            position + 1,
            groupNames.size,
        )
        binding.groupSelectorView.contentDescription = context.getString(
            R.string.format_proxy_group_description,
            groupNames[position],
            summary,
            position + 1,
            groupNames.size,
        )

        binding.previousGroupView.isEnabled = hasPrevious
        binding.previousGroupView.alpha = if (hasPrevious) 1f else DISABLED_ALPHA
        binding.nextGroupView.isEnabled = hasNext
        binding.nextGroupView.alpha = if (hasNext) 1f else DISABLED_ALPHA
    }

    private fun selectionSummary(position: Int): String {
        val current = groupStates[position].now

        return if (current == UNKNOWN_SELECTION) {
            context.getString(R.string.loading)
        } else {
            context.getString(R.string.format_current_proxy, current.ifEmpty { "*" })
        }
    }

    private fun updateUrlTestButtonStatus() {
        if (verticalBottomScrolled || horizontalScrolling || urlTesting) {
            binding.urlTestFloatView.hide()
        } else {
            binding.urlTestFloatView.show()
        }

        if (urlTesting) {
            binding.urlTestView.visibility = View.GONE
            binding.urlTestProgressView.visibility = View.VISIBLE
        } else {
            binding.urlTestView.visibility = View.VISIBLE
            binding.urlTestProgressView.visibility = View.GONE
        }
    }

    companion object {
        private const val MAX_INLINE_GROUPS = 4
        private const val UNKNOWN_SELECTION = "?"
        private const val DISABLED_ALPHA = 0.38f
    }
}
