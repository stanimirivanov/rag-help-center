package org.raghc.qa.question.application

import org.raghc.qa.retrieval.application.RetrievalQuery
import org.raghc.qa.retrieval.application.RetrieveKnowledge
import org.raghc.qa.retrieval.application.RetrievedChunk
import org.springframework.stereotype.Service

@Service
class AskQuestionService(
    private val retrieveKnowledge: RetrieveKnowledge,
    private val generateGroundedAnswer: GenerateGroundedAnswer,
) : AskQuestion {
    override fun ask(command: AskQuestionCommand): QuestionAnswer {
        val context =
            retrieveKnowledge.retrieve(
                RetrievalQuery(
                    tenantId = command.tenantId,
                    question = command.question,
                    collectionId = command.collectionId,
                    locale = command.locale,
                ),
            )
        return if (context.isEmpty()) {
            QuestionAnswer(AnswerOutcome.INSUFFICIENT_CONTEXT)
        } else {
            generate(command.question, context)
        }
    }

    private fun generate(
        question: String,
        context: List<RetrievedChunk>,
    ): QuestionAnswer =
        try {
            generateGroundedAnswer
                .generate(GroundedAnswerRequest(question, context))
                .toValidatedAnswer(context)
        } catch (_: ModelUnavailableException) {
            QuestionAnswer(AnswerOutcome.MODEL_UNAVAILABLE)
        }

    private fun GeneratedAnswer.toValidatedAnswer(context: List<RetrievedChunk>): QuestionAnswer {
        val chunksById = context.associateBy { it.chunkId }
        val valid = answer.isNotBlank() && citedChunkIds.isNotEmpty() && chunksById.keys.containsAll(citedChunkIds)
        return if (valid) groundedAnswer(chunksById) else QuestionAnswer(AnswerOutcome.INSUFFICIENT_CONTEXT)
    }

    private fun GeneratedAnswer.groundedAnswer(chunksById: Map<java.util.UUID, RetrievedChunk>): QuestionAnswer {
        val citations =
            citedChunkIds.map { chunkId ->
                val chunk = chunksById.getValue(chunkId)
                AnswerCitation(chunk.chunkId, chunk.articleId, chunk.revision)
            }
        return QuestionAnswer(AnswerOutcome.ANSWERED, answer.trim(), citations)
    }
}
