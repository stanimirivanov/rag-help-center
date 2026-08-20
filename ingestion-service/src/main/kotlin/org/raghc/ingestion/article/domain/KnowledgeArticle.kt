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

    private val changes = mutableListOf<ArticleEvent>()

    fun revise(
        title: String,
        body: String,
        occurredAt: Instant,
    ) {
        val revised = ArticleContent(title, body, content.locale)
        require(revised != content) { "revision must change the article content" }
        record(ArticleContentRevised(revised.title, revised.body, occurredAt))
    }

    fun pendingEvents(): List<ArticleEvent> = changes.toList()

    fun markChangesCommitted() = changes.clear()

    private fun record(event: ArticleEvent) {
        apply(event)
        changes += event
    }

    private fun apply(event: ArticleEvent) {
        when (event) {
            is ArticleCreated -> content = ArticleContent(event.title, event.body, event.locale)
            is ArticleContentRevised -> content = ArticleContent(event.title, event.body, content.locale)
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
                it.record(ArticleCreated(content.title, content.body, content.locale, occurredAt))
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
