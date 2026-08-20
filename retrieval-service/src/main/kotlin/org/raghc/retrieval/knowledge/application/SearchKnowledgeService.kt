package org.raghc.retrieval.knowledge.application

import org.springframework.stereotype.Service
import java.util.UUID

@Service
class SearchKnowledgeService(
    private val semanticIndex: SemanticKnowledgeSearchIndex,
    private val lexicalIndex: LexicalKnowledgeSearchIndex,
) : SearchKnowledge {
    override fun search(query: SearchKnowledgeQuery): List<KnowledgeChunk> {
        val scores = mutableMapOf<UUID, Double>()
        val chunks = mutableMapOf<UUID, KnowledgeChunk>()
        addRanked(semanticIndex.search(query), scores, chunks)
        addRanked(lexicalIndex.search(query), scores, chunks)
        return chunks.values
            .map { it.copy(score = scores.getValue(it.chunkId) / MAXIMUM_RRF_SCORE) }
            .sortedWith(compareByDescending<KnowledgeChunk> { it.score }.thenBy { it.chunkId })
            .take(query.topK)
    }

    private fun addRanked(
        ranked: List<KnowledgeChunk>,
        scores: MutableMap<UUID, Double>,
        chunks: MutableMap<UUID, KnowledgeChunk>,
    ) {
        ranked.forEachIndexed { index, chunk ->
            chunks.putIfAbsent(chunk.chunkId, chunk)
            scores.merge(chunk.chunkId, 1.0 / (RRF_RANK_CONSTANT + index + 1)) { first, second -> first + second }
        }
    }

    private companion object {
        const val RRF_RANK_CONSTANT = 60
        const val MAXIMUM_RRF_SCORE = 2.0 / 61.0
    }
}
