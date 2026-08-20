package org.raghc.ingestion.article.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class KnowledgeArticleTest {
    private val articleId = ArticleId(UUID.randomUUID())
    private val tenantId = TenantId(UUID.randomUUID())
    private val createdAt = Instant.parse("2026-08-18T08:00:00Z")

    @Test
    fun `creates a draft as the first event`() {
        val article =
            KnowledgeArticle.create(
                articleId,
                tenantId,
                ArticleContent("Reset a password", "Choose Forgot password.", "en"),
                createdAt,
            )

        assertThat(article.streamVersion).isEqualTo(1)
        assertThat(article.pendingEvents()).containsExactly(
            ArticleCreated("Reset a password", "Choose Forgot password.", "en", createdAt),
        )
    }

    @Test
    fun `rehydrates and revises without exposing prior events as changes`() {
        val history =
            listOf(
                ArticleCreated("Reset a password", "Old instructions", "en", createdAt),
                ArticleContentRevised("Reset a password", "Current instructions", createdAt.plusSeconds(60)),
            )
        val article = KnowledgeArticle.rehydrate(articleId, tenantId, history)

        article.revise("Reset a forgotten password", "Current instructions", createdAt.plusSeconds(120))

        assertThat(article.streamVersion).isEqualTo(3)
        assertThat(article.content.title).isEqualTo("Reset a forgotten password")
        assertThat(article.pendingEvents()).containsExactly(
            ArticleContentRevised(
                "Reset a forgotten password",
                "Current instructions",
                createdAt.plusSeconds(120),
            ),
        )
    }

    @Test
    fun `rejects an unchanged revision`() {
        val article =
            KnowledgeArticle.create(
                articleId,
                tenantId,
                ArticleContent("Reset a password", "Instructions", "en"),
                createdAt,
            )

        assertThatThrownBy {
            article.revise("Reset a password", "Instructions", createdAt.plusSeconds(1))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must change")
    }
}
