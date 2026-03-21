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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.local.subscription.SubscriptionManager
import org.schabi.newpipe.util.ExtractorHelper

class SuggestionViewModel(
    private val application: Application
) : ViewModel() {

    private val mutableStateLiveData = MutableLiveData<SuggestionState>()
    val stateLiveData: LiveData<SuggestionState> = mutableStateLiveData

    private val disposables = CompositeDisposable()
    private val subscriptionManager = SubscriptionManager(application)

    init {
        loadSuggestions()
    }

    fun loadSuggestions() {
        disposables.clear()
        mutableStateLiveData.postValue(SuggestionState.LoadingState())

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
                for (subscription in channels) {
                    if (allVideos.size >= MAX_VIDEOS) break

                    val serviceId = subscription.serviceId
                    val url = subscription.url ?: continue

                    if (serviceId == ServiceList.YouTube.serviceId) {
                        val count = youtubeExtractionCount.getAndIncrement()
                        if (count > 0 && count % YOUTUBE_BATCH_SIZE == 0) {
                            val delayMs = (YOUTUBE_DELAY_MIN_MS..YOUTUBE_DELAY_MAX_MS).random()
                            val end = System.currentTimeMillis() + delayMs
                            while (System.currentTimeMillis() < end) {
                                if (disposables.isDisposed) return@fromCallable emptyList()
                                Thread.sleep(200)
                            }
                        }
                    }

                    try {
                        val channelInfo = ExtractorHelper.getChannelInfo(serviceId, url, true)
                            .subscribeOn(Schedulers.io())
                            .timeout(10, TimeUnit.SECONDS)
                            .blockingGet()

                        val videosTab = channelInfo.tabs.firstOrNull { tab ->
                            tab.contentFilters.any { it in listOf("videos", "tracks", "streams") }
                        }

                        if (videosTab != null) {
                            val tabInfo = ExtractorHelper.getChannelTab(serviceId, videosTab, true)
                                .subscribeOn(Schedulers.io())
                                .timeout(10, TimeUnit.SECONDS)
                                .blockingGet()

                            val videos = tabInfo.relatedItems.filterIsInstance<StreamInfoItem>()
                                .shuffled()
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

                return@fromCallable allVideos.shuffled().take(MAX_VIDEOS)
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
