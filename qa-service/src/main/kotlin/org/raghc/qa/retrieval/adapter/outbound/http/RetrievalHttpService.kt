package org.raghc.qa.retrieval.adapter.outbound.http

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.service.annotation.PostExchange
import java.util.UUID

internal interface RetrievalHttpService {
    @PostExchange(url = "/internal/v1/search", contentType = MediaType.APPLICATION_JSON_VALUE)
    fun search(
        @RequestHeader("X-Tenant-Id") tenantId: UUID,
        @RequestBody request: RetrievalSearchRequest,
    ): RetrievalSearchResponse
}

internal data class RetrievalSearchRequest(
    val query: String,
    val locale: String?,
    val topK: Int,
    val minimumScore: Double,
)

internal data class RetrievalSearchResponse(
    val chunks: List<RetrievalChunkResponse>,
)

internal data class RetrievalChunkResponse(
    val chunkId: UUID,
    val articleId: UUID,
    val revision: Long,
    val chunkIndex: Int,
    val locale: String,
    val content: String,
    val score: Double,
)
