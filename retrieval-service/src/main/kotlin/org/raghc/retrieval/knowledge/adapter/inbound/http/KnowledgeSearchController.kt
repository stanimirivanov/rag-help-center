package org.raghc.retrieval.knowledge.adapter.inbound.http

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.raghc.retrieval.knowledge.application.KnowledgeChunk
import org.raghc.retrieval.knowledge.application.SearchKnowledge
import org.raghc.retrieval.knowledge.application.SearchKnowledgeQuery
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

private const val MAX_TOP_K_VALIDATION = 50L

@RestController
@RequestMapping("/internal/v1/search")
class KnowledgeSearchController(
    private val searchKnowledge: SearchKnowledge,
) {
    @PostMapping
    fun search(
        @RequestHeader("X-Tenant-Id") tenantId: UUID,
        @Valid @RequestBody request: KnowledgeSearchRequest,
    ) = KnowledgeSearchResponse(
        searchKnowledge
            .search(
                SearchKnowledgeQuery(
                    tenantId,
                    request.query,
                    request.locale,
                    request.topK,
                    request.minimumScore,
                ),
            ).map { it.toResponse() },
    )

    private fun KnowledgeChunk.toResponse() =
        KnowledgeChunkResponse(
            chunkId = chunkId,
            articleId = articleId,
            revision = revision,
            chunkIndex = chunkIndex,
            locale = locale,
            content = content,
            score = score,
        )
}

data class KnowledgeSearchRequest(
    @field:NotBlank @field:Size(max = 2_000) val query: String,
    @field:Pattern(regexp = "^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$") val locale: String? = null,
    @field:Min(1) @field:Max(MAX_TOP_K_VALIDATION) val topK: Int = SearchKnowledgeQuery.DEFAULT_TOP_K,
    @field:DecimalMin("0.0") @field:DecimalMax("1.0")
    val minimumScore: Double = SearchKnowledgeQuery.DEFAULT_MINIMUM_SCORE,
)

data class KnowledgeSearchResponse(
    val chunks: List<KnowledgeChunkResponse>,
)

data class KnowledgeChunkResponse(
    val chunkId: UUID,
    val articleId: UUID,
    val revision: Long,
    val chunkIndex: Int,
    val locale: String,
    val content: String,
    val score: Double,
)
