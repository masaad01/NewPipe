package org.schabi.newpipe.local.suggestions

import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.Info
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.util.image.ImageStrategy

data class SuggestionsUpdateInfo(
    val uid: Long,
    val name: String,
    val avatarUrl: String?,
    val url: String,
    val serviceId: Int,
    val streams: List<StreamInfoItem>,
    val errors: List<Throwable>
) {
    constructor(
        subscription: SubscriptionEntity,
        info: Info,
        streams: List<StreamInfoItem>,
        errors: List<Throwable>
    ) : this(
        uid = subscription.uid,
        name = info.name,
        avatarUrl = (info as? ChannelInfo)?.avatars?.let {
            ImageStrategy.imageListToDbUrl(it)
        } ?: subscription.avatarUrl,
        url = info.url,
        serviceId = info.serviceId,
        streams = streams,
        errors = errors
    )

    val pseudoId: Int
        get() = url.hashCode()
}
