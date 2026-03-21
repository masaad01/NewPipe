package org.schabi.newpipe.local.suggestions

import org.schabi.newpipe.extractor.stream.StreamInfoItem

object SuggestionsResultsHolder {
    val itemsErrors: List<Throwable>
        get() = itemsErrorsHolder

    private val itemsErrorsHolder: MutableList<Throwable> = ArrayList()
    private var loadedItems: List<StreamInfoItem> = emptyList()
    private var channelCount: Int = 0

    fun setLoadedItems(items: List<StreamInfoItem>) {
        loadedItems = items
    }

    fun getLoadedItems(): List<StreamInfoItem> = loadedItems

    fun setChannelCount(count: Int) {
        channelCount = count
    }

    fun getChannelCount(): Int = channelCount

    fun addError(error: Throwable) {
        itemsErrorsHolder.add(error)
    }

    fun addErrors(errors: List<Throwable>) {
        itemsErrorsHolder.addAll(errors)
    }

    fun clear() {
        itemsErrorsHolder.clear()
        loadedItems = emptyList()
        channelCount = 0
    }
}
