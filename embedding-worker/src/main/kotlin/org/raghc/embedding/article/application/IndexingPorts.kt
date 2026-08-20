package org.raghc.embedding.article.application

import org.raghc.embedding.article.domain.ArticleChunk
import org.raghc.embedding.article.domain.PublishedArticleRevision
import java.util.UUID

interface ArticleVectorProjection {
    fun replace(
        article: PublishedArticleRevision,
        chunks: List<ArticleChunk>,
    )

    fun withdraw(
        tenantId: UUID,
        articleId: UUID,
    )
}

interface IndexingCheckpoint {
    fun claim(
        eventId: UUID,
        eventType: String,
    ): Boolean

    fun recordStatus(
        event: ArticleIntegrationEvent,
        status: String,
    )
}
