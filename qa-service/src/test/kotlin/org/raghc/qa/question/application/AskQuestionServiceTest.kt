package org.raghc.qa.question.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.raghc.qa.retrieval.application.RetrieveKnowledge
import org.raghc.qa.retrieval.application.RetrievedChunk
import java.util.UUID

class AskQuestionServiceTest {
    private val tenantId = UUID.randomUUID()

    @Test
    fun `returns a grounded answer with citations resolved to source revisions`() {
        val chunk = chunk()
        val service =
            AskQuestionService(
                RetrieveKnowledge { listOf(chunk) },
                GenerateGroundedAnswer { GeneratedAnswer("Use account settings.", setOf(chunk.chunkId)) },
            )

        val result = service.ask(AskQuestionCommand(tenantId, "How do I reset my password?"))

        assertThat(result.outcome).isEqualTo(AnswerOutcome.ANSWERED)
        assertThat(result.answer).isEqualTo("Use account settings.")
        assertThat(result.citations).containsExactly(AnswerCitation(chunk.chunkId, chunk.articleId, chunk.revision))
    }

    @Test
    fun `does not call the model when retrieval has no sufficient context`() {
        var modelCalled = false
        val service =
            AskQuestionService(
                RetrieveKnowledge { emptyList() },
                GenerateGroundedAnswer {
                    modelCalled = true
                    GeneratedAnswer("unsupported", emptySet())
                },
            )

        val result = service.ask(AskQuestionCommand(tenantId, "Unknown question"))

        assertThat(result.outcome).isEqualTo(AnswerOutcome.INSUFFICIENT_CONTEXT)
        assertThat(modelCalled).isFalse()
    }

    @Test
    fun `rejects an invented citation as insufficient context`() {
        val chunk = chunk()
        val service =
            AskQuestionService(
                RetrieveKnowledge { listOf(chunk) },
                GenerateGroundedAnswer { GeneratedAnswer("Invented answer", setOf(UUID.randomUUID())) },
            )

        val result = service.ask(AskQuestionCommand(tenantId, "Question"))

        assertThat(result).isEqualTo(QuestionAnswer(AnswerOutcome.INSUFFICIENT_CONTEXT))
    }

    @Test
    fun `maps an unavailable model to an explicit outcome`() {
        val service =
            AskQuestionService(
                RetrieveKnowledge { listOf(chunk()) },
                GenerateGroundedAnswer { throw ModelUnavailableException() },
            )

        val result = service.ask(AskQuestionCommand(tenantId, "Question"))

        assertThat(result.outcome).isEqualTo(AnswerOutcome.MODEL_UNAVAILABLE)
        assertThat(result.answer).isNull()
        assertThat(result.citations).isEmpty()
    }

    private fun chunk() =
        RetrievedChunk(
            UUID.randomUUID(),
            UUID.randomUUID(),
            4,
            0,
            "en",
            "Reset your password from account settings.",
            0.9,
        )
}
