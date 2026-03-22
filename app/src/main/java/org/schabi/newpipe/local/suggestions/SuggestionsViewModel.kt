package org.schabi.newpipe.local.suggestions

import android.app.Application
import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.TimeUnit
import org.schabi.newpipe.App
import org.schabi.newpipe.database.stream.StreamWithState
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.local.feed.item.StreamItem
import org.schabi.newpipe.local.suggestions.SuggestionsEventManager.Event.ErrorResultEvent
import org.schabi.newpipe.local.suggestions.SuggestionsEventManager.Event.IdleEvent
import org.schabi.newpipe.local.suggestions.SuggestionsEventManager.Event.ProgressEvent
import org.schabi.newpipe.local.suggestions.SuggestionsEventManager.Event.SuccessResultEvent
import org.schabi.newpipe.util.DEFAULT_THROTTLE_TIMEOUT

class SuggestionsViewModel(
    private val application: Application
) : ViewModel() {
    private val mutableStateLiveData = MutableLiveData<SuggestionsState>()
    val stateLiveData: LiveData<SuggestionsState> = mutableStateLiveData

    private var combineDisposable = SuggestionsEventManager.events()
        .throttleLatest(DEFAULT_THROTTLE_TIMEOUT, TimeUnit.MILLISECONDS)
        .subscribeOn(Schedulers.io())
        .observeOn(Schedulers.io())
        .map { event ->
            val loadedItems = if (event is SuccessResultEvent || event is IdleEvent) {
                SuggestionsResultsHolder.getLoadedItems()
            } else {
                emptyList()
            }

            val selectedChannelCount = if (event is SuccessResultEvent || event is IdleEvent) {
                SuggestionsResultsHolder.getSelectedChannelCount()
            } else {
                0
            }

            val totalSubscriptionCount = if (event is SuccessResultEvent || event is IdleEvent) {
                SuggestionsResultsHolder.getTotalSubscriptionCount()
            } else {
                0
            }

            Triple(event, Pair(loadedItems, selectedChannelCount), totalSubscriptionCount)
        }
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe { (event, data, totalSubscriptionCount) ->
            val (loadedItems, selectedChannelCount) = data
            val items = loadedItems.map { streamInfoItem ->
                StreamItem(
                    StreamWithState(StreamEntity(streamInfoItem), null)
                )
            }
            mutableStateLiveData.postValue(
                when (event) {
                    is IdleEvent -> SuggestionsState.LoadedState(items, selectedChannelCount, totalSubscriptionCount)
                    is ProgressEvent -> SuggestionsState.ProgressState(event.currentProgress, event.maxProgress, selectedChannelCount, totalSubscriptionCount, event.progressMessage)
                    is SuccessResultEvent -> SuggestionsState.LoadedState(items, selectedChannelCount, totalSubscriptionCount)
                    is ErrorResultEvent -> SuggestionsState.ErrorState(event.error)
                }
            )

            if (event is ErrorResultEvent || event is SuccessResultEvent) {
                SuggestionsEventManager.reset()
            }
        }

    override fun onCleared() {
        super.onCleared()
        combineDisposable.dispose()
    }

    companion object {
        fun getFactory(context: Context) = viewModelFactory {
            initializer {
                SuggestionsViewModel(App.instance)
            }
        }
    }
}
