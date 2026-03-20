/*
 * SPDX-FileCopyrightText: 2025 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.suggestion

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.atomic.AtomicInteger
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.local.subscription.SubscriptionManager
import org.schabi.newpipe.util.ExtractorHelper

class SuggestionViewModel(
    private val application: Application
) : ViewModel() {

    private val mutableStateLiveData = MutableLiveData<SuggestionState>()
    val stateLiveData: LiveData<SuggestionState> = mutableStateLiveData

    private val disposables = CompositeDisposable()

    init {
        loadSuggestions()
    }

    fun loadSuggestions() {
        disposables.clear()
        mutableStateLiveData.postValue(SuggestionState.LoadingState())

        val subscriptionManager = SubscriptionManager(application)

        subscriptionManager.subscriptions()
            .firstOrError()
            .subscribeOn(Schedulers.io())
            .observeOn(Schedulers.io())
            .subscribe(
                { subscriptions ->
                    if (subscriptions.isEmpty()) {
                        mutableStateLiveData.postValue(SuggestionState.EmptyState)
                        return@subscribe
                    }

                    val selectedChannels = subscriptions.shuffled()
                        .take(minOf(DEFAULT_CHANNEL_COUNT, subscriptions.size))
                    val videosPerChannel = calculateVideosPerChannel(subscriptions.size, selectedChannels.size)
                    fetchVideosFromChannels(selectedChannels, videosPerChannel)
                },
                { error ->
                    mutableStateLiveData.postValue(SuggestionState.ErrorState(error))
                }
            )
    }

    private fun calculateVideosPerChannel(subscriptionCount: Int, channelCount: Int): Int {
        if (channelCount >= subscriptionCount) {
            return minOf(MAX_VIDEOS / subscriptionCount, MAX_VIDEOS_PER_CHANNEL)
        }
        return DEFAULT_VIDEOS_PER_CHANNEL
    }

    private fun fetchVideosFromChannels(channels: List<SubscriptionEntity>, videosPerChannel: Int) {
        val allVideos = mutableListOf<StreamInfoItem>()
        val youtubeExtractionCount = AtomicInteger(0)
        val progress = AtomicInteger(0)
        val maxProgress = channels.size

        disposables.add(
            Single.fromCallable {
                val channelChunks = channels.chunked(PARALLEL_EXTRACTIONS)

                for (chunk in channelChunks) {
                    if (allVideos.size >= MAX_VIDEOS) break

                    for (subscription in chunk) {
                        if (allVideos.size >= MAX_VIDEOS) break

                        val serviceId = subscription.serviceId
                        val url = subscription.url ?: continue

                        if (serviceId == 0) { // YouTube throttling
                            val count = youtubeExtractionCount.getAndIncrement()
                            if (count > 0 && count % YOUTUBE_BATCH_SIZE == 0) {
                                Thread.sleep((YOUTUBE_DELAY_MIN_MS..YOUTUBE_DELAY_MAX_MS).random())
                            }
                        }

                        try {
                            val channelInfo = ExtractorHelper.getChannelInfo(serviceId, url, true)
                                .subscribeOn(Schedulers.io())
                                .observeOn(Schedulers.io())
                                .blockingGet()

                            val videosTab = channelInfo.tabs.firstOrNull { tab ->
                                tab.contentFilters.any { it in listOf("videos", "tracks", "streams") }
                            }

                            if (videosTab != null) {
                                val tabInfo = ExtractorHelper.getChannelTab(serviceId, videosTab, true)
                                    .subscribeOn(Schedulers.io())
                                    .observeOn(Schedulers.io())
                                    .blockingGet()

                                val videos = tabInfo.relatedItems.filterIsInstance<StreamInfoItem>()
                                    .shuffled() // Shuffle videos from each channel too
                                    .take(videosPerChannel)
                                allVideos.addAll(videos)
                            }
                        } catch (e: Exception) {
                            // Skip failed channels
                        }

                        mutableStateLiveData.postValue(
                            SuggestionState.LoadingState(progress.incrementAndGet(), maxProgress)
                        )
                    }
                }

                allVideos.shuffled().take(MAX_VIDEOS) // Shuffle final results
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { videos ->
                        if (videos.isEmpty()) {
                            mutableStateLiveData.value = SuggestionState.EmptyState
                        } else {
                            mutableStateLiveData.value = SuggestionState.LoadedState(videos)
                        }
                    },
                    { error ->
                        mutableStateLiveData.value = SuggestionState.ErrorState(error)
                    }
                )
        )
    }

    override fun onCleared() {
        super.onCleared()
        disposables.dispose()
    }

    companion object {
        private const val MAX_VIDEOS = 100
        private const val DEFAULT_CHANNEL_COUNT = 50
        private const val DEFAULT_VIDEOS_PER_CHANNEL = 2
        private const val MAX_VIDEOS_PER_CHANNEL = 10
        private const val PARALLEL_EXTRACTIONS = 3
        private const val YOUTUBE_BATCH_SIZE = 50
        private const val YOUTUBE_DELAY_MIN_MS = 6000L
        private const val YOUTUBE_DELAY_MAX_MS = 12000L

        fun getFactory(application: Application) = viewModelFactory {
            initializer {
                SuggestionViewModel(application)
            }
        }
    }
}
