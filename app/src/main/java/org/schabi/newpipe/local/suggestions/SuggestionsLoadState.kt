package org.schabi.newpipe.local.suggestions

data class SuggestionsLoadState(
    val updateDescription: String,
    val maxProgress: Int,
    val currentProgress: Int
)
