package org.raghc.ingestion.article.adapter.outbound.identity

import org.raghc.ingestion.article.application.ArticleIdGenerator
import org.raghc.ingestion.article.domain.ArticleId
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class UuidArticleIdGenerator : ArticleIdGenerator {
    override fun next(): ArticleId = ArticleId(UUID.randomUUID())
}
