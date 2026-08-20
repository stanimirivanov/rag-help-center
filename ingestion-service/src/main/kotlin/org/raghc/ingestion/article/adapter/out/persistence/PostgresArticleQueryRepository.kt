package org.raghc.ingestion.article.adapter.out.persistence

import org.raghc.ingestion.article.application.ArticleQueryRepository
import org.raghc.ingestion.article.application.ArticleView
import org.raghc.ingestion.article.domain.ArticleId
import org.raghc.ingestion.article.domain.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class PostgresArticleQueryRepository(
    private val jdbcClient: JdbcClient,
) : ArticleQueryRepository {
    override fun find(
        tenantId: TenantId,
        articleId: ArticleId,
    ): ArticleView? =
        jdbcClient
            .sql(
                """
                select article_id, title, body, locale, lifecycle_status, revision,
                       stream_version, indexing_status, updated_at
                from article_projection where tenant_id = :tenantId and article_id = :articleId
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("articleId", articleId.value)
            .query { rs, _ ->
                ArticleView(
                    ArticleId(rs.getObject("article_id", UUID::class.java)),
                    rs.getString("title"),
                    rs.getString("body"),
                    rs.getString("locale"),
                    rs.getString("lifecycle_status"),
                    rs.getLong("revision"),
                    rs.getLong("stream_version"),
                    rs.getString("indexing_status"),
                    rs.getObject("updated_at", OffsetDateTime::class.java),
                )
            }.optional()
            .orElse(null)
}
