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
) : ArticleEvent

data class ArticleContentRevised(
    val title: String,
    val body: String,
    override val occurredAt: Instant,
) : ArticleEvent
