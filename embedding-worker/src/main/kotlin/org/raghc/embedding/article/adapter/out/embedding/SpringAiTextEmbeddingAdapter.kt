package org.raghc.embedding.article.adapter.out.embedding

import org.raghc.embedding.article.application.TextEmbeddingPort
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Component
@ConditionalOnProperty(name = ["app.embedding.provider"], havingValue = "ollama", matchIfMissing = true)
class SpringAiTextEmbeddingAdapter(
    private val embeddingModel: EmbeddingModel,
) : TextEmbeddingPort {
    override fun embed(texts: List<String>): List<FloatArray> = embeddingModel.embed(texts)
}

@Component
@ConditionalOnProperty(name = ["app.embedding.provider"], havingValue = "fake")
class DeterministicTextEmbeddingAdapter : TextEmbeddingPort {
    override fun embed(texts: List<String>): List<FloatArray> = texts.map(::embedding)

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
