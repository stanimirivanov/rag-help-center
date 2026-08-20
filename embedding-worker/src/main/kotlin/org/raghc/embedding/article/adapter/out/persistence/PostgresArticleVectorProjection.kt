package org.raghc.embedding.article.adapter.out.persistence

import org.raghc.embedding.article.application.ArticleVectorProjection
import org.raghc.embedding.article.domain.ArticleChunk
import org.raghc.embedding.article.domain.PublishedArticleRevision
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class PostgresArticleVectorProjection(
    private val jdbcClient: JdbcClient,
) : ArticleVectorProjection {
    override fun replace(
        article: PublishedArticleRevision,
        chunks: List<ArticleChunk>,
        embeddings: List<FloatArray>,
    ) {
        jdbcClient
            .sql(
                "update article_chunks set active = false where tenant_id = :tenantId and article_id = :articleId",
            ).param("tenantId", article.tenantId)
            .param("articleId", article.articleId)
            .update()
        chunks.zip(embeddings).forEach { (chunk, embedding) -> upsert(article, chunk, embedding) }
    }

    override fun withdraw(
        tenantId: UUID,
        articleId: UUID,
    ) {
        jdbcClient
            .sql(
                "update article_chunks set active = false where tenant_id = :tenantId and article_id = :articleId",
            ).param("tenantId", tenantId)
            .param("articleId", articleId)
            .update()
    }

    private fun upsert(
        article: PublishedArticleRevision,
        chunk: ArticleChunk,
        embedding: FloatArray,
    ) {
        jdbcClient
            .sql(
                """
                insert into article_chunks
                    (chunk_id, tenant_id, article_id, revision, chunk_index, locale, content, embedding, active)
                values (:chunkId, :tenantId, :articleId, :revision, :chunkIndex, :locale, :content,
                        cast(:embedding as vector), true)
                on conflict (chunk_id) do update set active = true, indexed_at = clock_timestamp()
                """.trimIndent(),
            ).param("chunkId", chunk.id)
            .param("tenantId", article.tenantId)
            .param("articleId", article.articleId)
            .param("revision", article.revision)
            .param("chunkIndex", chunk.index)
            .param("locale", article.locale)
            .param("content", chunk.content)
            .param("embedding", embedding.joinToString(prefix = "[", postfix = "]"))
            .update()
    }
}
