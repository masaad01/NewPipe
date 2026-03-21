/*
 * Copyright 2019 Mauricio Colli <mauriciocolli@outlook.com>
 * SuggestionsFragment.kt is part of NewPipe
 *
 * License: GPL-3.0+
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.schabi.newpipe.local.suggestions

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.Item
import com.xwray.groupie.OnItemClickListener
import com.xwray.groupie.OnItemLongClickListener
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentSuggestionsBinding
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorUtil
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.fragments.BaseStateFragment
import org.schabi.newpipe.info_list.ItemViewMode
import org.schabi.newpipe.info_list.dialog.InfoItemDialog
import org.schabi.newpipe.ktx.animate
import org.schabi.newpipe.ktx.animateHideRecyclerViewAllowingScrolling
import org.schabi.newpipe.local.feed.item.StreamItem
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.ThemeHelper.getGridSpanCountStreams
import org.schabi.newpipe.util.ThemeHelper.getItemViewMode
import org.schabi.newpipe.util.ThemeHelper.shouldUseGridLayout

class SuggestionsFragment : BaseStateFragment<SuggestionsState>() {
    private var _suggestionsBinding: FragmentSuggestionsBinding? = null
    private val suggestionsBinding get() = _suggestionsBinding!!

    private val disposables = CompositeDisposable()

    private lateinit var viewModel: SuggestionsViewModel

    @JvmField
    var listState: Parcelable? = null

    private lateinit var groupAdapter: GroupieAdapter

    private var isRefreshing = false

    init {
        setHasOptionsMenu(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_suggestions, container, false)
    }

    override fun onViewCreated(rootView: View, savedInstanceState: Bundle?) {
        _suggestionsBinding = FragmentSuggestionsBinding.bind(rootView)
        super.onViewCreated(rootView, savedInstanceState)

        val factory = SuggestionsViewModel.getFactory(requireContext())
        viewModel = ViewModelProvider(this, factory)[SuggestionsViewModel::class.java]
        viewModel.stateLiveData.observe(viewLifecycleOwner) { it?.let(::handleResult) }

        groupAdapter = GroupieAdapter().apply {
            setOnItemClickListener(listenerStreamItem)
            setOnItemLongClickListener(listenerStreamItem)
        }

        suggestionsBinding.itemsList.adapter = groupAdapter
        setupListViewMode()
    }

    override fun onPause() {
        super.onPause()
        listState = suggestionsBinding.itemsList.layoutManager?.onSaveInstanceState()
    }

    override fun onResume() {
        super.onResume()
        updateRelativeTimeViews()
    }

    private fun setupListViewMode() {
        groupAdapter.spanCount = if (shouldUseGridLayout(context)) getGridSpanCountStreams(context) else 1
        suggestionsBinding.itemsList.layoutManager = GridLayoutManager(requireContext(), groupAdapter.spanCount).apply {
            spanSizeLookup = groupAdapter.spanSizeLookup
        }
    }

    override fun initListeners() {
        super.initListeners()
        suggestionsBinding.refreshRootView.setOnClickListener { reloadContent() }
        suggestionsBinding.swipeRefreshLayout.setOnRefreshListener { reloadContent() }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)

        activity?.supportActionBar?.setDisplayShowTitleEnabled(true)
        activity?.supportActionBar?.setTitle(R.string.tab_suggestions)
    }

    override fun onDestroy() {
        disposables.dispose()
        super.onDestroy()
    }

    override fun onDestroyView() {
        suggestionsBinding.itemsList.adapter = null
        _suggestionsBinding = null
        super.onDestroyView()
    }

    override fun showLoading() {
        super.showLoading()
        suggestionsBinding.itemsList.animateHideRecyclerViewAllowingScrolling()
        suggestionsBinding.refreshRootView.animate(false, 0)
        suggestionsBinding.loadingProgressText.animate(true, 200)
        suggestionsBinding.swipeRefreshLayout.isRefreshing = true
        isRefreshing = true
    }

    override fun hideLoading() {
        super.hideLoading()
        suggestionsBinding.itemsList.animate(true, 0)
        suggestionsBinding.refreshRootView.animate(true, 200)
        suggestionsBinding.loadingProgressText.animate(false, 0)
        suggestionsBinding.swipeRefreshLayout.isRefreshing = false
        isRefreshing = false
    }

    override fun showEmptyState() {
        super.showEmptyState()
        suggestionsBinding.itemsList.animateHideRecyclerViewAllowingScrolling()
        suggestionsBinding.refreshRootView.animate(true, 200)
        suggestionsBinding.loadingProgressText.animate(false, 0)
        suggestionsBinding.swipeRefreshLayout.isRefreshing = false
        setEmptyStateMessage(R.string.no_channel_subscribed_yet)
    }

    override fun handleResult(result: SuggestionsState) {
        when (result) {
            is SuggestionsState.ProgressState -> handleProgressState(result)
            is SuggestionsState.LoadedState -> handleLoadedState(result)
            is SuggestionsState.EmptyState -> showEmptyState()
            is SuggestionsState.ErrorState -> if (handleErrorState(result)) return
        }

        updateRefreshViewState()
    }

    override fun handleError() {
        super.handleError()
        suggestionsBinding.itemsList.animateHideRecyclerViewAllowingScrolling()
        suggestionsBinding.refreshRootView.animate(false, 0)
        suggestionsBinding.loadingProgressText.animate(false, 0)
        suggestionsBinding.swipeRefreshLayout.isRefreshing = false
        isRefreshing = false
    }

    private fun handleProgressState(progressState: SuggestionsState.ProgressState) {
        showLoading()

        val isIndeterminate = progressState.currentProgress == -1 &&
            progressState.maxProgress == -1

        suggestionsBinding.loadingProgressText.text = if (!isIndeterminate) {
            "${progressState.currentProgress}/${progressState.maxProgress}"
        } else if (progressState.progressMessage > 0) {
            getString(progressState.progressMessage)
        } else {
            "∞/∞"
        }

        suggestionsBinding.loadingProgressBar.isIndeterminate = isIndeterminate ||
            (progressState.maxProgress > 0 && progressState.currentProgress == 0)
        suggestionsBinding.loadingProgressBar.progress = progressState.currentProgress
        suggestionsBinding.loadingProgressBar.max = progressState.maxProgress
    }

    private fun showInfoItemDialog(item: StreamInfoItem) {
        val context = context
        val activity: Activity? = getActivity()
        if (context == null || context.resources == null || activity == null) return

        InfoItemDialog.Builder(activity, context, this, item).create().show()
    }

    private val listenerStreamItem = object : OnItemClickListener, OnItemLongClickListener {
        override fun onItemClick(item: Item<*>, view: View) {
            if (item is StreamItem && !isRefreshing) {
                val stream = item.streamWithState.stream
                NavigationHelper.openVideoDetailFragment(
                    requireContext(),
                    fm,
                    stream.serviceId,
                    stream.url,
                    stream.title,
                    null,
                    false
                )
            }
        }

        override fun onItemLongClick(item: Item<*>, view: View): Boolean {
            if (item is StreamItem && !isRefreshing) {
                showInfoItemDialog(item.streamWithState.stream.toStreamInfoItem())
                return true
            }
            return false
        }
    }

    private fun handleLoadedState(loadedState: SuggestionsState.LoadedState) {
        val itemVersion = when (getItemViewMode(requireContext())) {
            ItemViewMode.GRID -> StreamItem.ItemVersion.GRID
            ItemViewMode.CARD -> StreamItem.ItemVersion.CARD
            else -> StreamItem.ItemVersion.NORMAL
        }
        loadedState.items.forEach { it.itemVersion = itemVersion }

        groupAdapter.updateAsync(loadedState.items)

        listState?.run {
            suggestionsBinding.itemsList.layoutManager?.onRestoreInstanceState(listState)
            listState = null
        }

        if (loadedState.items.isEmpty()) {
            showEmptyState()
        } else {
            hideLoading()
        }
    }

    private fun handleErrorState(errorState: SuggestionsState.ErrorState): Boolean {
        return if (errorState.error == null) {
            hideLoading()
            false
        } else {
            showError(ErrorInfo(errorState.error, UserAction.REQUESTED_FEED, "Loading suggestions"))
            true
        }
    }

    private fun updateRelativeTimeViews() {
        updateRefreshViewState()
        groupAdapter.notifyItemRangeChanged(
            0,
            groupAdapter.itemCount,
            StreamItem.UPDATE_RELATIVE_TIME
        )
    }

    private fun updateRefreshViewState() {
        suggestionsBinding.refreshText.text = getString(R.string.feed_oldest_subscription_update, "—")
    }

    override fun doInitialLoadLogic() {}

    override fun reloadContent() {
        activity?.startService(Intent(requireContext(), SuggestionsLoadService::class.java))
        listState = null
    }

    companion object {
        @JvmStatic
        fun newInstance(): SuggestionsFragment {
            return SuggestionsFragment()
        }
    }
}
