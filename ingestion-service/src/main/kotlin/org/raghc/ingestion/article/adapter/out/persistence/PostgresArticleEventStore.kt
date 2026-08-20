package org.raghc.ingestion.article.adapter.out.persistence

import org.raghc.ingestion.article.application.ArticleEventStore
import org.raghc.ingestion.article.application.ConcurrentArticleModificationException
import org.raghc.ingestion.article.domain.ArticleEvent
import org.raghc.ingestion.article.domain.ArticleId
import org.raghc.ingestion.article.domain.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.ZoneOffset
import java.util.UUID

@Repository
class PostgresArticleEventStore(
    private val jdbcClient: JdbcClient,
    private val codec: ArticleEventJsonCodec,
) : ArticleEventStore {
    override fun load(
        tenantId: TenantId,
        articleId: ArticleId,
    ): List<ArticleEvent> =
        jdbcClient
            .sql(
                """
                select event_type, schema_version, payload::text
                from article_events
                where tenant_id = :tenantId and aggregate_id = :articleId
                order by stream_version
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("articleId", articleId.value)
            .query { resultSet, _ ->
                codec.decode(
                    resultSet.getString("event_type"),
                    resultSet.getInt("schema_version"),
                    resultSet.getString("payload"),
                )
            }.list()

    override fun append(
        tenantId: TenantId,
        articleId: ArticleId,
        expectedVersion: Long,
        events: List<ArticleEvent>,
    ) {
        require(events.isNotEmpty()) { "at least one event is required" }
        lockStream(tenantId, articleId)
        val actualVersion = currentVersion(tenantId, articleId)
        if (actualVersion != expectedVersion) {
            throw ConcurrentArticleModificationException(expectedVersion, actualVersion)
        }

        events.forEachIndexed { index, event ->
            val encoded = codec.encode(event)
            jdbcClient
                .sql(
                    """
                    insert into article_events (
                        event_id, tenant_id, aggregate_id, stream_version,
                        event_type, schema_version, payload, occurred_at
                    ) values (
                        :eventId, :tenantId, :articleId, :streamVersion,
                        :eventType, :schemaVersion, cast(:payload as jsonb), :occurredAt
                    )
                    """.trimIndent(),
                ).param("eventId", UUID.randomUUID())
                .param("tenantId", tenantId.value)
                .param("articleId", articleId.value)
                .param("streamVersion", expectedVersion + index + 1)
                .param("eventType", encoded.eventType)
                .param("schemaVersion", encoded.schemaVersion)
                .param("payload", encoded.payload)
                .param("occurredAt", event.occurredAt.atOffset(ZoneOffset.UTC))
                .update()
        }
    }

    private fun lockStream(
        tenantId: TenantId,
        articleId: ArticleId,
    ) {
        jdbcClient
            .sql("select pg_advisory_xact_lock(hashtextextended(:streamKey, 0))")
            .param("streamKey", "${tenantId.value}:${articleId.value}")
            .query { _, _ -> Unit }
            .single()
    }

    private fun currentVersion(
        tenantId: TenantId,
        articleId: ArticleId,
    ): Long =
        jdbcClient
            .sql(
                """
                select coalesce(max(stream_version), 0)
                from article_events
                where tenant_id = :tenantId and aggregate_id = :articleId
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("articleId", articleId.value)
            .query(Long::class.java)
            .single()
}
