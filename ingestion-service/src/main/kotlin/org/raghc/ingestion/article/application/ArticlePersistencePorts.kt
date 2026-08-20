package org.raghc.ingestion.article.application

import org.raghc.ingestion.article.domain.ArticleEvent
import org.raghc.ingestion.article.domain.KnowledgeArticle
import org.raghc.ingestion.article.domain.TenantId

interface ArticleProjectionWriter {
    fun update(
        article: KnowledgeArticle,
        events: List<ArticleEvent>,
    )

    fun rebuild()
}

data class StoredCommandResult(
    val commandType: String,
    val requestHash: String,
    val result: ArticleCommandResult,
)

interface CommandIdempotencyStore {
    fun lock(
        tenantId: TenantId,
        key: String,
    )

    fun find(
        tenantId: TenantId,
        key: String,
    ): StoredCommandResult?

    fun save(
        tenantId: TenantId,
        key: String,
        commandType: String,
        requestHash: String,
        result: ArticleCommandResult,
    )
}

class IdempotencyKeyConflictException : RuntimeException("idempotency key was already used for a different command")
