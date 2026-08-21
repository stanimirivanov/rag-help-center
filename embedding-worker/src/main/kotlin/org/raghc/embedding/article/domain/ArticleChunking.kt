package org.raghc.embedding.article.domain

import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

data class PublishedArticleRevision(
    val tenantId: UUID,
    val articleId: UUID,
    val revision: Long,
    val title: String,
    val body: String,
    val locale: String,
    val collectionId: UUID? = null,
) {
    init {
        require(revision > 0) { "revision must be positive" }
        require(title.isNotBlank()) { "title must not be blank" }
        require(body.isNotBlank()) { "body must not be blank" }
        require(locale.isNotBlank()) { "locale must not be blank" }
    }
}

data class ArticleChunk(
    val id: UUID,
    val index: Int,
    val content: String,
)

fun interface ArticleChunker {
    fun chunk(article: PublishedArticleRevision): List<ArticleChunk>
}

@Component
class FixedWindowArticleChunker : ArticleChunker {
    override fun chunk(article: PublishedArticleRevision): List<ArticleChunk> {
        val source = "${article.title.trim()}\n\n${article.body.trim()}"
        val chunks = mutableListOf<ArticleChunk>()
        var start = 0
        while (start < source.length) {
            val end = boundary(source, start)
            val content = source.substring(start, end).trim()
            if (content.isNotEmpty()) {
                chunks += ArticleChunk(chunkId(article, chunks.size, content), chunks.size, content)
            }
            if (end == source.length) break
            start = nextStart(source, start, end)
        }
        return chunks
    }

    private fun boundary(
        source: String,
        start: Int,
    ): Int {
        val hardEnd = (start + MAX_CHARS).coerceAtMost(source.length)
        if (hardEnd == source.length) return hardEnd
        val softEnd = source.lastIndexOfAny(BOUNDARIES, hardEnd)
        return if (softEnd >= start + MIN_CHARS) softEnd + 1 else hardEnd
    }

    private fun nextStart(
        source: String,
        previousStart: Int,
        end: Int,
    ): Int {
        val overlapStart = (end - OVERLAP_CHARS).coerceAtLeast(previousStart + 1)
        val nextBoundary = source.indexOfAny(BOUNDARIES, overlapStart)
        return if (nextBoundary in overlapStart until end) nextBoundary + 1 else overlapStart
    }

    private fun chunkId(
        article: PublishedArticleRevision,
        index: Int,
        content: String,
    ): UUID {
        val identity = "${article.tenantId}:${article.articleId}:${article.revision}:$index:$content"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(StandardCharsets.UTF_8))
        val bytes = digest.copyOf(UUID_BYTES)
        bytes[VERSION_BYTE_INDEX] =
            ((bytes[VERSION_BYTE_INDEX].toInt() and VERSION_MASK) or UUID_VERSION).toByte()
        bytes[VARIANT_BYTE_INDEX] =
            ((bytes[VARIANT_BYTE_INDEX].toInt() and VARIANT_MASK) or UUID_VARIANT).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }

    private companion object {
        const val MAX_CHARS = 1_000
        const val MIN_CHARS = 500
        const val OVERLAP_CHARS = 150
        const val UUID_BYTES = 16
        const val VERSION_BYTE_INDEX = 6
        const val VARIANT_BYTE_INDEX = 8
        const val VERSION_MASK = 0x0f
        const val UUID_VERSION = 0x50
        const val VARIANT_MASK = 0x3f
        const val UUID_VARIANT = 0x80
        val BOUNDARIES = charArrayOf('\n', ' ', '.', '!', '?')
    }
}
