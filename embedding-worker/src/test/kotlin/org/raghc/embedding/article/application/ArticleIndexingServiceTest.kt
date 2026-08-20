package org.raghc.embedding.article.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.raghc.embedding.article.adapter.out.embedding.DeterministicEmbeddingModel
import org.raghc.embedding.article.domain.ArticleChunk
import org.raghc.embedding.article.domain.FixedWindowArticleChunker
import org.raghc.embedding.article.domain.PublishedArticleRevision
import java.time.Instant
import java.util.UUID

class ArticleIndexingServiceTest {
    private val tenantId = UUID.randomUUID()
    private val articleId = UUID.randomUUID()
    private val projection = RecordingProjection()
    private val checkpoint = RecordingCheckpoint()
    private val service = ArticleIndexingService(FixedWindowArticleChunker(), projection, checkpoint)

    @Test
    fun `indexes a publication once when the event is redelivered`() {
        val event = event("ArticlePublished", revision = 1)

        service.handle(event)
        service.handle(event)

        assertThat(projection.replacements).hasSize(1)
        assertThat(checkpoint.statuses).containsExactly("INDEXED")
    }

    @Test
    fun `replaces an old revision and makes withdrawal ineligible`() {
        val published = event("ArticlePublished", revision = 1)
        val restored = event("ArticleRestored", revision = 2)
        service.handle(published)
        service.handle(restored)

        service.handle(event("ArticleWithdrawn", revision = 2, content = false))

        assertThat(projection.replacements.map { it.revision }).containsExactly(1, 2)
        assertThat(projection.withdrawn).containsExactly(published.tenantId to published.aggregateId)
        assertThat(checkpoint.statuses).containsExactly("INDEXED", "INDEXED", "WITHDRAWN")
    }

    private fun event(
        type: String,
        revision: Long,
        content: Boolean = true,
    ): ArticleIntegrationEvent =
        ArticleIntegrationEvent(
            UUID.randomUUID(),
            type,
            1,
            "trace",
            tenantId,
            articleId,
            revision,
            Instant.parse("2026-08-20T12:00:00Z"),
            ArticleRevisionData(
                revision,
                "Reset a password".takeIf { content },
                "Follow the instructions.".takeIf { content },
                "en".takeIf { content },
            ),
        )

    private class RecordingProjection : ArticleVectorProjection {
        val replacements = mutableListOf<PublishedArticleRevision>()
        val withdrawn = mutableListOf<Pair<UUID, UUID>>()

        override fun replace(
            article: PublishedArticleRevision,
            chunks: List<ArticleChunk>,
        ) {
            replacements += article
        }

        override fun withdraw(
            tenantId: UUID,
            articleId: UUID,
        ) {
            withdrawn += tenantId to articleId
        }
    }

    private class RecordingCheckpoint : IndexingCheckpoint {
        private val claimed = mutableSetOf<UUID>()
        val statuses = mutableListOf<String>()

        override fun claim(
            eventId: UUID,
            eventType: String,
        ): Boolean = claimed.add(eventId)

        override fun recordStatus(
            event: ArticleIntegrationEvent,
            status: String,
        ) {
            statuses += status
        }
    }
}

class DeterministicEmbeddingModelTest {
    private val model = DeterministicEmbeddingModel()

    @Test
    fun `returns stable normalized embeddings without an external model`() {
        val first = model.embed(listOf("same text", "different text"))
        val replay = model.embed(listOf("same text", "different text"))

        assertThat(replay.map(FloatArray::toList)).isEqualTo(first.map(FloatArray::toList))
        assertThat(first[0].toList()).isNotEqualTo(first[1].toList())
        assertThat(first).allSatisfy { vector ->
            assertThat(vector).hasSize(8)
            vector.forEach { value -> assertThat(value).isBetween(-1.0f, 1.0f) }
        }
    }
}
