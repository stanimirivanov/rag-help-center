package org.raghc.ingestion.article.adapter.out.persistence

import org.raghc.ingestion.article.application.ArticleCommandResult
import org.raghc.ingestion.article.application.CommandIdempotencyStore
import org.raghc.ingestion.article.application.StoredCommandResult
import org.raghc.ingestion.article.domain.ArticleId
import org.raghc.ingestion.article.domain.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class PostgresCommandIdempotencyStore(
    private val jdbcClient: JdbcClient,
) : CommandIdempotencyStore {
    override fun lock(
        tenantId: TenantId,
        key: String,
    ) {
        jdbcClient
            .sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .param("key", "${tenantId.value}:$key")
            .query { _, _ -> Unit }
            .single()
    }

    override fun find(
        tenantId: TenantId,
        key: String,
    ): StoredCommandResult? =
        jdbcClient
            .sql(
                """
                select command_type, request_hash, article_id, stream_version
                from command_idempotency where tenant_id = :tenantId and idempotency_key = :key
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("key", key)
            .query { rs, _ ->
                StoredCommandResult(
                    rs.getString("command_type"),
                    rs.getString("request_hash"),
                    ArticleCommandResult(
                        ArticleId(rs.getObject("article_id", java.util.UUID::class.java)),
                        rs.getLong("stream_version"),
                    ),
                )
            }.optional()
            .orElse(null)

    override fun save(
        tenantId: TenantId,
        key: String,
        commandType: String,
        requestHash: String,
        result: ArticleCommandResult,
    ) {
        jdbcClient
            .sql(
                """
                insert into command_idempotency
                    (tenant_id, idempotency_key, command_type, request_hash, article_id, stream_version)
                values (:tenantId, :key, :commandType, :requestHash, :articleId, :streamVersion)
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("key", key)
            .param("commandType", commandType)
            .param("requestHash", requestHash)
            .param("articleId", result.articleId.value)
            .param("streamVersion", result.streamVersion)
            .update()
    }
}
