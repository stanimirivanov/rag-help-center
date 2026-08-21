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

@JvmInline
value class CollectionId(
    val value: UUID,
)

@JvmInline
value class ArticleLocale private constructor(
    val value: String,
) {
    companion object {
        const val LANGUAGE_TAG_PATTERN = "^[a-z]{2,3}(-[A-Z]{2})?$"
        private val LANGUAGE_TAG = Regex(LANGUAGE_TAG_PATTERN)

        fun of(value: String): ArticleLocale {
            if (!LANGUAGE_TAG.matches(value)) throw InvalidArticleLocaleException(value)
            return ArticleLocale(value)
        }
    }
}

@ConsistentCopyVisibility
data class ArticleContent private constructor(
    val title: String,
    val body: String,
    val locale: ArticleLocale,
) {
    companion object {
        const val MAX_TITLE_LENGTH = 200
        const val MAX_BODY_LENGTH = 100_000

        fun create(
            title: String,
            body: String,
            locale: ArticleLocale,
        ): ArticleContent {
            val violations =
                buildSet {
                    if (title.isBlank()) add(ArticleContentViolation.BLANK_TITLE)
                    if (title.length > MAX_TITLE_LENGTH) add(ArticleContentViolation.TITLE_TOO_LONG)
                    if (body.isBlank()) add(ArticleContentViolation.BLANK_BODY)
                    if (body.length > MAX_BODY_LENGTH) add(ArticleContentViolation.BODY_TOO_LONG)
                }
            if (violations.isNotEmpty()) throw InvalidArticleContentException(violations)
            return ArticleContent(title, body, locale)
        }
    }
}

enum class ArticleContentViolation {
    BLANK_TITLE,
    TITLE_TOO_LONG,
    BLANK_BODY,
    BODY_TOO_LONG,
}

class InvalidArticleContentException(
    val violations: Set<ArticleContentViolation>,
) : IllegalArgumentException("invalid article content: ${violations.joinToString()}")

class InvalidArticleLocaleException(
    value: String,
) : IllegalArgumentException("locale must be a BCP 47-like language tag; received $value")
