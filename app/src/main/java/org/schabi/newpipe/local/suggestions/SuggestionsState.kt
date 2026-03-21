package org.schabi.newpipe.local.suggestions

import androidx.annotation.StringRes
import org.schabi.newpipe.local.feed.item.StreamItem

sealed class SuggestionsState {
    data class ProgressState(
        val currentProgress: Int = -1,
        val maxProgress: Int = -1,
        @StringRes val progressMessage: Int = 0
    ) : SuggestionsState()

    data class LoadedState(
        val items: List<StreamItem>,
        val channelCount: Int = 0
    ) : SuggestionsState()

    data object EmptyState : SuggestionsState()

    data class ErrorState(
        val error: Throwable? = null
    ) : SuggestionsState()
}
