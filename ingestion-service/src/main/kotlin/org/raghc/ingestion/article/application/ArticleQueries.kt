package org.raghc.ingestion.article.application

import org.raghc.ingestion.article.domain.ArticleId
import org.raghc.ingestion.article.domain.TenantId
import java.time.OffsetDateTime

data class ArticleView(
    val articleId: ArticleId,
    val title: String,
    val body: String,
    val locale: String,
    val lifecycleStatus: String,
    val revision: Long,
    val streamVersion: Long,
    val indexingStatus: String,
    val updatedAt: OffsetDateTime,
)

interface ArticleQueryRepository {
    fun find(
        tenantId: TenantId,
        articleId: ArticleId,
    ): ArticleView?
}
