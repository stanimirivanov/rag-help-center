package org.raghc.embedding.article.adapter.out.embedding

import org.springframework.ai.document.Document
import org.springframework.ai.embedding.AbstractEmbeddingModel
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Component
@ConditionalOnProperty(name = ["app.embedding.provider"], havingValue = "fake")
class DeterministicEmbeddingModel : AbstractEmbeddingModel() {
    override fun call(request: EmbeddingRequest) =
        EmbeddingResponse(request.instructions.mapIndexed { index, text -> Embedding(embedding(text), index) })

    override fun embed(document: Document): FloatArray = embedding(document.formattedContent)

    override fun dimensions(): Int = DIMENSIONS

    private fun embedding(text: String): FloatArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
        return FloatArray(DIMENSIONS) { index ->
            val unsigned = digest[index].toInt() and BYTE_MASK
            (unsigned / BYTE_MAX.toFloat()) * SCALE - OFFSET
        }
    }

    private companion object {
        const val DIMENSIONS = 8
        const val BYTE_MASK = 0xff
        const val BYTE_MAX = 255
        const val SCALE = 2
        const val OFFSET = 1
    }
}
