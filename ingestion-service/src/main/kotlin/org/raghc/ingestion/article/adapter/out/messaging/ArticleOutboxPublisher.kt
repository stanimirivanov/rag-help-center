package org.raghc.ingestion.article.adapter.out.messaging

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
@ConditionalOnProperty(name = ["app.outbox.publisher.enabled"], havingValue = "true")
class ArticleOutboxPublisher(
    private val jdbcClient: JdbcClient,
    private val kafkaTemplate: KafkaTemplate<String, String>,
) {
    @Scheduled(fixedDelayString = "\${app.outbox.publisher.fixed-delay:1000}")
    @Transactional
    fun publishBatch() {
        val rows =
            jdbcClient
                .sql(
                    """
                    select outbox_id, aggregate_id, payload::text from article_outbox
                    where published_at is null order by recorded_at
                    for update skip locked limit $BATCH_SIZE
                    """.trimIndent(),
                ).query { rs, _ ->
                    OutboxRow(
                        rs.getObject("outbox_id", UUID::class.java),
                        rs.getObject("aggregate_id", UUID::class.java),
                        rs.getString("payload"),
                    )
                }.list()
        rows.forEach { row ->
            kafkaTemplate.send("help-center.article-events.v1", row.aggregateId.toString(), row.payload).get()
            jdbcClient
                .sql("update article_outbox set published_at = clock_timestamp() where outbox_id = :id")
                .param("id", row.id)
                .update()
        }
    }

    private data class OutboxRow(
        val id: UUID,
        val aggregateId: UUID,
        val payload: String,
    )

    private companion object {
        const val BATCH_SIZE = 50
    }
}
