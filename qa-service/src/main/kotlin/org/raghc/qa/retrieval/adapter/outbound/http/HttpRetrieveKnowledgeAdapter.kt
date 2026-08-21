package org.raghc.qa.retrieval.adapter.outbound.http

import org.raghc.qa.retrieval.application.RetrievalQuery
import org.raghc.qa.retrieval.application.RetrieveKnowledge
import org.raghc.qa.retrieval.application.RetrievedChunk

internal class HttpRetrieveKnowledgeAdapter(
    private val client: RetrievalHttpService,
) : RetrieveKnowledge {
    override fun retrieve(query: RetrievalQuery): List<RetrievedChunk> =
        client
            .search(
                query.tenantId,
                RetrievalSearchRequest(
                    query = query.question,
                    collectionId = query.collectionId,
                    locale = query.locale,
                    topK = query.topK,
                    minimumScore = query.minimumScore,
                ),
            ).chunks
            .map {
                RetrievedChunk(
                    chunkId = it.chunkId,
                    articleId = it.articleId,
                    revision = it.revision,
                    chunkIndex = it.chunkIndex,
                    locale = it.locale,
                    content = it.content,
                    score = it.score,
                )
            }
}
