package org.raghc.ingestion.article.application

import org.raghc.ingestion.article.domain.ArticleContent
import org.raghc.ingestion.article.domain.ArticleId
import org.raghc.ingestion.article.domain.KnowledgeArticle
import org.raghc.ingestion.article.domain.TenantId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class CreateArticleCommand(
    val tenantId: TenantId,
    val title: String,
    val body: String,
    val locale: String,
)

data class ReviseArticleCommand(
    val tenantId: TenantId,
    val articleId: ArticleId,
    val expectedVersion: Long,
    val title: String,
    val body: String,
)

data class ArticleCommandResult(
    val articleId: ArticleId,
    val streamVersion: Long,
)

@Service
class ArticleCommandService(
    private val eventStore: ArticleEventStore,
    private val clock: Clock,
) {
    @Transactional
    fun create(command: CreateArticleCommand): ArticleCommandResult {
        val article =
            KnowledgeArticle.create(
                ArticleId(UUID.randomUUID()),
                command.tenantId,
                ArticleContent(command.title, command.body, command.locale),
                Instant.now(clock),
            )
        eventStore.append(command.tenantId, article.id, 0, article.pendingEvents())
        article.markChangesCommitted()
        return ArticleCommandResult(article.id, article.streamVersion)
    }

    @Transactional
    fun revise(command: ReviseArticleCommand): ArticleCommandResult {
        val history = eventStore.load(command.tenantId, command.articleId)
        if (history.isEmpty()) throw ArticleNotFoundException(command.articleId)

        val article = KnowledgeArticle.rehydrate(command.articleId, command.tenantId, history)
        if (article.streamVersion != command.expectedVersion) {
            throw ConcurrentArticleModificationException(command.expectedVersion, article.streamVersion)
        }

        article.revise(command.title, command.body, Instant.now(clock))
        eventStore.append(command.tenantId, command.articleId, command.expectedVersion, article.pendingEvents())
        article.markChangesCommitted()
        return ArticleCommandResult(article.id, article.streamVersion)
    }
}
