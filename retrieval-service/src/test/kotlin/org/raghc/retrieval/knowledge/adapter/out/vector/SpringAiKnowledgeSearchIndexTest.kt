package org.raghc.retrieval.knowledge.adapter.out.vector

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.filter.Filter
import java.util.UUID

class SpringAiKnowledgeSearchIndexTest {
    private val vectorStore = RecordingVectorStore()
    private val searchIndex = SpringAiKnowledgeSearchIndex(vectorStore)

    @Test
    fun `always applies tenant filter and maps attributable chunk metadata`() {
        val tenantId = UUID.randomUUID()
        val articleId = UUID.randomUUID()
        val chunkId = UUID.randomUUID()
        vectorStore.results =
            listOf(
                Document
                    .builder()
                    .id(chunkId.toString())
                    .text("Reset your password from settings.")
                    .metadata(
                        mapOf(
                            "tenantId" to tenantId.toString(),
                            "articleId" to articleId.toString(),
                            "revision" to 3L,
                            "chunkIndex" to 1,
                            "locale" to "en",
                        ),
                    ).score(0.91)
                    .build(),
            )

        val results =
            searchIndex.search(
                org.raghc.retrieval.knowledge.application.SearchKnowledgeQuery(
                    tenantId,
                    "forgot password",
                    locale = "en",
                    topK = 5,
                    minimumScore = 0.8,
                ),
            )

        assertThat(vectorStore.request.query).isEqualTo("forgot password")
        assertThat(vectorStore.request.topK).isEqualTo(5)
        assertThat(vectorStore.request.similarityThreshold).isEqualTo(0.8)
        assertThat(vectorStore.request.filterExpression.toString())
            .contains("tenantId", tenantId.toString(), "locale", "en")
        val chunk = results.single()
        assertThat(chunk.chunkId).isEqualTo(chunkId)
        assertThat(chunk.articleId).isEqualTo(articleId)
        assertThat(chunk.revision).isEqualTo(3)
        assertThat(chunk.score).isEqualTo(0.91)
    }

    @Test
    fun `defensively excludes results below the requested similarity threshold`() {
        val tenantId = UUID.randomUUID()
        vectorStore.results =
            listOf(
                document(tenantId, "relevant", 0.81),
                document(tenantId, "weak", 0.79),
            )

        val results =
            searchIndex.search(
                org.raghc.retrieval.knowledge.application.SearchKnowledgeQuery(
                    tenantId,
                    "reset password",
                    minimumScore = 0.8,
                ),
            )

        assertThat(results).extracting<String> { it.content }.containsExactly("relevant")
    }

    private fun document(
        tenantId: UUID,
        content: String,
        score: Double,
    ) = Document
        .builder()
        .id(UUID.randomUUID().toString())
        .text(content)
        .metadata(
            mapOf(
                "tenantId" to tenantId.toString(),
                "articleId" to UUID.randomUUID().toString(),
                "revision" to 1L,
                "chunkIndex" to 0,
                "locale" to "en",
            ),
        ).score(score)
        .build()

    private class RecordingVectorStore : VectorStore {
        lateinit var request: SearchRequest
        var results: List<Document> = emptyList()

        override fun similaritySearch(request: SearchRequest): List<Document> {
            this.request = request
            return results
        }

        override fun add(documents: List<Document>) = Unit

        override fun delete(idList: List<String>) = Unit

        override fun delete(filterExpression: Filter.Expression) = Unit
    }
}
