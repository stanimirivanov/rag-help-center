package org.raghc.retrieval.knowledge.application

import java.util.UUID

data class SearchKnowledgeQuery(
    val tenantId: UUID,
    val query: String,
    val locale: String? = null,
    val topK: Int = DEFAULT_TOP_K,
    val minimumScore: Double = DEFAULT_MINIMUM_SCORE,
) {
    init {
        require(query.isNotBlank()) { "query must not be blank" }
        require(topK in 1..MAX_TOP_K) { "topK must be between 1 and $MAX_TOP_K" }
        require(minimumScore in 0.0..1.0) { "minimumScore must be between 0 and 1" }
        require(locale == null || locale.isNotBlank()) { "locale must not be blank" }
    }

    companion object {
        const val DEFAULT_TOP_K = 10
        const val MAX_TOP_K = 50
        const val DEFAULT_MINIMUM_SCORE = 0.65
    }
}

data class KnowledgeChunk(
    val chunkId: UUID,
    val articleId: UUID,
    val revision: Long,
    val chunkIndex: Int,
    val locale: String,
    val content: String,
    val score: Double,
)

fun interface SemanticKnowledgeSearchIndex {
    fun search(query: SearchKnowledgeQuery): List<KnowledgeChunk>
}

fun interface LexicalKnowledgeSearchIndex {
    fun search(query: SearchKnowledgeQuery): List<KnowledgeChunk>
}

fun interface SearchKnowledge {
    fun search(query: SearchKnowledgeQuery): List<KnowledgeChunk>
}
