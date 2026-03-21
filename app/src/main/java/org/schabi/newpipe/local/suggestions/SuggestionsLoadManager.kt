package org.schabi.newpipe.local.suggestions

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Notification
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.functions.Consumer
import io.reactivex.rxjava3.processors.PublishProcessor
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.schabi.newpipe.R
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.Info
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.local.subscription.SubscriptionManager
import org.schabi.newpipe.util.ExtractorHelper.getChannelInfo
import org.schabi.newpipe.util.ExtractorHelper.getChannelTab

class SuggestionsLoadManager(private val context: Context) {

    private val subscriptionManager = SubscriptionManager(context)

    private val notificationUpdater = PublishProcessor.create<String>()
    private val currentProgress = AtomicInteger(-1)
    private val maxProgress = AtomicInteger(-1)
    private val cancelSignal = AtomicBoolean()

    val notification: Flowable<SuggestionsLoadState> = notificationUpdater.map { description ->
        SuggestionsLoadState(description, maxProgress.get(), currentProgress.get())
    }

    fun startLoading(): Single<List<StreamInfoItem>> {
        val defaultSharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val youtubeExtractionCount = AtomicInteger()

        return subscriptionManager.subscriptions()
            .take(1)
            .doOnNext {
                currentProgress.set(0)
                maxProgress.set(it.size)
            }
            .filter { it.isNotEmpty() }
            .observeOn(AndroidSchedulers.mainThread())
            .doOnNext {
                notificationUpdater.onNext("")
                broadcastProgress()
            }
            .observeOn(Schedulers.io())
            .map { allSubscriptions ->
                val shuffled = allSubscriptions.shuffled()
                val sampleSize = minOf(shuffled.size, MAX_CHANNELS)
                val selectedChannels = shuffled.take(sampleSize)
                SuggestionsResultsHolder.setChannelCount(selectedChannels.size)
                selectedChannels
            }
            .flatMap { Flowable.fromIterable(it) }
            .takeWhile { !cancelSignal.get() }
            .doOnNext { subscriptionEntity ->
                if (subscriptionEntity.serviceId == ServiceList.YouTube.serviceId) {
                    val previousCount = youtubeExtractionCount.getAndIncrement()
                    if (previousCount != 0 && previousCount % BATCH_SIZE == 0) {
                        Thread.sleep(DELAY_BETWEEN_BATCHES_MILLIS.random())
                    }
                }
            }
            .parallel(PARALLEL_EXTRACTIONS, PARALLEL_EXTRACTIONS * 2)
            .runOn(Schedulers.io(), PARALLEL_EXTRACTIONS * 2)
            .filter { !cancelSignal.get() }
            .map { subscriptionEntity ->
                loadChannelVideos(subscriptionEntity, defaultSharedPreferences)
            }
            .sequential()
            .observeOn(AndroidSchedulers.mainThread())
            .doOnNext(NotificationConsumer())
            .observeOn(Schedulers.io())
            .toList()
            .flatMap { collectVideos(it) }
            .doOnSubscribe {
                SuggestionsEventManager.postEvent(SuggestionsEventManager.Event.ProgressEvent(R.string.feed_processing_message))
            }
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSuccess {
                SuggestionsEventManager.postEvent(SuggestionsEventManager.Event.SuccessResultEvent(SuggestionsResultsHolder.itemsErrors))
            }
            .observeOn(Schedulers.io())
            .doOnDispose {
                currentProgress.set(-1)
                maxProgress.set(-1)
            }
    }

    fun cancel() {
        cancelSignal.set(true)
    }

    private fun broadcastProgress() {
        SuggestionsEventManager.postEvent(
            SuggestionsEventManager.Event.ProgressEvent(
                currentProgress.get(),
                maxProgress.get()
            )
        )
    }

