package org.raghc.qa.retrieval.application

import java.util.UUID

data class RetrievalQuery(
    val tenantId: UUID,
    val question: String,
    val locale: String? = null,
    val topK: Int = 10,
    val minimumScore: Double = 0.65,
    val collectionId: UUID? = null,
)

data class RetrievedChunk(
    val chunkId: UUID,
    val articleId: UUID,
    val revision: Long,
    val chunkIndex: Int,
    val locale: String,
    val content: String,
    val score: Double,
)

fun interface RetrieveKnowledge {
    fun retrieve(query: RetrievalQuery): List<RetrievedChunk>
}
