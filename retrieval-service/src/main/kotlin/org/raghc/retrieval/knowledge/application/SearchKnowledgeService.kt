package org.raghc.retrieval.knowledge.application

import org.springframework.stereotype.Service

@Service
class SearchKnowledgeService(
    private val searchIndex: KnowledgeSearchIndex,
) : SearchKnowledge {
    override fun search(query: SearchKnowledgeQuery): List<KnowledgeChunk> = searchIndex.search(query)
}
