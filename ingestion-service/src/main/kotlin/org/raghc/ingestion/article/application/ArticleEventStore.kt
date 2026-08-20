package org.raghc.ingestion.article.application

import org.raghc.ingestion.article.domain.ArticleEvent
import org.raghc.ingestion.article.domain.ArticleId
import org.raghc.ingestion.article.domain.TenantId

interface ArticleEventStore {
    fun load(
        tenantId: TenantId,
        articleId: ArticleId,
    ): List<ArticleEvent>

    fun append(
        tenantId: TenantId,
        articleId: ArticleId,
        expectedVersion: Long,
        events: List<ArticleEvent>,
    )
}

class ArticleNotFoundException(
    articleId: ArticleId,
) : RuntimeException("article ${articleId.value} was not found")

class ConcurrentArticleModificationException(
    val expectedVersion: Long,
    val actualVersion: Long,
) : RuntimeException("expected article version $expectedVersion but found $actualVersion")
