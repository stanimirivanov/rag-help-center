package org.raghc.embedding.article.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class FixedWindowArticleChunkerTest {
    private val chunker = FixedWindowArticleChunker()
    private val tenantId = UUID.randomUUID()
    private val articleId = UUID.randomUUID()

    @Test
    fun `creates bounded overlapping chunks with stable indexes`() {
        val article = article(body = (1..300).joinToString(" ") { "instruction-$it" })

        val chunks = chunker.chunk(article)

        assertThat(chunks).hasSizeGreaterThan(1)
        assertThat(chunks.map { it.index }).containsExactlyElementsOf(chunks.indices.toList())
        assertThat(chunks).allSatisfy { assertThat(it.content.length).isLessThanOrEqualTo(1_000) }
        assertThat(chunks.zipWithNext()).allSatisfy { pair ->
            val sharedWords =
                pair.first.content
                    .split(' ')
                    .toSet()
                    .intersect(
                        pair.second.content
                            .split(' ')
                            .toSet(),
                    )
            assertThat(sharedWords).isNotEmpty()
        }
    }

    @Test
    fun `is deterministic but revision-sensitive`() {
        val first = chunker.chunk(article())
        val replay = chunker.chunk(article())
        val nextRevision = chunker.chunk(article(revision = 2))

        assertThat(replay).isEqualTo(first)
        assertThat(nextRevision.map { it.id }).doesNotContainAnyElementsOf(first.map { it.id })
    }

    private fun article(
        revision: Long = 1,
        body: String = "Follow the password reset instructions in account settings.",
    ) = PublishedArticleRevision(tenantId, articleId, revision, "Reset a password", body, "en")
}
