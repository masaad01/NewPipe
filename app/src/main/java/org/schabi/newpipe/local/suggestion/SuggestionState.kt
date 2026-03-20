/*
 * SPDX-FileCopyrightText: 2025 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.suggestion

import org.schabi.newpipe.extractor.stream.StreamInfoItem

sealed class SuggestionState {
    data class LoadingState(
        val currentProgress: Int = -1,
        val maxProgress: Int = -1
    ) : SuggestionState()

    data class LoadedState(
        val items: List<StreamInfoItem>
    ) : SuggestionState()

    data class ErrorState(
        val error: Throwable? = null
    ) : SuggestionState()

    data object EmptyState : SuggestionState()
}
