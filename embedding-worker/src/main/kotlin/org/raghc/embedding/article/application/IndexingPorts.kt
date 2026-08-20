package org.raghc.embedding.article.application

import org.raghc.embedding.article.domain.ArticleChunk
import org.raghc.embedding.article.domain.PublishedArticleRevision
import java.util.UUID

fun interface TextEmbeddingPort {
    fun embed(texts: List<String>): List<FloatArray>
}

interface ArticleVectorProjection {
    fun replace(
        article: PublishedArticleRevision,
        chunks: List<ArticleChunk>,
        embeddings: List<FloatArray>,
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
