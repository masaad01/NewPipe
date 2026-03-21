/*
 * SPDX-FileCopyrightText: 2025 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.suggestion

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import org.schabi.newpipe.App
import org.schabi.newpipe.databinding.FragmentSuggestionBinding
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorUtil
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.info_list.InfoListAdapter
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.OnClickGesture
import org.schabi.newpipe.util.ThemeHelper.getGridSpanCountStreams
import org.schabi.newpipe.util.ThemeHelper.shouldUseGridLayout

class SuggestionFragment : Fragment() {

    private var _binding: FragmentSuggestionBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: SuggestionViewModel
    private lateinit var infoListAdapter: InfoListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSuggestionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupSwipeRefresh()
        setupViewModel()
    }

    private fun setupRecyclerView() {
        val activity = requireActivity()
        infoListAdapter = InfoListAdapter(activity)

        val spanCount = if (shouldUseGridLayout(context)) {
            getGridSpanCountStreams(context)
        } else {
            1
        }

        val layoutManager = GridLayoutManager(context, spanCount)
        layoutManager.spanSizeLookup = infoListAdapter.getSpanSizeLookup(spanCount)
        binding.itemsList.layoutManager = layoutManager
        binding.itemsList.adapter = infoListAdapter

        infoListAdapter.setOnStreamSelectedListener(
            OnClickGesture { selectedItem ->
                openVideo(selectedItem)
            }
        )
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            viewModel.loadSuggestions()
        }
    }

    private fun setupViewModel() {
        val factory = SuggestionViewModel.getFactory(App.instance)
        viewModel = ViewModelProvider(this, factory)[SuggestionViewModel::class.java]

        viewModel.stateLiveData.observe(viewLifecycleOwner) { state ->
            handleState(state)
        }
    }

    private fun handleState(state: SuggestionState) {
        when (state) {
            is SuggestionState.LoadingState -> showLoading(state)
            is SuggestionState.LoadedState -> showLoaded(state)
            is SuggestionState.ErrorState -> showError(state)
            is SuggestionState.EmptyState -> showEmpty()
        }
    }

    private fun showLoading(state: SuggestionState.LoadingState) {
        binding.swipeRefreshLayout.isRefreshing = true
        binding.itemsList.visibility = View.GONE
        binding.emptyStateView.root.visibility = View.GONE
        binding.errorPanel.root.visibility = View.GONE

        binding.loadingProgressBar.visibility = View.VISIBLE
        binding.loadingProgressText.visibility = View.VISIBLE

        if (state.maxProgress > 0) {
            binding.loadingProgressText.text = "${state.currentProgress}/${state.maxProgress}"
            binding.loadingProgressBar.isIndeterminate = false
        } else {
            binding.loadingProgressBar.isIndeterminate = true
        }
    }

    private fun showLoaded(state: SuggestionState.LoadedState) {
        binding.swipeRefreshLayout.isRefreshing = false
        binding.loadingProgressBar.visibility = View.GONE
        binding.loadingProgressText.visibility = View.GONE
        binding.emptyStateView.root.visibility = View.GONE
        binding.errorPanel.root.visibility = View.GONE

        binding.itemsList.visibility = View.VISIBLE
        infoListAdapter.clearStreamItemList()
        infoListAdapter.addInfoItemList(state.items)
    }

    private fun showError(state: SuggestionState.ErrorState) {
        binding.swipeRefreshLayout.isRefreshing = false
        binding.loadingProgressBar.visibility = View.GONE
        binding.loadingProgressText.visibility = View.GONE
        binding.itemsList.visibility = View.GONE
        binding.emptyStateView.root.visibility = View.GONE

        binding.errorPanel.root.visibility = View.VISIBLE
        ErrorUtil.showSnackbar(
            this,
            ErrorInfo(
                state.error ?: Exception("Unknown error"),
                UserAction.REQUESTED_STREAM,
                "Loading suggestions"
            )
        )
    }

    private fun showEmpty() {
        binding.swipeRefreshLayout.isRefreshing = false
        binding.loadingProgressBar.visibility = View.GONE
        binding.loadingProgressText.visibility = View.GONE
        binding.errorPanel.root.visibility = View.GONE
        binding.itemsList.visibility = View.GONE

        binding.emptyStateView.root.visibility = View.VISIBLE
    }

    private fun openVideo(item: StreamInfoItem) {
        NavigationHelper.openVideoDetailFragment(
            requireContext(),
            requireActivity().supportFragmentManager,
            item.serviceId,
            item.url,
            item.name,
            null,
            false
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.itemsList.adapter = null
        _binding = null
    }

    companion object {
        @JvmStatic
        fun newInstance(): SuggestionFragment {
            return SuggestionFragment()
        }
    }
}
