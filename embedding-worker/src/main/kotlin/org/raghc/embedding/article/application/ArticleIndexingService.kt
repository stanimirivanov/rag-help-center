package org.raghc.embedding.article.application

import org.raghc.embedding.article.domain.ArticleChunker
import org.raghc.embedding.article.domain.PublishedArticleRevision
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ArticleIndexingService(
    private val chunker: ArticleChunker,
    private val vectorProjection: ArticleVectorProjection,
    private val checkpoint: IndexingCheckpoint,
) {
    @Transactional
    fun handle(event: ArticleIntegrationEvent) {
        require(event.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "unsupported ${event.eventType} schema version ${event.schemaVersion}"
        }
        if (!checkpoint.claim(event.eventId, event.eventType)) return
        when (event.eventType) {
            ARTICLE_PUBLISHED, ARTICLE_RESTORED -> index(event)
            ARTICLE_WITHDRAWN -> withdraw(event)
            else -> throw UnsupportedArticleEventException(event.eventType)
        }
    }

    private fun index(event: ArticleIntegrationEvent) {
        val article =
            PublishedArticleRevision(
                event.tenantId,
                event.aggregateId,
                event.data.revision,
                requireNotNull(event.data.title) { "published title is required" },
                requireNotNull(event.data.body) { "published body is required" },
                requireNotNull(event.data.locale) { "published locale is required" },
            )
        val chunks = chunker.chunk(article)
        vectorProjection.replace(article, chunks)
        checkpoint.recordStatus(event, "INDEXED")
    }

    private fun withdraw(event: ArticleIntegrationEvent) {
        vectorProjection.withdraw(event.tenantId, event.aggregateId)
        checkpoint.recordStatus(event, "WITHDRAWN")
    }

    companion object {
        const val ARTICLE_PUBLISHED = "ArticlePublished"
        const val ARTICLE_WITHDRAWN = "ArticleWithdrawn"
        const val ARTICLE_RESTORED = "ArticleRestored"
        const val SUPPORTED_SCHEMA_VERSION = 1
    }
}

class UnsupportedArticleEventException(
    eventType: String,
) : IllegalArgumentException("unsupported article integration event $eventType")
