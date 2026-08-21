package org.raghc.ingestion.article.domain

import java.time.Instant

sealed interface ArticleEvent {
    val occurredAt: Instant
}

data class ArticleCreated(
    val title: String,
    val body: String,
    val locale: String,
    override val occurredAt: Instant,
    val collectionId: java.util.UUID? = null,
) : ArticleEvent

data class ArticleContentRevised(
    val title: String,
    val body: String,
    override val occurredAt: Instant,
) : ArticleEvent

data class ArticlePublished(
    val revision: Long,
    override val occurredAt: Instant,
) : ArticleEvent

data class ArticleWithdrawn(
    override val occurredAt: Instant,
) : ArticleEvent

data class ArticleRestored(
    override val occurredAt: Instant,
) : ArticleEvent
