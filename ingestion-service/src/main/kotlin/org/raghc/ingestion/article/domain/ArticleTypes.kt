package org.raghc.ingestion.article.domain

import java.util.UUID

@JvmInline
value class ArticleId(
    val value: UUID,
)

@JvmInline
value class TenantId(
    val value: UUID,
)

data class ArticleContent(
    val title: String,
    val body: String,
    val locale: String,
) {
    init {
        require(title.isNotBlank()) { "title must not be blank" }
        require(title.length <= MAX_TITLE_LENGTH) { "title must not exceed $MAX_TITLE_LENGTH characters" }
        require(body.isNotBlank()) { "body must not be blank" }
        require(body.length <= MAX_BODY_LENGTH) { "body must not exceed $MAX_BODY_LENGTH characters" }
        require(LOCALE.matches(locale)) { "locale must be a BCP 47-like language tag" }
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 200
        const val MAX_BODY_LENGTH = 100_000
        val LOCALE = Regex("^[a-z]{2,3}(-[A-Z]{2})?$")
    }
}
