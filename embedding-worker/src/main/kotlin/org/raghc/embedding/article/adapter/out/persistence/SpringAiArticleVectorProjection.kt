package org.raghc.embedding.article.adapter.out.persistence

import org.raghc.embedding.article.application.ArticleVectorProjection
import org.raghc.embedding.article.domain.ArticleChunk
import org.raghc.embedding.article.domain.PublishedArticleRevision
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class SpringAiArticleVectorProjection(
    private val vectorStore: VectorStore,
) : ArticleVectorProjection {
    override fun replace(
        article: PublishedArticleRevision,
        chunks: List<ArticleChunk>,
    ) {
        deleteArticle(article.tenantId, article.articleId)
        vectorStore.add(chunks.map { it.toDocument(article) })
    }

    override fun withdraw(
        tenantId: UUID,
        articleId: UUID,
    ) = deleteArticle(tenantId, articleId)

    private fun deleteArticle(
        tenantId: UUID,
        articleId: UUID,
    ) {
        val filters = FilterExpressionBuilder()
        vectorStore.delete(
            filters
                .and(
                    filters.eq(TENANT_ID, tenantId.toString()),
                    filters.eq(ARTICLE_ID, articleId.toString()),
                ).build(),
        )
    }

    private fun ArticleChunk.toDocument(article: PublishedArticleRevision) =
        Document(
            id.toString(),
            content,
            mapOf(
                TENANT_ID to article.tenantId.toString(),
                ARTICLE_ID to article.articleId.toString(),
                REVISION to article.revision,
                CHUNK_INDEX to index,
                LOCALE to article.locale,
            ),
        )

    private companion object {
        const val TENANT_ID = "tenantId"
        const val ARTICLE_ID = "articleId"
        const val REVISION = "revision"
        const val CHUNK_INDEX = "chunkIndex"
        const val LOCALE = "locale"
    }
}
