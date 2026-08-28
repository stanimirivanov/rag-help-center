package org.raghc.qa.question.application

import org.raghc.qa.retrieval.application.RetrievedChunk
import java.util.UUID

data class AskQuestionCommand(
    val tenantId: UUID,
    val question: String,
    val collectionId: UUID? = null,
    val locale: String? = null,
)

enum class AnswerOutcome {
    ANSWERED,
    INSUFFICIENT_CONTEXT,
    MODEL_UNAVAILABLE,
}

data class QuestionAnswer(
    val outcome: AnswerOutcome,
    val answer: String? = null,
    val citations: List<AnswerCitation> = emptyList(),
)

data class AnswerCitation(
    val chunkId: UUID,
    val articleId: UUID,
    val revision: Long,
)

data class GroundedAnswerRequest(
    val question: String,
    val context: List<RetrievedChunk>,
)

data class GeneratedAnswer(
    val answer: String,
    val citedChunkIds: Set<UUID>,
)

fun interface GenerateGroundedAnswer {
    fun generate(request: GroundedAnswerRequest): GeneratedAnswer
}

class ModelUnavailableException(
    cause: Throwable? = null,
) : RuntimeException("chat model is unavailable", cause)

fun interface AskQuestion {
    fun ask(command: AskQuestionCommand): QuestionAnswer
}
