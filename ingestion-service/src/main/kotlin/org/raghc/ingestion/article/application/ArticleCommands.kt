package org.raghc.ingestion.article.application

import org.raghc.ingestion.article.domain.ArticleContent
import org.raghc.ingestion.article.domain.ArticleId
import org.raghc.ingestion.article.domain.ArticleLocale
import org.raghc.ingestion.article.domain.CollectionId
import org.raghc.ingestion.article.domain.KnowledgeArticle
import org.raghc.ingestion.article.domain.TenantId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock

data class CreateArticleCommand(
    val tenantId: TenantId,
    val title: String,
    val body: String,
    val locale: String,
    val idempotencyKey: String?,
    val collectionId: CollectionId,
)

data class ReviseArticleCommand(
    val tenantId: TenantId,
    val articleId: ArticleId,
    val expectedVersion: Long,
    val title: String,
    val body: String,
    val idempotencyKey: String?,
)

data class ChangeArticleStatusCommand(
    val tenantId: TenantId,
    val articleId: ArticleId,
    val expectedVersion: Long,
    val idempotencyKey: String?,
)

data class ArticleCommandResult(
    val articleId: ArticleId,
    val streamVersion: Long,
)

@Service
class ArticleCommandService(
    private val eventStore: ArticleEventStore,
    private val projectionWriter: ArticleProjectionWriter,
    private val idempotencyStore: CommandIdempotencyStore,
    private val articleIdGenerator: ArticleIdGenerator,
    private val clock: Clock,
) {
    @Transactional
    fun create(command: CreateArticleCommand): ArticleCommandResult =
        idempotent(
            command.tenantId,
            command.idempotencyKey,
            "CREATE",
            "${command.title}\u0000${command.body}\u0000${command.locale}\u0000${command.collectionId.value}",
        ) {
            val article =
                KnowledgeArticle.create(
                    articleIdGenerator.next(),
                    command.tenantId,
                    ArticleContent.create(command.title, command.body, ArticleLocale.of(command.locale)),
                    clock.instant(),
                    command.collectionId,
                )
            persist(article, 0)
        }

    @Transactional
    fun revise(command: ReviseArticleCommand): ArticleCommandResult =
        idempotent(
            command.tenantId,
            command.idempotencyKey,
            "REVISE",
            "${command.articleId.value}\u0000${command.expectedVersion}" +
                "\u0000${command.title}\u0000${command.body}",
        ) {
            change(command.tenantId, command.articleId, command.expectedVersion) {
                revise(command.title, command.body, clock.instant())
            }
        }

    @Transactional
    fun publish(command: ChangeArticleStatusCommand): ArticleCommandResult {
        val result = statusChange("PUBLISH", command) { publish(clock.instant()) }
        return result
    }

    @Transactional
    fun withdraw(command: ChangeArticleStatusCommand): ArticleCommandResult =
        statusChange("WITHDRAW", command) { withdraw(clock.instant()) }

    @Transactional
    fun restore(command: ChangeArticleStatusCommand): ArticleCommandResult {
        val result = statusChange("RESTORE", command) { restore(clock.instant()) }
        return result
    }

    private fun statusChange(
        type: String,
        command: ChangeArticleStatusCommand,
        operation: KnowledgeArticle.() -> Unit,
    ): ArticleCommandResult =
        idempotent(
            command.tenantId,
            command.idempotencyKey,
            type,
            "${command.articleId.value}\u0000${command.expectedVersion}",
        ) {
            change(command.tenantId, command.articleId, command.expectedVersion, operation)
        }

    private fun change(
        tenantId: TenantId,
        articleId: ArticleId,
        expectedVersion: Long,
        operation: KnowledgeArticle.() -> Unit,
    ): ArticleCommandResult {
        val history = eventStore.load(tenantId, articleId)
        if (history.isEmpty()) throw ArticleNotFoundException(articleId)
        val article = KnowledgeArticle.rehydrate(articleId, tenantId, history)
        if (article.streamVersion != expectedVersion) {
            throw ConcurrentArticleModificationException(expectedVersion, article.streamVersion)
        }
        article.operation()
        return persist(article, expectedVersion)
    }

    private fun persist(
        article: KnowledgeArticle,
        expectedVersion: Long,
    ): ArticleCommandResult {
        val events = article.pendingEvents()
        eventStore.append(article.tenantId, article.id, expectedVersion, events)
        projectionWriter.update(article, events)
        article.markChangesCommitted()
        return ArticleCommandResult(article.id, article.streamVersion)
    }

    private fun idempotent(
        tenantId: TenantId,
        key: String?,
        commandType: String,
        fingerprint: String,
        operation: () -> ArticleCommandResult,
    ): ArticleCommandResult {
        if (key == null) return operation()
        require(key.isNotBlank() && key.length <= MAX_IDEMPOTENCY_KEY_LENGTH) {
            "Idempotency-Key must contain 1 to $MAX_IDEMPOTENCY_KEY_LENGTH characters"
        }
        val requestHash = fingerprint.sha256()
        idempotencyStore.lock(tenantId, key)
        val stored = idempotencyStore.find(tenantId, key)
        return if (stored != null) {
            if (stored.commandType != commandType || stored.requestHash != requestHash) {
                throw IdempotencyKeyConflictException()
            }
            stored.result
        } else {
            operation().also { idempotencyStore.save(tenantId, key, commandType, requestHash, it) }
        }
    }

    private fun String.sha256(): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 200
    }
}
