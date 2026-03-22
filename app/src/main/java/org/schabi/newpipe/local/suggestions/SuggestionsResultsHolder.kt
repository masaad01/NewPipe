package org.schabi.newpipe.local.suggestions

import org.schabi.newpipe.extractor.stream.StreamInfoItem

object SuggestionsResultsHolder {
    val itemsErrors: List<Throwable>
        get() = itemsErrorsHolder

    private val itemsErrorsHolder: MutableList<Throwable> = ArrayList()
    private var loadedItems: List<StreamInfoItem> = emptyList()
    private var selectedChannelCount: Int = 0
    private var totalSubscriptionCount: Int = 0

    fun setLoadedItems(items: List<StreamInfoItem>) {
        loadedItems = items
    }

    fun getLoadedItems(): List<StreamInfoItem> = loadedItems

    fun setChannelCounts(selected: Int, total: Int) {
        selectedChannelCount = selected
        totalSubscriptionCount = total
    }

    fun getSelectedChannelCount(): Int = selectedChannelCount
    fun getTotalSubscriptionCount(): Int = totalSubscriptionCount

    fun addError(error: Throwable) {
        itemsErrorsHolder.add(error)
    }

    fun addErrors(errors: List<Throwable>) {
        itemsErrorsHolder.addAll(errors)
    }

    fun clear() {
        itemsErrorsHolder.clear()
        loadedItems = emptyList()
        selectedChannelCount = 0
        totalSubscriptionCount = 0
    }
}
