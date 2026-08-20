package org.raghc.retrieval.knowledge.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class SearchKnowledgeServiceTest {
    private val tenantId = UUID.randomUUID()

    @Test
    fun `reciprocal rank fusion rewards agreement and deduplicates chunks`() {
        val shared = chunk("shared")
        val semanticOnly = chunk("semantic")
        val lexicalOnly = chunk("lexical")
        val service =
            SearchKnowledgeService(
                SemanticKnowledgeSearchIndex { listOf(shared, semanticOnly) },
                LexicalKnowledgeSearchIndex { listOf(lexicalOnly, shared) },
            )

        val results = service.search(SearchKnowledgeQuery(tenantId, "reset password"))

        assertThat(results.map { it.chunkId }).containsExactly(
            shared.chunkId,
            lexicalOnly.chunkId,
            semanticOnly.chunkId,
        )
        assertThat(results).extracting<Double> { it.score }.allMatch { it in 0.0..1.0 }
    }

    @Test
    fun `fusion applies topK after combining both channels`() {
        val service =
            SearchKnowledgeService(
                SemanticKnowledgeSearchIndex { listOf(chunk("semantic")) },
                LexicalKnowledgeSearchIndex { listOf(chunk("lexical")) },
            )

        val results = service.search(SearchKnowledgeQuery(tenantId, "reset", topK = 1))

        assertThat(results).hasSize(1)
    }

    private fun chunk(label: String) =
        KnowledgeChunk(
            UUID.nameUUIDFromBytes(label.toByteArray()),
            UUID.randomUUID(),
            1,
            0,
            "en",
            label,
            0.9,
        )
}
