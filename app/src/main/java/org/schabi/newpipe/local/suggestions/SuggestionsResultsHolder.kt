package org.schabi.newpipe.local.suggestions

class SuggestionsResultsHolder {
    val itemsErrors: List<Throwable>
        get() = itemsErrorsHolder

    private val itemsErrorsHolder: MutableList<Throwable> = ArrayList()

    fun addError(error: Throwable) {
        itemsErrorsHolder.add(error)
    }

    fun addErrors(errors: List<Throwable>) {
        itemsErrorsHolder.addAll(errors)
    }
}
