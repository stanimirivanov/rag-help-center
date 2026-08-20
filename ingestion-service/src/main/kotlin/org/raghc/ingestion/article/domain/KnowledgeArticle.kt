package org.raghc.ingestion.article.domain

import java.time.Instant

class KnowledgeArticle private constructor(
    val id: ArticleId,
    val tenantId: TenantId,
) {
    lateinit var content: ArticleContent
        private set

    var streamVersion: Long = 0
        private set

    var revision: Long = 0
        private set

    var status: ArticleStatus = ArticleStatus.DRAFT
        private set

    private val changes = mutableListOf<ArticleEvent>()

    fun revise(
        title: String,
        body: String,
        occurredAt: Instant,
    ) {
        if (status != ArticleStatus.DRAFT) throw InvalidArticleLifecycleException(status, "revise")
        val revised = ArticleContent.create(title, body, content.locale)
        require(revised != content) { "revision must change the article content" }
        record(ArticleContentRevised(revised.title, revised.body, occurredAt))
    }

    fun publish(occurredAt: Instant) {
        if (status != ArticleStatus.DRAFT) throw InvalidArticleLifecycleException(status, "publish")
        record(ArticlePublished(revision + 1, occurredAt))
    }

    fun withdraw(occurredAt: Instant) {
        if (status != ArticleStatus.PUBLISHED) throw InvalidArticleLifecycleException(status, "withdraw")
        record(ArticleWithdrawn(occurredAt))
    }

    fun restore(occurredAt: Instant) {
        if (status != ArticleStatus.WITHDRAWN) throw InvalidArticleLifecycleException(status, "restore")
        record(ArticleRestored(occurredAt))
    }

    fun pendingEvents(): List<ArticleEvent> = changes.toList()

    fun markChangesCommitted() = changes.clear()

    private fun record(event: ArticleEvent) {
        apply(event)
        changes += event
    }

    private fun apply(event: ArticleEvent) {
        when (event) {
            is ArticleCreated -> {
                content = ArticleContent.create(event.title, event.body, ArticleLocale.of(event.locale))
            }

            is ArticleContentRevised -> {
                content = ArticleContent.create(event.title, event.body, content.locale)
            }

            is ArticlePublished -> {
                revision = event.revision
                status = ArticleStatus.PUBLISHED
            }

            is ArticleWithdrawn -> {
                status = ArticleStatus.WITHDRAWN
            }

            is ArticleRestored -> {
                status = ArticleStatus.PUBLISHED
            }
        }
        streamVersion++
    }

    companion object {
        fun create(
            id: ArticleId,
            tenantId: TenantId,
            content: ArticleContent,
            occurredAt: Instant,
        ): KnowledgeArticle =
            KnowledgeArticle(id, tenantId).also {
                it.record(ArticleCreated(content.title, content.body, content.locale.value, occurredAt))
            }

        fun rehydrate(
            id: ArticleId,
            tenantId: TenantId,
            history: List<ArticleEvent>,
        ): KnowledgeArticle {
            require(history.isNotEmpty()) { "article history must not be empty" }
            return KnowledgeArticle(id, tenantId).also { article -> history.forEach(article::apply) }
        }
    }
}

enum class ArticleStatus {
    DRAFT,
    PUBLISHED,
    WITHDRAWN,
}

class InvalidArticleLifecycleException(
    status: ArticleStatus,
    operation: String,
) : IllegalStateException("cannot $operation an article in $status state")
