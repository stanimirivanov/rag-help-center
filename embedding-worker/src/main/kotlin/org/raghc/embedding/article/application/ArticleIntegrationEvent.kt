package org.raghc.embedding.article.application

import java.time.Instant
import java.util.UUID

data class ArticleIntegrationEvent(
    val eventId: UUID,
    val eventType: String,
    val schemaVersion: Int,
    val traceId: String,
    val tenantId: UUID,
    val aggregateId: UUID,
    val streamVersion: Long,
    val occurredAt: Instant,
    val data: ArticleRevisionData,
)

data class ArticleRevisionData(
    val revision: Long,
    val title: String? = null,
    val body: String? = null,
    val locale: String? = null,
)