    private fun loadChannelVideos(
        subscriptionEntity: SubscriptionEntity,
        defaultSharedPreferences: SharedPreferences
    ): Notification<SuggestionsUpdateInfo> {
        var error: Throwable? = null
        val storeOriginalErrorAndRethrow = { e: Throwable ->
            error = e
            throw e
        }

        try {
            val channelInfo = getChannelInfo(
                subscriptionEntity.serviceId,
                subscriptionEntity.url,
                true
            )
                .onErrorReturn(storeOriginalErrorAndRethrow)
                .blockingGet()

            val errors = ArrayList<Throwable>()
            errors.addAll(channelInfo.errors)

            val videosTab = channelInfo.tabs.firstOrNull { tab ->
                tab.contentFilters.any { filter ->
                    filter.contains("videos", ignoreCase = true) ||
                        filter.contains("tracks", ignoreCase = true) ||
                        filter.contains("streams", ignoreCase = true)
                }
            } ?: channelInfo.tabs.firstOrNull()

            if (videosTab == null) {
                return Notification.createOnNext(
                    SuggestionsUpdateInfo(
                        subscriptionEntity,
                        channelInfo,
                        emptyList(),
                        errors
                    )
                )
            }

            val channelTabInfo = getChannelTab(
                subscriptionEntity.serviceId,
                videosTab,
                true
            )
                .onErrorReturn(storeOriginalErrorAndRethrow)
                .blockingGet()

            errors.addAll(channelTabInfo.errors)

            val streams = channelTabInfo.relatedItems
                .filterIsInstance<StreamInfoItem>()
                .take(VIDEOS_PER_CHANNEL_MAX)

            return Notification.createOnNext(
                SuggestionsUpdateInfo(
                    subscriptionEntity,
                    channelInfo,
                    streams,
                    errors
                )
            )
        } catch (e: Throwable) {
            val request = "${subscriptionEntity.serviceId}:${subscriptionEntity.url}"
            val wrapper = SuggestionsLoadService.RequestException(
                subscriptionEntity.uid,
                request,
                error ?: e
            )
            SuggestionsResultsHolder.addError(wrapper)
            return Notification.createOnError(wrapper)
        }
    }

    private fun collectVideos(
        notifications: List<Notification<SuggestionsUpdateInfo>>
    ): Single<List<StreamInfoItem>> {
        val allVideos = mutableListOf<StreamInfoItem>()

        for (notification in notifications) {
            if (notification.isOnNext) {
                val updateInfo = notification.value!!
                allVideos.addAll(updateInfo.streams)

                if (updateInfo.errors.isNotEmpty()) {
                    SuggestionsResultsHolder.addErrors(
                        updateInfo.errors.map { err ->
                            SuggestionsLoadService.RequestException(
                                updateInfo.uid,
                                "${updateInfo.serviceId}:${updateInfo.url}",
                                err
                            )
                        }
                    )
                }
            } else if (notification.isOnError) {
                SuggestionsResultsHolder.addError(notification.error!!)
            }
        }

        val cappedVideos = if (allVideos.size > MAX_VIDEOS) {
            allVideos.shuffled().take(MAX_VIDEOS)
        } else {
            allVideos
        }

        val shuffledVideos = cappedVideos.shuffled()
        SuggestionsResultsHolder.setLoadedItems(shuffledVideos)
        return Single.just(shuffledVideos)
    }

    private inner class NotificationConsumer : Consumer<Notification<SuggestionsUpdateInfo>> {
        override fun accept(item: Notification<SuggestionsUpdateInfo>) {
            currentProgress.incrementAndGet()
            notificationUpdater.onNext(item.value?.name.orEmpty())
            broadcastProgress()
        }
    }

    companion object {
        private const val PARALLEL_EXTRACTIONS = 3
        private const val BATCH_SIZE = 50
        private val DELAY_BETWEEN_BATCHES_MILLIS = (6000L..12000L)

        const val MAX_CHANNELS = 50
        const val MAX_VIDEOS = 100
        private const val VIDEOS_PER_CHANNEL_MAX = 10
    }
}
