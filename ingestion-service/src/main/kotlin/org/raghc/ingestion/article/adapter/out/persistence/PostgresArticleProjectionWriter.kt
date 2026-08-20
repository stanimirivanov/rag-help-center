package org.raghc.ingestion.article.adapter.out.persistence

import org.raghc.ingestion.article.application.ArticleProjectionWriter
import org.raghc.ingestion.article.domain.ArticleEvent
import org.raghc.ingestion.article.domain.ArticleId
import org.raghc.ingestion.article.domain.ArticlePublished
import org.raghc.ingestion.article.domain.ArticleRestored
import org.raghc.ingestion.article.domain.ArticleWithdrawn
import org.raghc.ingestion.article.domain.KnowledgeArticle
import org.raghc.ingestion.article.domain.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.ZoneOffset
import java.util.UUID

@Repository
class PostgresArticleProjectionWriter(
    private val jdbcClient: JdbcClient,
    private val codec: ArticleEventJsonCodec,
    private val objectMapper: ObjectMapper,
) : ArticleProjectionWriter {
    override fun update(
        article: KnowledgeArticle,
        events: List<ArticleEvent>,
    ) {
        val updatedAt = events.last().occurredAt.atOffset(ZoneOffset.UTC)
        upsert(article, updatedAt)

        val firstVersion = article.streamVersion - events.size + 1
        events.forEachIndexed { index, event ->
            if (event is ArticlePublished || event is ArticleWithdrawn || event is ArticleRestored) {
                insertOutbox(article, event, firstVersion + index)
            }
        }
    }

    private fun upsert(
        article: KnowledgeArticle,
        updatedAt: java.time.OffsetDateTime,
    ) {
        jdbcClient
            .sql(
                """
                insert into article_projection
                    (tenant_id, article_id, title, body, locale, lifecycle_status, revision,
                     stream_version, indexing_status, updated_at)
                values (:tenantId, :articleId, :title, :body, :locale, :status, :revision,
                        :streamVersion, :indexingStatus, :updatedAt)
                on conflict (tenant_id, article_id) do update set
                    title = excluded.title, body = excluded.body, locale = excluded.locale,
                    lifecycle_status = excluded.lifecycle_status, revision = excluded.revision,
                    stream_version = excluded.stream_version, indexing_status = excluded.indexing_status,
                    updated_at = excluded.updated_at
                """.trimIndent(),
            ).param("tenantId", article.tenantId.value)
            .param("articleId", article.id.value)
            .param("title", article.content.title)
            .param("body", article.content.body)
            .param("locale", article.content.locale.value)
            .param("status", article.status.name)
            .param("revision", article.revision)
            .param("streamVersion", article.streamVersion)
            .param("indexingStatus", if (article.status.name == "PUBLISHED") "PENDING" else "NOT_REQUESTED")
            .param("updatedAt", updatedAt)
            .update()
    }

    override fun rebuild() {
        jdbcClient.sql("delete from article_projection").update()
        val streams =
            jdbcClient
                .sql(
                    "select distinct tenant_id, aggregate_id from article_events order by tenant_id, aggregate_id",
                ).query { rs, _ ->
                    TenantId(rs.getObject("tenant_id", UUID::class.java)) to
                        ArticleId(rs.getObject("aggregate_id", UUID::class.java))
                }.list()
        streams.forEach { (tenantId, articleId) ->
            val history = loadHistory(tenantId, articleId)
            val article = KnowledgeArticle.rehydrate(articleId, tenantId, history)
            upsert(article, history.last().occurredAt.atOffset(ZoneOffset.UTC))
        }
    }

    private fun loadHistory(
        tenantId: TenantId,
        articleId: ArticleId,
    ): List<ArticleEvent> =
        jdbcClient
            .sql(
                """
                select event_type, schema_version, payload::text from article_events
                where tenant_id = :tenantId and aggregate_id = :articleId order by stream_version
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("articleId", articleId.value)
            .query { rs, _ ->
                codec.decode(
                    rs.getString("event_type"),
                    rs.getInt("schema_version"),
                    rs.getString("payload"),
                )
            }.list()

    private fun insertOutbox(
        article: KnowledgeArticle,
        event: ArticleEvent,
        streamVersion: Long,
    ) {
        val encoded = codec.encode(event)
        val outboxId = UUID.randomUUID()
        val traceId = UUID.randomUUID().toString().replace("-", "")
        val envelope =
            mapOf(
                "eventId" to outboxId,
                "eventType" to encoded.eventType,
                "schemaVersion" to encoded.schemaVersion,
                "traceId" to traceId,
                "tenantId" to article.tenantId.value,
                "aggregateId" to article.id.value,
                "streamVersion" to streamVersion,
                "occurredAt" to event.occurredAt,
                "data" to objectMapper.readTree(encoded.payload),
            )
        jdbcClient
            .sql(
                """
                insert into article_outbox
                    (outbox_id, tenant_id, aggregate_id, stream_version, event_type,
                     schema_version, trace_id, payload, occurred_at)
                values (:id, :tenantId, :articleId, :streamVersion, :eventType,
                        :schemaVersion, :traceId, cast(:payload as jsonb), :occurredAt)
                """.trimIndent(),
            ).param("id", outboxId)
            .param("tenantId", article.tenantId.value)
            .param("articleId", article.id.value)
            .param("streamVersion", streamVersion)
            .param("eventType", encoded.eventType)
            .param("schemaVersion", encoded.schemaVersion)
            .param("traceId", traceId)
            .param("payload", objectMapper.writeValueAsString(envelope))
            .param("occurredAt", event.occurredAt.atOffset(ZoneOffset.UTC))
            .update()
    }
}
