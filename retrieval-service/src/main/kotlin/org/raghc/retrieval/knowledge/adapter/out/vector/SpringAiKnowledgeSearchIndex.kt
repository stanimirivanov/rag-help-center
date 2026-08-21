package org.raghc.retrieval.knowledge.adapter.out.vector

import org.raghc.retrieval.knowledge.application.KnowledgeChunk
import org.raghc.retrieval.knowledge.application.SearchKnowledgeQuery
import org.raghc.retrieval.knowledge.application.SemanticKnowledgeSearchIndex
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class SpringAiKnowledgeSearchIndex(
    private val vectorStore: VectorStore,
) : SemanticKnowledgeSearchIndex {
    override fun search(query: SearchKnowledgeQuery): List<KnowledgeChunk> {
        val request =
            SearchRequest
                .builder()
                .query(query.query)
                .topK(query.topK)
                .similarityThreshold(query.minimumScore)
                .filterExpression(filters(query))
                .build()
        return vectorStore
            .similaritySearch(request)
            .map { it.toKnowledgeChunk() }
            .filter { it.score >= query.minimumScore }
    }

    private fun filters(query: SearchKnowledgeQuery) =
        FilterExpressionBuilder().let { builder ->
            var filters = builder.eq(TENANT_ID, query.tenantId.toString())
            query.collectionId?.let { filters = builder.and(filters, builder.eq(COLLECTION_ID, it.toString())) }
            query.locale?.let { filters = builder.and(filters, builder.eq(LOCALE, it)) }
            filters.build()
        }

    private fun Document.toKnowledgeChunk() =
        KnowledgeChunk(
            UUID.fromString(id),
            UUID.fromString(metadata.requiredString(ARTICLE_ID)),
            metadata.requiredNumber(REVISION).toLong(),
            metadata.requiredNumber(CHUNK_INDEX).toInt(),
            metadata.requiredString(LOCALE),
            requireNotNull(text) { "vector document $id has no text content" },
            requireNotNull(score) { "vector document $id has no similarity score" },
        )

    private fun Map<String, Any>.requiredString(key: String) =
        this[key] as? String ?: error("vector document metadata $key must be a string")

    private fun Map<String, Any>.requiredNumber(key: String) =
        this[key] as? Number ?: error("vector document metadata $key must be a number")

    private companion object {
        const val TENANT_ID = "tenantId"
        const val ARTICLE_ID = "articleId"
        const val COLLECTION_ID = "collectionId"
        const val REVISION = "revision"
        const val CHUNK_INDEX = "chunkIndex"
        const val LOCALE = "locale"
    }
}
