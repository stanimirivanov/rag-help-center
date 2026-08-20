package org.raghc.embedding.article.adapter.out.persistence

import org.raghc.embedding.article.application.ArticleIntegrationEvent
import org.raghc.embedding.article.application.IndexingCheckpoint
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Repository
class PostgresIndexingCheckpoint(
    private val jdbcClient: JdbcClient,
    private val objectMapper: ObjectMapper,
) : IndexingCheckpoint {
    override fun claim(
        eventId: UUID,
        eventType: String,
    ): Boolean =
        jdbcClient
            .sql(
                """
                insert into embedding_inbox (event_id, event_type) values (:eventId, :eventType)
                on conflict (event_id) do nothing
                """.trimIndent(),
            ).param("eventId", eventId)
            .param("eventType", eventType)
            .update() == 1

    override fun recordStatus(
        event: ArticleIntegrationEvent,
        status: String,
    ) {
        val payload =
            mapOf(
                "eventType" to "ArticleIndexStatusChanged",
                "schemaVersion" to 1,
                "traceId" to event.traceId,
                "tenantId" to event.tenantId,
                "articleId" to event.aggregateId,
                "revision" to event.data.revision,
                "status" to status,
            )
        jdbcClient
            .sql(
                """
                insert into embedding_status_outbox
                    (status_event_id, source_event_id, tenant_id, article_id, revision, status, payload)
                values (:statusEventId, :sourceEventId, :tenantId, :articleId, :revision, :status,
                        cast(:payload as jsonb))
                """.trimIndent(),
            ).param("statusEventId", UUID.randomUUID())
            .param("sourceEventId", event.eventId)
            .param("tenantId", event.tenantId)
            .param("articleId", event.aggregateId)
            .param("revision", event.data.revision)
            .param("status", status)
            .param("payload", objectMapper.writeValueAsString(payload))
            .update()
    }
}
